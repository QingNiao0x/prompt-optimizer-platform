import { ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { useAuthStore } from './auth';
import { getTrialStatus, initializeTrialSession } from '@/services/trialApi';
import { getApiErrorMessage } from '@/services/http';
import { trialFeatureInfo, type TrialFeature, type TrialStatus } from '@/types/trial';

/** 三个工具共享访客身份和服务端余额；新建任务、切换路由不创建新身份或重置次数。 */
export const useTrialStore = defineStore('trial', () => {
  const auth = useAuthStore();
  const status = ref<TrialStatus>();
  const initialized = ref(false);
  const loading = ref(false);
  const error = ref('');
  let pending: Promise<void> | undefined;
  let epoch = 0;

  watch(() => auth.user?.userId, () => {
    epoch += 1; pending = undefined; status.value = undefined;
    initialized.value = false; loading.value = false; error.value = '';
  }, { flush: 'sync' });

  const quota = (feature: TrialFeature) => status.value?.quotas[trialFeatureInfo[feature].key];
  const remaining = (feature: TrialFeature): number => Math.max(0, quota(feature)?.remaining ?? 0);
  const canUse = (feature: TrialFeature): boolean => auth.isAuthenticated
    || (initialized.value && !error.value && status.value?.available === true && remaining(feature) > 0);

  /** 失败只更新额度提示，不覆盖已经成功返回的生成结果；未知余额时禁止继续免费调用。 */
  const load = (createSession: boolean): Promise<void> => {
    if (auth.isAuthenticated) return Promise.resolve();
    if (pending) return pending;
    const current = epoch;
    loading.value = true; error.value = '';
    const request = (async () => {
      try {
        if (createSession) await initializeTrialSession();
        if (current !== epoch) return;
        const value = await getTrialStatus();
        if (current !== epoch) return;
        if (value.initialized === false && value.available) {
          throw new Error('免费体验身份未建立或已过期，请允许本站 Cookie 后重试，或注册登录。');
        }
        status.value = value; initialized.value = true;
      } catch (failure: unknown) {
        if (current === epoch) {
          status.value = undefined;
          error.value = getApiErrorMessage(failure);
        }
      } finally {
        if (current === epoch) { loading.value = false; pending = undefined; }
      }
    })();
    pending = request;
    return request;
  };
  const initialize = (): Promise<void> => initialized.value ? Promise.resolve() : load(true);
  const refresh = async (): Promise<void> => {
    // 生成完成后的刷新必须晚于此前的查询，不能复用生成前的旧余额响应。
    if (pending) await pending;
    await load(!initialized.value);
  };
  return { status, initialized, loading, error, quota, remaining, canUse, initialize, refresh };
});
