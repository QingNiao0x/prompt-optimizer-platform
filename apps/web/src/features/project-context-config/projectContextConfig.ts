export type ProjectContextProfileCode = 'WEB_STANDARD' | 'IDE_LOCAL' | 'LONG_CONTEXT';
export type ProjectIndexRetention = 'SESSION' | 'PERSISTENT';
export type ProjectContextProfileAvailability = 'AVAILABLE' | 'PLANNED';

export interface ProjectContextLimits {
  maxScanFiles: number;
  maxLocalIndexBytes: number;
  maxIndexableFileBytes: number;
  maxContextCharacters: number;
  maxContextChunks: number;
}

export interface ProjectContextProfile {
  code: ProjectContextProfileCode;
  label: string;
  description: string;
  availability: ProjectContextProfileAvailability;
  availabilityLabel: string;
  limits: ProjectContextLimits;
}

export interface ProjectContextSettings {
  profile: ProjectContextProfileCode;
  retention: ProjectIndexRetention;
  autoCleanupDays: 1 | 7 | 30;
  confirmBeforeSendingCode: boolean;
}

export interface BrowserStorageEstimate {
  usage?: number;
  quota?: number;
}

export interface BrowserStorageStatus extends BrowserStorageEstimate {
  supported: boolean;
  persisted: boolean;
}

export interface EffectiveProjectIndexLimits {
  maxScanFiles: number;
  maxIndexBytes: number;
  maxIndexableFileBytes: number;
}

export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

interface StorageManagerLike {
  estimate?: () => Promise<BrowserStorageEstimate>;
  persisted?: () => Promise<boolean>;
}

const SETTINGS_STORAGE_KEY = 'prompt-optimizer.project-context-settings.v1';
const STORAGE_HEADROOM_RATIO = 0.8;

export const PROJECT_CONTEXT_PROFILES: readonly ProjectContextProfile[] = [
  {
    code: 'WEB_STANDARD',
    label: '标准 Web 模式',
    description: '在浏览器本地建立完整索引，只向模型发送与当前任务相关的代码片段。',
    availability: 'AVAILABLE',
    availabilityLabel: '当前可用',
    limits: {
      maxScanFiles: 1_000_000,
      maxLocalIndexBytes: 2 * 1024 * 1024 * 1024,
      maxIndexableFileBytes: 50 * 1024 * 1024,
      maxContextCharacters: 120_000,
      maxContextChunks: 40,
    },
  },
  {
    code: 'IDE_LOCAL',
    label: 'IDE / 本地代理模式',
    description: '面向后续 IDE 插件或本地代理，可使用更大的本地索引和任务上下文。',
    availability: 'PLANNED',
    availabilityLabel: '预留配置',
    limits: {
      maxScanFiles: 2_000_000,
      maxLocalIndexBytes: 10 * 1024 * 1024 * 1024,
      maxIndexableFileBytes: 100 * 1024 * 1024,
      maxContextCharacters: 300_000,
      maxContextChunks: 80,
    },
  },
  {
    code: 'LONG_CONTEXT',
    label: '超长上下文模式',
    description: '为支持超长上下文的模型预留，启用前还需完成模型能力检测和请求体分片。',
    availability: 'PLANNED',
    availabilityLabel: '待后端支持',
    limits: {
      maxScanFiles: 1_000_000,
      maxLocalIndexBytes: 4 * 1024 * 1024 * 1024,
      maxIndexableFileBytes: 100 * 1024 * 1024,
      maxContextCharacters: 4_000_000,
      maxContextChunks: 320,
    },
  },
] as const;

export const DEFAULT_PROJECT_CONTEXT_SETTINGS: ProjectContextSettings = {
  profile: 'WEB_STANDARD',
  retention: 'SESSION',
  autoCleanupDays: 7,
  confirmBeforeSendingCode: true,
};

/**
 * 当前 Web 运行时只启用经过端到端验证的配置。预留配置被读取时会安全回退到标准模式。
 */
export const resolveProjectContextProfile = (
  code: ProjectContextProfileCode,
): ProjectContextProfile => {
  const selected = PROJECT_CONTEXT_PROFILES.find((profile) => profile.code === code);
  if (selected?.availability === 'AVAILABLE') {
    return selected;
  }
  return PROJECT_CONTEXT_PROFILES[0];
};

/**
 * 浏览器配额是整个站点共享的。索引最多使用剩余空间的 80%，为应用缓存和事务留出余量。
 */
export const resolveEffectiveProjectIndexLimits = (
  profile: ProjectContextProfile,
  estimate?: BrowserStorageEstimate,
): EffectiveProjectIndexLimits => {
  const remainingBytes = estimate?.quota !== undefined
    ? Math.max(0, estimate.quota - (estimate.usage ?? 0))
    : undefined;
  const quotaBudget = remainingBytes === undefined
    ? profile.limits.maxLocalIndexBytes
    : Math.floor(remainingBytes * STORAGE_HEADROOM_RATIO);

  return {
    maxScanFiles: profile.limits.maxScanFiles,
    maxIndexBytes: Math.min(profile.limits.maxLocalIndexBytes, quotaBudget),
    maxIndexableFileBytes: profile.limits.maxIndexableFileBytes,
  };
};

export const loadProjectContextSettings = (
  storage: KeyValueStorage = window.localStorage,
): ProjectContextSettings => {
  try {
    const value = storage.getItem(SETTINGS_STORAGE_KEY);
    return value ? normalizeSettings(JSON.parse(value) as unknown) : { ...DEFAULT_PROJECT_CONTEXT_SETTINGS };
  } catch {
    return { ...DEFAULT_PROJECT_CONTEXT_SETTINGS };
  }
};

export const saveProjectContextSettings = (
  settings: ProjectContextSettings,
  storage: KeyValueStorage = window.localStorage,
): void => {
  storage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(normalizeSettings(settings)));
};

export const readBrowserStorageStatus = async (
  storageManager: StorageManagerLike | undefined =
    typeof navigator === 'undefined' ? undefined : navigator.storage,
): Promise<BrowserStorageStatus> => {
  if (!storageManager?.estimate) {
    return { supported: false, persisted: false };
  }
  const [estimate, persisted] = await Promise.all([
    storageManager.estimate(),
    storageManager.persisted?.().catch(() => false) ?? Promise.resolve(false),
  ]);
  return {
    supported: true,
    persisted,
    usage: estimate.usage,
    quota: estimate.quota,
  };
};

const normalizeSettings = (value: unknown): ProjectContextSettings => {
  const source = value && typeof value === 'object'
    ? value as Partial<ProjectContextSettings>
    : {};
  const selectedProfile = PROJECT_CONTEXT_PROFILES.find((item) => item.code === source.profile);
  const profile = selectedProfile?.availability === 'AVAILABLE'
    ? selectedProfile.code
    : DEFAULT_PROJECT_CONTEXT_SETTINGS.profile;
  const retention = source.retention === 'PERSISTENT' || source.retention === 'SESSION'
    ? source.retention
    : DEFAULT_PROJECT_CONTEXT_SETTINGS.retention;
  const autoCleanupDays = source.autoCleanupDays === 1
    || source.autoCleanupDays === 7
    || source.autoCleanupDays === 30
    ? source.autoCleanupDays
    : DEFAULT_PROJECT_CONTEXT_SETTINGS.autoCleanupDays;

  return {
    profile,
    retention,
    autoCleanupDays,
    confirmBeforeSendingCode: typeof source.confirmBeforeSendingCode === 'boolean'
      ? source.confirmBeforeSendingCode
      : DEFAULT_PROJECT_CONTEXT_SETTINGS.confirmBeforeSendingCode,
  };
};
