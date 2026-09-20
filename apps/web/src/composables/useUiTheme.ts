import { computed, ref } from 'vue';

export type UiThemeId = 'glass-light' | 'glass-dark';

export interface UiThemeDefinition {
  id: UiThemeId;
  label: string;
  description: string;
}

export const UI_THEMES: readonly UiThemeDefinition[] = [
  {
    id: 'glass-light',
    label: '浅蓝玻璃',
    description: '通透的浅蓝背景与柔和高光，适合日间使用。',
  },
  {
    id: 'glass-dark',
    label: '深色玻璃',
    description: '低亮度深蓝背景与克制高光，适合长时间工作。',
  },
];

const STORAGE_KEY = 'prompt-optimizer.ui-theme';
const activeThemeId = ref<UiThemeId>('glass-light');
let initialized = false;

const isUiThemeId = (value: string | null): value is UiThemeId =>
  UI_THEMES.some((theme) => theme.id === value);

const resolveStoredTheme = (value: string | null): UiThemeId => {
  return isUiThemeId(value) ? value : 'glass-light';
};

const applyTheme = (themeId: UiThemeId): void => {
  activeThemeId.value = themeId;
  if (typeof document !== 'undefined') {
    document.documentElement.dataset.uiTheme = themeId;
  }
  if (typeof window !== 'undefined') {
    try {
      window.localStorage.setItem(STORAGE_KEY, themeId);
    } catch {
      // 隐私模式或浏览器禁用站点存储时，仍保留当前页面内的主题切换。
    }
  }
};

const initializeTheme = (): void => {
  if (initialized) {
    return;
  }
  initialized = true;

  let storedTheme: string | null = null;
  if (typeof window !== 'undefined') {
    try {
      storedTheme = window.localStorage.getItem(STORAGE_KEY);
    } catch {
      // 无法读取站点存储时使用浅蓝玻璃主题。
    }
  }
  applyTheme(resolveStoredTheme(storedTheme));
};

export const useUiTheme = () => {
  initializeTheme();

  const currentTheme = computed(() =>
    UI_THEMES.find((theme) => theme.id === activeThemeId.value) ?? UI_THEMES[0],
  );
  const isDark = computed(() => activeThemeId.value === 'glass-dark');

  const selectTheme = (themeId: UiThemeId): void => {
    applyTheme(themeId);
  };

  const toggleTheme = (): void => {
    applyTheme(isDark.value ? 'glass-light' : 'glass-dark');
  };

  return {
    activeThemeId,
    currentTheme,
    isDark,
    themes: UI_THEMES,
    selectTheme,
    toggleTheme,
  };
};
