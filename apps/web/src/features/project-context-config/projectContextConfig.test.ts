import { describe, expect, it } from 'vitest';

import {
  DEFAULT_PROJECT_CONTEXT_SETTINGS,
  loadProjectContextSettings,
  resolveEffectiveProjectIndexLimits,
  resolveProjectContextProfile,
  saveProjectContextSettings,
  type KeyValueStorage,
} from './projectContextConfig';

class MemoryStorage implements KeyValueStorage {
  private readonly values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

describe('projectContextConfig', () => {
  it('should restore validated local settings without accepting unknown values', () => {
    const storage = new MemoryStorage();
    storage.setItem('prompt-optimizer.project-context-settings.v1', JSON.stringify({
      profile: 'UNKNOWN',
      retention: 'FOREVER',
      autoCleanupDays: 999,
      confirmBeforeSendingCode: false,
    }));

    expect(loadProjectContextSettings(storage)).toEqual({
      ...DEFAULT_PROJECT_CONTEXT_SETTINGS,
      confirmBeforeSendingCode: false,
    });
  });

  it('should persist privacy settings locally', () => {
    const storage = new MemoryStorage();
    saveProjectContextSettings({
      profile: 'WEB_STANDARD',
      retention: 'PERSISTENT',
      autoCleanupDays: 7,
      confirmBeforeSendingCode: false,
    }, storage);

    expect(loadProjectContextSettings(storage)).toEqual({
      profile: 'WEB_STANDARD',
      retention: 'PERSISTENT',
      autoCleanupDays: 7,
      confirmBeforeSendingCode: false,
    });
  });

  it('should keep planned profiles unavailable to the current web runtime', () => {
    expect(resolveProjectContextProfile('IDE_LOCAL').code).toBe('WEB_STANDARD');
    expect(resolveProjectContextProfile('LONG_CONTEXT').code).toBe('WEB_STANDARD');

    const storage = new MemoryStorage();
    storage.setItem('prompt-optimizer.project-context-settings.v1', JSON.stringify({
      ...DEFAULT_PROJECT_CONTEXT_SETTINGS,
      profile: 'LONG_CONTEXT',
    }));
    expect(loadProjectContextSettings(storage).profile).toBe('WEB_STANDARD');
  });

  it('should reserve only part of the remaining browser quota for an index', () => {
    const profile = resolveProjectContextProfile('WEB_STANDARD');
    const limits = resolveEffectiveProjectIndexLimits(profile, {
      usage: 300 * 1024 * 1024,
      quota: 500 * 1024 * 1024,
    });

    expect(limits.maxScanFiles).toBe(1_000_000);
    expect(limits.maxIndexableFileBytes).toBe(50 * 1024 * 1024);
    expect(limits.maxIndexBytes).toBe(160 * 1024 * 1024);
  });
});
