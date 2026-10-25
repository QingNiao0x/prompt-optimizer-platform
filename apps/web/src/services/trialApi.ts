import { httpClient } from './http';
import { initializeCsrf } from './authApi';
import { useAuthStore } from '@/stores/auth';
import type { ApiResponse } from '@/types/api';
import type { TrialStatus } from '@/types/trial';

/** 仅三个试用工具显式调用此分流；历史、管理和账号接口始终保留原认证路径。 */
export const trialEndpoint = (authenticatedPath: string, guestPath: string): string =>
  useAuthStore().isAuthenticated ? authenticatedPath : '/api/v1/trial' + guestPath;

/** 建立服务端签发的访客 Cookie；CSRF 不因匿名访问而跳过。 */
export const initializeTrialSession = async (): Promise<void> => {
  await initializeCsrf();
  await httpClient.post('/api/v1/trial/session');
};

export const getTrialStatus = async (): Promise<TrialStatus> =>
  (await httpClient.get<ApiResponse<TrialStatus>>('/api/v1/trial/status')).data.data;
