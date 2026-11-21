import assert from 'node:assert/strict';
import { test } from 'node:test';
import { seedDemo } from '../../deploy/upgrade/seed-demo.mjs';

const database = 'so_ui001_demo_20261121';
const now = new Date('2026-11-21T01:00:00Z');
function fixture(options = {}) {
  const orders = options.orders ? structuredClone(options.orders) : [];
  const events = [], posts = [], keys = new Map();
  let failed = false, hidden = false;
  const reply = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
  const fetchImpl = async (url, config = {}) => {
    const path = new URL(url).pathname;
    if (path === '/api/users/login') return reply({ code: 200, data: 'fixture-token' });
    if (path === '/api/demo/environment') return reply(options.identity ?? { smartofficeDemo: { mode: 'demo', database } });
    if (path === '/api/rooms/list') return reply({ code: 200, data: [{ id: 2, roomName: '产品评审室', status: 1 }, { id: 1, roomName: '研发一号会议室', status: 1 }] });
    if (path === '/api/orders/my') {
      if (options.hideCommitted && failed && !hidden) { hidden = true; return reply({ code: 200, data: [] }); }
      return reply({ code: 200, data: structuredClone(orders) });
    }
    assert.equal(path, '/api/orders/book');
    const data = JSON.parse(config.body); posts.push(data);
    if (options.failSecond && data.topic === '项目周例会' && !failed) {
      failed = true; return reply({ code: 503, message: 'temporary failure' }, 503);
    }
    if (keys.has(data.requestId)) return reply({ code: 200, data: keys.get(data.requestId) });
    const order = { ...data, id: orders.length + 1, status: 1 };
    orders.push(order); keys.set(data.requestId, order.id); events.push(order.id);
    if (options.loseFirstResponse && !failed) { failed = true; throw new Error('response lost'); }
    return reply({ code: 200, data: order.id });
  };
  const run = (overrides = {}) => seedDemo({ gateway: 'http://127.0.0.1:25173', username: 'user', password: 'fixture-password', expectedDatabase: database, fetchImpl, now, log: () => {}, ...overrides });
  return { run, orders, posts, events };
}

test('normal initialization creates both meetings and repeated execution adds no orders or messages', async () => {
  const f = fixture(); assert.equal((await f.run()).submitted, 2); assert.equal((await f.run()).submitted, 0);
  assert.equal(f.orders.length, 2); assert.equal(f.events.length, 2); assert.equal(f.posts.length, 2);
});
test('unrelated existing reservations do not prevent the target meetings being initialized', async () => {
  const normal = { id: 99, roomId: 1, topic: '架构讨论', startTime: '2026-11-26T15:00:00', endTime: '2026-11-26T16:00:00', status: 1 };
  const f = fixture({ orders: [normal] }); await f.run();
  assert.deepEqual(f.orders[0], normal); assert.equal(f.events.length, 2);
});
test('retry fills only the missing meeting after the second request fails', async () => {
  const f = fixture({ failSecond: true }); await assert.rejects(f.run(), /temporary failure/);
  assert.equal(f.orders.length, 1); assert.equal((await f.run()).submitted, 1);
  await f.run(); assert.equal(f.orders.length, 2); assert.equal(f.events.length, 2);
});
test('a committed request with a lost response reuses the same requestId even if the next read is stale', async () => {
  const f = fixture({ loseFirstResponse: true, hideCommitted: true }); await assert.rejects(f.run(), /response lost/);
  await f.run(); assert.equal(f.posts[0].requestId, f.posts[1].requestId);
  assert.equal(f.orders.length, 2); assert.equal(f.events.length, 2);
});
test('concurrent initializers share deterministic intent keys', async () => {
  const f = fixture(); await Promise.all([f.run(), f.run()]);
  assert.equal(f.orders.length, 2); assert.equal(f.events.length, 2);
  assert.equal(new Set(f.posts.map(row => row.requestId)).size, 2);
  assert.ok(f.posts.every(row => /^[A-Za-z0-9_-]{1,64}$/.test(row.requestId)));
});
test('matching canceled bookings remain canceled and are not recreated', async () => {
  const f = fixture(); await f.run(); f.orders.forEach(row => { row.status = 2; });
  await f.run(); assert.equal(f.posts.length, 2); assert.ok(f.orders.every(row => row.status === 2));
});
test('a retry on a later day keeps the same plan and fills the second meeting', async () => {
  const f = fixture({ failSecond: true }); await assert.rejects(f.run());
  await f.run({ now: new Date('2026-11-23T04:00:00Z') });
  assert.deepEqual(f.orders.map(row => row.startTime), ['2026-11-23T10:00:00', '2026-11-24T14:00:00']);
});
test('an actual non-demo or different database is rejected despite using the same localhost address', async () => {
  for (const actual of ['smartoffice_booking', 'so_ui001_demo_20261122']) {
    const f = fixture({ identity: { smartofficeDemo: { mode: 'demo', database: actual } } });
    await assert.rejects(f.run(), /独立演示/); assert.equal(f.posts.length, 0); assert.equal(f.events.length, 0);
  }
});
test('missing or disabled runtime identity is rejected before any booking write', async () => {
  for (const identity of [{}, { smartofficeDemo: { mode: 'disabled', database } }]) {
    const f = fixture({ identity }); await assert.rejects(f.run(), /独立演示/); assert.equal(f.posts.length, 0);
  }
});
test('an expired missing plan is rejected without silently moving dates or writing data', async () => {
  const f = fixture(); await assert.rejects(f.run({ now: new Date('2026-11-25T04:00:00Z') }), /过期/);
  assert.equal(f.posts.length, 0);
});
test('invalid database dates and non-local origins are rejected before connecting', async () => {
  const f = fixture();
  await assert.rejects(f.run({ expectedDatabase: 'so_ui001_demo_20260230' }), /日期/);
  await assert.rejects(f.run({ gateway: 'https://example.com' }), /本机/);
  await assert.rejects(f.run({ expectedDatabase: undefined }), /演示数据库/);
  assert.equal(f.posts.length, 0);
});
