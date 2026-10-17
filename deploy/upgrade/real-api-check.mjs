#!/usr/bin/env node
/**
 * Real local Compose acceptance checks. Uses only Node built-ins.
 * Run from any directory: node deploy/upgrade/real-api-check.mjs --with-sentinel-burst
 * Optional credentials: REAL_API_ADMIN_USERNAME / REAL_API_ADMIN_PASSWORD.
 * Fixtures are retained. No DELETE, schema migration, or container lifecycle command is used.
 */
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const args = process.argv.slice(2);
let baseUrl = 'http://127.0.0.1:18080/api';
let withSentinelBurst = false;
for (let index = 0; index < args.length; index++) {
  if (args[index] === '--base-url') baseUrl = args[++index];
  else if (args[index] === '--with-sentinel-burst') withSentinelBurst = true;
  else if (args[index] === '--help') {
    console.log('node deploy/upgrade/real-api-check.mjs [--base-url http://127.0.0.1:18080/api] [--with-sentinel-burst]');
    console.log('Optional admin credential overrides: REAL_API_ADMIN_USERNAME / REAL_API_ADMIN_PASSWORD; otherwise uses local seed credentials.');
    process.exit(0);
  } else throw new Error(`Unknown argument: ${args[index]}`);
}
const target = new URL(baseUrl);
if (target.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname)
  || target.username || target.password || target.search || target.hash || target.pathname.replace(/\/$/, '') !== '/api') {
  throw new Error('The target must be a loopback http:// URL ending in /api, without credentials, query, or fragment.');
}
baseUrl = target.href.replace(/\/$/, '');

const runId = `real_${new Date().toISOString().replace(/[-:.]/g, '').slice(0, 15)}_${randomBytes(4).toString('hex')}`;
const outputDirectory = resolve(root, 'logs/real-integration');
const outputFile = resolve(outputDirectory, 'real-api-result.json');
const historyFile = resolve(outputDirectory, `real-api-result.${runId}.json`);
const composeArgs = ['compose', '--env-file', 'deploy/.env.upgrade', '-f', 'deploy/docker-compose.upgrade.yml'];
const adminUsername = process.env.REAL_API_ADMIN_USERNAME || 'admin';
const adminPassword = process.env.REAL_API_ADMIN_PASSWORD || '123456';
const secrets = new Set([adminPassword]);
const tokens = {};
const roomLabels = ['overlap', 'idempotency', 'boundaries', 'cancellation', 'validation', 'cache'];
const report = {
  runId, startedAt: new Date().toISOString(), completedAt: null,
  status: 'RUNNING', passed: false, target: baseUrl,
  environment: { architecture: 'Docker Compose upgrade / Gateway HTTP / real MySQL', database: 'not yet verified' },
  safety: { loopbackOnly: true, fixturesRetained: true, sqlReadOnly: true, containerLifecycleChanged: false },
  fixtures: { users: {}, rooms: {}, bookings: {} },
  checks: [], requests: [], sql: [], commands: [],
};
let currentCheck;
let dockerVerified = false;
const pause = (milliseconds) => new Promise((done) => setTimeout(done, milliseconds));

function redact(value) {
  if (typeof value === 'string') {
    let clean = value.replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[REDACTED_TOKEN]');
    for (const secret of secrets) if (secret) clean = clean.split(secret).join('[REDACTED]');
    return clean;
  }
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, child]) =>
      [key, /password|authorization|token|secret/i.test(key) ? '[REDACTED]' : redact(child)]));
  }
  return value;
}

async function persist() {
  await mkdir(outputDirectory, { recursive: true });
  const contents = `${JSON.stringify(redact(report), null, 2)}\n`;
  await writeFile(historyFile, contents, 'utf8');
  await writeFile(outputFile, contents, 'utf8');
}

function assert(condition, description, actual) {
  currentCheck.assertions.push({ passed: Boolean(condition), description, ...(actual === undefined ? {} : { actual }) });
}

async function execute(command, commandArgs, input = '') {
  return await new Promise((done) => {
    const child = spawn(command, commandArgs, { cwd: root, windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    let finished = false;
    const timer = setTimeout(() => {
      child.kill();
      finish({ code: -1, error: 'Command timeout after 30 seconds' });
    }, 30_000);
    const finish = (result) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      done({ stdout: stdout.trim(), stderr: stderr.trim(), ...result });
    };
    child.stdout.on('data', (data) => { stdout += data; });
    child.stderr.on('data', (data) => { stderr += data; });
    child.on('error', (error) => finish({ code: -1, error: error.message }));
    child.stdin.on('error', () => {});
    child.on('close', (code) => finish({ code }));
    child.stdin.end(input);
  });
}

function isLocalDockerEndpoint(endpoint) {
  if (typeof endpoint !== 'string') return false;
  if (/^npipe:\/{2,4}\.\/pipe\//i.test(endpoint) || endpoint.startsWith('unix://')) return true;
  try { return ['127.0.0.1', 'localhost', '[::1]'].includes(new URL(endpoint).hostname); }
  catch { return false; }
}

async function verifyLocalDocker() {
  const environmentEndpoint = process.env.DOCKER_HOST;
  if (environmentEndpoint && !isLocalDockerEndpoint(environmentEndpoint)) throw new Error('Remote DOCKER_HOST is refused.');
  const result = await execute('docker', ['context', 'inspect']);
  if (result.code !== 0) throw new Error(`Cannot inspect Docker context: ${result.error || result.stderr}`);
  const contexts = JSON.parse(result.stdout);
  const endpoint = contexts[0]?.Endpoints?.docker?.Host;
  if (!environmentEndpoint && !isLocalDockerEndpoint(endpoint)) throw new Error('Remote Docker context is refused.');
  report.environment.dockerContext = { name: contexts[0]?.Name, endpoint: environmentEndpoint || endpoint };
  dockerVerified = true;
}

async function sql(label, query) {
  if (!dockerVerified) throw new Error('Local Docker endpoint has not been verified.');
  if (!/^\s*SELECT\b/i.test(query) || /;\s*\S/.test(query) || /\b(INTO|OUTFILE|DUMPFILE|FOR\s+UPDATE)\b/i.test(query)) {
    throw new Error('Only one read-only SELECT is permitted.');
  }
  const result = await execute('docker', [
    ...composeArgs, 'exec', '-T', 'mysql-booking', 'sh', '-c',
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h127.0.0.1 -u"$MYSQL_USER" -D"$MYSQL_DATABASE" --batch --raw --skip-column-names --default-character-set=utf8mb4',
  ], `${query.replace(/;\s*$/, '')};\n`);
  const evidence = { label, check: currentCheck.id, query, exitCode: result.code, stdout: result.stdout,
    ...(result.stderr ? { stderr: result.stderr } : {}), ...(result.error ? { error: result.error } : {}) };
  report.sql.push(evidence);
  currentCheck.sqlLabels.push(label);
  if (result.code !== 0) throw new Error(`MySQL query ${label} failed: ${result.error || result.stderr}`);
  try { evidence.data = JSON.parse(result.stdout); }
  catch { throw new Error(`MySQL query ${label} did not return one JSON row.`); }
  return evidence.data;
}

async function redisRead(label, operation, suffix) {
  if (!dockerVerified) throw new Error('Local Docker endpoint has not been verified.');
  if (!['GET', 'TTL'].includes(operation) || !['detail', 'version'].includes(suffix)) throw new Error('Only scoped Redis GET/TTL reads are allowed.');
  const key = `smartoffice:{room:${roomId('cache')}}:${suffix}`;
  const commandArgs = [...composeArgs, 'exec', '-T', 'redis', 'sh', '-c',
    'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw "$1" "$2"', 'real-api-cache-read', operation, key];
  const record = { index: report.commands.length, check: currentCheck.id, label, command: 'docker', args: commandArgs,
    redisCommand: ['redis-cli', '--raw', operation, key], startedAt: new Date().toISOString() };
  report.commands.push(record);
  currentCheck.commandIndexes.push(record.index);
  const result = await execute('docker', commandArgs);
  Object.assign(record, redact(result), { completedAt: new Date().toISOString() });
  if (result.code !== 0 || /^(?:\(error\)\s*)?(?:ERR|NOAUTH|WRONGPASS|WRONGTYPE)\b/m.test(result.stdout)) {
    throw new Error(`Redis read ${label} failed: ${result.error || result.stderr || result.stdout}`);
  }
  return result.stdout;
}

async function redisSnapshot(label) {
  const [detailRaw, version, detailTtlRaw, versionTtlRaw] = await Promise.all([
    redisRead(`${label}-GET-detail`, 'GET', 'detail'), redisRead(`${label}-GET-version`, 'GET', 'version'),
    redisRead(`${label}-TTL-detail`, 'TTL', 'detail'), redisRead(`${label}-TTL-version`, 'TTL', 'version'),
  ]);
  let detail = null;
  if (detailRaw) {
    try { detail = JSON.parse(detailRaw); }
    catch { throw new Error(`${label}: Redis room detail is not JSON.`); }
  }
  const snapshot = { detailRaw, detail, version, detailTtl: Number(detailTtlRaw), versionTtl: Number(versionTtlRaw) };
  assert(detailTtlRaw !== '' && versionTtlRaw !== '' && Number.isInteger(snapshot.detailTtl) && Number.isInteger(snapshot.versionTtl),
    `${label}: Redis returns actual integer TTL evidence`, snapshot);
  return snapshot;
}

function roomId(label) {
  const id = report.fixtures.rooms[label]?.id;
  if (!Number.isSafeInteger(id) || id < 1) throw new Error(`Fixture room ${label} is unavailable.`);
  return id;
}

function sqlString(value) {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(value)) throw new Error('Unsafe fixture request ID.');
  return `'${value}'`;
}

async function counts(label, key) {
  const id = roomId(label);
  const filter = `b.room_id=${id}${key ? ` AND b.request_id=${sqlString(key)}` : ''}`;
  return await sql(`${label}${key ? '-request' : ''}-counts`, `SELECT JSON_OBJECT(
    'roomId',${id}, 'orders',(SELECT COUNT(*) FROM booking_order b WHERE ${filter}),
    'booked',(SELECT COUNT(*) FROM booking_order b WHERE ${filter} AND b.status=1),
    'cancelled',(SELECT COUNT(*) FROM booking_order b WHERE ${filter} AND b.status=2),
    'outbox',(SELECT COUNT(*) FROM booking_outbox o JOIN booking_order b ON b.id=o.aggregate_id WHERE ${filter} AND o.event_type='BOOKING_CREATED_V1'),
    'rows',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',b.id,'userId',b.user_id,'requestId',b.request_id,'status',b.status,'startTime',b.start_time,'endTime',b.end_time)) FROM booking_order b WHERE ${filter}),JSON_ARRAY())
  )`);
}

async function request(label, method, path, actor, body, options = {}) {
  const startedAt = new Date().toISOString();
  const start = performance.now();
  const headers = { Accept: 'application/json' };
  if (tokens[actor]) headers.Authorization = `Bearer ${tokens[actor]}`;
  if (body !== undefined) headers['Content-Type'] = options.contentType || 'application/json';
  const record = { index: report.requests.length, check: currentCheck.id, label, method, path, actor: actor || 'anonymous', startedAt,
    ...(body === undefined ? {} : { body: options.raw ? '[RAW_BODY]' : redact(body) }) };
  report.requests.push(record);
  currentCheck.requestIndexes.push(record.index);
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method, headers, body: body === undefined ? undefined : (options.raw ? body : JSON.stringify(body)),
      signal: AbortSignal.timeout(20_000), redirect: 'manual',
    });
    const raw = await response.text();
    let json;
    try { json = JSON.parse(raw); } catch {}
    record.status = response.status;
    record.contentType = response.headers.get('content-type');
    record.response = options.sensitiveResponse && json
      ? { ...json, data: '[REDACTED_TOKEN]' } : redact(json ?? raw.slice(0, 2000));
    if (options.sensitiveResponse && typeof json?.data === 'string') secrets.add(json.data);
    return { status: response.status, json, raw };
  } catch (error) {
    record.status = 0;
    record.error = redact(error.message);
    return { status: 0, error: record.error };
  } finally { record.elapsedMs = Math.round(performance.now() - start); }
}

function expectStatus(response, expected, label) {
  assert(response.status === expected && response.json?.code === expected, label,
    { expected, status: response.status, code: response.json?.code, message: response.json?.message, error: response.error });
}

function successId(response, label) {
  expectStatus(response, 200, label);
  const id = response.json?.data;
  assert(Number.isSafeInteger(id) && id > 0, `${label}: positive safe integer ID`, id);
  return response.status === 200 && Number.isSafeInteger(id) && id > 0 ? id : undefined;
}

const futureDay = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
const time = (clock) => `${futureDay}T${clock}`;
const requestId = (suffix) => `${runId}_${suffix}`;
function booking(label, suffix, start = '10:00:00', end = '11:00:00') {
  return { roomId: roomId(label), requestId: requestId(suffix), topic: `真实验收 ${runId} ${suffix}`, startTime: time(start), endTime: time(end) };
}

function hasUsers(...actors) { return actors.every((actor) => Boolean(tokens[actor])); }
function hasRooms(...labels) { return labels.every((label) => Number.isSafeInteger(report.fixtures.rooms[label]?.id)); }

async function runCheck(check) {
  currentCheck = check;
  if (!check.enabled) { check.reason = 'Optional burst requires --with-sentinel-burst.'; return; }
  if (!check.ready()) { check.reason = 'Required fixture or verified local Docker/MySQL is unavailable.'; return; }
  // 12-request correctness groups stay below the default Sentinel 20 QPS window.
  await pause(1400);
  check.startedAt = new Date().toISOString();
  try {
    await check.run();
    check.status = check.assertions.some((item) => !item.passed) ? 'FAIL' : 'PASS';
  } catch (error) { check.status = 'FAIL'; check.error = redact(error.message); }
  check.completedAt = new Date().toISOString();
  console.log(`${check.status} ${check.id}`);
  await persist();
}

function addCheck(id, title, ready, run, required = true, enabled = true) {
  report.checks.push({ id, title, required, enabled, status: 'NOT_EXECUTED', assertions: [], requestIndexes: [], sqlLabels: [], commandIndexes: [], ready, run });
}

addCheck('real-mysql', 'Verify a local Docker context and real MySQL database', () => true, async () => {
  await verifyLocalDocker();
  const data = await sql('mysql-engine', "SELECT JSON_OBJECT('version',VERSION(),'database',DATABASE(),'hostname',@@hostname,'currentUser',CURRENT_USER())");
  assert(/^8\./.test(data.version), 'MySQL 8 server version', data.version);
  assert(data.database === 'smartoffice_booking', 'Compose booking database', data.database);
  report.environment.database = data;
});

addCheck('admin-login', 'HTTP admin login and Gateway /users/me', () => true, async () => {
  const login = await request('admin-login', 'POST', '/users/login', undefined,
    { username: adminUsername, password: adminPassword }, { sensitiveResponse: true });
  expectStatus(login, 200, 'Admin login');
  if (login.status !== 200 || typeof login.json?.data !== 'string') return;
  tokens.admin = login.json.data;
  const me = await request('admin-gateway-me', 'GET', '/users/me', 'admin');
  expectStatus(me, 200, 'Admin Gateway me');
  assert(me.json?.data?.username === adminUsername && me.json?.data?.role === 'ADMIN', 'Database admin identity and role', me.json?.data);
  if (me.json?.data?.role !== 'ADMIN') delete tokens.admin;
  report.fixtures.users.admin = me.json?.data;
});

for (const actor of ['owner', 'intruder']) {
  addCheck(`${actor}-registration-login`, `Unique ${actor} registration, HTTP login, and Gateway identity`, () => true, async () => {
    const username = `${runId}_${actor}`;
    const password = `A_${randomBytes(18).toString('hex')}`;
    secrets.add(password);
    const registration = await request(`${actor}-register`, 'POST', '/users/register', undefined, { username, password });
    const userId = successId(registration, `${actor} registration`);
    report.fixtures.users[actor] = { username, userId };
    const login = await request(`${actor}-login`, 'POST', '/users/login', undefined, { username, password }, { sensitiveResponse: true });
    expectStatus(login, 200, `${actor} HTTP login`);
    if (login.status !== 200 || typeof login.json?.data !== 'string') return;
    tokens[actor] = login.json.data;
    const me = await request(`${actor}-gateway-me`, 'GET', '/users/me', actor);
    expectStatus(me, 200, `${actor} Gateway me`);
    assert(me.json?.data?.userId === userId && me.json?.data?.username === username && me.json?.data?.role === 'USER',
      `${actor} registered database identity`, me.json?.data);
  });
}

addCheck('fixture-rooms', 'Administrator creates dedicated retained rooms', () => hasUsers('admin'), async () => {
  for (const label of roomLabels) {
    const roomName = `${runId}_${label}`;
    const response = await request(`create-${label}`, 'POST', '/rooms', 'admin', { roomName, capacity: 12, equipment: ['验收专用'], status: 1 });
    const id = successId(response, `Create ${label} room`);
    report.fixtures.rooms[label] = { id, roomName };
    if (id) {
      const detail = await request(`detail-${label}`, 'GET', `/rooms/${id}`, 'admin');
      expectStatus(detail, 200, `Read ${label} room through Gateway`);
      assert(detail.json?.data?.roomName === roomName, `${label} room matches this run`, detail.json?.data?.roomName);
    }
  }
});

addCheck('authentication-authorization', 'Missing bearer 401 and regular-user admin access 403', () => hasUsers('owner'), async () => {
  const anonymous = await request('missing-bearer', 'GET', '/rooms/list');
  expectStatus(anonymous, 401, 'Missing bearer');
  for (const path of ['/orders', '/orders/export', '/admin/messages/outbox']) {
    expectStatus(await request(`forbidden-${path}`, 'GET', path, 'owner'), 403, `User forbidden ${path}`);
  }
  const forbidden = await request('user-create-room', 'POST', '/rooms', 'owner',
    { roomName: `${runId}_forbidden`, capacity: 1, equipment: [], status: 1 });
  expectStatus(forbidden, 403, 'Regular user cannot create room');
  if (forbidden.status === 200 && Number.isSafeInteger(forbidden.json?.data)) {
    report.fixtures.rooms.unexpectedForbidden = { id: forbidden.json.data, roomName: `${runId}_forbidden` };
  }
});

const dbReady = (...labels) => dockerVerified && hasUsers('owner') && hasRooms(...labels);
addCheck('cache-aside-update', 'Dedicated room update invalidates and refills the real Redis cache after MySQL commit',
  () => dbReady('cache') && hasUsers('admin'), async () => {
    const id = roomId('cache');
    const initialName = report.fixtures.rooms.cache.roomName;
    const initial = await request('cache-initial-fill-GET', 'GET', `/rooms/${id}`, 'owner');
    expectStatus(initial, 200, 'GET fills the dedicated room cache');
    assert(initial.json?.data?.roomName === initialName && initial.json?.data?.capacity === 12, 'Initial HTTP room values match the dedicated fixture', initial.json?.data);
    const oldCache = await redisSnapshot('cache-before-update');
    report.cacheAside = { roomId: id, beforeUpdate: oldCache };
    assert(oldCache.detail?.id === id && oldCache.detail?.roomName === initialName && oldCache.detail?.capacity === 12,
      'Actual Redis detail contains the original room values', oldCache);
    assert(Boolean(oldCache.version) && oldCache.detailTtl > 0 && oldCache.versionTtl > oldCache.detailTtl,
      'Actual Redis detail and version have bounded positive TTLs', oldCache);

    const updatedName = `${runId}_cache_updated`;
    const updatedBody = { roomName: updatedName, capacity: 18, equipment: ['验收专用'], status: 1 };
    const updated = await request('cache-admin-PUT', 'PUT', `/rooms/${id}`, 'admin', updatedBody);
    expectStatus(updated, 200, 'Administrator updates only the dedicated room');
    report.fixtures.rooms.cache.updatedRoomName = updatedName;
    const invalidated = await redisSnapshot('cache-after-commit-before-GET');
    report.cacheAside.afterCommit = invalidated;
    assert(invalidated.detailRaw === '' && invalidated.detailTtl === -2,
      'Committed room update deletes the old Redis detail before any refill GET', invalidated);
    assert(Boolean(invalidated.version) && invalidated.version !== oldCache.version && invalidated.versionTtl > 0,
      'Committed update rotates the actual Redis version', { oldVersion: oldCache.version, ...invalidated });

    const refreshed = await request('cache-new-value-refill-GET', 'GET', `/rooms/${id}`, 'owner');
    expectStatus(refreshed, 200, 'GET succeeds after cache invalidation');
    assert(refreshed.json?.data?.roomName === updatedName && refreshed.json?.data?.capacity === 18,
      'HTTP GET returns the committed new room name and capacity', refreshed.json?.data);
    const newCache = await redisSnapshot('cache-after-refill');
    report.cacheAside.afterRefill = newCache;
    assert(newCache.detail?.id === id && newCache.detail?.roomName === updatedName && newCache.detail?.capacity === 18,
      'Actual Redis refill contains the new room values', newCache);
    assert(newCache.version === invalidated.version && newCache.detailTtl > 0 && newCache.versionTtl > newCache.detailTtl,
      'Refill uses the rotated version and restores a bounded detail TTL', newCache);

    const roomQuery = `SELECT JSON_OBJECT('id',id,'roomName',room_name,'capacity',capacity,'equipment',equipment,'status',status,'isDeleted',is_deleted,'updatedAt',updated_at)
      FROM meeting_room WHERE id=${id}`;
    const committed = await sql('cache-committed-mysql-room', roomQuery);
    assert(committed.id === id && committed.roomName === updatedName && committed.capacity === 18,
      'Real MySQL contains the committed new room values', committed);
    const forbidden = await request('cache-user-PUT-forbidden', 'PUT', `/rooms/${id}`, 'owner',
      { ...updatedBody, roomName: `${runId}_cache_forbidden`, capacity: 1 });
    expectStatus(forbidden, 403, 'Regular user cannot update the dedicated room');
    const unchanged = await sql('cache-after-forbidden-PUT-mysql-room', roomQuery);
    assert(JSON.stringify(unchanged) === JSON.stringify(committed), 'Forbidden PUT changes no real MySQL room field', { before: committed, after: unchanged });
    const afterForbidden = await redisSnapshot('cache-after-forbidden-PUT');
    report.cacheAside.afterForbidden = afterForbidden;
    assert(afterForbidden.detailRaw === newCache.detailRaw && afterForbidden.version === newCache.version,
      'Forbidden PUT changes neither the actual Redis detail nor its version', afterForbidden);
    const final = await request('cache-after-forbidden-PUT-GET', 'GET', `/rooms/${id}`, 'owner');
    expectStatus(final, 200, 'Dedicated room remains readable after forbidden PUT');
    assert(final.json?.data?.roomName === updatedName && final.json?.data?.capacity === 18,
      'Final HTTP values remain the authorized update', final.json?.data);
  });

addCheck('concurrent-overlap', '12 overlapping requests with different request IDs have exactly one winner', () => dbReady('overlap'), async () => {
  const responses = await Promise.all(Array.from({ length: 12 }, (_, index) =>
    request(`overlap-${index}`, 'POST', '/orders/book', 'owner', booking('overlap', `overlap_${index}`))));
  const winners = responses.filter((response) => response.status === 200);
  assert(winners.length === 1, 'At least one HTTP success and at most one winner', winners.length);
  assert(responses.every((response) => [200, 409].includes(response.status)
    || (response.status === 429 && response.json?.message === '预约繁忙，请稍后重试')),
  'Other overlapping requests are conflicts or explicit room-lock contention', responses.map((response) => ({ status: response.status, message: response.json?.message })));
  report.fixtures.bookings.overlap = winners.map((response) => response.json?.data);
  const data = await counts('overlap');
  assert(data.orders === 1 && data.booked === 1 && data.outbox === 1, 'Exactly one real BOOKED order and one outbox row', data);
});

let idempotentPayload;
addCheck('concurrent-idempotency', '12 identical request IDs return one booking ID and one outbox row', () => dbReady('idempotency'), async () => {
  idempotentPayload = booking('idempotency', 'idem');
  const responses = await Promise.all(Array.from({ length: 12 }, (_, index) =>
    request(`idempotent-${index}`, 'POST', '/orders/book', 'owner', idempotentPayload)));
  responses.forEach((response, index) => expectStatus(response, 200, `Idempotent request ${index}`));
  const ids = responses.filter((response) => response.status === 200).map((response) => response.json?.data);
  assert(ids.length === 12 && new Set(ids).size === 1 && Number.isSafeInteger(ids[0]), 'All concurrent HTTP requests return the same ID', ids);
  report.fixtures.bookings.idempotency = ids[0];
  const data = await counts('idempotency', idempotentPayload.requestId);
  assert(data.orders === 1 && data.booked === 1 && data.outbox === 1, 'Idempotency has one real order and one outbox row', data);
});

addCheck('idempotency-parameter-conflict', 'Reusing one key with a different topic, time, or room returns 409',
  () => dbReady('idempotency', 'validation') && Boolean(report.fixtures.bookings.idempotency), async () => {
    const changes = [
      { topic: `${idempotentPayload.topic} changed` },
      { startTime: time('12:00:00'), endTime: time('13:00:00') },
      { roomId: roomId('validation') },
    ];
    for (const [index, change] of changes.entries()) {
      expectStatus(await request(`changed-key-${index}`, 'POST', '/orders/book', 'owner', { ...idempotentPayload, ...change }), 409, `Different parameters ${index}`);
    }
    const data = await counts('idempotency', idempotentPayload.requestId);
    assert(data.orders === 1 && data.outbox === 1, 'Parameter conflicts retain the single original row and event', data);
    const otherRoom = await counts('validation');
    assert(otherRoom.orders === 0 && otherRoom.outbox === 0, 'Cross-room key conflict creates no order', otherRoom);
  });

addCheck('half-open-and-containing', 'Adjacent half-open intervals succeed; nested and containing intervals return 409', () => dbReady('boundaries'), async () => {
  for (const [suffix, start, end] of [['base', '10:00:00', '11:00:00'], ['before', '09:00:00', '10:00:00'], ['after', '11:00:00', '12:00:00']]) {
    const response = await request(`boundary-${suffix}`, 'POST', '/orders/book', 'owner', booking('boundaries', `boundary_${suffix}`, start, end));
    const id = successId(response, `Half-open ${suffix}`);
    if (id) report.fixtures.bookings[`boundary_${suffix}`] = id;
  }
  for (const [suffix, start, end] of [['nested', '10:15:00', '10:45:00'], ['containing', '08:00:00', '13:00:00']]) {
    expectStatus(await request(`boundary-${suffix}`, 'POST', '/orders/book', 'owner', booking('boundaries', `boundary_${suffix}`, start, end)), 409, `${suffix} interval conflict`);
  }
  const data = await counts('boundaries');
  assert(data.orders === 3 && data.booked === 3 && data.outbox === 3, 'Only the three adjacent intervals are persisted', data);
});

addCheck('cancel-owner-and-repeat', 'Cross-user cancellation 403, repeated owner cancellation, and replacement',
  () => dbReady('cancellation') && hasUsers('intruder'), async () => {
    const firstPayload = booking('cancellation', 'cancel_original');
    const id = successId(await request('cancel-create', 'POST', '/orders/book', 'owner', firstPayload), 'Create cancellation fixture');
    if (!id) return;
    report.fixtures.bookings.cancelOriginal = id;
    expectStatus(await request('intruder-cancel', 'POST', `/orders/${id}/cancel`, 'intruder'), 403, 'Another user cannot cancel');
    const before = await counts('cancellation', firstPayload.requestId);
    assert(before.booked === 1 && before.cancelled === 0, 'Forbidden cancellation leaves original BOOKED', before);
    for (let index = 0; index < 2; index++) {
      expectStatus(await request(`repeat-cancel-${index}`, 'POST', `/orders/${id}/cancel`, 'owner'), 200, `Repeated cancellation ${index}`);
    }
    const cancelled = await counts('cancellation', firstPayload.requestId);
    assert(cancelled.orders === 1 && cancelled.cancelled === 1 && cancelled.booked === 0 && cancelled.outbox === 1, 'CAS leaves one CANCELLED order and the original event', cancelled);
    const replacement = successId(await request('cancel-replacement', 'POST', '/orders/book', 'owner', booking('cancellation', 'cancel_replacement')), 'Replacement after cancellation');
    report.fixtures.bookings.cancelReplacement = replacement;
    assert(Boolean(replacement) && replacement !== id, 'Replacement is a new booking ID', replacement);
    const replay = await request('cancelled-idempotent-replay', 'POST', '/orders/book', 'owner', firstPayload);
    expectStatus(replay, 200, 'Replay of cancelled original remains idempotent');
    assert(replay.json?.data === id, 'Cancelled request ID returns the original booking ID', replay.json?.data);
    const data = await counts('cancellation');
    assert(data.orders === 2 && data.cancelled === 1 && data.booked === 1 && data.outbox === 2, 'Replacement leaves exactly one active order', data);
  });

addCheck('concurrent-cancel-and-replacement', 'Seven concurrent CAS cancellations and a replacement preserve one active order', () => dbReady('cancellation'), async () => {
  const original = booking('cancellation', 'race_original', '14:00:00', '15:00:00');
  const replacement = booking('cancellation', 'race_replacement', '14:00:00', '15:00:00');
  const id = successId(await request('race-create', 'POST', '/orders/book', 'owner', original), 'Create concurrent cancellation fixture');
  if (!id) return;
  report.fixtures.bookings.raceOriginal = id;
  const responses = await Promise.all([
    ...Array.from({ length: 7 }, (_, index) => request(`race-cancel-${index}`, 'POST', `/orders/${id}/cancel`, 'owner')),
    request('race-replacement', 'POST', '/orders/book', 'owner', replacement),
  ]);
  responses.slice(0, 7).forEach((response, index) => expectStatus(response, 200, `Concurrent cancel ${index}`));
  assert([200, 409].includes(responses[7].status), 'Racing replacement either wins or conflicts before cancellation', responses[7].status);
  const beforeRetry = await counts('cancellation', replacement.requestId);
  assert(beforeRetry.booked <= 1, 'At most one active racing replacement', beforeRetry);
  const originalAfter = await counts('cancellation', original.requestId);
  assert(originalAfter.orders === 1 && originalAfter.cancelled === 1 && originalAfter.booked === 0 && originalAfter.outbox === 1,
    'Concurrent cancellation preserves one cancelled original and one original outbox row', originalAfter);
  const retry = successId(await request('race-replacement-retry', 'POST', '/orders/book', 'owner', replacement), 'Replacement succeeds after all cancellations');
  report.fixtures.bookings.raceReplacement = retry;
  if (responses[7].status === 200) assert(retry === responses[7].json?.data, 'Successful racing replacement retry returns its original ID', retry);
  expectStatus(await request('race-old-cancel-again', 'POST', `/orders/${id}/cancel`, 'owner'), 200, 'Late repeated cancellation succeeds');
  const afterRetry = await counts('cancellation', replacement.requestId);
  assert(afterRetry.orders === 1 && afterRetry.booked === 1 && afterRetry.outbox === 1, 'Late cancellation does not alter replacement or duplicate its event', afterRetry);
});

addCheck('time-precision-and-parameters', 'Reject subsecond precision and invalid DTO/time/body parameters', () => dbReady('validation'), async () => {
  const valid = booking('validation', 'seconds_valid', '16:00:01', '16:10:02');
  report.fixtures.bookings.wholeSeconds = successId(await request('whole-second-valid', 'POST', '/orders/book', 'owner', valid), 'Whole seconds are accepted');
  const changes = [
    ['subsecond-start', { startTime: time('17:00:00.001'), endTime: time('18:00:00') }],
    ['subsecond-end', { startTime: time('17:00:00'), endTime: time('18:00:00.999999') }],
    ['zero-room', { roomId: 0 }], ['missing-room', { roomId: null }],
    ['blank-topic', { topic: '  ' }], ['long-topic', { topic: 'x'.repeat(201) }],
    ['null-start', { startTime: null }], ['null-end', { endTime: null }],
    ['equal-times', { startTime: time('17:00:00'), endTime: time('17:00:00') }],
    ['reverse-times', { startTime: time('18:00:00'), endTime: time('17:00:00') }],
    ['past-time', { startTime: '2000-01-01T10:00:00', endTime: '2000-01-01T11:00:00' }],
    ['malformed-time', { startTime: 'not-a-date' }],
    ['invalid-key', { requestId: 'invalid key!' }], ['empty-key', { requestId: '' }], ['long-key', { requestId: 'a'.repeat(65) }],
  ];
  for (const [index, [label, change]] of changes.entries()) {
    // A smaller secondary window keeps even invalid requests safely below 20 QPS.
    if (index === 7) await pause(1400);
    expectStatus(await request(label, 'POST', '/orders/book', 'owner', { ...booking('validation', `invalid_${index}`, '17:00:00', '18:00:00'), ...change }), 400, label);
  }
  expectStatus(await request('malformed-json', 'POST', '/orders/book', 'owner', '{', { raw: true }), 400, 'Malformed JSON body');
  expectStatus(await request('invalid-cancel-path', 'POST', '/orders/not-a-number/cancel', 'owner'), 400, 'Invalid path parameter');
  expectStatus(await request('unsupported-method', 'PUT', '/orders/my', 'owner'), 405, 'Unsupported HTTP method');
  expectStatus(await request('unsupported-media', 'POST', '/orders/book', 'owner', 'plain', { raw: true, contentType: 'text/plain' }), 415, 'Unsupported media type');
  const data = await counts('validation');
  assert(data.orders === 1 && data.booked === 1 && data.outbox === 1, 'Rejected validation requests create no order or event', data);
});

addCheck('missing-resource', 'Unknown valid room/order IDs and route return 404', () => hasUsers('owner') && hasRooms('validation'), async () => {
  const missingId = Number.MAX_SAFE_INTEGER;
  expectStatus(await request('missing-room', 'GET', `/rooms/${missingId}`, 'owner'), 404, 'Missing room detail');
  expectStatus(await request('missing-room-booking', 'POST', '/orders/book', 'owner', { ...booking('validation', 'missing_room', '19:00:00', '20:00:00'), roomId: missingId }), 404, 'Booking missing room');
  expectStatus(await request('missing-order', 'POST', `/orders/${missingId}/cancel`, 'owner'), 404, 'Cancellation of missing order');
  expectStatus(await request('unknown-route', 'GET', '/orders/not-configured/extra', 'owner'), 404, 'Unknown route in booking service');
});

addCheck('sentinel-burst', 'A separate burst proves Sentinel 429 text and does not duplicate the booking',
  () => dbReady('idempotency') && Boolean(report.fixtures.bookings.idempotency), async () => {
    const responses = await Promise.all(Array.from({ length: 64 }, (_, index) =>
      request(`sentinel-${index}`, 'POST', '/orders/book', 'owner', idempotentPayload)));
    const blocked = responses.filter((response) => response.status === 429 && response.json?.message === '预约请求过多，请稍后重试');
    assert(blocked.length > 0, 'At least one 429 uses the exact Sentinel guard message', { sentinel429: blocked.length, statuses: responses.map((response) => response.status) });
    assert(responses.every((response) => response.status === 200
      || (response.status === 429 && response.json?.code === 429 && response.json?.message === '预约请求过多，请稍后重试')),
    'Burst responses are successful replays or Sentinel throttling', responses.map((response) => ({ status: response.status, message: response.json?.message })));
    assert(responses.filter((response) => response.status === 200).every((response) => response.json?.data === report.fixtures.bookings.idempotency),
      'Successful burst replays return the original booking ID');
    const data = await counts('idempotency', idempotentPayload.requestId);
    assert(data.orders === 1 && data.outbox === 1, 'Burst retains one order and one event', data);
  }, false, withSentinelBurst);

addCheck('final-fixture-integrity', 'Final read-only MySQL evidence is scoped to this run\'s room IDs',
  () => dockerVerified && Object.values(report.fixtures.rooms).some((room) => Number.isSafeInteger(room.id)), async () => {
    const ids = Object.values(report.fixtures.rooms).map((room) => room.id).filter((id) => Number.isSafeInteger(id) && id > 0);
    const scoped = `b.room_id IN (${ids.join(',')})`;
    const data = await sql('final-scoped-integrity', `SELECT JSON_OBJECT(
      'roomIds',JSON_ARRAY(${ids.join(',')}),
      'orders',(SELECT COUNT(*) FROM booking_order b WHERE ${scoped}),
      'outbox',(SELECT COUNT(*) FROM booking_outbox o JOIN booking_order b ON b.id=o.aggregate_id WHERE ${scoped} AND o.event_type='BOOKING_CREATED_V1'),
      'ordersWithoutExactlyOneOutbox',(SELECT COUNT(*) FROM booking_order b WHERE ${scoped} AND (SELECT COUNT(*) FROM booking_outbox o WHERE o.aggregate_id=b.id AND o.event_type='BOOKING_CREATED_V1')<>1),
      'overlappingActivePairs',(SELECT COUNT(*) FROM booking_order b JOIN booking_order other ON other.room_id=b.room_id AND other.id>b.id AND other.status=1 AND b.start_time<other.end_time AND b.end_time>other.start_time WHERE ${scoped} AND b.status=1)
    )`);
    assert(data.orders > 0 && data.orders === data.outbox && data.ordersWithoutExactlyOneOutbox === 0, 'Every retained test order has exactly one BOOKING_CREATED_V1 outbox row', data);
    assert(data.overlappingActivePairs === 0, 'No overlapping active bookings in any test room', data.overlappingActivePairs);
  });

try {
  await persist();
  console.log(`Real local acceptance: ${runId}`);
  for (const check of report.checks) await runCheck(check);
} catch (error) {
  report.fatalError = redact(error.message);
} finally {
  report.completedAt = new Date().toISOString();
  report.summary = {
    PASS: report.checks.filter((check) => check.status === 'PASS').length,
    FAIL: report.checks.filter((check) => check.status === 'FAIL').length,
    NOT_EXECUTED: report.checks.filter((check) => check.status === 'NOT_EXECUTED').length,
    requiredNotExecuted: report.checks.filter((check) => check.required && check.status === 'NOT_EXECUTED').length,
    requests: report.requests.length, sqlQueries: report.sql.length, redisCommands: report.commands.length,
  };
  report.passed = !report.fatalError && report.summary.FAIL === 0 && report.summary.requiredNotExecuted === 0;
  report.status = report.passed ? 'PASS' : 'FAIL';
  await persist();
  console.log(`${report.status}: ${JSON.stringify(report.summary)}`);
  console.log(`Report: ${outputFile}`);
  console.log(`Retained fixture history: ${historyFile}`);
  process.exitCode = report.passed ? 0 : 1;
}
