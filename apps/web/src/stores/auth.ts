import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import {
  getCurrentUser,
  initializeCsrf,
  login as loginRequest,
  logout as logoutRequest,
} from '@/services/authApi';
import { isAuthenticationRequired } from '@/services/http';
import type { AuthenticatedUser, LoginPayload } from '@/types/api';

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthenticatedUser>();
  const initialized = ref(false);
  const isAuthenticated = computed(() => user.value !== undefined);

  const initialize = async (): Promise<void> => {
    if (initialized.value) {
      return;
    }
    try {
      const response = await getCurrentUser();
      user.value = response.data;
      await initializeCsrf();
    } catch (error: unknown) {
      user.value = undefined;
      if (!isAuthenticationRequired(error)) {
        throw error;
      }
    }
    initialized.value = true;
  };

  const login = async (payload: LoginPayload): Promise<void> => {
    const response = await loginRequest(payload);
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
    user.value = undefined;
    initialized.value = true;
  };

  return {
    user,
    initialized,
    isAuthenticated,
    initialize,
    login,
    logout,
  };
});
