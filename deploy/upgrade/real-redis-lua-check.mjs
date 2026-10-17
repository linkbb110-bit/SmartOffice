import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Real Redis-only supplement. This does not validate Java serialization, Spring clients, or Redisson locks.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const sourcePath = resolve(root, 'backend/booking-service/src/main/java/com/geek/smartoffice/cache/RedisRoomCacheStore.java');
const outputDirectory = resolve(root, 'logs/real-integration');
const runId = `${Date.now()}-${randomUUID().slice(0, 8)}`;
const roomId = (8_000_000_000_000_000n + BigInt(Date.now()) * 100n + BigInt(Math.floor(Math.random() * 100))).toString();
const versionKey = `smartoffice:{room:${roomId}}:version`;
const detailKey = `smartoffice:{room:${roomId}}:detail`;
const lockKey = `smartoffice:{room:${roomId}}:load-lock`;
// These deliberately public, probe-only version markers are not passwords or authentication tokens.
const oldVersion = `probe-old-${runId}`;
const newVersion = `probe-new-${runId}`;
const unusedCandidate = `probe-unused-${runId}`;
const payload = JSON.stringify({ id: roomId, roomName: `lua-probe-${runId}`, capacity: 1 });
const result = {
  runId, startedAt: new Date().toISOString(), status: 'RUNNING',
  sourcePath, sourceSha256: null, roomId,
  keys: [versionKey, detailKey, lockKey],
  ttlArgumentsSeconds: { value: 180, null: 30, version: 600 },
  scope: 'Real redis-cli EVAL of Lua extracted from application source; no Java/Redisson claim.',
  cleanup: 'No global cleanup. Only this run\'s detail is removed by the application INVALIDATE Lua; final keys expire by TTL.',
  extractedScripts: {}, actualCommands: [], checks: [],
};
let activeStep = 'extract-application-lua';
let dockerTarget;

function checkedOutput(execution) {
  if (execution.error || execution.status !== 0) throw new Error('Docker target inspection failed');
  return (execution.stdout ?? '').trim();
}

function localDocker(...args) {
  if (!dockerTarget) throw new Error('Docker target has not been verified');
  return spawnSync('docker', ['--host', dockerTarget.endpoint, ...args], {
    cwd: root, encoding: 'utf8', windowsHide: true, timeout: 20_000, maxBuffer: 1_048_576,
    env: dockerTarget.env,
  });
}

function verifyDockerTarget() {
  // Docker resolves DOCKER_CONTEXT, DOCKER_HOST and currentContext itself,
  // including the special "default" context. This command reads local metadata.
  const contexts = JSON.parse(checkedOutput(spawnSync('docker', ['context', 'inspect'], {
    cwd: root, encoding: 'utf8', windowsHide: true, timeout: 20_000, maxBuffer: 1_048_576,
  })));
  const endpoint = contexts.length === 1 ? contexts[0]?.Endpoints?.docker?.Host : null;
  if (typeof endpoint !== 'string' || !(/^(?:npipe:\/\/\/\/\.\/pipe\/[a-zA-Z0-9_.-]+|unix:\/\/\/[^/\\\s?#][^\\\s?#]*)$/.test(endpoint))) {
    throw new Error('Only a local Docker named pipe or Unix socket is allowed');
  }
  const env = { ...process.env };
  for (const key of ['DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']) delete env[key];
  dockerTarget = { endpoint, env };
  const ids = checkedOutput(localDocker('compose', '--project-name', 'smartoffice-upgrade',
    '--env-file', resolve(root, 'deploy/.env.upgrade'), '-f', resolve(root, 'deploy/docker-compose.upgrade.yml'),
    'ps', '--quiet', 'redis')).split(/\s+/).filter(Boolean);
  if (ids.length !== 1 || !/^[a-f0-9]{12,64}$/.test(ids[0])) throw new Error('One existing Redis container is required');
  const container = JSON.parse(checkedOutput(localDocker('inspect', '--format', '{{json .}}', ids[0])));
  const labels = container.Config?.Labels;
  if (!/^[a-f0-9]{64}$/.test(container.Id) || labels?.['com.docker.compose.project'] !== 'smartoffice-upgrade'
      || labels?.['com.docker.compose.service'] !== 'redis' || labels?.['com.docker.compose.oneoff'] !== 'False'
      || container.State?.Running !== true) throw new Error('Container must be the running smartoffice-upgrade/redis service');
  dockerTarget.containerId = container.Id;
  result.dockerTarget = { endpoint, project: 'smartoffice-upgrade', service: 'redis', containerId: container.Id };
}

function extractLua(source, name) {
  const expression = new RegExp(`private static final DefaultRedisScript<[^>]+>\\s+${name}\\s*=\\s*new DefaultRedisScript<>\\("""\\r?\\n([\\s\\S]*?)^[ \\t]*""",`, 'm');
  const match = source.match(expression);
  if (!match) throw new Error(`Missing ${name} text block`);
  const lines = match[1].replaceAll('\r\n', '\n').split('\n');
  const indents = lines.filter(line => line.trim()).map(line => line.match(/^[ \t]*/)[0].length);
  const indent = Math.min(...indents);
  return lines.map(line => line.trim() ? line.slice(indent).trimEnd() : '').join('\n');
}

function redis(...command) {
  const argumentsList = [
    'exec', dockerTarget.containerId, 'sh', '-c',
    'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --no-auth-warning --raw "$@"', 'redis-cli',
    ...command.map(String),
  ];
  const startedAt = new Date().toISOString();
  const execution = localDocker(...argumentsList);
  const stdout = (execution.stdout ?? '').replaceAll('\r\n', '\n').trimEnd();
  // Do not publish arbitrary infrastructure stderr; a failed command is reported by type and status.
  result.actualCommands.push({
    step: activeStep, startedAt, executable: 'docker', arguments: ['--host', dockerTarget.endpoint, ...argumentsList],
    exitCode: execution.status, stdout,
    infrastructureErrorType: execution.error?.name ?? null,
    stderrPresent: Boolean(execution.stderr?.trim()),
  });
  if (execution.error || execution.status !== 0 || /^(?:ERR|NOAUTH|WRONGPASS|BUSY|LOADING)\b/m.test(stdout)) {
    throw new Error('Real Redis command failed');
  }
  return stdout;
}

function check(name, actual, expected) {
  activeStep = name;
  const passed = actual === expected;
  result.checks.push({ name, status: passed ? 'PASS' : 'FAIL', expected, actual });
  if (!passed) throw new Error('Real Redis assertion failed');
  console.log(`PASS ${name}`);
}

function checkTtl(name, key, expected) {
  activeStep = name;
  const actual = Number(redis('TTL', key));
  const passed = Number.isInteger(actual) && actual > 0 && actual <= expected && actual >= expected - 10;
  result.checks.push({ name, status: passed ? 'PASS' : 'FAIL', configuredSeconds: expected, actualSeconds: actual });
  if (!passed) throw new Error('Real Redis TTL assertion failed');
  console.log(`PASS ${name} actualSeconds=${actual} configuredSeconds=${expected}`);
}

try {
  const source = readFileSync(sourcePath, 'utf8');
  result.sourceSha256 = createHash('sha256').update(source).digest('hex');
  const scripts = {};
  for (const name of ['VERSION', 'REFILL', 'INVALIDATE']) {
    scripts[name] = extractLua(source, name);
    result.extractedScripts[name] = {
      sha256: createHash('sha256').update(scripts[name]).digest('hex'), source: scripts[name],
    };
  }
  activeStep = 'local-docker-target';
  verifyDockerTarget();
  activeStep = 'real-redis-ping';
  check(activeStep, redis('PING'), 'PONG');
  activeStep = 'exclusive-probe-keys';
  check(activeStep, redis('EXISTS', versionKey, detailKey, lockKey), '0');
  activeStep = 'version-lua-create';
  check(activeStep, redis('EVAL', scripts.VERSION, 1, versionKey, oldVersion, 600), oldVersion);
  checkTtl('version-ttl-600', versionKey, 600);
  activeStep = 'version-lua-preserve-existing';
  check(activeStep, redis('EVAL', scripts.VERSION, 1, versionKey, unusedCandidate, 600), oldVersion);
  activeStep = 'refill-lua-normal';
  check(activeStep, redis('EVAL', scripts.REFILL, 2, versionKey, detailKey, oldVersion, payload, 180), '1');
  activeStep = 'refill-value-readable';
  check(activeStep, redis('GET', detailKey), payload);
  checkTtl('value-ttl-180', detailKey, 180);
  activeStep = 'invalidate-lua';
  check(activeStep, redis('EVAL', scripts.INVALIDATE, 2, versionKey, detailKey, newVersion, 600), '1');
  activeStep = 'invalidate-updated-version';
  check(activeStep, redis('GET', versionKey), newVersion);
  activeStep = 'invalidate-deleted-own-detail';
  check(activeStep, redis('EXISTS', detailKey), '0');
  checkTtl('invalidated-version-ttl-600', versionKey, 600);
  activeStep = 'refill-stale-version-rejected';
  check(activeStep, redis('EVAL', scripts.REFILL, 2, versionKey, detailKey, oldVersion, payload, 180), '0');
  activeStep = 'stale-refill-did-not-create-detail';
  check(activeStep, redis('EXISTS', detailKey), '0');
  activeStep = 'refill-new-version-accepted';
  check(activeStep, redis('EVAL', scripts.REFILL, 2, versionKey, detailKey, newVersion, payload, 180), '1');
  activeStep = 'new-version-value-readable';
  check(activeStep, redis('GET', detailKey), payload);
  checkTtl('new-version-value-ttl-180', detailKey, 180);
  activeStep = 'null-cache-refill';
  check(activeStep, redis('EVAL', scripts.REFILL, 2, versionKey, detailKey, newVersion, 'null', 30), '1');
  activeStep = 'null-cache-readable';
  check(activeStep, redis('GET', detailKey), 'null');
  checkTtl('null-cache-ttl-30', detailKey, 30);
  activeStep = 'no-lock-claim';
  check(activeStep, redis('EXISTS', lockKey), '0');
  result.status = 'PASS';
} catch (error) {
  result.status = 'FAIL';
  result.failure = { step: activeStep, type: error.name };
  console.error(`FAIL ${activeStep} type=${error.name}`);
  process.exitCode = 1;
} finally {
  result.completedAt = new Date().toISOString();
  mkdirSync(outputDirectory, { recursive: true });
  const serialized = `${JSON.stringify(result, null, 2)}\n`;
  writeFileSync(resolve(outputDirectory, `redis-lua-result.${runId}.json`), serialized, 'utf8');
  writeFileSync(resolve(outputDirectory, 'redis-lua-result.json'), serialized, 'utf8');
  console.log(`ARTIFACT ${resolve(outputDirectory, 'redis-lua-result.json')}`);
}
