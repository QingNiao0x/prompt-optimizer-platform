import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';

import {
  loadProjectContextSettings,
  PROJECT_CONTEXT_PROFILES,
  readBrowserStorageStatus,
  resolveEffectiveProjectIndexLimits,
  resolveProjectContextProfile,
  saveProjectContextSettings,
  type BrowserStorageStatus,
} from '@/features/project-context-config/projectContextConfig';
import { projectIndexRepository } from '@/features/project-index/indexedDbProjectIndexRepository';

export const useProjectContextSettingsStore = defineStore('project-context-settings', () => {
  const settings = ref(loadProjectContextSettings());
  const storageStatus = ref<BrowserStorageStatus>({
    supported: false,
    persisted: false,
  });
  const storageError = ref('');
  const isClearingIndexes = ref(false);

  const activeProfile = computed(() => resolveProjectContextProfile(settings.value.profile));
  const effectiveIndexLimits = computed(() => resolveEffectiveProjectIndexLimits(
    activeProfile.value,
    storageStatus.value,
  ));

  watch(
    settings,
    (value) => {
      try {
        saveProjectContextSettings(value);
        storageError.value = '';
      } catch {
        storageError.value = '浏览器未能保存项目上下文设置，请检查站点存储权限。';
      }
    },
    { deep: true },
  );

  const refreshStorageStatus = async (): Promise<void> => {
    try {
      storageStatus.value = await readBrowserStorageStatus();
      storageError.value = '';
    } catch {
      storageError.value = '无法读取浏览器本地存储容量。';
    }
  };

  const clearLocalIndexes = async (): Promise<void> => {
    isClearingIndexes.value = true;
    try {
      await projectIndexRepository.deleteAllProjects();
      await refreshStorageStatus();
    } finally {
      isClearingIndexes.value = false;
    }
  };

  return {
    profiles: PROJECT_CONTEXT_PROFILES,
    settings,
    storageStatus,
    storageError,
    isClearingIndexes,
    activeProfile,
    effectiveIndexLimits,
    refreshStorageStatus,
    clearLocalIndexes,
  };
});
