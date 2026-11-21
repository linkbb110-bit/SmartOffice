import { request } from "@/utils/request";
import type { Room } from "@/types/room";
import type { RoomInput } from "@/types/console";

export function getRoomListApi() {
  return request<Room[]>({
    url: "/rooms/list",
    method: "get"
  });
}

export function createRoomApi(data: RoomInput) { return request<number>({ url: '/rooms', method: 'post', data }); }
export function updateRoomApi(id: number, data: RoomInput) { return request<void>({ url: `/rooms/${id}`, method: 'put', data }); }
export function deleteRoomApi(id: number) { return request<void>({ url: `/rooms/${id}`, method: 'delete' }); }
