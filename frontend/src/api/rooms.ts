import { request } from "@/utils/request";
import type { Room } from "@/types/room";

export function getRoomListApi() {
  return request<Room[]>({
    url: "/rooms/list",
    method: "get"
  });
}
