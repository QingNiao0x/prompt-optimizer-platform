import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import {
  getCurrentUser,
  initializeCsrf,
  login as loginRequest,
  logout as logoutRequest,
  register as registerRequest,
  getAuthCapabilities,
  registerPhone as registerPhoneRequest,
  loginWithSms as smsLoginRequest,
  bindPhone as bindPhoneRequest,
} from '@/services/authApi';
import { isAuthenticationRequired } from '@/services/http';
import type { AuthenticatedUser, AuthCapabilities, EmailRegistrationPayload, LoginPayload, PhoneCredentials } from '@/types/api';

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthenticatedUser>();
  const initialized = ref(false);
  const capabilities = ref<AuthCapabilities>({ phoneRegistration: false, smsLogin: false, phoneBinding: false });
  const isAuthenticated = computed(() => user.value !== undefined);
  let initialization: Promise<void> | undefined;
  let identityRevision = 0;

  const initialize = async (): Promise<void> => {
    if (initialized.value) {
      return;
    }
    // 首页菜单和受保护路由可能同时恢复身份；共享请求避免重复探测及 CSRF 初始化竞态。
    if (initialization) return initialization;
    const revision = identityRevision;
    initialization = (async (): Promise<void> => {
      try {
        const response = await getCurrentUser();
        await initializeCsrf();
        if (revision !== identityRevision) return;
        // 仅在 CSRF 就绪后公布身份，统计重放不能抢在恢复过程前发送写请求。
        user.value = response.data;
      } catch (error: unknown) {
        if (revision !== identityRevision) return;
        user.value = undefined;
        if (!isAuthenticationRequired(error)) throw error;
      }
      initialized.value = true;
    })();
    try {
      await initialization;
    } finally {
      initialization = undefined;
    }
  };

  const login = async (payload: LoginPayload): Promise<void> => {
    const response = await loginRequest(payload);
    identityRevision += 1;
    user.value = response.data;
    initialized.value = true;
  };

  const register = async (payload: EmailRegistrationPayload): Promise<void> => {
    const response = await registerRequest(payload);
    identityRevision += 1;
    user.value = response.data;
    initialized.value = true;
  };

  const logout = async (): Promise<void> => {
    try {
      await logoutRequest();
    } catch (error: unknown) {
      if (!isAuthenticationRequired(error)) {
        throw error;
      }
    }
    identityRevision += 1;
    user.value = undefined;
    initialized.value = true;
  };

  /** 能力查询失败只关闭新入口，原有邮箱登录仍然可用。 */
  const loadCapabilities = async (): Promise<void> => {
    try { capabilities.value = (await getAuthCapabilities()).data; } catch {
      capabilities.value = { phoneRegistration: false, smsLogin: false, phoneBinding: false };
    }
  };

  const registerPhone = async (payload: PhoneCredentials & { password: string }): Promise<void> => {
    const response = await registerPhoneRequest(payload);
    identityRevision += 1;
    user.value = response.data;
    initialized.value = true;
  };

  const loginSms = async (payload: PhoneCredentials): Promise<void> => {
    const response = await smsLoginRequest(payload);
    identityRevision += 1;
    user.value = response.data;
    initialized.value = true;
  };

  /** 绑定只更新当前用户投影，不接收客户端指定的目标账户。 */
  const refreshUser = async (): Promise<void> => {
    const revision = identityRevision;
    const response = await getCurrentUser();
    if (revision === identityRevision) user.value = response.data;
  };

  /** 绑定响应已包含安全资料；不追加无必要的查询，避免成功后刷新故障误报绑定失败。 */
  const bindPhone = async (payload: PhoneCredentials & { currentPassword: string }): Promise<void> => {
    const revision = identityRevision;
    const response = await bindPhoneRequest(payload);
    if (revision === identityRevision && user.value?.userId === response.data.userId) user.value = response.data;
  };

  return {
    user,
    initialized,
    isAuthenticated,
    initialize,
    login,
    register,
    logout,
    capabilities,
    loadCapabilities,
    registerPhone,
    loginSms,
    refreshUser,
    bindPhone,
  };
});
