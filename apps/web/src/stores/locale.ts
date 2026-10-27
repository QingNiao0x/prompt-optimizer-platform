import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import en from 'element-plus/es/locale/lang/en';
import zhCn from 'element-plus/es/locale/lang/zh-cn';

import { settingsMessages, type SettingsMessageKey } from '@/features/settings/messages';
import type { InterfaceLocale } from '@/types/systemSettings';

export const INTERFACE_LOCALE_STORAGE_KEY = 'prompt-optimizer.locale.v1';

/** 只缓存语言偏好；不在本地存储任何账号资料、密码或验证码。 */
const readLocale = (): InterfaceLocale => {
  try { return localStorage.getItem(INTERFACE_LOCALE_STORAGE_KEY) === 'en' ? 'en' : 'zh-CN'; }
  catch { return 'zh-CN'; }
};

/** 可扩展的界面语言入口，账号偏好成功加载/保存后由设置 store 同步。 */
export const useLocaleStore = defineStore('locale', () => {
  const locale = ref<InterfaceLocale>(readLocale());
  const elementLocale = computed(() => locale.value === 'en' ? en : zhCn);
  const t = (key: SettingsMessageKey): string => settingsMessages[locale.value][key];

  /** 仅用于已确认的偏好；存储受限时仍保持当前页面语言可用。 */
  const apply = (value: InterfaceLocale): void => {
    locale.value = value;
    document.documentElement.lang = value;
    try { localStorage.setItem(INTERFACE_LOCALE_STORAGE_KEY, value); } catch { /* 浏览器可禁止持久存储。 */ }
  };
  document.documentElement.lang = locale.value;

  const pageTitle = (routeName: unknown, fallback: string): string => {
    const titles: Record<string, SettingsMessageKey> = {
      settings: 'systemSettings', home: 'home', login: 'loginTitle', workbench: 'enhancement',
      'image-to-prompt': 'imagePrompt', 'text-polish': 'textPolish', history: 'historyTitle',
      'admin-models': 'modelsTitle', 'admin-analytics': 'analyticsTitle', terms: 'terms', privacy: 'privacy',
    };
    const key = typeof routeName === 'string' ? titles[routeName] : undefined;
    return `${key ? t(key) : fallback} · Prompt Optimizer`;
  };
  return { locale, elementLocale, t, apply, pageTitle };
});
