import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

export type UiThemeId = 'midnight' | 'neon-control-room' | 'dusk-glass';

export interface UiThemeDefinition {
  id: UiThemeId;
  label: string;
  shortLabel: string;
  description: string;
}

export const UI_THEMES: readonly UiThemeDefinition[] = [
  {
    id: 'midnight',
    label: '午夜工作台',
    shortLabel: '午夜工作台',
    description: '克制的深色画布，适合长时间编写和阅读提示词。',
  },
  {
    id: 'neon-control-room',
    label: '霓虹工程控制室',
    shortLabel: '霓虹控制室',
    description: '蓝紫主控色配合青色状态，强调工程流程和操作反馈。',
  },
  {
    id: 'dusk-glass',
    label: '暮色玻璃工作区',
    shortLabel: '暮色玻璃区',
    description: '低饱和紫灰与半透明面板，适合沉浸式整理和复盘。',
  },
];

const STORAGE_KEY = 'prompt-optimizer.ui-theme';
const activeThemeId = ref<UiThemeId>('midnight');
let initialized = false;

const isUiThemeId = (value: string | null): value is UiThemeId =>
  UI_THEMES.some((theme) => theme.id === value);

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
      // 无法读取站点存储时使用默认主题。
    }
  }
  applyTheme(isUiThemeId(storedTheme) ? storedTheme : 'midnight');
};

export const useUiTheme = () => {
  initializeTheme();

  const isMenuOpen = ref(false);
  const currentTheme = computed(() =>
    UI_THEMES.find((theme) => theme.id === activeThemeId.value) ?? UI_THEMES[0],
  );

  const closeMenu = (): void => {
    isMenuOpen.value = false;
  };

  const toggleMenu = (): void => {
    isMenuOpen.value = !isMenuOpen.value;
  };

  const selectTheme = (themeId: UiThemeId): void => {
    applyTheme(themeId);
    closeMenu();
  };

  const handleDocumentPointerDown = (event: PointerEvent): void => {
    if (!(event.target instanceof Element)) {
      return;
    }
    if (!event.target.closest('[data-theme-switcher]')) {
      closeMenu();
    }
  };

  const handleDocumentKeydown = (event: KeyboardEvent): void => {
    if (event.key === 'Escape') {
      closeMenu();
    }
  };

  onMounted(() => {
    document.addEventListener('pointerdown', handleDocumentPointerDown);
    document.addEventListener('keydown', handleDocumentKeydown);
  });

  onBeforeUnmount(() => {
    document.removeEventListener('pointerdown', handleDocumentPointerDown);
    document.removeEventListener('keydown', handleDocumentKeydown);
  });

  return {
    activeThemeId,
    currentTheme,
    isMenuOpen,
    themes: UI_THEMES,
    closeMenu,
    toggleMenu,
    selectTheme,
  };
};
