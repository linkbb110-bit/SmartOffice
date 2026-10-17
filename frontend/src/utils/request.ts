import axios, { type AxiosError, type AxiosRequestConfig } from "axios";
import { ElMessage } from "element-plus";

import router from "@/router";
import { clearToken, getToken } from "@/utils/auth";

export interface ApiResult<T = unknown> {
  code: number;
  message: string;
  data: T;
}

function isApiResult<T>(payload: unknown): payload is ApiResult<T> {
  return (
    !!payload &&
    typeof payload === "object" &&
    "code" in payload &&
    "message" in payload
  );
}

function isLoginRequest(config?: AxiosRequestConfig) {
  return config?.method?.toLowerCase() === "post" && config.url === "/users/login";
}

function redirectToLogin() {
  clearToken();
  if (router.currentRoute.value.path !== "/login") {
    router.replace({
      path: "/login",
      query: { redirect: router.currentRoute.value.fullPath }
    });
  }
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 10000
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => {
    const payload = response.data;

    if (!isApiResult(payload)) {
      return payload;
    }

    if (payload.code === 200) {
      return payload.data;
    }

    if (payload.code === 401) {
      const loginRequest = isLoginRequest(response.config);
      ElMessage.error(payload.message || (loginRequest ? "用户名或密码错误" : "登录已过期，请重新登录"));
      if (!loginRequest) redirectToLogin();
      return Promise.reject(payload);
    }

    ElMessage.error(payload.message || "请求失败，请稍后重试");
    return Promise.reject(payload);
  },
  (error: AxiosError<ApiResult>) => {
    const status = error.response?.status;
    const message =
      error.response?.data?.message || error.message || "网络异常，请稍后重试";

    if (status === 401) {
      const loginRequest = isLoginRequest(error.config);
      ElMessage.error(loginRequest ? error.response?.data?.message || "用户名或密码错误" : "登录已过期，请重新登录");
      if (!loginRequest) redirectToLogin();
      return Promise.reject(error);
    }

    ElMessage.error(message);
    return Promise.reject(error);
  }
);

export function request<T = unknown>(config: AxiosRequestConfig) {
  return http.request<T, T>(config);
}

export default http;
