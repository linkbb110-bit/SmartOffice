#!/usr/bin/env node
/**
 * Real local RocketMQ failure acceptance. Node built-ins only; fixtures remain.
 * node deploy/upgrade/real-mq-failure-check.mjs --mode producer-outage
 * Modes: producer-outage | consumer-failure | consumer-restart (one per run).
 * Credentials: REAL_MQ_ADMIN_USERNAME/PASSWORD, or REAL_API_ADMIN_USERNAME/PASSWORD.
 * Only this project's pinned broker/booking-service may be stopped and started.
 * No down, volume removal, order cancellation, fixture cleanup, H2 or mock path.
 */
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const modes = ['producer-outage', 'consumer-failure', 'consumer-restart'];
const project = 'smartoffice-upgrade';
const topic = 'smartoffice-booking-created-v1';
const group = 'smartoffice-notification-v1';
const brokerName = 'upgrade-broker';
const compose = ['compose', '--project-name', project, '--env-file', 'deploy/.env.upgrade', '-f', 'deploy/docker-compose.upgrade.yml'];
const options = { mode: '', baseUrl: 'http://127.0.0.1:18080/api', timeoutMs: 180_000, healthTimeoutMs: 300_000 };
for (let i = 0; i < process.argv.slice(2).length; i++) {
  const args = process.argv.slice(2);
  const flag = args[i];
  if (flag === '--help') {
    console.log('node deploy/upgrade/real-mq-failure-check.mjs --mode producer-outage|consumer-failure|consumer-restart [--base-url http://127.0.0.1:18080/api] [--timeout-ms 180000] [--health-timeout-ms 300000]');
    process.exit(0);
  }
  if (flag === '--mode') options.mode = args[++i];
  else if (flag === '--base-url') options.baseUrl = args[++i];
  else if (flag === '--timeout-ms') options.timeoutMs = Number(args[++i]);
  else if (flag === '--health-timeout-ms') options.healthTimeoutMs = Number(args[++i]);
  else throw new Error(`Unknown argument: ${flag}`);
}
if (!modes.includes(options.mode)) throw new Error('Exactly one supported --mode is required.');
for (const key of ['timeoutMs', 'healthTimeoutMs']) {
  if (!Number.isSafeInteger(options[key]) || options[key] < 15_000 || options[key] > 900_000) {
    throw new Error(`${key} must be between 15000 and 900000 milliseconds.`);
  }
}
const target = new URL(options.baseUrl);
if (target.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname)
  || target.username || target.password || target.search || target.hash || target.pathname.replace(/\/$/, '') !== '/api') {
  throw new Error('The API must be a loopback http:// URL ending in /api.');
}
options.baseUrl = target.href.replace(/\/$/, '');
const runId = `mq_${new Date().toISOString().replace(/[-:.]/g, '').slice(0, 15)}_${randomBytes(5).toString('hex')}`;
const outputDirectory = resolve(root, 'logs/real-integration');
const outputFile = resolve(outputDirectory, `mq-failure-result.${runId}.json`);
const username = process.env.REAL_MQ_ADMIN_USERNAME || process.env.REAL_API_ADMIN_USERNAME || 'admin';
const password = process.env.REAL_MQ_ADMIN_PASSWORD || process.env.REAL_API_ADMIN_PASSWORD || '123456';
const secrets = new Set([password]);
const pinned = new Map();
const restoreServices = new Set();
const fixture = {
  roomName: `${runId}_${options.mode}`, requestId: `${runId}_event`,
  bookingTopic: `真实MQ故障验收 ${runId} ${options.mode}`,
};
const triggerName = `mqfail_${runId.replace(/[^A-Za-z0-9_]/g, '')}`;
const report = {
  runId, mode: options.mode, status: 'NOT_EXECUTED', passed: false,
  startedAt: new Date().toISOString(), completedAt: null, target: options.baseUrl,
  environment: { project, realMySql: false, realRocketMq: false, containers: {} },
  safety: { loopbackOnly: true, pinnedProjectContainers: true, fixturesRetained: true,
    cancellationUsed: false, volumesRemoved: false, triggerName: options.mode === 'consumer-failure' ? triggerName : null },
  fixtures: fixture, modes: Object.fromEntries(modes.map((mode) => [mode, { status: 'NOT_EXECUTED', reason: mode === options.mode ? 'Environment not yet verified.' : 'Independent mode not selected.' }])),
  assertions: [], commands: [], http: [], sql: [], observations: [], restoration: [],
};
let phase = 'preflight';
let adminToken;
let interrupted = false;
let restoring = false;
let localDockerVerified = false;
let preflightComplete = false;
let triggerMayExist = false;
let triggerRemoved = false;
let recovered = false;
let activeChild;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  interrupted = true;
  if (activeChild && !restoring) activeChild.kill();
});
const pause = (ms) => new Promise((done) => setTimeout(done, ms));

class CheckFailure extends Error {
  constructor(message, category = 'BUSINESS') { super(message); this.category = category; }
}
function redact(value) {
  if (typeof value === 'string') {
    let clean = value.replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[REDACTED_TOKEN]');
    for (const secret of secrets) if (secret) clean = clean.split(secret).join('[REDACTED]');
    return clean.replace(/(password|passwd|authorization|jwt_secret)(\s*[=:]\s*)[^\s,;]+/gi, '$1$2[REDACTED]');
  }
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, child]) =>
    [key, /password|authorization|token|secret/i.test(key) ? '[REDACTED]' : redact(child)]));
  return value;
}
async function persist() {
  await mkdir(outputDirectory, { recursive: true });
  await writeFile(outputFile, `${JSON.stringify(redact(report), null, 2)}\n`, 'utf8');
}
function check(condition, description, actual, category = 'BUSINESS') {
  report.assertions.push({ phase, passed: Boolean(condition), description, category, ...(actual === undefined ? {} : { actual: redact(actual) }) });
  if (!condition) throw new CheckFailure(description, category);
}
function abortIfRequested() {
  if (interrupted && !restoring) throw new CheckFailure('Interrupted; restoration will run.', 'EXECUTION');
}
async function command(label, executable, args, input = '', timeoutMs = 45_000) {
  abortIfRequested();
  const evidence = { index: report.commands.length, phase, label, executable, args, startedAt: new Date().toISOString() };
  report.commands.push(evidence);
  const result = await new Promise((done) => {
    const child = spawn(executable, args, { cwd: root, windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'] });
    activeChild = child;
    let stdout = '', stderr = '', finished = false;
    const finish = (extra) => {
      if (finished) return;
      finished = true; clearTimeout(timer);
      if (activeChild === child) activeChild = undefined;
      done({ stdout: stdout.trim(), stderr: stderr.trim(), ...extra });
    };
    const timer = setTimeout(() => { child.kill(); finish({ code: -1, error: `Timeout after ${timeoutMs}ms` }); }, timeoutMs);
    child.stdout.on('data', (data) => { stdout += data; });
    child.stderr.on('data', (data) => { stderr += data; });
    child.stdin.on('error', () => {});
    child.on('error', (error) => finish({ code: -1, error: error.message }));
    child.on('close', (code) => finish({ code }));
    child.stdin.end(input);
  });
  Object.assign(evidence, redact(result), { completedAt: new Date().toISOString() });
  await persist();
  return result;
}
async function mustCommand(label, executable, args, input = '', timeoutMs) {
  const result = await command(label, executable, args, input, timeoutMs);
  if (result.code !== 0) throw new CheckFailure(`${label} failed: ${redact(result.error || result.stderr || `exit ${result.code}`)}`, 'ENVIRONMENT');
  return result;
}
function localEndpoint(value) {
  if (typeof value !== 'string') return false;
  if (/^npipe:\/{2,4}\.\/pipe\//i.test(value) || value.startsWith('unix://')) return true;
  try { return ['127.0.0.1', 'localhost', '[::1]'].includes(new URL(value).hostname); } catch { return false; }
}
async function inspect(service) {
  const id = pinned.get(service);
  if (!id || !localDockerVerified) throw new CheckFailure(`Unverified container ${service}.`, 'ENVIRONMENT');
  const format = '{"id":{{json .Id}},"image":{{json .Image}},"state":{{json .State}},"labels":{{json .Config.Labels}},"mounts":{{json .Mounts}}}';
  const result = await mustCommand(`inspect-${service}`, 'docker', ['inspect', '--format', format, id]);
  const data = JSON.parse(result.stdout);
  const labels = data.labels || {};
  if (data.id !== id || labels['com.docker.compose.project'] !== project
    || labels['com.docker.compose.service'] !== service || String(labels['com.docker.compose.oneoff']).toLowerCase() !== 'false') {
    throw new CheckFailure(`Pinned ${service} is not this project's existing service container.`, 'ENVIRONMENT');
  }
  return data;
}
async function pin(service) {
  const result = await mustCommand(`pin-${service}`, 'docker', [...compose, 'ps', '--all', '--quiet', service]);
  const ids = result.stdout.split(/\s+/).filter(Boolean);
  if (ids.length !== 1 || !/^[a-f0-9]{64}$/.test(ids[0])) throw new CheckFailure(`Exactly one existing ${service} container is required.`, 'ENVIRONMENT');
  pinned.set(service, ids[0]);
  const data = await inspect(service);
  report.environment.containers[service] = data;
  if (!data.state.Running || data.state.Health?.Status !== 'healthy') throw new CheckFailure(`${service} must already be running and healthy.`, 'ENVIRONMENT');
  return data;
}
async function waitFor(label, read, predicate, timeoutMs = options.timeoutMs, category = 'BUSINESS') {
  const deadline = Date.now() + timeoutMs;
  let last;
  do {
    abortIfRequested();
    last = await read();
    report.observations.push({ phase, label, at: new Date().toISOString(), value: redact(last) });
    if (predicate(last)) { await persist(); return last; }
    if (Date.now() < deadline) await pause(Math.min(1000, deadline - Date.now()));
  } while (Date.now() < deadline);
  check(false, `${label} reached its deadline without the required observation.`, last, category);
}
async function healthy(service) {
  return await waitFor(`${service}-healthy`, () => inspect(service), (data) => data.state.Running && data.state.Health?.Status === 'healthy', options.healthTimeoutMs, 'ENVIRONMENT');
}
async function stopService(service) {
  if (!['broker', 'booking-service'].includes(service)) throw new CheckFailure('This service may not be stopped.', 'EXECUTION');
  const before = await inspect(service);
  check(before.state.Running && before.state.Health?.Status === 'healthy', `${service} was healthy immediately before stop.`, before.state, 'ENVIRONMENT');
  restoreServices.add(service); // A timeout can occur after Docker has accepted stop.
  await mustCommand(`stop-${service}`, 'docker', ['stop', '--time', '30', pinned.get(service)], '', 60_000);
  const after = await inspect(service);
  check(!after.state.Running, `${service} is stopped.`, after.state, 'ENVIRONMENT');
}
async function startService(service) {
  const current = await inspect(service);
  if (!current.state.Running) await mustCommand(`start-${service}`, 'docker', ['start', pinned.get(service)]);
  const data = await healthy(service);
  restoreServices.delete(service);
  return data;
}
function sqlLiteral(value) { return `CONVERT(0x${Buffer.from(String(value), 'utf8').toString('hex')} USING utf8mb4)`; }
function safeId(value) {
  if (!Number.isSafeInteger(value) || value < 1) throw new CheckFailure('A scoped positive fixture ID is required.', 'EXECUTION');
  return value;
}
async function sql(label, query, { rootUser = false, ddl = false } = {}) {
  await inspect('mysql-booking');
  if (!ddl && (!/^\s*SELECT\b/i.test(query) || /;\s*\S/.test(query) || /\b(INTO|OUTFILE|DUMPFILE|FOR\s+UPDATE)\b/i.test(query))) {
    throw new CheckFailure('Only a single read-only SELECT is allowed outside the scoped trigger.', 'EXECUTION');
  }
  if (ddl && (!rootUser || ![triggerCreateSql(), triggerDropSql()].includes(query))) {
    throw new CheckFailure('Only this run\'s exact trigger creation/drop is permitted.', 'EXECUTION');
  }
  const shellCommand = rootUser
    ? 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -D"$MYSQL_DATABASE" --batch --raw --skip-column-names --default-character-set=utf8mb4'
    : 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h127.0.0.1 -u"$MYSQL_USER" -D"$MYSQL_DATABASE" --batch --raw --skip-column-names --default-character-set=utf8mb4';
  const evidence = { phase, label, query, identity: rootUser ? 'existing-container-root' : 'existing-container-application', startedAt: new Date().toISOString() };
  report.sql.push(evidence);
  const result = await command(label, 'docker', [...compose, 'exec', '-T', 'mysql-booking', 'sh', '-c', shellCommand], `${query}\n`);
  Object.assign(evidence, redact(result));
  if (result.code !== 0) throw new CheckFailure(`SQL ${label} failed: ${redact(result.error || result.stderr)}`, 'ENVIRONMENT');
  if (ddl) { await persist(); return; }
  try { evidence.data = JSON.parse(result.stdout); } catch { throw new CheckFailure(`SQL ${label} did not return one JSON result.`, 'ENVIRONMENT'); }
  await persist();
  return evidence.data;
}
function triggerCreateSql() {
  // Binary content equality limits rejection to the unique topic of this run.
  return `DELIMITER $$\nCREATE TRIGGER \`${triggerName}\` BEFORE INSERT ON booking_notification FOR EACH ROW\nBEGIN\n IF BINARY NEW.content = BINARY ${sqlLiteral(`预约创建成功：${fixture.bookingTopic}`)} THEN\n  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='${triggerName}';\n END IF;\nEND$$\nDELIMITER ;`;
}
function triggerDropSql() { return `DROP TRIGGER IF EXISTS \`${triggerName}\`;`; }
async function removeTrigger() {
  if (!triggerMayExist) return;
  await sql('remove-this-run-trigger', triggerDropSql(), { rootUser: true, ddl: true });
  triggerMayExist = false; triggerRemoved = true;
  const result = await sql('verify-this-run-trigger-removed', `SELECT JSON_OBJECT('remaining',COUNT(*)) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=${sqlLiteral(triggerName)};`);
  check(result.remaining === 0, 'Only this run\'s trigger has been removed.', result, 'ENVIRONMENT');
}
async function http(label, method, path, body, authenticated = true, sensitive = false) {
  abortIfRequested();
  const record = { phase, label, method, path, startedAt: new Date().toISOString(), ...(body === undefined ? {} : { body: redact(body) }) };
  report.http.push(record);
  const headers = { Accept: 'application/json' };
  if (authenticated) headers.Authorization = `Bearer ${adminToken}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  try {
    const response = await fetch(`${options.baseUrl}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20_000), redirect: 'manual' });
    const raw = await response.text();
    let json; try { json = JSON.parse(raw); } catch {}
    if (sensitive && typeof json?.data === 'string') secrets.add(json.data);
    Object.assign(record, { status: response.status, response: sensitive ? { ...json, data: '[REDACTED_TOKEN]' } : redact(json ?? raw.slice(0, 2000)) });
    return { status: response.status, json };
  } catch (error) { record.status = 0; record.error = redact(error.message); return { status: 0, error: record.error }; }
  finally { record.completedAt = new Date().toISOString(); await persist(); }
}
function success(response, description, category = 'BUSINESS') {
  check(response.status === 200 && response.json?.code === 200, description, response, category);
}
async function createBooking({ witness = false } = {}) {
  const day = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  const payload = { roomId: safeId(fixture.roomId), requestId: witness ? `${runId}_witness` : fixture.requestId,
    topic: witness ? `${fixture.bookingTopic} persistence-witness` : fixture.bookingTopic,
    startTime: `${day}T${witness ? '08' : '10'}:00:00`, endTime: `${day}T${witness ? '09' : '11'}:00:00` };
  const response = await http(witness ? 'create-persistence-witness' : 'create-fault-scenario-booking', 'POST', '/orders/book', payload);
  success(response, witness ? 'Persistence witness was booked through real HTTP.' : 'Booking HTTP remains 200.');
  const id = safeId(response.json?.data);
  const booking = { id, roomId: fixture.roomId, requestId: payload.requestId, topic: payload.topic };
  if (witness) fixture.witness = booking; else fixture.booking = booking;
  await persist();
  return booking;
}
async function bookingState(booking) {
  const filter = `b.id=${safeId(booking.id)} AND b.room_id=${safeId(booking.roomId)} AND b.request_id=${sqlLiteral(booking.requestId)} AND BINARY b.topic=BINARY ${sqlLiteral(booking.topic)}`;
  const result = await sql(`scoped-state-${booking.requestId}`, `SELECT JSON_OBJECT(
   'orders',(SELECT COUNT(*) FROM booking_order b WHERE ${filter}),
   'booked',(SELECT COUNT(*) FROM booking_order b WHERE ${filter} AND b.status=1),
   'cancelled',(SELECT COUNT(*) FROM booking_order b WHERE ${filter} AND b.status=2),
   'outbox',(SELECT COUNT(*) FROM booking_outbox o JOIN booking_order b ON b.id=o.aggregate_id WHERE ${filter} AND o.event_type='BOOKING_CREATED_V1'),
   'events',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('eventId',o.event_id,'status',o.status,'attempts',o.attempts,'lastError',o.last_error,'brokerMessageId',o.broker_message_id)) FROM booking_outbox o JOIN booking_order b ON b.id=o.aggregate_id WHERE ${filter} AND o.event_type='BOOKING_CREATED_V1'),JSON_ARRAY()),
   'notifications',(SELECT COUNT(*) FROM booking_notification n JOIN booking_outbox o ON o.event_id=n.event_id JOIN booking_order b ON b.id=o.aggregate_id WHERE ${filter}),
   'audits',(SELECT COUNT(*) FROM business_event_audit a JOIN booking_outbox o ON o.event_id=a.event_id JOIN booking_order b ON b.id=o.aggregate_id WHERE ${filter}),
   'receipts',(SELECT COUNT(*) FROM message_consumption c JOIN booking_outbox o ON o.event_id=c.event_id JOIN booking_order b ON b.id=o.aggregate_id WHERE ${filter} AND c.consumer_group=${sqlLiteral(group)})
  );`);
  if (result.events?.length === 1) {
    const eventId = result.events[0].eventId;
    if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(eventId)) throw new CheckFailure('Real Outbox event ID is invalid.');
    if (booking.eventId && booking.eventId !== eventId) throw new CheckFailure('Scoped Outbox event identity changed.');
    booking.eventId = eventId;
  }
  return result;
}
function oneOrder(state) { return state.orders === 1 && state.booked === 1 && state.cancelled === 0 && state.outbox === 1 && state.events?.length === 1; }
function delivered(state) { return oneOrder(state) && state.events[0].status === 'SENT' && Boolean(state.events[0].brokerMessageId) && state.notifications === 1 && state.audits === 1 && state.receipts === 1; }
async function waitDelivered(booking, label = 'event-delivered-once') {
  return await waitFor(label, () => bookingState(booking), delivered);
}
async function mqadmin(label, subcommand, args = [], allowMissingTopic = false) {
  const broker = await inspect('broker');
  if (!broker.state.Running) throw new CheckFailure('mqadmin requires the pinned broker running.', 'ENVIRONMENT');
  const result = await command(label, 'docker', ['exec', pinned.get('broker'), 'sh', 'mqadmin', subcommand, '-n', 'namesrv:9876', ...args], '', 30_000);
  const missing = /TOPIC_NOT_EXIST|topic[^\n]*(?:not exist|does not exist)|CODE:\s*17\b/i.test(`${result.stdout}\n${result.stderr}`);
  if (allowMissingTopic && missing) return { ...result, missing: true };
  if (result.code !== 0) throw new CheckFailure(`${label} mqadmin failed: ${redact(result.error || result.stderr)}`, 'ENVIRONMENT');
  return result;
}
function topicRows(stdout) {
  return stdout.split(/\r?\n/).flatMap((line) => {
    const match = line.trim().match(/^(\S+)\s+(\d+)\s+(\d+)\s+(\d+)(?:\s|$)/);
    return match && match[1] === brokerName ? [{ broker: match[1], queue: Number(match[2]), minOffset: Number(match[3]), maxOffset: Number(match[4]) }] : [];
  });
}
async function topicOffsets(name, allowMissingTopic = false) {
  const result = await mqadmin(`topic-offsets-${name}`, 'topicStatus', ['-t', name], allowMissingTopic);
  if (result.missing) return { name, total: 0, rows: [], missing: true };
  const rows = topicRows(result.stdout);
  if (!rows.length) throw new CheckFailure(`Cannot parse real broker topicStatus rows for ${name}.`, 'ENVIRONMENT');
  return { name, total: rows.reduce((sum, row) => sum + row.maxOffset, 0), rows };
}
async function retryOffsets() {
  const result = {};
  for (const name of [`%RETRY%${group}`, `%DLQ%${group}`, 'SCHEDULE_TOPIC_XXXX']) result[name] = await topicOffsets(name, true);
  return result;
}
function increasedRetry(before, after) { return [`%RETRY%${group}`, 'SCHEDULE_TOPIC_XXXX'].some((name) => after[name].total > before[name].total); }
function sameRetry(before, after) { return Object.keys(before).every((name) => before[name].total === after[name].total); }
async function consumerOffsets() {
  const result = await mqadmin('business-group-consumer-progress', 'consumerProgress', ['-g', group, '-t', topic]);
  const rows = result.stdout.split(/\r?\n/).flatMap((line) => {
    const cells = line.trim().split(/\s+/);
    if (cells[0] !== topic || cells[1] !== brokerName || !cells.slice(2, 6).every((cell) => /^\d+$/.test(cell || ''))) return [];
    return [{ topic: cells[0], broker: cells[1], queue: Number(cells[2]), brokerOffset: Number(cells[3]), consumerOffset: Number(cells[4]), diff: Number(cells[5]) }];
  });
  if (!rows.length) throw new CheckFailure('Cannot parse real business-group consumerProgress rows.', 'ENVIRONMENT');
  return rows;
}
async function queryWitness(booking) {
  const result = await mqadmin('query-persistent-witness-by-event-key', 'queryMsgByKey', ['-t', topic, '-k', booking.eventId]);
  const ids = result.stdout.split(/\r?\n/).flatMap((line) => {
    const match = line.trim().match(/^([a-f0-9]{32}|[a-f0-9]{56})(?:\s|$)/i);
    return match ? [match[1].toUpperCase()] : [];
  });
  return { eventId: booking.eventId, messageIds: [...new Set(ids)].sort() };
}
async function preflight() {
  if (process.env.DOCKER_HOST && !localEndpoint(process.env.DOCKER_HOST)) throw new CheckFailure('Remote DOCKER_HOST is refused.', 'ENVIRONMENT');
  const result = await mustCommand('local-docker-context', 'docker', ['context', 'inspect']);
  const contexts = JSON.parse(result.stdout);
  const endpoint = process.env.DOCKER_HOST || contexts[0]?.Endpoints?.docker?.Host;
  check(localEndpoint(endpoint), 'Docker endpoint is local.', endpoint, 'ENVIRONMENT');
  localDockerVerified = true;
  report.environment.dockerContext = { name: contexts[0]?.Name, endpoint };
  for (const service of ['mysql-booking', 'mysql-user', 'redis', 'namesrv', 'broker', 'nacos', 'user-service', 'booking-service', 'gateway', 'frontend']) await pin(service);
  const engine = await sql('real-mysql-engine', "SELECT JSON_OBJECT('version',VERSION(),'database',DATABASE(),'hostname',@@hostname);");
  check(/^8\./.test(engine.version) && engine.database === 'smartoffice_booking', 'Real Compose MySQL 8 booking database.', engine, 'ENVIRONMENT');
  report.environment.realMySql = true;
  const config = await mustCommand('broker-durable-store-config', 'docker', ['exec', pinned.get('broker'), 'sh', '-c', 'cat /tmp/smartoffice-broker.conf']);
  check(/^flushDiskType=SYNC_FLUSH\s*$/m.test(config.stdout) && /^storePathRootDir=\/home\/rocketmq\/store\s*$/m.test(config.stdout), 'Broker uses synchronous flush and its persisted store.', undefined, 'ENVIRONMENT');
  const broker = report.environment.containers.broker;
  const store = broker.mounts.find((mount) => mount.Destination === '/home/rocketmq/store');
  check(store?.Type === 'volume' && store.Name === `${project}_broker_store`, 'Broker store is this project\'s existing named volume.', store, 'ENVIRONMENT');
  const cluster = await mqadmin('real-broker-cluster', 'clusterList');
  check(cluster.stdout.includes(brokerName), 'Real broker is registered in NameServer.', undefined, 'ENVIRONMENT');
  report.environment.realRocketMq = true;
  const login = await http('admin-login', 'POST', '/users/login', { username, password }, false, true);
  success(login, 'Real HTTP admin login.', 'ENVIRONMENT');
  check(typeof login.json?.data === 'string' && login.json.data.length > 20, 'Login supplied an in-memory token.', undefined, 'ENVIRONMENT');
  adminToken = login.json.data;
  const me = await http('admin-identity', 'GET', '/users/me');
  success(me, 'Real Gateway admin identity.', 'ENVIRONMENT');
  check(me.json?.data?.role === 'ADMIN' && me.json?.data?.username === username, 'Database account is the intended ADMIN.', me.json?.data, 'ENVIRONMENT');
  fixture.adminUserId = safeId(me.json.data.userId);
}
async function createRoom() {
  const response = await http('create-exclusive-room', 'POST', '/rooms', { roomName: fixture.roomName, capacity: 2, equipment: ['本轮真实MQ验收专用'], status: 1 });
  success(response, 'Unique dedicated room created through real HTTP.');
  fixture.roomId = safeId(response.json?.data);
  const room = await sql('exclusive-room-identity', `SELECT JSON_OBJECT('count',COUNT(*),'name',MAX(room_name),'status',MAX(status),'deleted',MAX(is_deleted)) FROM meeting_room WHERE id=${fixture.roomId} AND BINARY room_name=BINARY ${sqlLiteral(fixture.roomName)};`);
  check(room.count === 1 && room.name === fixture.roomName && room.status === 1 && room.deleted === 0, 'Fixture room exists only under its new ID/name.', room);
}
async function producerOutage() {
  phase = 'producer-outage-baseline';
  const witness = await createBooking({ witness: true });
  await waitDelivered(witness, 'persistence-witness-delivered');
  const witnessBefore = await waitFor('witness-index-ready', () => queryWitness(witness), (value) => value.messageIds.length === 1);
  const beforeOffsets = await topicOffsets(topic);
  const beforeBroker = await inspect('broker');
  report.observations.push({ phase, label: 'persistence-baseline', value: { witnessBefore, beforeOffsets } });
  phase = 'producer-outage-injected';
  await stopService('broker');
  const booking = await createBooking();
  check(!(await inspect('broker')).state.Running, 'Broker remained stopped throughout successful HTTP booking.');
  const pending = await waitFor('producer-failure-persisted', () => bookingState(booking), (state) => oneOrder(state) && state.events[0].status === 'PENDING' && state.events[0].attempts > 0);
  check(pending.notifications === 0 && pending.audits === 0 && pending.receipts === 0, 'Broker outage leaves consumer tables empty while the BOOKED order and retryable Outbox remain.', pending);
  phase = 'producer-outage-recovery';
  const afterBroker = await startService('broker');
  const beforeStore = beforeBroker.mounts.find((mount) => mount.Destination === '/home/rocketmq/store');
  const afterStore = afterBroker.mounts.find((mount) => mount.Destination === '/home/rocketmq/store');
  check(afterBroker.id === beforeBroker.id && afterBroker.image === beforeBroker.image && afterStore?.Name === beforeStore?.Name && afterStore?.Source === beforeStore?.Source, 'Broker restarted with the same pinned container/image/store volume.', { beforeStore, afterStore });
  const deliveredState = await waitDelivered(booking, 'outbox-retry-and-consumption-recovered');
  const witnessAfter = await waitFor('persistent-witness-recovered', () => queryWitness(witness), (value) => JSON.stringify(value.messageIds) === JSON.stringify(witnessBefore.messageIds));
  const afterOffsets = await topicOffsets(topic);
  check(beforeOffsets.rows.every((before) => afterOffsets.rows.some((after) => after.queue === before.queue && after.maxOffset >= before.maxOffset)) && afterOffsets.total > beforeOffsets.total, 'Persisted broker queue offsets survive restart and advance for the recovered event.', { beforeOffsets, afterOffsets, witnessAfter });
  check(delivered(deliveredState) && deliveredState.events[0].attempts >= pending.events[0].attempts, 'Recovered event is SENT with exactly one notification/audit/receipt and remains BOOKED.', deliveredState);
  recovered = true;
}
async function consumerFailure() {
  phase = 'consumer-failure-baseline';
  const existing = await sql('unique-trigger-name-unused', `SELECT JSON_OBJECT('count',COUNT(*)) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=${sqlLiteral(triggerName)};`);
  check(existing.count === 0, 'Unique trigger name did not already exist.', existing, 'ENVIRONMENT');
  const beforeRetry = await retryOffsets();
  const since = new Date().toISOString();
  phase = 'consumer-failure-injected';
  triggerMayExist = true;
  try {
    await sql('create-this-run-trigger', triggerCreateSql(), { rootUser: true, ddl: true });
    const trigger = await sql('verify-scoped-trigger-installed', `SELECT JSON_OBJECT('count',COUNT(*),'timing',MAX(ACTION_TIMING),'event',MAX(EVENT_MANIPULATION),'tableName',MAX(EVENT_OBJECT_TABLE),'statement',MAX(ACTION_STATEMENT)) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=${sqlLiteral(triggerName)};`);
    check(trigger.count === 1 && trigger.timing === 'BEFORE' && trigger.event === 'INSERT' && trigger.tableName === 'booking_notification', 'Only the dedicated BEFORE INSERT notification trigger was installed.', trigger, 'ENVIRONMENT');
    const booking = await createBooking();
    const sent = await waitFor('outbox-sent-despite-consumer-failure', () => bookingState(booking), (state) => oneOrder(state) && state.events[0].status === 'SENT' && Boolean(state.events[0].brokerMessageId));
    check(sent.notifications === 0 && sent.audits === 0 && sent.receipts === 0, 'Consumer transaction rolled back all three tables after producer SEND_OK.', sent);
    const failure = await waitFor('real-consumer-failure-and-broker-retry', async () => {
      const state = await bookingState(booking);
      const logs = await mustCommand('actual-mq-consumer-retry-logs', 'docker', ['logs', '--since', since, '--timestamps', '--tail', '1500', pinned.get('booking-service')]);
      const eventKeyPattern = new RegExp(`\\beventKey=${booking.eventId}(?:\\s|[;,]|$)`);
      const lines = `${logs.stdout}\n${logs.stderr}`.split(/\r?\n/).filter((line) => line.includes('booking event consumption failed')
        && line.includes('broker retry requested') && eventKeyPattern.test(line));
      const reconsumeTimes = lines.map((line) => Number(line.match(/\breconsumeTimes=(\d+)\b/)?.[1])).filter(Number.isSafeInteger);
      const afterRetry = await retryOffsets();
      return { state, eventId: booking.eventId, matchingFailureLines: lines, reconsumeTimes,
        redeliveryFailureObserved: reconsumeTimes.some((count) => count >= 1),
        brokerRetries: afterRetry, retryCountersAdvanced: increasedRetry(beforeRetry, afterRetry) };
    }, (value) => value.matchingFailureLines.length >= 2 && value.redeliveryFailureObserved && value.retryCountersAdvanced
      && oneOrder(value.state) && value.state.events[0].status === 'SENT'
      && value.state.notifications === 0 && value.state.audits === 0 && value.state.receipts === 0);
    check(failure.matchingFailureLines.length >= 2 && failure.redeliveryFailureObserved && failure.retryCountersAdvanced,
      'This exact event failed at least twice, including reconsumeTimes>=1; broker retry/schedule offsets advanced.', failure);
  } finally {
    phase = 'consumer-failure-trigger-removal';
    await removeTrigger();
  }
  phase = 'consumer-failure-recovery';
  const finalState = await waitDelivered(fixture.booking, 'broker-retry-recovered-after-trigger-removal');
  check(delivered(finalState), 'Broker retry succeeds after removal; notification/audit/receipt are each one and order remains BOOKED.', finalState);
  recovered = true;
}
async function consumerRestart() {
  phase = 'consumer-restart-baseline';
  const booking = await createBooking();
  await waitDelivered(booking, 'event-delivered-before-consumer-stop');
  phase = 'consumer-restart-stopped';
  await stopService('booking-service');
  const beforeOffsets = await consumerOffsets();
  const beforeRetry = await retryOffsets();
  const sender = await mustCommand('send-existing-real-event-with-consumer-stopped', process.platform === 'win32' ? 'powershell.exe' : 'pwsh', [
    '-NoLogo', '-NoProfile', '-NonInteractive', '-File', resolve(root, 'deploy/upgrade/run-middleware-probe.ps1'),
    '-Mode', 'MqSendOnly', '-OneOff', '-EventId', booking.eventId, '-ContainerId', pinned.get('booking-service'),
  ], '', 300_000);
  check(!(await inspect('booking-service')).state.Running, 'Business consumer stayed stopped until the send-only SDK returned SEND_OK.');
  const acknowledgements = [...sender.stdout.matchAll(/^PASS mq-send-only-broker-ack sendStatus=SEND_OK brokerMessageId=(\S+) queue=(\d+) offset=(\d+) copies=1\s*$/gm)];
  check(acknowledgements.length === 1, 'One real Java SDK broker acknowledgement identifies the exact queued copy.', sender.stdout);
  const [, messageId, queue, offset] = acknowledgements[0];
  const sent = { eventId: booking.eventId, messageId, queue: Number(queue), offset: Number(offset), acknowledgedWhileStopped: true };
  fixture.queuedCopy = sent;
  const artifactMatch = sender.stdout.match(/^ARTIFACT (.+)\s*$/m);
  check(Boolean(artifactMatch), 'Send-only probe recorded artifact provenance.', undefined, 'ENVIRONMENT');
  const artifactDirectory = resolve(artifactMatch[1].trim());
  const logsPrefix = `${resolve(root, 'logs')}${process.platform === 'win32' ? '\\' : '/'}`;
  check(artifactDirectory.toLowerCase().startsWith(logsPrefix.toLowerCase()), 'Probe provenance path belongs to this workspace logs.', artifactDirectory, 'ENVIRONMENT');
  const source = JSON.parse((await readFile(resolve(artifactDirectory, 'artifact-source.json'), 'utf8')).replace(/^\uFEFF/, ''));
  report.observations.push({ phase, label: 'same-image-send-only-provenance', value: source });
  check(source.oneOff === true && source.source === 'deployed-container-jar' && source.containerId === pinned.get('booking-service')
    && source.composeProject === project && source.runtimeImageId === report.environment.containers['booking-service'].image,
  'Send-only OneOff used the stopped service\'s deployed JAR and same image without starting Spring.', source, 'ENVIRONMENT');
  const queuedOffsets = await consumerOffsets();
  const queued = queuedOffsets.find((row) => row.queue === sent.queue);
  check(queued && queued.brokerOffset > sent.offset && queued.consumerOffset <= sent.offset, 'Exact acknowledged message is a real backlog behind the stopped business group.', { beforeOffsets, sent, queuedOffsets });
  const whileStopped = await bookingState(booking);
  check(delivered(whileStopped), 'Sending during downtime leaves original business rows each at one.', whileStopped);
  phase = 'consumer-restart-recovery';
  await startService('booking-service');
  const afterOffsets = await waitFor('business-group-consumed-exact-backlog-copy', () => consumerOffsets(), (rows) => rows.some((row) => row.queue === sent.queue && row.consumerOffset > sent.offset && row.brokerOffset > sent.offset));
  const afterRetry = await retryOffsets();
  check(sameRetry(beforeRetry, afterRetry), 'Backlog copy did not enter retry/DLQ/schedule failure paths.', { beforeRetry, afterRetry });
  const finalState = await bookingState(booking);
  check(delivered(finalState), 'Real business-group offset passed the copy; duplicate event remains idempotent with exactly one notification/audit/receipt.', { sent, afterOffsets, finalState });
  recovered = true;
}
async function restore() {
  restoring = true; phase = 'finally-restoration';
  const errors = [];
  if (triggerMayExist) {
    try { await removeTrigger(); report.restoration.push({ resource: triggerName, status: 'PASS', action: 'drop-only-this-run-trigger' }); }
    catch (error) { errors.push(error); report.restoration.push({ resource: triggerName, status: 'FAIL', error: redact(error.message) }); }
  }
  for (const service of [...restoreServices]) {
    try { await startService(service); report.restoration.push({ resource: service, status: 'PASS', action: 'start-same-pinned-container-and-wait-healthy' }); }
    catch (error) { errors.push(error); report.restoration.push({ resource: service, status: 'FAIL', error: redact(error.message) }); }
  }
  // Health is checked even if the recovery path already started the container.
  for (const service of ['broker', 'booking-service']) if (preflightComplete && pinned.has(service)) {
    try { await healthy(service); report.restoration.push({ resource: service, status: 'PASS', action: 'finally-healthy' }); }
    catch (error) { errors.push(error); report.restoration.push({ resource: service, status: 'FAIL', error: redact(error.message) }); }
  }
  if (options.mode === 'consumer-failure' && triggerRemoved && fixture.booking?.eventId && !recovered && !errors.length) {
    try { await waitDelivered(fixture.booking, 'finally-delivery-after-trigger-removal'); report.restoration.push({ resource: fixture.booking.eventId, status: 'PASS', action: 'retry-delivery-recovered' }); }
    catch (error) { errors.push(error); report.restoration.push({ resource: fixture.booking.eventId, status: 'FAIL', error: redact(error.message) }); }
  }
  return errors;
}

let error;
try {
  await persist();
  await preflight();
  preflightComplete = true;
  phase = 'fixture-creation';
  report.status = 'RUNNING'; report.modes[options.mode] = { status: 'RUNNING', startedAt: new Date().toISOString() };
  await createRoom();
  console.log(`RUNNING ${options.mode} run=${runId} room=${fixture.roomId}; fixtures will remain.`);
  const executeMode = { 'producer-outage': producerOutage, 'consumer-failure': consumerFailure, 'consumer-restart': consumerRestart };
  await executeMode[options.mode]();
} catch (failure) {
  error = { category: failure.category || 'EXECUTION', phase, message: redact(failure.message) };
  report.error = error;
} finally {
  if (interrupted && !error) {
    error = { category: 'EXECUTION', phase, message: 'Interrupted; restoration is required before completion.' };
    report.error = error;
  }
  const restorationErrors = await restore();
  if (restorationErrors.length) {
    report.restorationFailed = true;
    error ||= { category: 'ENVIRONMENT', phase: 'finally-restoration', message: 'Required restoration or health verification failed.' };
    report.error = error;
  }
  const executed = report.modes[options.mode].status === 'RUNNING';
  report.status = error ? (executed || report.restorationFailed ? 'FAIL' : 'NOT_EXECUTED') : 'PASS';
  report.passed = report.status === 'PASS' && recovered && !restorationErrors.length;
  if (!report.passed && !error) { report.status = 'FAIL'; report.error = { category: 'EXECUTION', message: 'Scenario did not produce all required recovery evidence.' }; }
  report.completedAt = new Date().toISOString();
  report.modes[options.mode] = { ...report.modes[options.mode], status: report.status, completedAt: report.completedAt,
    ...(report.error ? { failure: report.error } : {}), ...(report.status === 'NOT_EXECUTED' ? { reason: 'Environment preflight failed before fault injection.' } : {}) };
  adminToken = undefined;
  await persist();
  secrets.clear();
  console.log(`${report.status} ${options.mode}${report.error ? ` category=${report.error.category}` : ''}`);
  console.log(`EVIDENCE ${outputFile}`);
  process.exitCode = report.passed ? 0 : 1;
}
