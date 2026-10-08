import { httpClient } from './http';

import type {
  ApiResponse,
  AuthenticatedUser,
  CsrfTokenMetadata,
  EmailRegistrationCodePayload,
  EmailRegistrationCodeStatus,
  EmailRegistrationPayload,
  LoginPayload,
  AuthCapabilities,
  SmsChallengeStatus,
  PhoneCredentials,
} from '@/types/api';

/** 只读取公开能力开关；失败由界面隐藏新入口，不影响旧邮箱流程。 */
export const getAuthCapabilities = async (): Promise<ApiResponse<AuthCapabilities>> => (
  (await httpClient.get<ApiResponse<AuthCapabilities>>('/api/v1/auth/capabilities')).data
);

/** 手机认证的写请求与原登录共享 CSRF，不自动重试收费的发送操作。 */
const phonePost = async <T>(url: string, payload: object): Promise<ApiResponse<T>> => {
  await initializeCsrf();
  return (await httpClient.post<ApiResponse<T>>(url, payload)).data;
};

/** 统一注册表单使用 REGISTER；短信登录使用 LOGIN，图形验证码只随发送请求提交。 */
export const requestSmsChallenge = (payload: { phone: string; purpose: 'REGISTER' | 'LOGIN'; captcha: string }): Promise<ApiResponse<SmsChallengeStatus>> => (
  phonePost('/api/v1/auth/sms/challenges', payload)
);
/** 手机注册提交号码及对应挑战，复用邮箱注册后的当前用户投影和会话处理。 */
export const registerPhone = (payload: PhoneCredentials & { password: string }): Promise<ApiResponse<AuthenticatedUser>> => (
  phonePost('/api/v1/auth/phone/register', payload)
);
export const loginWithSms = (payload: PhoneCredentials): Promise<ApiResponse<AuthenticatedUser>> => (
  phonePost('/api/v1/auth/phone/login', payload)
);
export const requestPhoneBinding = (payload: { phone: string; captcha: string; currentPassword: string }): Promise<ApiResponse<SmsChallengeStatus>> => (
  phonePost('/api/v1/me/phone-binding/challenges', payload)
);
export const bindPhone = (payload: PhoneCredentials & { currentPassword: string }): Promise<ApiResponse<AuthenticatedUser>> => (
  phonePost('/api/v1/me/phone-binding', payload)
);

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
