<script setup lang="ts">
import { ref } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { getApiErrorMessage } from '@/services/http';

const auth = useAuthStore();
const busy = ref(false);
const errorMessage = ref('');
const logout = async (): Promise<void> => {
  busy.value = true;
  errorMessage.value = '';
  try {
    await auth.logout();
    window.location.replace('/');
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error);
  } finally {
    busy.value = false;
  }
};
</script>

<template>
  <div v-if="auth.isAuthenticated" class="account-menu">
    <span :title="auth.user?.email">{{ auth.user?.displayName }}</span>
    <button type="button" :disabled="busy" @click="logout">{{ busy ? '退出中…' : '退出登录' }}</button>
    <span v-if="errorMessage" role="alert">{{ errorMessage }}</span>
  </div>
</template>

<style scoped>
.account-menu { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.account-menu > span:first-child { max-width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
button { padding: 6px 10px; border: 1px solid var(--glass-border); border-radius: var(--radius-pill); background: var(--glass-bg); color: var(--text-primary); cursor: pointer; }
[role='alert'] { max-width: 180px; }
@media (max-width: 640px) { .account-menu > span:first-child { display: none; } }
</style>
