export interface CurrentUser { userId: number; username: string; role: 'USER' | 'ADMIN' }
export interface Notice { id: number; booking_id: number; content: string; created_at: string }
