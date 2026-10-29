import { initializeCsrf } from '@/services/authApi';
import { httpClient } from '@/services/http';
import type { ApiResponse } from '@/types/api';
import type { InterfaceLocale, SettingsEmailChallenge, SystemSettingsView } from '@/types/systemSettings';

const basePath = '/api/v1/me/settings';

/** 所有写操作沿用双提交 CSRF；不重试发信或安全变更，避免重复费用与一次性凭证重放。 */
const write = async <T>(method: 'post' | 'patch', path: string, payload: object): Promise<T> => {
  await initializeCsrf();
  return (await httpClient.request<ApiResponse<T>>({ method, url: `${basePath}${path}`, data: payload })).data.data;
};

/** 读取服务端当前用户投影；接口不接收目标账号标识。 */
export const getSystemSettings = async (): Promise<SystemSettingsView> => (
  (await httpClient.get<ApiResponse<SystemSettingsView>>(basePath)).data.data
);

export const saveDisplayName = (displayName: string): Promise<SystemSettingsView> => write('patch', '/profile', { displayName });
export const saveInterfaceLocale = (locale: InterfaceLocale): Promise<SystemSettingsView> => write('patch', '/preferences', { locale });
export const requestSettingsEmailCode = (payload: { email: string; currentPassword: string }): Promise<SettingsEmailChallenge> => write('post', '/email-challenges', payload);
export const bindSettingsEmail = (payload: { email: string; currentPassword: string; challengeId: string; verificationCode: string }): Promise<SystemSettingsView> => write('post', '/email', payload);
export const saveSettingsUsername = (payload: { username: string; currentPassword: string }): Promise<SystemSettingsView> => write('patch', '/username', payload);
/** 密码只发送至当前已登录账户的安全接口；服务端成功后会注销全部会话。 */
export const changeSettingsPassword = (payload: { currentPassword: string; newPassword: string }): Promise<void> => write('patch', '/password', payload);
export const logoutAllSettingsSessions = (currentPassword: string): Promise<void> => write('post', '/logout-all', { currentPassword });
export const deactivateSettingsAccount = (currentPassword: string): Promise<void> => write('post', '/deactivate', { currentPassword, confirmation: 'DELETE' });
