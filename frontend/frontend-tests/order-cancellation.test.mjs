import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createOrderCancellation } from '../src/utils/orderCancellation.ts';

test('cancellation guards repeated clicks and changes the visible order only after success', async () => {
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  const sent = [];
  const order = { id: 12, status: 1 };
  const cancel = createOrderCancellation(async id => { sent.push(id); await pending; });
  const first = cancel(order);
  assert.equal(order.status, 1);
  const second = cancel(order);
  release();
  assert.equal(await second, false);
  assert.equal(await first, true);
  assert.equal(order.status, 2);
  assert.equal(await cancel(order), false);
  assert.deepEqual(sent, [12]);
});

test('failed cancellation preserves the booked state and permits retry', async () => {
  let failing = true;
  const states = [];
  const order = { id: 13, status: 1 };
  const cancel = createOrderCancellation(async () => { if (failing) throw new Error('offline'); }, (id, value) => states.push([id, value]));
  await assert.rejects(cancel(order), /offline/);
  assert.equal(order.status, 1);
  failing = false;
  assert.equal(await cancel(order), true);
  assert.equal(order.status, 2);
  assert.deepEqual(states, [[13, true], [13, false], [13, true], [13, false]]);
});
