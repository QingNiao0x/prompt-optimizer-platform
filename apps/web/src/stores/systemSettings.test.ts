import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

import { getSystemSettings, saveInterfaceLocale } from '@/services/systemSettingsApi';
import { useAuthStore } from '@/stores/auth';
import { INTERFACE_LOCALE_STORAGE_KEY, useLocaleStore } from '@/stores/locale';
import { useSystemSettingsStore } from '@/stores/systemSettings';
import type { SystemSettingsView } from '@/types/systemSettings';

vi.mock('@/services/systemSettingsApi', () => ({
  getSystemSettings: vi.fn(), saveDisplayName: vi.fn(), saveInterfaceLocale: vi.fn(),
}));

const view: SystemSettingsView = {
  displayName: 'Settings fixture', email: 'fixture@example.com', username: 'fixture-user', locale: 'zh-CN',
  emailBindingAvailable: true, accountDeletionAvailable: true,
};

const authenticate = (id = 'settings-user'): void => {
  useAuthStore().user = {
    userId: id, tenantId: 'settings-tenant', workspaceId: 'settings-workspace',
    displayName: 'Settings fixture', email: 'fixture@example.com', platformAdmin: false,
  };
};

describe('system settings identity and language isolation', () => {
  let storage: Map<string, string>;
  beforeEach(() => {
    vi.resetAllMocks();
    storage = new Map();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
    });
    vi.stubGlobal('document', { documentElement: { lang: 'zh-CN' } });
    setActivePinia(createPinia());
    authenticate();
    vi.mocked(getSystemSettings).mockResolvedValue(view);
  });

  it('shares identity and settings-page loading without duplicate requests', async () => {
    const settings = useSystemSettingsStore();
    await Promise.all([settings.load(), settings.load()]);
    expect(getSystemSettings).toHaveBeenCalledOnce();
    expect(settings.view).toEqual(view);
  });

  it('discards the previous account response after an identity switch', async () => {
    let finish: ((value: SystemSettingsView) => void) | undefined;
    vi.mocked(getSystemSettings).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const settings = useSystemSettingsStore();
    const oldRequest = settings.load();
    useAuthStore().clearLocalSession();
    authenticate('new-settings-user');
    const newView = { ...view, displayName: 'New account', locale: 'en' as const };
    vi.mocked(getSystemSettings).mockResolvedValueOnce(newView);
    await settings.load();
    finish?.(view);
    await oldRequest;
    expect(settings.view).toEqual(newView);
    expect(useLocaleStore().locale).toBe('en');
  });

  it('keeps the last confirmed language when server persistence fails', async () => {
    const settings = useSystemSettingsStore();
    await settings.load();
    vi.mocked(saveInterfaceLocale).mockRejectedValueOnce(new Error('offline'));
    await expect(settings.updateLocale('en')).rejects.toThrow('offline');
    expect(useLocaleStore().locale).toBe('zh-CN');
    expect(storage.get(INTERFACE_LOCALE_STORAGE_KEY)).toBe('zh-CN');
    expect(settings.view?.locale).toBe('zh-CN');
  });

  it('persists only a confirmed locale and changes shared navigation and titles', async () => {
    const settings = useSystemSettingsStore();
    await settings.load();
    vi.mocked(saveInterfaceLocale).mockResolvedValueOnce({ ...view, locale: 'en' });
    await settings.updateLocale('en');
    const language = useLocaleStore();
    expect(language.t('systemSettings')).toBe('System settings');
    expect(language.t('history')).toBe('History');
    expect(language.elementLocale.name).toBe('en');
    expect(language.pageTitle('settings', '')).toBe('System settings · Prompt Optimizer');
    expect(document.documentElement.lang).toBe('en');
    expect([...storage.entries()]).toEqual([[INTERFACE_LOCALE_STORAGE_KEY, 'en']]);
  });

  it('does not repopulate account information or language after a late save and logout', async () => {
    const settings = useSystemSettingsStore();
    await settings.load();
    let finish: ((value: SystemSettingsView) => void) | undefined;
    vi.mocked(saveInterfaceLocale).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const save = settings.updateLocale('en');
    useAuthStore().clearLocalSession();
    finish?.({ ...view, locale: 'en' });
    await save;
    expect(settings.view).toBeUndefined();
    expect(useLocaleStore().locale).toBe('zh-CN');
  });
});
