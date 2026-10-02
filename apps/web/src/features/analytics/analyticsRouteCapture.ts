import { watch } from 'vue';
import type { Pinia } from 'pinia';
import type { Router } from 'vue-router';

import { useAuthStore } from '@/stores/auth';
import { useAnalyticsDeliveryStore } from '@/stores/analyticsDelivery';

/**
 * 同一次成功导航只产生一次账号访问；首页可以先显示，身份与 CSRF 恢复后再补报。
 * 刷新会生成新的 PV 事件，日访问人数由后端按账号去重，不能在浏览器把 PV 当 UV。
 */
export const installAnalyticsRouteCapture = (router: Router, pinia: Pinia): (() => void) => {
  const auth = useAuthStore(pinia);
  const delivery = useAnalyticsDeliveryStore(pinia);
  let navigation = 0;
  let completedPath: string | undefined;
  let captured: string | undefined;
  const report = (): void => {
    if (!auth.initialized || !auth.user || completedPath === undefined
        || router.currentRoute.value.name === 'login'
        || completedPath !== router.currentRoute.value.fullPath) return;
    const key = `${navigation}:${auth.user.userId}`;
    if (captured === key) return;
    captured = key;
    delivery.enqueue('APP_VISIT');
  };
  const stopWatching = watch(() => [auth.initialized, auth.user?.userId], report, { flush: 'sync' });
  const stopNavigation = router.afterEach((to, _from, failure) => {
    if (failure) return;
    navigation += 1;
    completedPath = to.fullPath;
    report();
  });
  return (): void => {
    stopWatching();
    stopNavigation();
  };
};
