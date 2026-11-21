import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

function planFor(database) {
  const match = /^so_ui001_demo_(\d{4})(\d{2})(\d{2})$/.exec(database || '');
  if (!match) throw new Error('请明确指定独立演示数据库 SMARTOFFICE_DEMO_DATABASE');
  const [year, month, day] = match.slice(1).map(Number);
  const anchor = new Date(Date.UTC(year, month - 1, day));
  if (anchor.toISOString().slice(0, 10).replaceAll('-', '') !== match.slice(1).join('')) throw new Error('演示数据库的计划日期无效');
  const workday = offset => {
    const date = new Date(anchor);
    while (offset > 0) {
      date.setUTCDate(date.getUTCDate() + 1);
      if (![0, 6].includes(date.getUTCDay())) offset--;
    }
    return date.toISOString().slice(0, 10);
  };
  return [
    { key: 'review', roomName: '产品评审室', topic: '产品需求评审会', day: workday(1), start: '10:00:00', end: '11:00:00' },
    { key: 'weekly', roomName: '研发一号会议室', topic: '项目周例会', day: workday(2), start: '14:00:00', end: '15:00:00' }
  ];
}

function localTime(value) {
  const text = String(value || '').replace(' ', 'T').replace(/\.0+$/, '');
  return text.length === 16 ? `${text}:00` : text;
}

/** 逐条补齐演示预约；重试使用同一计划和请求键，不改写已有订单或消息。 */
export async function seedDemo({ gateway, username, password, expectedDatabase, fetchImpl = fetch, now = new Date(), log = console.log }) {
  const plan = planFor(expectedDatabase);
  if (!gateway || !username || !password) throw new Error('请提供演示环境地址和账号环境变量');
  const base = new URL(gateway);
  if (!['http:', 'https:'].includes(base.protocol) || !['localhost', '127.0.0.1', '[::1]'].includes(base.hostname) || base.username || base.password) throw new Error('仅允许无内嵌凭据的本机独立演示环境');
  async function request(path, method = 'GET', data, token) {
    const response = await fetchImpl(new URL(`/api${path}`, base), {
      method, redirect: 'error', signal: AbortSignal.timeout(10000),
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      ...(data ? { body: JSON.stringify(data) } : {})
    });
    let result;
    try { result = await response.json(); } catch { throw new Error(`接口未返回有效 JSON：${response.status}`); }
    if (!response.ok) throw new Error(result.message || `请求失败：${response.status}`);
    return result;
  }
  async function api(path, method, data, token) {
    const result = await request(path, method, data, token);
    if (result.code !== 200) throw new Error(result.message || '业务接口返回失败');
    return result.data;
  }
  const token = await api('/users/login', 'POST', { username, password });
  const identity = await request('/demo/environment', 'GET', undefined, token);
  if (identity.smartofficeDemo?.mode !== 'demo' || identity.smartofficeDemo.database !== expectedDatabase) throw new Error('实际连接的不是指定独立演示数据库，已拒绝初始化');
  const orders = await api('/orders/my', 'GET', undefined, token);
  const rooms = await api('/rooms/list', 'GET', undefined, token);
  if (!Array.isArray(orders) || !Array.isArray(rooms)) throw new Error('演示数据查询结果无效');
  const targets = plan.map(meeting => {
    const matchingRooms = rooms.filter(room => room.roomName === meeting.roomName);
    if (matchingRooms.length !== 1) throw new Error(`会议室不存在或名称不唯一：${meeting.roomName}`);
    const room = matchingRooms[0], startTime = `${meeting.day}T${meeting.start}`, endTime = `${meeting.day}T${meeting.end}`;
    const existing = orders.find(row => row.roomId === room.id && row.topic === meeting.topic && localTime(row.startTime) === startTime && localTime(row.endTime) === endTime);
    if (!existing && room.status !== 1) throw new Error(`会议室不可预约：${meeting.roomName}`);
    if (!existing && new Date(`${startTime}+08:00`) <= now) throw new Error('缺失的演示计划已过期，请使用新的独立演示库');
    return { meeting, existing, data: {
      roomId: room.id, topic: meeting.topic, startTime, endTime,
      requestId: `demo-v1-${expectedDatabase.slice(-8)}-${meeting.key}-${room.id}`
    } };
  });
  let submitted = 0, skipped = 0;
  for (const { meeting, existing, data } of targets) {
    if (existing) { skipped++; log(`目标预约已存在：${meeting.topic}`); continue; }
    await api('/orders/book', 'POST', data, token);
    submitted++; log(`已确认${meeting.topic}：${meeting.day} ${meeting.start.slice(0, 5)}–${meeting.end.slice(0, 5)}`);
  }
  return { submitted, skipped };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const result = await seedDemo({ gateway: process.argv[2], username: process.env.SMARTOFFICE_DEMO_USERNAME, password: process.env.SMARTOFFICE_DEMO_PASSWORD, expectedDatabase: process.env.SMARTOFFICE_DEMO_DATABASE });
    console.log(`演示预约确认完成：提交 ${result.submitted} 项，已有 ${result.skipped} 项`);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
