import type { RoomInput } from '../types/console';

export function prepareRoom(input: RoomInput): RoomInput {
  const roomName = input.roomName.trim();
  if (!roomName || roomName.length > 100) throw new Error('会议室名称须为 1～100 个字符');
  if (!Number.isInteger(input.capacity) || input.capacity < 1 || input.capacity > 30) throw new Error('容量须为 1～30 的整数');
  if (![0, 1].includes(input.status)) throw new Error('请选择可预约或维护中');
  const equipment = input.equipment.map(x => x.trim()).filter(Boolean);
  // 后端按设备 JSON 的 Unicode 字符数限制存储长度，包含引号、逗号和转义。
  if (Array.from(JSON.stringify(equipment)).length > 500) throw new Error('设备列表过长，编码后不能超过 500 个字符');
  return { roomName, capacity: input.capacity, status: input.status, equipment };
}

export async function requireCsv(blob: Blob): Promise<Blob> {
  if (blob instanceof Blob && /^text\/csv(?:;|$)/i.test(blob.type)) return blob;
  if (blob instanceof Blob && /json/i.test(blob.type)) {
    let message = '';
    try { message = JSON.parse(await blob.text()).message || ''; } catch { /* 非法 JSON 使用统一错误。 */ }
    if (message) throw new Error(message);
  }
  throw new Error('导出未返回 CSV 文件，请重试');
}
