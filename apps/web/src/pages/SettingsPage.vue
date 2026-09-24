<script setup lang="ts">
import {
  ElAlert,
  ElButton,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElProgress,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTag,
} from 'element-plus';
import { storeToRefs } from 'pinia';
import { computed, onMounted } from 'vue';

import { useProjectContextSettingsStore } from '@/stores/projectContextSettings';

const projectContextSettingsStore = useProjectContextSettingsStore();
const {
  settings: contextSettings,
  storageStatus,
  storageError,
  isClearingIndexes,
  activeProfile,
} = storeToRefs(projectContextSettingsStore);
const contextProfiles = projectContextSettingsStore.profiles;

const storagePercentage = computed(() => {
  if (!storageStatus.value.quota) {
    return 0;
  }
  return Math.min(100, Math.round(((storageStatus.value.usage ?? 0) / storageStatus.value.quota) * 100));
});

const clearLocalIndexes = async (): Promise<void> => {
  try {
    await ElMessageBox.confirm(
      '确认清除本浏览器保存的全部项目源码索引吗？该操作不会删除电脑中的源文件。',
      '清除本地项目索引',
      { type: 'warning', confirmButtonText: '清除索引', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    await projectContextSettingsStore.clearLocalIndexes();
    ElMessage.success('本地项目索引已清除。');
  } catch {
    ElMessage.error('清除本地项目索引失败，请关闭其他项目页面后重试。');
  }
};

const formatBytes = (value?: number): string => {
  if (value === undefined) {
    return '未知';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let amount = value;
  let unitIndex = 0;
  while (amount >= 1024 && unitIndex < units.length - 1) {
    amount /= 1024;
    unitIndex += 1;
  }
  return `${amount >= 10 || unitIndex === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[unitIndex]}`;
};

const formatDate = (value: string): string => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
};

onMounted(() => {
  void projectContextSettingsStore.refreshStorageStatus();
});
</script>

<template>
  <section class="settings-page">
    <header class="page-heading">
      <div>
        <span class="eyebrow">Settings</span>
        <h1>设置</h1>
        <p>管理本地项目索引、上下文处理方式和隐私选项。</p>
      </div>
    </header>

    <section class="settings-card context-settings-card" aria-labelledby="context-settings-title">
      <div class="section-heading">
        <div>
          <span class="eyebrow">Project context</span>
          <h2 id="context-settings-title">项目上下文与隐私</h2>
          <p>完整源码索引保留在当前浏览器，本次任务只取回相关代码片段。</p>
        </div>
        <ElButton
          type="danger"
          plain
          :loading="isClearingIndexes"
          @click="clearLocalIndexes"
        >
          清除本地索引
        </ElButton>
      </div>

      <div class="setting-block">
        <span class="setting-label">运行模式</span>
        <ElRadioGroup v-model="contextSettings.profile" class="profile-selector">
          <ElRadioButton
            v-for="profile in contextProfiles"
            :key="profile.code"
            :value="profile.code"
            :disabled="profile.availability !== 'AVAILABLE'"
          >
            {{ profile.label }}
          </ElRadioButton>
        </ElRadioGroup>
        <div class="profile-notes">
          <div v-for="profile in contextProfiles" :key="`${profile.code}-note`">
            <ElTag
              size="small"
              effect="plain"
              :type="profile.availability === 'AVAILABLE' ? 'success' : 'info'"
            >
              {{ profile.availabilityLabel }}
            </ElTag>
            <span>{{ profile.description }}</span>
          </div>
        </div>
      </div>

      <div class="limit-grid" aria-label="当前项目上下文限制">
        <div>
          <span>扫描文件上限</span>
          <strong>{{ activeProfile.limits.maxScanFiles.toLocaleString('zh-CN') }}</strong>
        </div>
        <div>
          <span>本地索引上限</span>
          <strong>{{ formatBytes(activeProfile.limits.maxLocalIndexBytes) }}</strong>
        </div>
        <div>
          <span>单文件索引上限</span>
          <strong>{{ formatBytes(activeProfile.limits.maxIndexableFileBytes) }}</strong>
        </div>
        <div>
          <span>单次模型上下文</span>
          <strong>{{ activeProfile.limits.maxContextCharacters.toLocaleString('zh-CN') }} 字符</strong>
        </div>
      </div>

      <div class="privacy-grid">
        <label>
          <span class="setting-label">索引保留方式</span>
          <ElSelect v-model="contextSettings.retention">
            <ElOption label="临时保留（不申请持久存储）" value="SESSION" />
            <ElOption label="在本机持久保留" value="PERSISTENT" />
          </ElSelect>
        </label>
        <label>
          <span class="setting-label">自动清理时间</span>
          <ElSelect v-model="contextSettings.autoCleanupDays">
            <ElOption label="1 天" :value="1" />
            <ElOption label="7 天" :value="7" />
            <ElOption label="30 天" :value="30" />
          </ElSelect>
        </label>
        <div class="switch-setting">
          <div>
            <span class="setting-label">发送代码前确认</span>
            <small>分析或增强前，显示将发送的片段数量与字符数。</small>
          </div>
          <ElSwitch v-model="contextSettings.confirmBeforeSendingCode" />
        </div>
      </div>

      <div class="storage-status">
        <div>
          <span class="setting-label">浏览器站点存储</span>
          <small v-if="storageStatus.supported">
            已用 {{ formatBytes(storageStatus.usage) }} / 配额 {{ formatBytes(storageStatus.quota) }}
            · {{ storageStatus.persisted ? '已获持久存储保护' : '可能由浏览器自动回收' }}
          </small>
          <small v-else>当前浏览器不提供容量估算，建立索引时仍会处理配额不足错误。</small>
        </div>
        <ElProgress
          v-if="storageStatus.supported"
          :percentage="storagePercentage"
          :stroke-width="8"
        />
      </div>
      <ElAlert v-if="storageError" type="warning" :closable="false" :title="storageError" show-icon />
    </section>

  </section>
</template>

<style scoped>
.settings-page {
  max-width: 1180px;
  margin: 0 auto;
}

.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.eyebrow {
  color: var(--accent-blue);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.8px;
  text-transform: uppercase;
}

h1 {
  margin: 10px 0 6px;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: clamp(30px, 4vw, 44px);
  letter-spacing: -0.05em;
}

.page-heading p {
  margin: 0;
  color: var(--ink-muted);
  font-size: 14px;
}

.security-note {
  margin-bottom: 16px;
}

.settings-card {
  padding: 0;
  border: 1px solid var(--line-subtle);
  border-radius: var(--radius-large);
  background: var(--surface-panel);
  box-shadow: var(--shadow-panel);
}

.context-settings-card {
  display: grid;
  gap: 22px;
  margin-bottom: 18px;
  padding: 22px;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.section-heading h2 {
  margin: 7px 0 5px;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: 21px;
}

.section-heading p {
  margin: 0;
  color: var(--ink-muted);
  font-size: 13px;
}

.setting-block,
.storage-status {
  display: grid;
  gap: 10px;
}

.setting-label {
  display: block;
  margin-bottom: 7px;
  color: var(--ink-strong);
  font-size: 13px;
  font-weight: 600;
}

.profile-selector {
  display: flex;
  flex-wrap: wrap;
}

.profile-notes {
  display: grid;
  gap: 7px;
}

.profile-notes > div {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.6;
}

.limit-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--line-subtle);
  border-radius: 10px;
  background: var(--line-subtle);
}

.limit-grid > div {
  display: grid;
  gap: 5px;
  padding: 13px;
  background: var(--surface-elevated);
}

.limit-grid span,
.storage-status small,
.switch-setting small {
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.6;
}

.limit-grid strong {
  color: var(--ink-strong);
  font-family: var(--font-mono);
  font-size: 13px;
}

.privacy-grid {
  display: grid;
  grid-template-columns: 180px 160px minmax(240px, 1fr);
  align-items: end;
  gap: 14px;
}

.switch-setting {
  display: flex;
  min-height: 56px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 9px 12px;
  border: 1px solid var(--line-subtle);
  border-radius: 8px;
  background: var(--surface-elevated);
}

.switch-setting .setting-label {
  margin-bottom: 2px;
}

.storage-status {
  grid-template-columns: minmax(220px, 1fr) minmax(220px, 0.8fr);
  align-items: center;
}

.storage-status .setting-label {
  margin-bottom: 2px;
}

@media (max-width: 860px) {
  .limit-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .privacy-grid,
  .storage-status {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .page-heading,
  .section-heading {
    display: grid;
  }

  .page-heading :deep(.el-button),
  .section-heading :deep(.el-button) {
    width: 100%;
  }

  .limit-grid {
    grid-template-columns: 1fr;
  }

  .profile-notes > div {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
