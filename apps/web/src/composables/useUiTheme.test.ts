import { afterEach, describe, expect, it, vi } from 'vitest';

describe('useUiTheme', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it('should expose only the current glass themes', async () => {
    const { UI_THEMES } = await import('./useUiTheme');

    expect(UI_THEMES.map((theme) => theme.id)).toEqual(['glass-light', 'glass-dark']);
  });

  it('should fall back to the light glass theme when persisted storage contains an unknown theme', async () => {
    const setItem = vi.fn();
    const classList = {
      toggle: vi.fn(),
    };
    const documentElement = {
      dataset: {} as Record<string, string>,
      classList,
    };
    const body = { classList };
    vi.stubGlobal('window', {
      localStorage: {
        getItem: () => 'removed-dark-theme',
        setItem,
      },
    });
    vi.stubGlobal('document', { documentElement, body });

    const { useUiTheme } = await import('./useUiTheme');
    const { activeThemeId } = useUiTheme();

    expect(activeThemeId.value).toBe('glass-light');
    expect(documentElement.dataset.uiTheme).toBe('glass-light');
    expect(classList.toggle).toHaveBeenCalledWith('dark', false);
    expect(setItem).toHaveBeenCalledWith('po-theme', 'light');
  });

  it('should restore dark theme from po-theme and mark body.dark', async () => {
    const setItem = vi.fn();
    const classList = {
      toggle: vi.fn(),
    };
    const documentElement = {
      dataset: {} as Record<string, string>,
      classList,
    };
    const body = { classList };
    vi.stubGlobal('window', {
      localStorage: {
        getItem: (key: string) => (key === 'po-theme' ? 'dark' : null),
        setItem,
      },
    });
    vi.stubGlobal('document', { documentElement, body });

    const { useUiTheme } = await import('./useUiTheme');
    const { activeThemeId, isDark } = useUiTheme();

    expect(activeThemeId.value).toBe('glass-dark');
    expect(isDark.value).toBe(true);
    expect(documentElement.dataset.uiTheme).toBe('glass-dark');
    expect(classList.toggle).toHaveBeenCalledWith('dark', true);
    expect(setItem).toHaveBeenCalledWith('po-theme', 'dark');
  });
});
