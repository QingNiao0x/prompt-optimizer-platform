import { defineStore } from 'pinia';
import { ref, watch } from 'vue';

import { getSystemSettings, saveDisplayName, saveInterfaceLocale } from '@/services/systemSettingsApi';
import { useAuthStore } from '@/stores/auth';
import { useLocaleStore } from '@/stores/locale';
import type { InterfaceLocale, SystemSettingsView } from '@/types/systemSettings';

/** 设置投影按当前身份隔离；迟到响应不得覆盖退出或切换账号后的界面。 */
export const useSystemSettingsStore = defineStore('system-settings', () => {
  const auth = useAuthStore();
  const locale = useLocaleStore();
  const view = ref<SystemSettingsView>();
  let revision = 0;
  let pending: Promise<void> | undefined;
  watch(() => auth.user?.userId, () => {
    revision += 1;
    pending = undefined;
    view.value = undefined;
  }, { flush: 'sync' });

  const apply = (response: SystemSettingsView, expectedRevision: number): void => {
    if (revision !== expectedRevision || !auth.isAuthenticated) return;
    view.value = response;
    locale.apply(response.locale);
  };

  /** 首页身份恢复与设置页复用同一读取，失败不覆盖本机语言，也不伪造账号资料。 */
  const load = async (): Promise<void> => {
    if (!auth.isAuthenticated || view.value) return;
    if (pending) return pending;
    const expected = revision;
    const request = getSystemSettings().then(response => apply(response, expected));
    pending = request;
    try { await request; } finally { if (pending === request) pending = undefined; }
  };

  const updateDisplayName = async (name: string): Promise<void> => {
    const expected = revision;
    apply(await saveDisplayName(name), expected);
  };

  const updateLocale = async (value: InterfaceLocale): Promise<void> => {
    const expected = revision;
    apply(await saveInterfaceLocale(value), expected);
  };
  return { view, load, updateDisplayName, updateLocale };
});
