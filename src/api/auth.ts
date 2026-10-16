import { request } from "@/utils/request";
import type { LoginParams } from "@/types/room";

export function loginApi(data: LoginParams) {
  return request<string>({
    url: "/users/login",
    method: "post",
    data
  });
}

export function registerApi(data: LoginParams) {
  return request<number>({ url: "/users/register", method: "post", data });
}
