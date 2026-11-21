import assert from 'node:assert/strict';
import { test } from 'node:test';
import { filterRooms, filterOrders, dashboardSummary, safeRedirect, validateSessionIdentity } from '../src/utils/console.ts';
import { createIdentityLoader } from '../src/utils/identitySession.ts';

const tokenFor = (user) => `e30.${Buffer.from(JSON.stringify({ uid: user.userId, sub: user.username, role: user.role })).toString('base64url')}.test`;
const alice = { userId: 1, username: 'alice', role: 'USER' };
test('identity and role come from the API and must match token claims', () => {
  assert.equal(validateSessionIdentity(alice, tokenFor(alice)), true);
  assert.equal(validateSessionIdentity({ ...alice, role: 'ADMIN' }, tokenFor(alice)), false);
  assert.equal(validateSessionIdentity({ ...alice, userId: 2 }, tokenFor(alice)), false);
  assert.equal(validateSessionIdentity(alice, 'invalid'), false);
});
test('return paths stay inside the application and cannot loop to login', () => {
  for (const path of ['https://evil.test', '//evil.test', '/\\evil.test', '/login', '/session-error', '/login?redirect=/rooms']) assert.equal(safeRedirect(path), '/dashboard');
  assert.equal(safeRedirect('/bookings?status=1'), '/bookings?status=1');
});
test('room filters operate on real loaded rooms without changing the input', () => {
  const rooms = [{ id: 1, roomName: '评审室', capacity: 12, status: 1 }, { id: 2, roomName: '小会议室', capacity: 4, status: 0 }];
  assert.deepEqual(filterRooms(rooms, '评审', 8, 1).map(x => x.id), [1]);
  assert.equal(rooms.length, 2);
});
test('failed/unloaded sources remain unknown instead of showing fake zero metrics', () => {
  const now = new Date('2099-01-01T09:00:00');
  assert.equal(dashboardSummary(null, null, now).rooms, null);
  const orders = [{ id: 1, status: 1, topic: '跨日', startTime: '2098-12-31T23:00:00', endTime: '2099-01-01T10:00:00' }, { id: 2, status: 2, topic: '取消', startTime: '2099-01-01T11:00:00', endTime: '2099-01-01T12:00:00' }];
  assert.equal(dashboardSummary([], orders, now).today, 1);
  assert.equal(dashboardSummary([], orders, now).upcoming, 0);
  assert.deepEqual(filterOrders(orders, '跨日', 1).map(x => x.id), [1]);
});
test('concurrent identity loads share one request and failures remain retryable', async () => {
  let calls = 0;
  const loader = createIdentityLoader(() => tokenFor(alice), async () => { calls++; if (calls === 1) throw new Error('offline'); return alice; });
  const first = await Promise.allSettled([loader.load(), loader.load()]);
  assert.equal(first.every(x => x.status === 'rejected'), true);
  assert.equal(calls, 1);
  assert.deepEqual(await loader.load(), alice);
  assert.equal(calls, 2);
});
test('a late identity response cannot restore an account after session change', async () => {
  let current = tokenFor(alice), finish;
  const loader = createIdentityLoader(() => current, () => new Promise(resolve => { finish = resolve; }));
  const pending = loader.load();
  current = null;
  loader.reset();
  finish(alice);
  await assert.rejects(pending, /会话已切换/);
});
