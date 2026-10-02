import { computed, onScopeDispose, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { AxiosError } from 'axios';

import { createAnalyticsDeliveryQueue, createAnalyticsEventId } from '@/features/analytics/analyticsDeliveryQueue';
import { getClientAnalyticsContext, postClientAnalyticsEvent } from '@/services/clientAnalyticsApi';
import { useAuthStore } from '@/stores/auth';

/** 在当前登录账号范围恢复浏览器上报；不阻塞路由、复制或其他主业务。 */
export const useAnalyticsDeliveryStore = defineStore('analytics-delivery', () => {
  const auth = useAuthStore();
  const pendingCount = ref(0);
  const deliveryDegraded = ref(false);
  let loginSessionId: string | null = null;
  let contextGeneration = 0;
  let storage: Storage | undefined;
  try {
    storage = window.localStorage;
  } catch {
    // 隐私模式可能拒绝存储；队列会明确告警，并在本页面保留内存副本。
  }
  const queue = createAnalyticsDeliveryQueue({
    storage, send: postClientAnalyticsEvent,
    online: () => navigator.onLine,
    now: () => new Date(), uuid: createAnalyticsEventId,
    loginSessionId: () => loginSessionId,
    warning: (reason) => {
      deliveryDegraded.value = true;
      console.warn('event=analytics.client_delivery_degraded reason=%s', reason);
    },
    changed: (count) => {
      pendingCount.value = count;
      if (count === 0) deliveryDegraded.value = false;
    },
    status: (error) => error instanceof AxiosError ? error.response?.status : undefined,
  });
  watch(() => auth.initialized ? auth.user : undefined, (user) => {
    const generation = ++contextGeneration;
    loginSessionId = null;
    // 同账号重新登录也要解除认证暂停；旧记录自带发生时关联号，不能回填当前登录信息。
    queue.activateAccount(undefined);
    queue.activateAccount(user?.userId);
    if (!user) return;
    void getClientAnalyticsContext().then((context) => {
      if (generation === contextGeneration && context.userId === user.userId) {
        loginSessionId = context.loginSessionId;
      }
    }).catch(() => {
      console.warn('event=analytics.client_context_unavailable');
    });
  }, { immediate: true, flush: 'sync' });
  const resume = (): void => queue.resume();
  const storageChanged = (event: StorageEvent): void => {
    if (event.storageArea === storage && event.key?.startsWith('prompt-optimizer.analytics.pending.v1:')) queue.resume();
  };
  window.addEventListener('online', resume);
  window.addEventListener('storage', storageChanged);
  onScopeDispose(() => {
    window.removeEventListener('online', resume);
    window.removeEventListener('storage', storageChanged);
    queue.dispose();
  });
  return {
    pendingCount, deliveryDegraded, hasPendingEvents: computed(() => pendingCount.value > 0),
    enqueue: queue.enqueue, resume,
  };
});
