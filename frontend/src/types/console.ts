export interface CurrentUser { userId: number; username: string; role: 'USER' | 'ADMIN' }
export interface Notice { id: number; booking_id: number; content: string; created_at: string }
export interface RoomInput { roomName: string; capacity: number; equipment: string[]; status: number }
export interface OutboxEvent {
  event_id: string;
  aggregate_id: number;
  event_type: string;
  status: string;
  attempts: number;
  available_at: string | null;
  lease_until: string | null;
  last_error: string | null;
  broker_message_id: string | null;
}
