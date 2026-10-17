import { request } from "@/utils/request";
import type { BookRoomParams, Order } from "@/types/room";

export function bookRoomApi(data: BookRoomParams) {
  return request<number>({
    url: "/orders/book",
    method: "post",
    data
  });
}

export function getMyOrdersApi() {
  return request<Order[]>({ url: "/orders/my", method: "get" });
}

export function cancelOrderApi(id: number) {
  return request<void>({ url: `/orders/${id}/cancel`, method: "post" });
}
