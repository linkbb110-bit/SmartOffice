#!/usr/bin/env node
/**
 * Local real failure acceptance, one explicitly selected mode per invocation.
 * Node built-ins only. Fixtures are retained; temporary trigger and stopped services
 * are restored in the parent process finally. Never run modes concurrently.
 */
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const modes = ['mysql-lock', 'mysql-rollback', 'redis-outage', 'user-outage'];
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const args = process.argv.slice(2);
let mode;
let baseUrl = 'http://127.0.0.1:18080/api';
for (let index = 0; index < args.length; index++) {
  if (args[index] === '--mode') mode = args[++index];
  else if (args[index] === '--base-url') baseUrl = args[++index];
  else if (args[index] === '--help') {
    console.log(`node deploy/upgrade/real-failure-check.mjs --mode <${modes.join('|')}> [--base-url http://127.0.0.1:18080/api]`);
    console.log('Run serially after all Compose services are healthy. Admin override: REAL_API_ADMIN_USERNAME / REAL_API_ADMIN_PASSWORD.');
    process.exit(0);
  } else throw new Error(`Unknown argument: ${args[index]}`);
}
if (!modes.includes(mode)) throw new Error(`Exactly one --mode is required: ${modes.join(', ')}.`);
const target = new URL(baseUrl);
if (target.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(target.hostname)
  || target.username || target.password || target.search || target.hash || target.pathname.replace(/\/$/, '') !== '/api') {
  throw new Error('Only a credential-free loopback http:// URL ending in /api is allowed.');
}
baseUrl = target.href.replace(/\/$/, '');
const runId = `fail_${Date.now()}_${randomBytes(5).toString('hex')}`;
const compose = ['compose', '--env-file', '.env.upgrade', '-f', 'docker-compose.upgrade.yml'];
const outputDirectory = resolve(root, 'logs/real-integration');
const outputFile = resolve(outputDirectory, `real-failure-${mode}-result.json`);
const historyFile = resolve(outputDirectory, `real-failure-${mode}.${runId}.json`);
const commandFile = resolve(outputDirectory, `real-failure-${mode}.${runId}.log`);
const secrets = new Set();
const tokens = {};
const containers = new Map();
const stoppedServices = new Map();
const requestAbort = new AbortController();
let interrupted = false;
let holder;
let pendingBooking;
let triggerIntent = false;
const triggerName = `real_rollback_${runId}`;
const rollbackTopic = `rollback_${runId}`;
const rollbackSignal = `real_outbox_rollback_${runId}`;
const modeChecks = {
  'mysql-lock': ['lock-observation', 'lock-release-success'],
  'mysql-rollback': ['outbox-rollback', 'post-trigger-recovery'],
  'redis-outage': ['redis-down-fallback', 'redis-restored'],
  'user-outage': ['user-down-fail-closed', 'user-restored'],
};
const report = {
  runId, mode, command: ['node', 'deploy/upgrade/real-failure-check.mjs', '--mode', mode, '--base-url', baseUrl],
  startedAt: new Date().toISOString(), status: 'RUNNING', passed: false, target: baseUrl,
  safety: { loopbackOnly: true, project: 'smartoffice-upgrade', fixturesRetained: true, oldRowsDeleted: false },
  fixtures: { users: {}, rooms: {}, bookings: {} }, environmentFailures: [], requests: [], commands: [], sql: [],
  checks: ['environment', 'fixtures', ...modeChecks[mode], 'cleanup'].map((id) =>
    ({ id, status: 'NOT_EXECUTED', assertions: [], requestIndexes: [], sqlLabels: [] })),
};
let phase = report.checks[0];
class EnvironmentError extends Error {}
const pause = (milliseconds) => new Promise((done) => setTimeout(done, milliseconds));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  interrupted = true;
  requestAbort.abort();
  report.interruptedBy = signal;
});

function redact(value) {
  if (typeof value === 'string') {
    let result = value.replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[REDACTED_TOKEN]');
    for (const secret of secrets) if (secret) result = result.split(secret).join('[REDACTED]');
    return result;
  }
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, child]) =>
    [key, /password|authorization|token|secret/i.test(key) ? '[REDACTED]' : redact(child)]));
  return value;
}

async function persist() {
  await mkdir(outputDirectory, { recursive: true });
  const contents = `${JSON.stringify(redact(report), null, 2)}\n`;
  await writeFile(historyFile, contents, 'utf8');
  await writeFile(outputFile, contents, 'utf8');
  await writeFile(commandFile, [
    `Command: ${report.command.join(' ')}`, `Working directory: ${root}`, `Started: ${report.startedAt}`,
    `Status: ${report.status}`, ...report.checks.map((check) => `${check.status} ${check.id}`),
    ...report.commands.map((record) => `${record.exitCode ?? 'OPEN'} ${record.label}: docker ${record.args.join(' ')}`),
    `SQL/HTTP evidence: ${historyFile}`,
  ].join('\n') + '\n', 'utf8');
}

function assert(condition, description, actual) {
  phase.assertions.push({ passed: Boolean(condition), description, ...(actual === undefined ? {} : { actual }) });
}
function environmentFailure(error, where = phase.id) {
  report.environmentFailures.push({ phase: where, message: redact(error.message || String(error)) });
}
async function check(id, action) {
  phase = report.checks.find((item) => item.id === id);
  phase.startedAt = new Date().toISOString();
  try {
    if (interrupted && id !== 'cleanup') throw new EnvironmentError('Interrupted before this phase.');
    await action();
    phase.status = phase.assertions.some((item) => !item.passed) ? 'FAIL' : 'PASS';
  } catch (error) {
    phase.status = error instanceof EnvironmentError ? 'NOT_EXECUTED' : 'FAIL';
    phase.reason = redact(error.message);
    if (error instanceof EnvironmentError) environmentFailure(error);
    throw error;
  } finally {
    phase.completedAt = new Date().toISOString();
    console.log(`${phase.status} ${id}`);
    await persist();
  }
}

async function docker(label, commandArgs, input = '', timeoutMs = 30_000) {
  const record = { label, phase: phase.id, args: commandArgs, startedAt: new Date().toISOString() };
  report.commands.push(record);
  const result = await new Promise((done) => {
    const child = spawn('docker', commandArgs, { cwd: root, windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    let finished = false;
    const finish = (value) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      done({ stdout: stdout.trim(), stderr: stderr.trim(), ...value });
    };
    const timer = setTimeout(() => { child.kill(); finish({ exitCode: -1, error: `Command timeout after ${timeoutMs} ms.` }); }, timeoutMs);
    child.stdout.on('data', (data) => { stdout += data; });
    child.stderr.on('data', (data) => { stderr += data; });
    child.stdin.on('error', () => {});
    child.on('error', (error) => finish({ exitCode: -1, error: error.message }));
    child.on('close', (exitCode) => finish({ exitCode }));
    child.stdin.end(input);
  });
  Object.assign(record, redact(result), { completedAt: new Date().toISOString() });
  return result;
}

function localEndpoint(endpoint) {
  if (typeof endpoint !== 'string') return false;
  if (/^npipe:\/{2,4}\.\/pipe\//i.test(endpoint) || endpoint.startsWith('unix://')) return true;
  try { return ['localhost', '127.0.0.1', '[::1]'].includes(new URL(endpoint).hostname); }
  catch { return false; }
}
async function requireCommand(label, commandArgs, input = '') {
  const result = await docker(label, commandArgs, input);
  if (result.exitCode !== 0) throw new EnvironmentError(`${label}: ${result.error || result.stderr}`);
  return result;
}
async function state(service) {
  const id = containers.get(service);
  if (!id) throw new EnvironmentError(`Unverified service: ${service}`);
  const result = await requireCommand(`state-${service}`, ['inspect', '--format',
    '{"status":"{{.State.Status}}","running":{{.State.Running}},"health":"{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}"}', id]);
  return JSON.parse(result.stdout);
}
async function verifyContainer(service) {
  const result = await requireCommand(`container-${service}`, [...compose, 'ps', '--all', '-q', service]);
  const ids = result.stdout.split(/\s+/).filter(Boolean);
  if (ids.length !== 1 || !/^[a-f0-9]{12,64}$/.test(ids[0])) throw new EnvironmentError(`Expected exactly one existing ${service} container.`);
  const labels = JSON.parse((await requireCommand(`labels-${service}`, ['inspect', '--format', '{{json .Config.Labels}}', ids[0]])).stdout);
  if (labels['com.docker.compose.project'] !== 'smartoffice-upgrade' || labels['com.docker.compose.service'] !== service) {
    throw new EnvironmentError(`Refusing a container outside smartoffice-upgrade/${service}.`);
  }
  containers.set(service, ids[0]);
  const initial = await state(service);
  if (!initial.running || initial.health !== 'healthy') throw new EnvironmentError(`${service} must initially be running and healthy.`);
  return { id: ids[0], labels, state: initial };
}
async function stopService(service) {
  if (!['redis', 'user-service'].includes(service)) throw new Error('This service cannot be stopped by the probe.');
  const before = await state(service);
  if (!before.running || before.health !== 'healthy') throw new EnvironmentError(`${service} is not healthy before the fault.`);
  const id = containers.get(service);
  // Register compensation before issuing the mutation, including an uncertain stop outcome.
  stoppedServices.set(service, id);
  await requireCommand(`stop-only-${service}`, ['stop', '--time', '10', id]);
  const after = await state(service);
  if (after.running) throw new EnvironmentError(`${service} did not stop.`);
  report[`${service}Fault`] = { containerId: id, before, after };
}
async function restoreService(service) {
  const id = stoppedServices.get(service);
  if (!id) return;
  await requireCommand(`restore-only-${service}`, ['start', id]);
  const deadline = Date.now() + 150_000;
  while (Date.now() < deadline) {
    const restored = await state(service);
    if (restored.running && restored.health === 'healthy') {
      report[`${service}Recovery`] = { containerId: id, state: restored, at: new Date().toISOString() };
      stoppedServices.delete(service);
      return;
    }
    await pause(2500);
  }
  throw new EnvironmentError(`${service} did not become healthy within 150 seconds after restart.`);
}

function mysqlArgs(rootUser = false, unbuffered = false) {
  if (!containers.has('mysql-booking')) throw new EnvironmentError('The local booking MySQL container is not verified.');
  const credential = rootUser ? 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot' : 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h127.0.0.1 -u"$MYSQL_USER"';
  return ['exec', '-i', containers.get('mysql-booking'), 'sh', '-c',
    `${credential} -D"$MYSQL_DATABASE" --batch --raw --skip-column-names --default-character-set=utf8mb4${unbuffered ? ' --unbuffered' : ''}`];
}
async function sql(label, query, rootUser = false) {
  const evidence = { label, phase: phase.id, query, rootUser };
  report.sql.push(evidence);
  phase.sqlLabels.push(label);
  const result = await docker(label, mysqlArgs(rootUser), `${query}\n`);
  Object.assign(evidence, redact(result));
  if (result.exitCode !== 0) throw new EnvironmentError(`${label}: ${result.error || result.stderr}`);
  return result.stdout;
}
async function jsonSql(label, query, rootUser = false) {
  try { return JSON.parse(await sql(label, query, rootUser)); }
  catch (error) { if (error instanceof EnvironmentError) throw error; throw new EnvironmentError(`${label}: expected one MySQL JSON row (${error.message}).`); }
}
function fixtureRoom() {
  const id = report.fixtures.rooms.main?.id;
  if (!Number.isSafeInteger(id) || id < 1) throw new EnvironmentError('A valid test room is not available.');
  return id;
}
function literal(value) {
  if (!/^[A-Za-z0-9_-]{1,200}$/.test(value)) throw new Error('Only generated ASCII identifiers may be interpolated into SQL.');
  return `'${value}'`;
}
async function counts(label, requestId) {
  const room = fixtureRoom();
  const filter = `b.room_id=${room}${requestId ? ` AND b.request_id=${literal(requestId)}` : ''}`;
  return await jsonSql(label, `SELECT JSON_OBJECT(
    'roomId',${room},'orders',(SELECT COUNT(*) FROM booking_order b WHERE ${filter}),
    'booked',(SELECT COUNT(*) FROM booking_order b WHERE ${filter} AND b.status=1),
    'cancelled',(SELECT COUNT(*) FROM booking_order b WHERE ${filter} AND b.status=2),
    'outbox',(SELECT COUNT(*) FROM booking_outbox o JOIN booking_order b ON b.id=o.aggregate_id WHERE ${filter} AND o.event_type='BOOKING_CREATED_V1'),
    'rows',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',b.id,'requestId',b.request_id,'status',b.status)) FROM booking_order b WHERE ${filter}),JSON_ARRAY()),
    'overlappingActivePairs',(SELECT COUNT(*) FROM booking_order b JOIN booking_order other ON other.room_id=b.room_id AND other.id>b.id AND other.status=1 AND b.start_time<other.end_time AND b.end_time>other.start_time WHERE ${filter} AND b.status=1)
  );`);
}
async function http(label, method, path, actor, body, sensitive = false) {
  const record = { index: report.requests.length, phase: phase.id, label, method, path, actor: actor || 'anonymous',
    startedAt: new Date().toISOString(), ...(body === undefined ? {} : { body: redact(body) }) };
  report.requests.push(record);
  phase.requestIndexes.push(record.index);
  const headers = { Accept: 'application/json' };
  if (tokens[actor]) headers.Authorization = `Bearer ${tokens[actor]}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const started = performance.now();
  try {
    const response = await fetch(`${baseUrl}${path}`, { method, headers,
      body: body === undefined ? undefined : JSON.stringify(body), redirect: 'manual',
      signal: AbortSignal.any([AbortSignal.timeout(45_000), requestAbort.signal]) });
    const raw = await response.text();
    let json;
    try { json = JSON.parse(raw); } catch {}
    record.status = response.status;
    record.response = sensitive && json ? { ...json, data: '[REDACTED_TOKEN]' } : redact(json ?? raw.slice(0, 2000));
    if (sensitive && typeof json?.data === 'string') secrets.add(json.data);
    return { status: response.status, json };
  } catch (error) {
    record.status = 0;
    record.error = redact(error.message);
    return { status: 0, error: record.error };
  } finally { record.elapsedMs = Math.round(performance.now() - started); }
}
function status(response, expected, label) {
  if (response.status === 0) throw new EnvironmentError(`${label}: HTTP transport failed (${response.error}).`);
  assert(response.status === expected && response.json?.code === expected, label,
    { expected, actual: response.status, code: response.json?.code, message: response.json?.message });
}
function bookingId(response, label) {
  status(response, 200, label);
  const id = response.json?.data;
  assert(Number.isSafeInteger(id) && id > 0, `${label}: valid booking ID`, id);
  if (response.status === 200 && Number.isSafeInteger(id) && id > 0) return id;
  return undefined;
}
const futureDay = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
function payload(suffix, start = '10:00:00', end = '11:00:00', topic) {
  return { roomId: fixtureRoom(), requestId: `${runId}_${suffix}`, topic: topic || `真实故障验收 ${runId} ${suffix}`,
    startTime: `${futureDay}T${start}`, endTime: `${futureDay}T${end}` };
}
async function book(label, body) { return await http(label, 'POST', '/orders/book', 'owner', body); }

async function prepare() {
  await check('environment', async () => {
    if (process.env.DOCKER_HOST && !localEndpoint(process.env.DOCKER_HOST)) throw new EnvironmentError('Remote DOCKER_HOST is refused.');
    const contexts = JSON.parse((await requireCommand('local-docker-context', ['context', 'inspect'])).stdout);
    const endpoint = process.env.DOCKER_HOST || contexts[0]?.Endpoints?.docker?.Host;
    if (!localEndpoint(endpoint)) throw new EnvironmentError('Remote Docker context is refused.');
    report.dockerContext = { name: contexts[0]?.Name, endpoint };
    report.initialContainers = {};
    for (const service of ['mysql-booking', 'booking-service', 'user-service', 'redis', 'gateway']) {
      report.initialContainers[service] = await verifyContainer(service);
    }
    const engine = await jsonSql('real-mysql-engine', "SELECT JSON_OBJECT('version',VERSION(),'database',DATABASE(),'performanceSchema',@@performance_schema);", mode.startsWith('mysql-'));
    if (!/^8\./.test(engine.version) || engine.database !== 'smartoffice_booking') throw new EnvironmentError('A real MySQL 8 smartoffice_booking schema is required.');
    if (mode === 'mysql-lock' && engine.performanceSchema !== 1) throw new EnvironmentError('MySQL performance_schema is disabled.');
    report.mysql = engine;
    assert(true, 'Local Compose project and real MySQL prerequisites verified', engine);
  });
  await check('fixtures', async () => {
    const username = process.env.REAL_API_ADMIN_USERNAME || 'admin';
    const password = process.env.REAL_API_ADMIN_PASSWORD || '123456';
    secrets.add(password);
    const login = await http('admin-login', 'POST', '/users/login', undefined, { username, password }, true);
    if ([0, 502, 503, 504].includes(login.status)) throw new EnvironmentError('Gateway/user service is not ready for admin login.');
    status(login, 200, 'Real admin login');
    if (typeof login.json?.data !== 'string' || login.status !== 200) throw new Error('Admin login did not provide a token.');
    tokens.admin = login.json.data;
    const adminMe = await http('admin-gateway-me', 'GET', '/users/me', 'admin');
    status(adminMe, 200, 'Admin Gateway identity');
    assert(adminMe.json?.data?.role === 'ADMIN', 'Admin has database ADMIN role', adminMe.json?.data);
    if (adminMe.json?.data?.role !== 'ADMIN') throw new Error('Fixture creator is not an administrator.');
    report.fixtures.users.admin = adminMe.json.data;
    const ownerName = `${runId}_owner`;
    const ownerPassword = `A_${randomBytes(18).toString('hex')}`;
    secrets.add(ownerPassword);
    const registration = await http('owner-register', 'POST', '/users/register', undefined, { username: ownerName, password: ownerPassword });
    const ownerId = bookingId(registration, 'Register unique acceptance user');
    report.fixtures.users.owner = { username: ownerName, userId: ownerId };
    const ownerLogin = await http('owner-login', 'POST', '/users/login', undefined, { username: ownerName, password: ownerPassword }, true);
    status(ownerLogin, 200, 'Acceptance user login');
    if (ownerLogin.status !== 200 || typeof ownerLogin.json?.data !== 'string') throw new Error('Acceptance user login failed.');
    tokens.owner = ownerLogin.json.data;
    const ownerMe = await http('owner-gateway-me', 'GET', '/users/me', 'owner');
    status(ownerMe, 200, 'Acceptance user Gateway identity');
    assert(ownerMe.json?.data?.userId === ownerId && ownerMe.json?.data?.role === 'USER', 'Acceptance user is a real database user', ownerMe.json?.data);
    const roomName = `${runId}_${mode}`;
    const room = await http('admin-create-dedicated-room', 'POST', '/rooms', 'admin', { roomName, capacity: 12, equipment: ['真实故障验收专用'], status: 1 });
    const id = bookingId(room, 'Admin creates dedicated room');
    if (!id) throw new Error('Fixture room creation failed.');
    report.fixtures.rooms.main = { id, roomName };
    await pause(1400);
  });
}

async function beginHolder() {
  const room = fixtureRoom();
  const input = `SET SESSION innodb_lock_wait_timeout=15;\nSTART TRANSACTION;\nSELECT id FROM meeting_room WHERE id=${room} FOR UPDATE;\nSELECT CONCAT('REAL_LOCK_HELD:',CONNECTION_ID());\n`;
  const commandArgs = mysqlArgs(true, true);
  const record = { label: 'hold-only-test-room-transaction', phase: phase.id, args: commandArgs, sql: input, startedAt: new Date().toISOString() };
  report.commands.push(record);
  report.sql.push({ label: record.label, phase: phase.id, query: input, rootUser: true });
  phase.sqlLabels.push(record.label);
  const child = spawn('docker', commandArgs, { cwd: root, windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'] });
  holder = { child, record, stdout: '', stderr: '', closed: false };
  const lease = holder;
  lease.closedPromise = new Promise((done) => {
    child.on('close', (exitCode) => {
      lease.closed = true;
      Object.assign(record, { exitCode, stdout: lease.stdout.trim(), stderr: lease.stderr.trim(), completedAt: new Date().toISOString() });
      done(exitCode);
    });
    child.on('error', (error) => { lease.error = error.message; });
  });
  child.stdout.on('data', (data) => { lease.stdout += data; });
  child.stderr.on('data', (data) => { lease.stderr += data; });
  child.stdin.on('error', () => {});
  child.stdin.write(input);
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline && !lease.closed && !lease.error) {
    const match = lease.stdout.match(/REAL_LOCK_HELD:(\d+)/);
    if (match) {
      lease.connectionId = Number(match[1]);
      report.holderConnectionId = lease.connectionId;
      const identity = await jsonSql('mysql-holder-server-thread', `SELECT JSON_OBJECT('connectionId',${lease.connectionId},
        'threadId',(SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=${lease.connectionId}));`, true);
      lease.serverThreadId = identity.threadId;
      report.holderServerThreadId = identity.threadId;
      if (!Number.isSafeInteger(lease.serverThreadId)) throw new EnvironmentError('Cannot identify the holder server thread for cleanup verification.');
      return lease;
    }
    if (interrupted) throw new EnvironmentError('Interrupted while acquiring fixture row lock.');
    await pause(50);
  }
  throw new EnvironmentError(`Cannot establish the real fixture row lock: ${lease.error || lease.stderr || 'no confirmation from mysql client'}`);
}
async function releaseHolder() {
  if (!holder) return;
  const lease = holder;
  let exitCode = lease.record.exitCode;
  if (!lease.closed) {
    lease.record.releaseSql = 'COMMIT;';
    lease.child.stdin.end('COMMIT;\n');
    const timer = setTimeout(() => { lease.record.releaseTimedOut = true; lease.child.kill(); }, 10_000);
    exitCode = await lease.closedPromise;
    clearTimeout(timer);
  }
  if (!Number.isSafeInteger(lease.connectionId) || !Number.isSafeInteger(lease.serverThreadId)) {
    throw new EnvironmentError('Holder cleanup cannot be verified without its actual MySQL connection and server thread IDs.');
  }
  const deadline = Date.now() + 10_000;
  let evidence;
  let index = 0;
  do {
    evidence = await jsonSql(`mysql-holder-cleanup-${index++}`, `SELECT JSON_OBJECT('connectionId',${lease.connectionId},'serverThreadId',${lease.serverThreadId},
      'processlist',(SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=${lease.connectionId}),
      'transactions',(SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id=${lease.connectionId}),
      'locks',(SELECT COUNT(*) FROM performance_schema.data_locks WHERE THREAD_ID=${lease.serverThreadId}));`, true);
    report.holderCleanup = { ...evidence, clientExitCode: exitCode, timedOut: Boolean(lease.record.releaseTimedOut),
      verified: evidence.processlist === 0 && evidence.transactions === 0 && evidence.locks === 0 };
    if (report.holderCleanup.verified) {
      holder = undefined;
      if (exitCode !== 0 || lease.record.releaseTimedOut) {
        throw new EnvironmentError(`Holder client did not exit normally (exit=${exitCode}, timeout=${Boolean(lease.record.releaseTimedOut)}); actual MySQL cleanup was verified.`);
      }
      return;
    }
    await pause(500);
  } while (Date.now() < deadline);
  throw new EnvironmentError(`Holder connection/transaction/locks remain after release: ${JSON.stringify(evidence)}. No connection was killed.`);
}
function waitQuery(connectionId) {
  return `SELECT JSON_OBJECT('waits',COALESCE((
    SELECT JSON_ARRAYAGG(JSON_OBJECT('blockingConnectionId',blockingThread.PROCESSLIST_ID,'requestingConnectionId',requestingThread.PROCESSLIST_ID,
      'roomLockData',requested.LOCK_DATA,'table',requested.OBJECT_NAME,'state',apiTrx.trx_state,'query',apiTrx.trx_query,'isolation',apiTrx.trx_isolation_level))
    FROM performance_schema.data_lock_waits waits
    JOIN performance_schema.data_locks requested ON requested.ENGINE=waits.ENGINE AND requested.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID
    JOIN performance_schema.data_locks blocking ON blocking.ENGINE=waits.ENGINE AND blocking.ENGINE_LOCK_ID=waits.BLOCKING_ENGINE_LOCK_ID
    JOIN performance_schema.threads blockingThread ON blockingThread.THREAD_ID=blocking.THREAD_ID
    JOIN performance_schema.threads requestingThread ON requestingThread.THREAD_ID=requested.THREAD_ID
    JOIN information_schema.INNODB_TRX apiTrx ON apiTrx.trx_mysql_thread_id=requestingThread.PROCESSLIST_ID
    WHERE blockingThread.PROCESSLIST_ID=${connectionId} AND requested.OBJECT_SCHEMA=DATABASE()
      AND requested.OBJECT_NAME='meeting_room' AND requested.LOCK_TYPE='RECORD' AND requested.LOCK_DATA='${fixtureRoom()}'
  ),JSON_ARRAY()),'holderTransaction',(SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id=${connectionId}));`;
}
async function mysqlLock() {
  const body = payload('lock');
  await check('lock-observation', async () => {
    // Probe table/column access before introducing a fault; a privilege issue is environmental.
    await jsonSql('lock-instrumentation-access', waitQuery(0), true);
    const lease = await beginHolder();
    let settled = false;
    pendingBooking = book('booking-while-real-row-locked', body).then((response) => { settled = true; return response; });
    let observation;
    const deadline = Date.now() + 10_000;
    let index = 0;
    while (Date.now() < deadline && !settled && !interrupted) {
      observation = await jsonSql(`actual-api-lock-wait-${index++}`, waitQuery(lease.connectionId), true);
      if (observation.waits?.length) break;
      await pause(200);
    }
    const waits = observation?.waits || [];
    assert(waits.length > 0 && !settled && observation.holderTransaction === 1,
      'API transaction is observed waiting on this room record held by this exact mysql connection', observation);
    assert(waits.length > 0 && waits.every((item) => item.blockingConnectionId === lease.connectionId
      && item.state === 'LOCK WAIT' && /FOR UPDATE/i.test(item.query || '')),
    'performance_schema wait and INNODB_TRX identify the actual application SELECT FOR UPDATE', waits);
    report.applicationTransactionIsolation = waits.map((item) => ({ connectionId: item.requestingConnectionId, isolation: item.isolation }));
    assert(waits.length > 0 && waits.every((item) => String(item.isolation).replace(/[ -]/g, '_') === 'READ_COMMITTED'),
      'Actual waiting application transaction uses READ COMMITTED', report.applicationTransactionIsolation);
  });
  await check('lock-release-success', async () => {
    await releaseHolder();
    const response = await pendingBooking;
    pendingBooking = undefined;
    report.fixtures.bookings.lock = bookingId(response, 'Booking succeeds after releasing the real database lock');
    const data = await counts('lock-order-and-outbox', body.requestId);
    assert(data.orders === 1 && data.booked === 1 && data.outbox === 1, 'Released lock produces exactly one order and one outbox event', data);
  });
}

async function triggerMetadata() {
  return await jsonSql('own-trigger-metadata', `SELECT JSON_OBJECT('count',COUNT(*),'statement',MAX(ACTION_STATEMENT),'table',MAX(EVENT_OBJECT_TABLE),'timing',MAX(ACTION_TIMING),'event',MAX(EVENT_MANIPULATION))
    FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=${literal(triggerName)};`, true);
}
async function removeOwnTrigger() {
  if (!triggerIntent) return;
  const metadata = await triggerMetadata();
  if (metadata.count === 0) { triggerIntent = false; return; }
  if (metadata.count !== 1 || metadata.table !== 'booking_outbox' || metadata.timing !== 'BEFORE' || metadata.event !== 'INSERT'
    || !metadata.statement?.includes(rollbackTopic) || !metadata.statement?.includes(rollbackSignal)) throw new EnvironmentError('Refusing to drop a trigger whose ownership/scope cannot be verified.');
  await sql('drop-only-this-run-trigger', `DROP TRIGGER ${triggerName};`, true);
  const after = await triggerMetadata();
  if (after.count !== 0) throw new EnvironmentError('The temporary test trigger was not removed.');
  triggerIntent = false;
  report.temporaryTriggerRemoved = true;
}
async function verifyRollbackApplicationLog(since) {
  let logs;
  let matches = [];
  for (let attempt = 0; attempt < 3; attempt++) {
    const result = await requireCommand(`booking-rollback-error-log-${attempt}`, ['logs', '--since', since, '--timestamps', containers.get('booking-service')]);
    logs = `${result.stdout}\n${result.stderr}`.trim();
    matches = logs.split(/\r?\n/).filter((line) => line.includes(rollbackSignal)
      && /SQLException|SQL.?state\s*\[?45000|SQLSTATE\s*[:=]?\s*45000/i.test(line));
    if (matches.length) break;
    await pause(500);
  }
  const logFile = resolve(outputDirectory, `real-failure-mysql-rollback.${runId}.booking-error.log`);
  await writeFile(logFile, `${redact(logs || '')}\n`, 'utf8');
  report.rollbackApplicationError = { since, containerId: containers.get('booking-service'), logFile,
    signalMessage: rollbackSignal, matchedExceptionLines: redact(matches) };
  assert(matches.length > 0, 'Actual booking-service SQLException/SQLSTATE log identifies this run\'s unique Outbox trigger failure', report.rollbackApplicationError);
}
async function mysqlRollback() {
  const body = payload('rollback', '10:00:00', '11:00:00', rollbackTopic);
  await check('outbox-rollback', async () => {
    const before = await triggerMetadata();
    if (before.count !== 0) throw new EnvironmentError('Refusing to reuse any existing trigger.');
    triggerIntent = true;
    report.temporaryTrigger = { name: triggerName, topic: rollbackTopic, signalMessage: rollbackSignal, existingBefore: before.count };
    await sql('create-only-topic-scoped-outbox-trigger', `DELIMITER $$
CREATE TRIGGER ${triggerName} BEFORE INSERT ON booking_outbox FOR EACH ROW
BEGIN
  IF JSON_UNQUOTE(JSON_EXTRACT(NEW.payload,'$.topic'))=${literal(rollbackTopic)}
    AND CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.payload,'$.roomId')) AS UNSIGNED)=${fixtureRoom()} THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=${literal(rollbackSignal)};
  END IF;
END$$
DELIMITER ;`, true);
    try {
      const requestStartedAt = new Date().toISOString();
      const response = await book('trigger-rejects-outbox-in-real-http-transaction', body);
      status(response, 500, 'Outbox INSERT failure returns real HTTP 500');
      await verifyRollbackApplicationLog(requestStartedAt);
      const data = await jsonSql('rollback-has-no-order-or-outbox', `SELECT JSON_OBJECT(
        'orders',(SELECT COUNT(*) FROM booking_order WHERE room_id=${fixtureRoom()} AND request_id=${literal(body.requestId)}),
        'outbox',(SELECT COUNT(*) FROM booking_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.topic'))=${literal(rollbackTopic)}
          AND CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.roomId')) AS UNSIGNED)=${fixtureRoom()}));`);
      assert(data.orders === 0 && data.outbox === 0, 'The failed Outbox INSERT rolls back the booking and leaves no orphan event', data);
    } finally { await removeOwnTrigger(); }
  });
  await check('post-trigger-recovery', async () => {
    await pause(1400);
    const response = await book('same-request-recovers-after-own-trigger-removed', body);
    report.fixtures.bookings.rollbackRecovered = bookingId(response, 'The same request succeeds after removing the test trigger');
    const data = await counts('rollback-recovery-one-order-one-event', body.requestId);
    assert(data.orders === 1 && data.booked === 1 && data.outbox === 1, 'Rollback leaves no phantom idempotency key and recovery creates one event', data);
  });
}

async function redisOutage() {
  await check('redis-down-fallback', async () => {
    await stopService('redis');
    const detail = await http('room-detail-with-redis-stopped', 'GET', `/rooms/${fixtureRoom()}`, 'owner');
    status(detail, 200, 'Room detail succeeds through real MySQL during Redis outage');
    assert(detail.json?.data?.id === fixtureRoom(), 'Redis fallback returns the dedicated real room', detail.json?.data?.id);
    await pause(1400);
    const overlaps = await Promise.all(Array.from({ length: 12 }, (_, index) => book(`redis-down-overlap-${index}`, payload(`overlap_${index}`))));
    const winners = overlaps.filter((response) => response.status === 200);
    assert(winners.length === 1 && overlaps.every((response) => [200, 409].includes(response.status)),
      'Without Redis, 12 HTTP overlaps have one success and remaining conflicts', overlaps.map((response) => response.status));
    report.fixtures.bookings.redisOverlap = winners.map((response) => response.json?.data);
    const overlapData = await counts('redis-down-overlap-real-mysql');
    assert(overlapData.orders === 1 && overlapData.booked === 1 && overlapData.outbox === 1, 'MySQL alone preserves one active overlap and one event', overlapData);
    await pause(1400);
    const idem = payload('idempotent', '12:00:00', '13:00:00');
    const id = bookingId(await book('redis-down-first-key', idem), 'Idempotent booking during Redis outage');
    report.fixtures.bookings.redisIdempotent = id;
    const replay = await book('redis-down-replay', idem);
    status(replay, 200, 'Same-key replay succeeds without Redis');
    assert(Boolean(id) && replay.json?.data === id, 'Same-key replay returns the original ID during outage', replay.json?.data);
    const keyed = await counts('redis-down-keyed-one-event', idem.requestId);
    assert(keyed.orders === 1 && keyed.outbox === 1, 'Redis outage does not duplicate idempotent order/outbox', keyed);
    if (id) {
      for (let index = 0; index < 2; index++) status(await http(`redis-down-cancel-${index}`, 'POST', `/orders/${id}/cancel`, 'owner'), 200, `CAS cancellation ${index} works without Redis`);
      report.fixtures.bookings.redisReplacement = bookingId(await book('redis-down-replacement', payload('replacement', '12:00:00', '13:00:00')), 'Cancelled interval can be replaced without Redis');
    }
    const final = await counts('redis-down-final-scoped-integrity');
    assert(final.orders === 3 && final.cancelled === 1 && final.booked === 2 && final.outbox === 3 && final.overlappingActivePairs === 0,
      'Real database remains consistent throughout Redis outage', final);
  });
  await check('redis-restored', async () => {
    await restoreService('redis');
    let detail;
    let result;
    const deadline = Date.now() + 30_000;
    do {
      detail = await http('room-detail-after-redis-restored', 'GET', `/rooms/${fixtureRoom()}`, 'owner');
      result = await requireCommand('redis-own-room-cache-recovered', ['exec', containers.get('redis'), 'sh', '-c',
        `REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli EXISTS "smartoffice:{room:${fixtureRoom()}}:detail"`]);
      if (detail.status === 200 && result.stdout === '1') break;
      await pause(2000);
    } while (Date.now() < deadline && !interrupted);
    status(detail, 200, 'Room detail succeeds after healthy Redis restart');
    assert(result.stdout === '1', 'Actual Redis detail cache for this room is populated again', result.stdout);
    await pause(1400);
    const body = payload('redis_recovered', '16:00:00', '17:00:00');
    report.fixtures.bookings.redisRecovered = bookingId(await book('booking-after-redis-healthy', body), 'Booking succeeds after Redis recovery');
    const data = await counts('redis-recovered-order-and-event', body.requestId);
    assert(data.orders === 1 && data.booked === 1 && data.outbox === 1, 'Recovered Redis booking has one real order and outbox row', data);
  });
}
async function waitUserRoute() {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline && !interrupted) {
    const me = await http('user-route-recovery-probe', 'GET', '/users/me', 'owner');
    if (me.status === 200 && me.json?.data?.userId === report.fixtures.users.owner.userId) return;
    await pause(2500);
  }
  throw new EnvironmentError('The healthy user-service route did not recover through Gateway within 90 seconds.');
}
async function userOutage() {
  const blockedBody = payload('user_blocked', '12:00:00', '13:00:00');
  await check('user-down-fail-closed', async () => {
    const existingBody = payload('existing');
    const id = bookingId(await book('existing-order-before-user-fault', existingBody), 'Create existing order before user-service fault');
    if (!id) throw new Error('A pre-outage order is required to verify cancellation.');
    report.fixtures.bookings.userExisting = id;
    await stopService('user-service');
    await pause(1400);
    const blocked = await book('new-booking-with-user-service-stopped', blockedBody);
    status(blocked, 503, 'New booking fails closed with 503 when user verification is unavailable');
    assert(blocked.json?.message === '用户服务暂不可用，请稍后重试预约', '503 is the application user-verifier unavailable response', blocked.json?.message);
    const noWrite = await counts('user-down-failed-request-has-no-row', blockedBody.requestId);
    assert(noWrite.orders === 0 && noWrite.outbox === 0, 'Unavailable user verification writes no order or outbox', noWrite);
    const detail = await http('room-query-during-user-outage', 'GET', `/rooms/${fixtureRoom()}`, 'owner');
    status(detail, 200, 'Room query remains usable with the existing local JWT');
    const orders = await http('orders-query-during-user-outage', 'GET', '/orders/my', 'owner');
    status(orders, 200, 'Order query remains usable during user-service outage');
    assert(Array.isArray(orders.json?.data) && orders.json.data.some((order) => order.id === id && order.status === 1), 'Existing dedicated order is visible', id);
    status(await http('existing-order-cancel-during-user-outage', 'POST', `/orders/${id}/cancel`, 'owner'), 200, 'Existing order can be cancelled during user-service outage');
    const cancelled = await counts('user-down-existing-cancelled', existingBody.requestId);
    assert(cancelled.orders === 1 && cancelled.cancelled === 1 && cancelled.booked === 0 && cancelled.outbox === 1,
      'Cancellation changes only the existing order state and retains its event', cancelled);
  });
  await check('user-restored', async () => {
    await restoreService('user-service');
    await waitUserRoute();
    await pause(1400);
    // Gateway discovery and booking-service's Feign/load-balancer cache recover independently.
    // A retry uses the same key so an ambiguous successful response cannot duplicate data.
    let recovered;
    const deadline = Date.now() + 90_000;
    do {
      recovered = await book('failed-request-retry-after-user-healthy', blockedBody);
      if (recovered.status !== 503 || recovered.json?.message !== '用户服务暂不可用，请稍后重试预约') break;
      await pause(2500);
    } while (Date.now() < deadline && !interrupted);
    report.fixtures.bookings.userRecovered = bookingId(recovered, 'The failed request succeeds after healthy user-service recovery');
    const data = await counts('user-recovered-one-order-one-event', blockedBody.requestId);
    assert(data.orders === 1 && data.booked === 1 && data.outbox === 1, 'User-service recovery produces exactly one new booking/event', data);
  });
}

try {
  await persist();
  console.log(`Real failure acceptance ${mode}: ${runId}`);
  await prepare();
  await ({ 'mysql-lock': mysqlLock, 'mysql-rollback': mysqlRollback, 'redis-outage': redisOutage, 'user-outage': userOutage })[mode]();
} catch (error) {
  report.error = redact(error.message);
} finally {
  try {
    await check('cleanup', async () => {
      const failures = [];
      try { await releaseHolder(); } catch (error) { failures.push(error); }
      try { await removeOwnTrigger(); } catch (error) { failures.push(error); }
      for (const service of [...stoppedServices.keys()]) {
        try { await restoreService(service); } catch (error) { failures.push(error); }
      }
      if (pendingBooking) { await pendingBooking; pendingBooking = undefined; }
      if (failures.length) throw new EnvironmentError(`Fault restoration failed: ${failures.map((error) => error.message).join('; ')}`);
      const verified = !holder && !triggerIntent && stoppedServices.size === 0
        && (!report.holderConnectionId || report.holderCleanup?.verified === true);
      assert(verified, 'Parent process restored every introduced fault with actual MySQL connection/lock cleanup evidence; all data fixtures remain', report.holderCleanup);
      report.cleanupComplete = verified;
    });
  } catch (error) { report.cleanupError = redact(error.message); }
  report.completedAt = new Date().toISOString();
  for (const item of report.checks) if (item.status === 'NOT_EXECUTED' && !item.reason) item.reason = 'A prerequisite phase did not complete.';
  report.summary = {
    PASS: report.checks.filter((item) => item.status === 'PASS').length,
    FAIL: report.checks.filter((item) => item.status === 'FAIL').length,
    NOT_EXECUTED: report.checks.filter((item) => item.status === 'NOT_EXECUTED').length,
    environmentFailures: report.environmentFailures.length, requests: report.requests.length, sqlQueries: report.sql.length,
  };
  report.passed = !report.error && !report.cleanupError && !interrupted && report.summary.FAIL === 0 && report.summary.NOT_EXECUTED === 0;
  report.status = report.passed ? 'PASS' : (report.summary.FAIL > 0 ? 'FAIL' : 'NOT_EXECUTED');
  report.exitCode = report.passed ? 0 : (report.summary.FAIL > 0 ? 1 : 2);
  await persist();
  console.log(`${report.status}: ${JSON.stringify(report.summary)}`);
  console.log(`Report: ${outputFile}`);
  console.log(`Command log: ${commandFile}`);
  process.exitCode = report.exitCode;
}
