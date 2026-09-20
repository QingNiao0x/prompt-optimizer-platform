import { ref } from 'vue';

export interface PlanModePreference {
  enabled: boolean;
  introSeen: boolean;
}

export const PLAN_MODE_STORAGE_KEY = 'prompt-optimizer.plan-mode.v1';

/** 首次访问默认关闭，避免在用户尚未了解前强制进入提问流程。 */
export const DEFAULT_PLAN_MODE_PREFERENCE: PlanModePreference = {
  enabled: false,
  introSeen: false,
};

const enabled = ref(DEFAULT_PLAN_MODE_PREFERENCE.enabled);
const introSeen = ref(DEFAULT_PLAN_MODE_PREFERENCE.introSeen);
let initialized = false;

export const parsePlanModePreference = (raw: string | null): PlanModePreference => {
  if (!raw) {
    return { ...DEFAULT_PLAN_MODE_PREFERENCE };
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') {
      return { ...DEFAULT_PLAN_MODE_PREFERENCE };
    }
    const record = parsed as Partial<PlanModePreference>;
    return {
      enabled: record.enabled === true,
      introSeen: record.introSeen === true,
    };
  } catch {
    return { ...DEFAULT_PLAN_MODE_PREFERENCE };
  }
};

const readStoredPreference = (): PlanModePreference => {
  if (typeof window === 'undefined') {
    return { ...DEFAULT_PLAN_MODE_PREFERENCE };
  }
  try {
    return parsePlanModePreference(window.localStorage.getItem(PLAN_MODE_STORAGE_KEY));
  } catch {
    return { ...DEFAULT_PLAN_MODE_PREFERENCE };
  }
};

const persistPreference = (): void => {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage.setItem(PLAN_MODE_STORAGE_KEY, JSON.stringify({
      enabled: enabled.value,
      introSeen: introSeen.value,
    } satisfies PlanModePreference));
  } catch {
    // 隐私模式无法写入时，仍保留当前页面内的选择。
  }
};

const applyPreference = (preference: PlanModePreference): void => {
  enabled.value = preference.enabled;
  introSeen.value = preference.introSeen;
};

const initializePreference = (): void => {
  if (initialized) {
    return;
  }
  initialized = true;
  applyPreference(readStoredPreference());
};

export const usePlanModePreference = () => {
  initializePreference();

  const setEnabled = (value: boolean): void => {
    enabled.value = value;
    persistPreference();
  };

  const acceptIntro = (): void => {
    enabled.value = true;
    introSeen.value = true;
    persistPreference();
  };

  const dismissIntro = (): void => {
    enabled.value = false;
    introSeen.value = true;
    persistPreference();
  };

  return {
    enabled,
    introSeen,
    setEnabled,
    acceptIntro,
    dismissIntro,
  };
};
