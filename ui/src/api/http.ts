import { message } from "antd";

// API 响应类型定义，匹配后端 ApiResponse 结构
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
}

// 请求配置选项
export interface RequestOptions extends RequestInit {
  params?: Record<string, string | number | boolean | null | undefined>;
  /** 为 true 时，HTTP 401 不自动跳转登录页（供登录态探测接口使用） */
  skipAuthRedirect?: boolean;
}

// API 基础路径：开发模式直连本机后端；生产构建用同源相对路径（nginx 反代 /api 与 /sse 到 8080）
export const BASE_URL = import.meta.env.DEV
  ? "http://localhost:8080/api"
  : "/api";

// SSE 等非 /api 端点的源（开发为本机后端源，生产为同源）
export const API_ORIGIN = import.meta.env.DEV
  ? "http://localhost:8080"
  : "";

/**
 * 会话失效时跳转登录页，并携带当前路径供登录后回跳
 */
export function redirectToLogin() {
  if (window.location.pathname === "/login") return;
  const redirect = encodeURIComponent(
    window.location.pathname + window.location.search,
  );
  window.location.href = `/login?redirect=${redirect}`;
}

/**
 * 构建完整的 URL（包含查询参数）
 */
function buildUrl(url: string, params?: Record<string, string | number | boolean | null | undefined>): string {
  const fullUrl = `${BASE_URL}${url}`;
  
  if (!params || Object.keys(params).length === 0) {
    return fullUrl;
  }

  const searchParams = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined) {
      searchParams.append(key, String(value));
    }
  });

  const queryString = searchParams.toString();
  return queryString ? `${fullUrl}?${queryString}` : fullUrl;
}

/**
 * 处理响应
 */
async function handleResponse<T>(response: Response): Promise<ApiResponse<T>> {
  if (!response.ok) {
    // HTTP 状态码错误
    throw new Error(`HTTP error! status: ${response.status}`);
  }

  const data: ApiResponse<T> = await response.json();

  // 检查业务状态码
  if (data.code !== 200) {
    message.error(data.message || "请求失败");
    throw new Error(data.message || "请求失败");
  }

  return data;
}

/**
 * 封装的 fetch 请求函数
 */
async function request<T = unknown>(
  url: string,
  options: RequestOptions = {}
): Promise<T> {
  const { params, headers, skipAuthRedirect, ...restOptions } = options;

  // 构建完整 URL
  const fullUrl = buildUrl(url, params);

  // 设置默认请求头
  const defaultHeaders: HeadersInit = {
    "Content-Type": "application/json",
    ...headers,
  };

  try {
    const response = await fetch(fullUrl, {
      ...restOptions,
      headers: defaultHeaders,
      // 跨域携带会话 Cookie（后端 CORS 已开 allowCredentials）
      credentials: "include",
    });

    // 后端认证拦截器返回 HTTP 401：会话不存在或已过期，跳转登录页
    if (response.status === 401 && !skipAuthRedirect) {
      redirectToLogin();
      throw new Error("未登录或登录已过期");
    }

    const apiResponse = await handleResponse<T>(response);
    return apiResponse.data;
  } catch (error) {
    // 统一错误处理
    if (error instanceof Error) {
      throw error;
    }
    throw new Error("网络请求失败");
  }
}

/**
 * GET 请求
 */
export function get<T = unknown>(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
  options?: Omit<RequestOptions, "method" | "body" | "params">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "GET",
    params,
  });
}

/**
 * POST 请求
 */
export function post<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "POST",
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * PUT 请求
 */
export function put<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "PUT",
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * PATCH 请求
 */
export function patch<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "PATCH",
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * DELETE 请求
 */
export function del<T = unknown>(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
  options?: Omit<RequestOptions, "method" | "body" | "params">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "DELETE",
    params,
  });
}

// 导出默认对象，方便使用
export default {
  get,
  post,
  put,
  patch,
  delete: del,
};
