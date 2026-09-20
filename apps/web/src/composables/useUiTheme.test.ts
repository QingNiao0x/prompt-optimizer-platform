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
    const documentElement = { dataset: {} as Record<string, string> };
    vi.stubGlobal('window', {
      localStorage: {
        getItem: () => 'removed-dark-theme',
        setItem,
      },
    });
    vi.stubGlobal('document', { documentElement });

    const { useUiTheme } = await import('./useUiTheme');
    const { activeThemeId } = useUiTheme();

    expect(activeThemeId.value).toBe('glass-light');
    expect(documentElement.dataset.uiTheme).toBe('glass-light');
    expect(setItem).toHaveBeenCalledWith('prompt-optimizer.ui-theme', 'glass-light');
  });
});
