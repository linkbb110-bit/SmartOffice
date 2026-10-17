#!/usr/bin/env node
/** Verifies V1 only in a new, unique, retained MySQL schema. Never migrates the application schema. */
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
if (process.argv.slice(2).includes('--help')) {
  console.log('node deploy/upgrade/real-migration-check.mjs');
  console.log('Creates and retains one unique real_api_v1_* schema. Uses mysql-booking root credentials inside the container.');
  process.exit(0);
}
if (process.argv.length !== 2) throw new Error('No options are supported; schema names cannot be supplied.');
const runId = `${Date.now()}_${randomBytes(6).toString('hex')}`;
const schema = `real_api_v1_${runId}`;
const outputDirectory = resolve(root, 'logs/real-integration');
const outputFile = resolve(outputDirectory, 'real-migration-result.json');
const report = { runId, schema, startedAt: new Date().toISOString(), status: 'RUNNING', passed: false,
  safety: { newUniqueSchemaOnly: true, applicationSchemaModified: false, fixturesRetained: true }, checks: [], commands: [] };
let created = false;

async function command(args, input = '') {
  return await new Promise((done) => {
    const child = spawn('docker', args, { cwd: root, windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    let finished = false;
    const finish = (value) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      done({ stdout: stdout.trim(), stderr: stderr.trim(), ...value });
    };
    const timer = setTimeout(() => { child.kill(); finish({ exitCode: -1, error: 'Docker command exceeded 30 seconds.' }); }, 30_000);
    child.stdout.on('data', (data) => { stdout += data; });
    child.stderr.on('data', (data) => { stderr += data; });
    child.on('error', (error) => finish({ exitCode: -1, error: error.message }));
    child.stdin.on('error', () => {});
    child.on('close', (exitCode) => finish({ exitCode }));
    child.stdin.end(input);
  });
}

function localEndpoint(endpoint) {
  if (typeof endpoint !== 'string') return false;
  if (/^npipe:\/{2,4}\.\/pipe\//i.test(endpoint) || endpoint.startsWith('unix://')) return true;
  try { return ['localhost', '127.0.0.1', '[::1]'].includes(new URL(endpoint).hostname); }
  catch { return false; }
}

async function mysql(label, query, useSchema = true, expectedError = false) {
  if (useSchema && !created) throw new Error('The unique schema has not been created by this run.');
  const databaseOption = useSchema ? ` -D${schema}` : '';
  const result = await command(['compose', '--env-file', 'deploy/.env.upgrade', '-f', 'deploy/docker-compose.upgrade.yml',
    'exec', '-T', 'mysql-booking', 'sh', '-c',
    `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot${databaseOption} --batch --raw --skip-column-names --default-character-set=utf8mb4`], `${query}\n`);
  report.commands.push({ label, database: useSchema ? schema : 'none', sql: query, ...result });
  if (!expectedError && result.exitCode !== 0) throw new Error(`${label}: ${result.error || result.stderr}`);
  return result;
}

async function json(label, query) { return JSON.parse((await mysql(label, query)).stdout); }
function assert(condition, description, evidence) {
  report.checks.push({ description, status: condition ? 'PASS' : 'FAIL', evidence });
  console.log(`${condition ? 'PASS' : 'FAIL'} ${description}`);
}

try {
  if (process.env.DOCKER_HOST && !localEndpoint(process.env.DOCKER_HOST)) throw new Error('Remote DOCKER_HOST is refused.');
  const context = await command(['context', 'inspect']);
  if (context.exitCode !== 0) throw new Error(`Docker context cannot be inspected: ${context.error || context.stderr}`);
  const selected = JSON.parse(context.stdout)[0];
  if (!process.env.DOCKER_HOST && !localEndpoint(selected?.Endpoints?.docker?.Host)) throw new Error('Remote Docker context is refused.');
  report.dockerContext = { name: selected?.Name, endpoint: process.env.DOCKER_HOST || selected?.Endpoints?.docker?.Host };

  const database = JSON.parse((await mysql('schema-must-not-exist',
    `SELECT JSON_OBJECT('version',VERSION(),'existingSchemas',(SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='${schema}'));`, false)).stdout);
  assert(/^8\./.test(database.version) && database.existingSchemas === 0, 'Real MySQL 8 and fresh schema name', database);
  if (database.existingSchemas !== 0 || !/^8\./.test(database.version)) throw new Error('Refusing to reuse a schema or run on a different database engine.');
  await mysql('create-unique-schema', `CREATE DATABASE ${schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`, false);
  created = true;

  const currentSchema = await readFile(resolve(root, 'backend/booking-service/src/main/resources/schema.sql'), 'utf8');
  const oldSchema = currentSchema.split(/\r?\n/).filter((line) => !/^\s*request_id\s/i.test(line) && !/UNIQUE KEY uk_booking_request/.test(line)).join('\n');
  if (/request_id|uk_booking_request/.test(oldSchema)) throw new Error('Cannot construct the V0 schema without request_id.');
  await mysql('create-v0-tables', oldSchema);
  await mysql('insert-retained-legacy-fixtures', `INSERT INTO meeting_room(id,room_name,capacity,equipment,status,is_deleted)
    VALUES(1,'V1 retained legacy room ${runId}',12,'[]',1,0);
    INSERT INTO booking_order(id,user_id,room_id,topic,start_time,end_time,status,created_at,updated_at)
    VALUES(1,77,1,'V1 retained legacy order ${runId}','2099-01-01 10:00:00','2099-01-01 11:00:00',1,'2020-01-01 00:00:00','2020-01-01 00:00:00');`);
  const snapshotQuery = `SELECT JSON_OBJECT('room',(SELECT JSON_OBJECT('id',id,'name',room_name,'capacity',capacity,'status',status,'isDeleted',is_deleted) FROM meeting_room WHERE id=1),
    'booking',(SELECT JSON_OBJECT('id',id,'userId',user_id,'roomId',room_id,'topic',topic,'startTime',start_time,'endTime',end_time,'status',status,'createdAt',created_at,'updatedAt',updated_at) FROM booking_order WHERE id=1));`;
  const before = await json('legacy-before-migration', snapshotQuery);
  const migration = await readFile(resolve(root, 'deploy/migrations/V1__booking_request_id.sql'), 'utf8');
  if (!/ALTER TABLE booking_order ADD COLUMN request_id/.test(migration) || !/ALTER TABLE booking_order ADD UNIQUE KEY uk_booking_request/.test(migration)
    || /\b(USE|DROP|DELETE|TRUNCATE|UPDATE|INSERT|CREATE\s+DATABASE)\b/i.test(migration)) throw new Error('V1 migration is outside the expected additive ALTER scope.');
  await mysql('apply-original-v1-in-unique-schema', migration);
  const after = await json('legacy-after-migration', snapshotQuery);
  assert(JSON.stringify(before) === JSON.stringify(after), 'V1 preserves every legacy fixture field', { before, after });

  const metadata = await json('request-id-metadata', `SELECT JSON_OBJECT(
    'requestColumn',(SELECT JSON_OBJECT('type',DATA_TYPE,'length',CHARACTER_MAXIMUM_LENGTH,'nullable',IS_NULLABLE,'charset',CHARACTER_SET_NAME,'collation',COLLATION_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='${schema}' AND TABLE_NAME='booking_order' AND COLUMN_NAME='request_id'),
    'uniqueColumns',(SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='${schema}' AND TABLE_NAME='booking_order' AND INDEX_NAME='uk_booking_request' AND NON_UNIQUE=0),
    'legacyRequestIsNull',(SELECT request_id IS NULL FROM booking_order WHERE id=1),
    'roomRows',(SELECT COUNT(*) FROM meeting_room),'bookingRows',(SELECT COUNT(*) FROM booking_order));`);
  const column = metadata.requestColumn;
  assert(column?.type === 'varchar' && column.length === 64 && column.nullable === 'YES' && column.charset === 'ascii' && column.collation === 'ascii_bin',
    'request_id is nullable VARCHAR(64) ASCII with binary collation', column);
  assert(metadata.uniqueColumns === 'user_id,request_id', 'Unique index columns are user_id then request_id', metadata.uniqueColumns);
  assert(metadata.legacyRequestIsNull === 1 && metadata.roomRows === 1 && metadata.bookingRows === 1, 'Original room and booking remain with NULL legacy request ID', metadata);

  await mysql('insert-new-request', `INSERT INTO booking_order(user_id,room_id,topic,start_time,end_time,status,request_id)
    VALUES(77,1,'new keyed request','2099-01-01 12:00:00','2099-01-01 13:00:00',1,'V1_key');`);
  const duplicate = await mysql('expected-duplicate-key', `INSERT INTO booking_order(user_id,room_id,topic,start_time,end_time,status,request_id)
    VALUES(77,1,'rejected duplicate','2099-01-01 14:00:00','2099-01-01 15:00:00',1,'V1_key');`, true, true);
  assert(duplicate.exitCode !== 0 && /ERROR 1062/.test(duplicate.stderr), 'Unique request key actually rejects a same-user duplicate', { exitCode: duplicate.exitCode, stderr: duplicate.stderr });
  await mysql('insert-distinct-case-and-user-and-null', `INSERT INTO booking_order(user_id,room_id,topic,start_time,end_time,status,request_id) VALUES
    (77,1,'case distinct key','2099-01-01 16:00:00','2099-01-01 17:00:00',1,'v1_key'),
    (78,1,'different user key','2099-01-01 18:00:00','2099-01-01 19:00:00',1,'V1_key'),
    (77,1,'second NULL legacy key','2099-01-01 20:00:00','2099-01-01 21:00:00',1,NULL);`);
  const final = await json('retained-final-row-counts', "SELECT JSON_OBJECT('orders',(SELECT COUNT(*) FROM booking_order),'sameUserKey',(SELECT COUNT(*) FROM booking_order WHERE user_id=77 AND request_id='V1_key'),'nullRequests',(SELECT COUNT(*) FROM booking_order WHERE request_id IS NULL));");
  assert(final.orders === 5 && final.sameUserKey === 1 && final.nullRequests === 2, 'Binary key case, per-user scope, and multiple NULL legacy keys work', final);
} catch (error) { report.error = error.message; }
finally {
  report.completedAt = new Date().toISOString();
  report.schemaCreated = created;
  report.passed = !report.error && report.checks.length > 0 && report.checks.every((check) => check.status === 'PASS');
  report.status = report.passed ? 'PASS' : 'FAIL';
  await mkdir(outputDirectory, { recursive: true });
  const contents = `${JSON.stringify(report, null, 2)}\n`;
  await writeFile(resolve(outputDirectory, `real-migration-result.${runId}.json`), contents, 'utf8');
  await writeFile(outputFile, contents, 'utf8');
  console.log(`${report.status}: ${outputFile}`);
  console.log(`Retained unique schema: ${created ? schema : 'none created'}`);
  process.exitCode = report.passed ? 0 : 1;
}
