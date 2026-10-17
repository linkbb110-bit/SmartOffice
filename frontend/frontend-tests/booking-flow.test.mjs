import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createBookingSubmission } from '../src/utils/bookingSubmission.ts';

const payload = { roomId: 1, topic: '讨论', startTime: '2026-10-15T09:00:00', endTime: '2026-10-15T10:00:00' };

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

test('guards a second submit while asynchronous form validation is still pending', async () => {
  const validation = deferred();
  const sent = [];
  const flow = createBookingSubmission({
    validate: () => validation.promise,
    send: async body => { sent.push(body); return 41; },
    newRequestId: () => 'attempt-1'
  });
  const first = flow.submit();
  const second = flow.submit();
  validation.resolve(payload);
  assert.deepEqual(await second, { status: 'busy' });
  assert.deepEqual(await first, { status: 'success', orderId: 41 });
  assert.equal(sent.length, 1);
});

test('a failed network request retains its requestId for the same payload retry', async () => {
  const sent = [];
  const flow = createBookingSubmission({
    validate: async () => payload,
    send: async body => { sent.push(body); if (sent.length === 1) throw new Error('timeout'); return 42; },
    newRequestId: () => 'retry-id'
  });
  await assert.rejects(flow.submit(), /timeout/);
  assert.deepEqual(await flow.submit(), { status: 'success', orderId: 42 });
  assert.deepEqual(sent, [{ ...payload, requestId: 'retry-id' }, { ...payload, requestId: 'retry-id' }]);
});

test('payload changes, successful requests, and a newly opened dialog each create a fresh requestId', async () => {
  let current = payload;
  let sequence = 0;
  let fails = true;
  const sent = [];
  const flow = createBookingSubmission({
    validate: async () => current,
    send: async body => { sent.push(body); if (fails) throw new Error('timeout'); return 43; },
    newRequestId: () => `id-${++sequence}`
  });
  await assert.rejects(flow.submit());
  current = { ...payload, topic: '另一个主题' };
  await assert.rejects(flow.submit());
  current = payload;
  flow.resetIntent(); // Form edited away and back, or dialog opened again.
  await assert.rejects(flow.submit());
  flow.resetIntent();
  fails = false;
  await flow.submit();
  await flow.submit();
  assert.deepEqual(sent.map(body => body.requestId), ['id-1', 'id-2', 'id-3', 'id-4', 'id-5']);
});

test('invalid form never sends and always releases the submit guard', async () => {
  let valid = false;
  const states = [];
  const flow = createBookingSubmission({
    validate: async () => valid ? payload : null,
    send: async () => 44,
    newRequestId: () => 'valid-id',
    onPending: state => states.push(state)
  });
  assert.deepEqual(await flow.submit(), { status: 'invalid' });
  valid = true;
  assert.deepEqual(await flow.submit(), { status: 'success', orderId: 44 });
  assert.deepEqual(states, [true, false, true, false]);
});
