import { afterEach, describe, expect, it, vi } from 'vitest';

import { parsePlanModePreference, PLAN_MODE_STORAGE_KEY } from './usePlanModePreference';

describe('parsePlanModePreference', () => {
  it('treats a missing value as plan mode off and intro unseen', () => {
    expect(parsePlanModePreference(null)).toEqual({ enabled: false, introSeen: false });
  });

  it('ignores malformed storage instead of turning plan mode on', () => {
    expect(parsePlanModePreference('{')).toEqual({ enabled: false, introSeen: false });
    expect(parsePlanModePreference('[]')).toEqual({ enabled: false, introSeen: false });
  });

  it('only accepts explicit true flags', () => {
    expect(parsePlanModePreference(JSON.stringify({
      enabled: true,
      introSeen: true,
    }))).toEqual({ enabled: true, introSeen: true });
    expect(parsePlanModePreference(JSON.stringify({
      enabled: 'yes',
      introSeen: 1,
    }))).toEqual({ enabled: false, introSeen: false });
  });
});

describe('usePlanModePreference', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it('persists the switch and remembers that the intro has been handled', async () => {
    const storage = new Map<string, string>();
    vi.stubGlobal('window', {
      localStorage: {
        getItem: (key: string) => storage.get(key) ?? null,
        setItem: (key: string, value: string) => {
          storage.set(key, value);
        },
      },
    });

    const { usePlanModePreference } = await import('./usePlanModePreference');
    const preference = usePlanModePreference();

    expect(preference.enabled.value).toBe(false);
    expect(preference.introSeen.value).toBe(false);

    preference.setEnabled(true);
    expect(JSON.parse(storage.get(PLAN_MODE_STORAGE_KEY) ?? '')).toEqual({
      enabled: true,
      introSeen: false,
    });

    preference.dismissIntro();
    expect(preference.enabled.value).toBe(false);
    expect(preference.introSeen.value).toBe(true);

    preference.acceptIntro();
    expect(JSON.parse(storage.get(PLAN_MODE_STORAGE_KEY) ?? '')).toEqual({
      enabled: true,
      introSeen: true,
    });
  });
});
