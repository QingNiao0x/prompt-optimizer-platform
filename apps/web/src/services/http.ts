import axios, { AxiosError } from 'axios';

import type { ApiErrorPayload } from '@/types/api';

export const httpClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  // 后端会在模型读取超时后返回可区分的错误；这里必须比后端时间略长，
  // 否则浏览器会先中断请求，用户只能看到笼统的前端超时提示。
  timeout: 70_000,
  headers: {
    'Content-Type': 'application/json',
  },
});

export const isAuthenticationRequired = (error: unknown): boolean => (
  error instanceof AxiosError && error.response?.status === 401
);

// 会话失效时重新装载公开页面，清除当前页面内存中的文件、计划和回答。
// 不重放失败的写请求，避免重复提交；登录/身份探测的 401 由调用方处理。
httpClient.interceptors.response.use(undefined, (error: unknown) => {
  if (isAuthenticationRequired(error) && error instanceof AxiosError
      && !error.config?.url?.startsWith('/api/v1/auth/')
      && (['/workbench', '/history', '/settings'].includes(window.location.pathname)
        || window.location.pathname.startsWith('/admin/'))) {
    window.location.replace('/login?expired=1');
  }
  return Promise.reject(error);
});

/**
 * 将后端统一错误和网络异常转换为用户可执行的提示，不暴露上游模型原始响应。
 */
export const getApiErrorMessage = (error: unknown): string => {
  if (error instanceof AxiosError) {
    const payload = error.response?.data as ApiErrorPayload | undefined;
    if (payload?.error?.message) {
      return payload.error.message;
    }
    if (error.code === 'ECONNABORTED') {
      return '请求等待超时，请检查模型服务状态后重试。';
    }
    if (!error.response) {
      return '无法连接后端服务，请确认 API 服务已经启动。';
    }
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return '请求处理失败，请稍后重试。';
};

/**
 * 提取后端已生成的请求标识，用于把用户界面中的失败提示与服务端日志关联起来。
 * 网络层在收到任何业务响应前就中断时不会有该标识，此时返回空字符串。
 */
export const getApiErrorRequestId = (error: unknown): string => {
  if (!(error instanceof AxiosError)) {
    return '';
  }
  const payload = error.response?.data as ApiErrorPayload | undefined;
  return payload?.requestId ?? '';
};

export const getApiErrorCode = (error: unknown): string => {
  if (!(error instanceof AxiosError)) return '';
  const payload = error.response?.data as ApiErrorPayload | undefined;
  return payload?.error?.code ?? '';
};
