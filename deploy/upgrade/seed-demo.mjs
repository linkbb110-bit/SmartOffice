import { randomUUID } from 'node:crypto';

const gateway = process.argv[2];
const username = process.env.SMARTOFFICE_DEMO_USERNAME;
const password = process.env.SMARTOFFICE_DEMO_PASSWORD;
if (!gateway || !username || !password) throw new Error('请提供独立演示环境地址及 SMARTOFFICE_DEMO_USERNAME、SMARTOFFICE_DEMO_PASSWORD');
const base = new URL(gateway);
if (!['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)) throw new Error('仅允许本机独立演示环境');

async function api(path, method = 'GET', data, token) {
  const response = await fetch(new URL(`/api${path}`, base), {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...(data ? { body: JSON.stringify(data) } : {})
  });
  const result = await response.json();
  if (!response.ok || result.code !== 200) throw new Error(result.message || `请求失败：${response.status}`);
  return result.data;
}
function futureWorkday(offset) {
  const date = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date());
  const [year, month, day] = date.split('-').map(Number);
  const next = new Date(Date.UTC(year, month - 1, day));
  while (offset > 0) {
    next.setUTCDate(next.getUTCDate() + 1);
    if (![0, 6].includes(next.getUTCDay())) offset--;
  }
  return next.toISOString().slice(0, 10);
}

const token = await api('/users/login', 'POST', { username, password });
const orders = await api('/orders/my', 'GET', undefined, token);
if (orders.length) {
  console.log('已有预约，未重复初始化演示数据');
} else {
  const rooms = await api('/rooms/list', 'GET', undefined, token);
  const meetings = [
    { roomName: '产品评审室', topic: '产品需求评审会', day: futureWorkday(1), start: '10:00:00', end: '11:00:00' },
    { roomName: '研发一号会议室', topic: '项目周例会', day: futureWorkday(2), start: '14:00:00', end: '15:00:00' }
  ];
  for (const meeting of meetings) {
    const room = rooms.find(item => item.roomName === meeting.roomName && item.status === 1);
    if (!room) throw new Error(`未找到可预约的${meeting.roomName}`);
    await api('/orders/book', 'POST', {
      roomId: room.id, topic: meeting.topic,
      startTime: `${meeting.day}T${meeting.start}`, endTime: `${meeting.day}T${meeting.end}`,
      requestId: randomUUID()
    }, token);
    console.log(`已创建${meeting.topic}：${meeting.day} ${meeting.start.slice(0, 5)}–${meeting.end.slice(0, 5)}`);
  }
}
