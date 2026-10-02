import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import {
  getCurrentUser,
  initializeCsrf,
  login as loginRequest,
  logout as logoutRequest,
  register as registerRequest,
} from '@/services/authApi';
import { isAuthenticationRequired } from '@/services/http';
import type { AuthenticatedUser, EmailRegistrationPayload, LoginPayload } from '@/types/api';

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthenticatedUser>();
  const initialized = ref(false);
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

  return {
    user,
    initialized,
    isAuthenticated,
    initialize,
    login,
    register,
    logout,
  };
});
