export function bookingNumber(id: number, startTime?: string): string {
  const date = startTime?.match(/^(\d{4})-(\d{2})-(\d{2})(?:T| )/);
  return `BK${date ? date.slice(1).join('') : ''}-${String(id).padStart(3, '0')}`;
}

export function shortEventId(id: string): string {
  return id.length > 18 ? `${id.slice(0, 8)}…${id.slice(-8)}` : id;
}
