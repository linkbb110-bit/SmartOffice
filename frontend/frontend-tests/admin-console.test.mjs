import assert from 'node:assert/strict';
import { test } from 'node:test';
import { prepareRoom, requireCsv } from '../src/utils/admin.ts';

const room = { roomName: '  讨论室  ', capacity: 12, equipment: [' 白板 ', ''], status: 1 };
test('room input follows actual server constraints and preserves meaningful equipment', () => {
  assert.deepEqual(prepareRoom(room), { roomName: '讨论室', capacity: 12, equipment: ['白板'], status: 1 });
  for (const change of [{roomName:''}, {roomName:'a'.repeat(101)}, {capacity:31}, {capacity:0}, {capacity:1.5}, {status:2}]) {
    assert.throws(() => prepareRoom({...room, ...change}));
  }
});
test('equipment length checks JSON codepoints including escapes, matching the server', () => {
  assert.doesNotThrow(() => prepareRoom({...room, equipment:['😀'.repeat(496)]}));
  assert.throws(() => prepareRoom({...room, equipment:['😀'.repeat(497)]}));
  assert.throws(() => prepareRoom({...room, equipment:['"'.repeat(249)]}));
});
test('CSV export accepts only actual CSV and never downloads a JSON or HTML error', async () => {
  const csv = new Blob(['id,topic\n1,meeting'],{type:'text/csv;charset=UTF-8'});
  assert.equal(await requireCsv(csv),csv);
  await assert.rejects(requireCsv(new Blob(['<html>Error</html>'],{type:'text/html'})));
  await assert.rejects(requireCsv(new Blob([JSON.stringify({code:403,message:'无权限'})],{type:'application/json'})), /无权限/);
});
