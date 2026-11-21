import assert from 'node:assert/strict';
import { test } from 'node:test';
import { bookingNumber, shortEventId } from '../src/utils/display.ts';
import { filterOrders } from '../src/utils/console.ts';

test('booking display number uses the actual meeting date and retains the full ID', () => {
  assert.equal(bookingNumber(1, '2026-11-21T09:00:00'), 'BK20261121-001');
  assert.equal(bookingNumber(1042, '2026-11-22T14:30:00'), 'BK20261122-1042');
  assert.equal(bookingNumber(7), 'BK-007');
  assert.equal(bookingNumber(7, 'invalid'), 'BK-007');
});
test('booking search accepts the display number without changing API IDs', () => {
  const orders=[{id:1,topic:'产品需求评审会',status:1,startTime:'2026-11-21T09:00:00'}];
  assert.deepEqual(filterOrders(orders,'BK20261121-001'),orders);
  assert.equal(orders[0].id,1);
});
test('short UUID preserves recognizable ends and keeps short identifiers readable', () => {
  const id='42e0395a-1a08-422e-be5d-0083bdf51234';
  assert.equal(shortEventId(id),'42e0395a…bdf51234');
  assert.equal(shortEventId('short-id'),'short-id');
});
