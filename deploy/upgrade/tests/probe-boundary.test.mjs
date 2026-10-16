import assert from 'node:assert/strict';
import { test, mock } from 'node:test';
import { spawnSync as launchProcess } from 'node:child_process';
import * as fs from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Execute both real entry points. Docker, Java compilation and report writes are
// intercepted at their external boundaries; no daemon or middleware is contacted.
const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const fixtureDirectory = join(root, 'logs/final-fixes/probe-fixtures');
const local = 'npipe:////./pipe/docker_engine';
const remote = 'tcp://192.0.2.1:2375';
const id = 'a'.repeat(64);
const scenarios = [
  { name: 'remote DOCKER_HOST', env: { DOCKER_HOST: remote }, resolvedEndpoint: remote, deny: true },
  { name: 'remote active context', current: remote, deny: true },
  { name: 'CLI metadata selects remote context despite local DOCKER_HOST', env: { DOCKER_CONTEXT: 'remote', DOCKER_HOST: local }, resolvedEndpoint: remote, deny: true },
  { name: 'CLI metadata selects local context despite remote DOCKER_HOST', env: { DOCKER_CONTEXT: 'local', DOCKER_HOST: remote }, resolvedEndpoint: local },
  { name: 'DOCKER_HOST overrides remote current context', env: { DOCKER_HOST: local }, current: remote, resolvedEndpoint: local },
  { name: 'default context uses DOCKER_HOST', env: { DOCKER_CONTEXT: 'default', DOCKER_HOST: remote }, resolvedEndpoint: remote, deny: true },
  { name: 'Docker 29.7.2 metadata selects remote host over named context', env: { DOCKER_CONTEXT: 'local', DOCKER_HOST: remote }, resolvedEndpoint: remote, deny: true },
  { name: 'CLI metadata selects local host despite remote named context', env: { DOCKER_CONTEXT: 'remote', DOCKER_HOST: local }, resolvedEndpoint: local },
  { name: 'local named pipe' },
  { name: 'local Unix socket', current: 'unix:///var/run/docker.sock' },
  { name: 'remote named pipe', current: 'npipe:////remote/pipe/docker_engine', deny: true },
  { name: 'Unix URI with authority', current: 'unix://remote/var/run/docker.sock', deny: true },
  { name: 'UNC-like Unix path', current: 'unix:////remote/docker.sock', deny: true },
  { name: 'loopback TCP is refused', current: 'tcp://127.0.0.1:2375', deny: true },
  { name: 'SSH is refused', current: 'ssh://user@192.0.2.1', deny: true },
  { name: 'context inspection fails', contextFailure: true, deny: true },
  { name: 'missing endpoint', missingEndpoint: true, deny: true },
  { name: 'COMPOSE_PROJECT_NAME cannot select another project', env: { COMPOSE_PROJECT_NAME: 'other-project' } },
  { name: 'wrong project label', projectLabel: 'other-project', deny: true },
  { name: 'wrong service label', serviceLabel: 'wrong-service', deny: true },
  { name: 'one-off container is refused', oneoffLabel: 'True', deny: true },
  { name: 'ambiguous service containers', multipleContainers: true, deny: true },
  { name: 'container identity inspection fails', inspectFailure: true, deny: true },
  { name: 'invalid canonical container ID', containerId: 'friendly-name', deny: true },
  { name: 'stopped container cannot execute', running: false, deny: true },
  { name: 'environment changes after validation cannot redirect commands', changeEnvironment: true },
];

function effectiveEndpoint(scenario) {
  // Literal CLI metadata fixtures, not an implementation of environment priority.
  // Both precedence outcomes are covered; the entry points must trust the endpoint
  // actually resolved by their installed CLI, then pin and validate that endpoint.
  return scenario.resolvedEndpoint || scenario.current || local;
}
function verb(args) { return args[0] === '--host' ? args[2] : args[0]; }
function isMutation(call) { return ['exec', 'cp', 'run'].some(value => call.args.includes(value)); }
function assertBoundary(calls, scenario) {
  const mutations = calls.filter(isMutation);
  if (scenario.deny) {
    assert.equal(mutations.length, 0, 'must reject before any exec/cp/run');
    return;
  }
  assert.ok(mutations.length > 0, 'valid local target must reach the probe');
  for (const call of calls.filter(call => verb(call.args) !== 'context')) {
    assert.deepEqual(call.args.slice(0, 2), ['--host', effectiveEndpoint(scenario)], 'pin the verified endpoint');
    assert.ok(!call.env.DOCKER_CONTEXT && !call.env.DOCKER_HOST, 'do not inherit a later endpoint override');
    if (verb(call.args) === 'compose') {
      assert.equal(call.args[call.args.indexOf('--project-name') + 1], 'smartoffice-upgrade', 'pin the intended Compose project');
    }
  }
  const inspectAt = calls.findIndex(call => verb(call.args) === 'inspect');
  if (!scenario.hostJar) assert.ok(inspectAt >= 0 && inspectAt < calls.indexOf(mutations[0]), 'check container identity before commands');
  for (const call of mutations) {
    if (verb(call.args) === 'exec') assert.ok(call.args.includes(id), 'exec uses immutable inspected container ID');
    if (verb(call.args) === 'cp') assert.ok(call.args.some(value => value.startsWith(id + ':')), 'cp uses immutable inspected container ID');
  }
}

let fixture;
mock.module('node:child_process', { namedExports: {
  spawnSync(executable, args, options = {}) {
    assert.equal(executable, 'docker', 'the Lua probe must not start other programs');
    const env = options.env || process.env;
    fixture.calls.push({ args, env: { DOCKER_CONTEXT: env.DOCKER_CONTEXT, DOCKER_HOST: env.DOCKER_HOST } });
    const ok = value => ({ status: 0, stdout: typeof value === 'string' ? value : JSON.stringify(value), stderr: '' });
    const fail = () => ({ status: 1, stdout: '', stderr: 'isolated fixture failure' });
    const scenario = fixture.scenario;
    const command = verb(args);
    if (command === 'context') {
      if (scenario.contextFailure) return fail();
      return ok([{ Name: 'fixture', Endpoints: { docker: { Host: scenario.missingEndpoint ? undefined : effectiveEndpoint(scenario) } } }]);
    }
    if (command === 'compose' && args.includes('config')) return ok({ name: 'smartoffice-upgrade', services: { redis: {} } });
    if (command === 'compose' && args.includes('ps')) return ok(scenario.multipleContainers ? `${id}\n${'b'.repeat(64)}` : id);
    if (command === 'inspect') {
      if (scenario.inspectFailure) return fail();
      if (scenario.changeEnvironment) { process.env.DOCKER_HOST = remote; process.env.DOCKER_CONTEXT = 'remote'; }
      const labels = { 'com.docker.compose.project': scenario.projectLabel ?? 'smartoffice-upgrade',
        'com.docker.compose.service': scenario.serviceLabel ?? 'redis', 'com.docker.compose.oneoff': scenario.oneoffLabel ?? 'False' };
      return ok({ Id: scenario.containerId ?? id, Config: { Labels: labels }, State: { Running: scenario.running ?? true }, Image: 'sha256:fixture' });
    }
    if (args.includes('redis-cli')) {
      const [operation, ...values] = args.slice(args.lastIndexOf('redis-cli') + 1);
      if (operation === 'PING') return ok('PONG');
      if (operation === 'EXISTS') return ok(String(values.filter(key => fixture.redis.has(key)).length));
      if (operation === 'GET') return ok(fixture.redis.get(values[0])?.value ?? '');
      if (operation === 'TTL') return ok(String(fixture.redis.get(values[0])?.ttl ?? -2));
      if (operation === 'EVAL') {
        const [script, count, ...params] = values;
        const keys = params.slice(0, Number(count)), argv = params.slice(Number(count));
        if (count === '1') {
          if (!fixture.redis.has(keys[0])) fixture.redis.set(keys[0], { value: argv[0], ttl: argv[1] });
          return ok(fixture.redis.get(keys[0]).value);
        }
        if (script.includes("redis.call('DEL'")) {
          fixture.redis.set(keys[0], { value: argv[0], ttl: argv[1] }); fixture.redis.delete(keys[1]); return ok('1');
        }
        if (fixture.redis.get(keys[0])?.value !== argv[0]) return ok('0');
        fixture.redis.set(keys[1], { value: argv[1], ttl: argv[2] }); return ok('1');
      }
    }
    throw new Error(`Unrecognized Docker fixture call: ${args.join(' ')}`);
  }
} });
mock.module('node:fs', { namedExports: { ...fs, mkdirSync() {}, writeFileSync() {} } });

for (const scenario of scenarios) {
  test(`Lua: ${scenario.name}`, async () => {
    const originalEnv = { ...process.env }, originalExitCode = process.exitCode;
    fixture = { scenario, calls: [], redis: new Map() };
    for (const key of ['DOCKER_HOST', 'DOCKER_CONTEXT', 'COMPOSE_PROJECT_NAME']) delete process.env[key];
    Object.assign(process.env, scenario.env);
    process.exitCode = 0;
    try {
      await import(`../real-redis-lua-check.mjs?case=${encodeURIComponent(scenario.name)}`);
      assertBoundary(fixture.calls, scenario);
      assert.equal(process.exitCode, scenario.deny ? 1 : 0);
    } finally {
      for (const key of Object.keys(process.env)) if (!(key in originalEnv)) delete process.env[key];
      Object.assign(process.env, originalEnv); process.exitCode = originalExitCode;
    }
  });
}

const psScenarios = [...scenarios,
  { name: 'explicit foreign ContainerId', explicitContainer: 'other-booking', projectLabel: 'other-project', deny: true },
  { name: 'explicit container name pins inspected ID', explicitContainer: 'smartoffice-upgrade-booking-service-1' },
  { name: 'one-off send uses fixed local project', oneOff: true },
  { name: 'one-off with host JAR uses fixed local project', oneOff: true, hostJar: true },
  { name: 'one-off image mismatch fails before cp', oneOff: true, imageMismatch: true, deny: true },
];
for (const scenario of psScenarios) {
  test(`PowerShell: ${scenario.name}`, () => {
    fs.mkdirSync(fixtureDirectory, { recursive: true });
    const testRoot = fs.mkdtempSync(join(fixtureDirectory, 'case-'));
    const scenarioPath = join(testRoot, 'scenario.json'), callsPath = join(testRoot, 'calls.jsonl');
    fs.mkdirSync(join(testRoot, 'jdk/bin'), { recursive: true });
    fs.mkdirSync(join(testRoot, 'deploy/upgrade'), { recursive: true });
    // Execute an unchanged copy so the real script's run artifacts stay under this
    // test's temporary directory, separate from genuine middleware evidence.
    fs.copyFileSync(join(root, 'deploy/upgrade/run-middleware-probe.ps1'), join(testRoot, 'deploy/upgrade/run-middleware-probe.ps1'));
    for (const file of ['jdk/bin/javac.exe', 'jdk/bin/jar.exe', 'host.jar']) fs.writeFileSync(join(testRoot, file), 'isolated fixture');
    fs.writeFileSync(scenarioPath, JSON.stringify({ ...scenario, testRoot, callsPath, repoRoot: testRoot }));
    const env = { ...process.env };
    for (const key of ['DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'COMPOSE_PROJECT_NAME']) delete env[key];
    Object.assign(env, scenario.env);
    const execution = launchProcess('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', join(here, 'middleware-boundary-harness.ps1'), scenarioPath],
      { cwd: root, env, encoding: 'utf8', windowsHide: true, timeout: 20_000 });
    const calls = fs.existsSync(callsPath) ? fs.readFileSync(callsPath, 'utf8').split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line.replace(/^\uFEFF/, ''))) : [];
    try { assertBoundary(calls, scenario); }
    catch (error) { console.error({ status: execution.status, stdout: execution.stdout, stderr: execution.stderr }); throw error; }
    assert.equal(execution.status, scenario.deny ? 1 : 0, `${execution.stdout}\n${execution.stderr}`);
    if (!scenario.deny) assert.ok(calls.some(call => call.args.includes(scenario.oneOff ? 'run' : 'exec')), 'must reach the real middleware launch branch');
  });
}
