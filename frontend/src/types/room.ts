export interface Room {
  id: number;
  roomName: string;
  capacity: number;
  equipment: string[] | string | null;
  status: number;
}

export interface LoginParams {
  username: string;
  password: string;
}

export interface BookRoomParams {
  roomId: number;
  topic: string;
  startTime: string;
  endTime: string;
  requestId?: string;
}

export interface Order {
  id: number;
  userId: number;
  roomId: number;
  topic: string;
  startTime: string;
  endTime: string;
  status: 1 | 2;
}
