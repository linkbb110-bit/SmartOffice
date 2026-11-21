import type { CurrentUser } from '../types/console';
import type { Order, Room } from '../types/room';

export function safeRedirect(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u001f]/.test(value) || /^\/(login|session-error)([/?#]|$)/.test(value)) return '/dashboard';
  return value;
}
export function validateSessionIdentity(user: CurrentUser, token: string): boolean {
  try {
    const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=')), c => c.charCodeAt(0));
    const claims = JSON.parse(new TextDecoder().decode(bytes));
    return user.userId > 0 && ['USER', 'ADMIN'].includes(user.role) && claims.uid === user.userId && claims.sub === user.username && claims.role === user.role;
  } catch { return false; }
}
export function normalizeEquipment(value: Room['equipment']): string[] {
  if (Array.isArray(value)) return value.filter(Boolean);
  if (!value) return [];
  try { const result = JSON.parse(value); if (Array.isArray(result)) return result.filter(x => typeof x === 'string' && x); } catch { /* 兼容历史分隔格式。 */ }
  return value.split(/[,，、]/).map(x => x.trim()).filter(Boolean);
}
export function filterRooms<T extends Pick<Room, 'roomName' | 'capacity' | 'status'>>(rooms: T[], keyword = '', capacity: number | null = null, status: number | null = null): T[] {
  return rooms.filter(room => room.roomName.toLocaleLowerCase().includes(keyword.trim().toLocaleLowerCase()) && (capacity == null || room.capacity >= capacity) && (status == null || room.status === status));
}
export function filterOrders<T extends Pick<Order, 'id' | 'topic' | 'status'>>(orders: T[], keyword = '', status: number | null = null): T[] {
  const query = keyword.trim().toLocaleLowerCase();
  return orders.filter(row => (row.topic.toLocaleLowerCase().includes(query) || String(row.id).includes(query)) && (status == null || row.status === status));
}
export function isToday(order: Pick<Order, 'startTime' | 'endTime' | 'status'>, now = new Date()) {
  const start = new Date(now); start.setHours(0, 0, 0, 0);
  const end = new Date(start); end.setDate(end.getDate() + 1);
  return order.status === 1 && new Date(order.startTime) < end && new Date(order.endTime) > start;
}
export function dashboardSummary(rooms: Room[] | null, orders: Order[] | null, now = new Date()) {
  return { rooms: rooms?.length ?? null, upcoming: orders?.filter(x => x.status === 1 && new Date(x.startTime) >= now).length ?? null, today: orders?.filter(x => isToday(x, now)).length ?? null };
}
export function displayTime(value: string | null | undefined) { return value ? value.replace('T', ' ').slice(0, 19) : '—'; }
export function errorMessage(error: unknown): string { return error && typeof error === 'object' && 'message' in error ? String(error.message) : '加载失败，请重试'; }
