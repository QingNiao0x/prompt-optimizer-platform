import { httpClient } from './http';

import type {
  ApiResponse,
  AuthenticatedUser,
  CsrfTokenMetadata,
  EmailRegistrationCodePayload,
  EmailRegistrationCodeStatus,
  EmailRegistrationPayload,
  LoginPayload,
} from '@/types/api';

/** 初始化 Cookie/Header 双提交所需的 CSRF Cookie。 */
export const initializeCsrf = async (): Promise<ApiResponse<CsrfTokenMetadata>> => {
  const response = await httpClient.get<ApiResponse<CsrfTokenMetadata>>('/api/v1/auth/csrf');
  return response.data;
};

export const login = async (
  payload: LoginPayload,
): Promise<ApiResponse<AuthenticatedUser>> => {
  await initializeCsrf();
  const response = await httpClient.post<ApiResponse<AuthenticatedUser>>(
    '/api/v1/auth/login',
    payload,
  );
  return response.data;
};

export const requestRegistrationCode = async (
  payload: EmailRegistrationCodePayload,
): Promise<ApiResponse<EmailRegistrationCodeStatus>> => {
  await initializeCsrf();
  const response = await httpClient.post<ApiResponse<EmailRegistrationCodeStatus>>(
    '/api/v1/auth/registration-code',
    payload,
  );
  return response.data;
};

export const register = async (
  payload: EmailRegistrationPayload,
): Promise<ApiResponse<AuthenticatedUser>> => {
  await initializeCsrf();
  const response = await httpClient.post<ApiResponse<AuthenticatedUser>>(
    '/api/v1/auth/register',
    payload,
  );
  return response.data;
};

export const getCurrentUser = async (): Promise<ApiResponse<AuthenticatedUser>> => {
  const response = await httpClient.get<ApiResponse<AuthenticatedUser>>('/api/v1/auth/me');
  return response.data;
};

export const logout = async (): Promise<ApiResponse<null>> => {
  await initializeCsrf();
  const response = await httpClient.post<ApiResponse<null>>('/api/v1/auth/logout');
  return response.data;
};
