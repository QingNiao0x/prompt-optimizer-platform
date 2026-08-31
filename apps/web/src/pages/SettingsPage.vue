<script setup lang="ts">
import { Delete, EditPen, Plus } from '@element-plus/icons-vue';
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElProgress,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  type FormInstance,
  type FormRules,
} from 'element-plus';
import { storeToRefs } from 'pinia';
import { computed, onMounted, reactive, ref } from 'vue';

import { getApiErrorMessage } from '@/services/http';
import {
  createProviderConfig,
  deleteProviderConfig,
  listProviderConfigs,
  updateProviderConfig,
} from '@/services/providerConfigApi';
import { useProjectContextSettingsStore } from '@/stores/projectContextSettings';
import type { ProviderConfigSummary, ProviderType } from '@/types/api';

const loading = ref(false);
const saving = ref(false);
const configs = ref<ProviderConfigSummary[]>([]);

const projectContextSettingsStore = useProjectContextSettingsStore();
const {
  settings: contextSettings,
  storageStatus,
  storageError,
  isClearingIndexes,
  activeProfile,
} = storeToRefs(projectContextSettingsStore);
const contextProfiles = projectContextSettingsStore.profiles;

const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const formRef = ref<FormInstance>();

const form = reactive({
  providerType: 'DEEPSEEK' as ProviderType,
  displayName: '',
  endpointUrl: '',
  modelName: '',
  apiKey: '',
  parametersText: '',
  enabled: true,
});

const rules: FormRules = {
  providerType: [{ required: true, message: '请选择供应商类型', trigger: 'change' }],
  displayName: [{ required: true, message: '请填写显示名称', trigger: 'blur' }],
  endpointUrl: [{ required: true, message: '请填写模型端点', trigger: 'blur' }],
  modelName: [{ required: true, message: '请填写模型名称', trigger: 'blur' }],
  apiKey: [
    {
      validator: (_rule, value, callback) => {
        if (!editingId.value && !String(value ?? '').trim()) {
          callback(new Error('新增配置时必须填写 API Key'));
          return;
        }
        callback();
      },
      trigger: 'blur',
    },
  ],
};

const storagePercentage = computed(() => {
  if (!storageStatus.value.quota) {
    return 0;
  }
  return Math.min(100, Math.round(((storageStatus.value.usage ?? 0) / storageStatus.value.quota) * 100));
});

const loadConfigs = async (): Promise<void> => {
  loading.value = true;
  try {
    const response = await listProviderConfigs();
    configs.value = response.data;
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  } finally {
    loading.value = false;
  }
};

const openCreate = (): void => {
  editingId.value = null;
  Object.assign(form, {
    providerType: 'DEEPSEEK',
    displayName: '',
    endpointUrl: 'https://api.deepseek.com/chat/completions',
    modelName: 'deepseek-chat',
    apiKey: '',
    parametersText: '',
    enabled: true,
  });
  dialogVisible.value = true;
};

const openEdit = (value: unknown): void => {
  const config = value as ProviderConfigSummary;
  editingId.value = config.id;
  Object.assign(form, {
    providerType: config.providerType,
    displayName: config.displayName,
    endpointUrl: config.endpointUrl,
    modelName: config.modelName,
    apiKey: '',
    parametersText: JSON.stringify(config.parameters ?? {}, null, 2),
    enabled: config.enabled,
  });
  dialogVisible.value = true;
};

const parseParameters = (): Record<string, unknown> | null => {
  if (!form.parametersText.trim()) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(form.parametersText);
    if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
      throw new Error('参数必须是 JSON 对象');
    }
    return parsed as Record<string, unknown>;
  } catch {
    ElMessage.error('模型参数必须是合法的 JSON 对象。');
    return null;
  }
};

const save = async (): Promise<void> => {
  if (!formRef.value) {
    return;
  }
  try {
    await formRef.value.validate();
  } catch {
    return;
  }

  const parameters = parseParameters();
  if (parameters === null) {
    return;
  }

  saving.value = true;
  try {
    if (editingId.value) {
      await updateProviderConfig(editingId.value, {
        displayName: form.displayName.trim(),
        endpointUrl: form.endpointUrl.trim(),
        modelName: form.modelName.trim(),
        apiKey: form.apiKey.trim() || undefined,
        parameters,
        enabled: form.enabled,
      });
      ElMessage.success('模型配置已更新。');
    } else {
      await createProviderConfig({
        providerType: form.providerType,
        displayName: form.displayName.trim(),
        endpointUrl: form.endpointUrl.trim(),
        modelName: form.modelName.trim(),
        apiKey: form.apiKey.trim(),
        parameters,
        enabled: form.enabled,
      });
      ElMessage.success('模型配置已保存，API Key 已加密存储。');
    }
    dialogVisible.value = false;
    await loadConfigs();
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  } finally {
    saving.value = false;
  }
};

const remove = async (value: unknown): Promise<void> => {
  const config = value as ProviderConfigSummary;
  try {
    await ElMessageBox.confirm(
      `确认删除“${config.displayName}”吗？删除后需要重新配置才能继续使用。`,
      '删除模型配置',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    await deleteProviderConfig(config.id);
    ElMessage.success('模型配置已删除。');
    await loadConfigs();
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  }
};

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
  void loadConfigs();
  void projectContextSettingsStore.refreshStorageStatus();
});
</script>

<template>
  <section class="settings-page">
    <header class="page-heading">
      <div>
        <span class="eyebrow">Settings</span>
        <h1>模型设置</h1>
        <p>管理模型供应商、端点和 API Key。密钥以 AES-256-GCM 加密保存，接口只返回末四位。</p>
      </div>
      <ElButton type="primary" :icon="Plus" @click="openCreate">新增配置</ElButton>
    </header>

    <ElAlert
      class="security-note"
      type="info"
      show-icon
      :closable="false"
      title="API Key 只允许写入，不允许读取明文。保存配置前请确认服务端已配置加密主密钥。"
    />

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

    <section class="settings-card provider-settings-card" aria-label="模型供应商配置">
      <ElTable v-if="loading || configs.length" v-loading="loading" :data="configs" row-key="id">
        <ElTableColumn label="显示名称" min-width="140">
          <template #default="{ row }">{{ row.displayName }}</template>
        </ElTableColumn>
        <ElTableColumn label="类型" width="110">
          <template #default="{ row }">
            <ElTag size="small" effect="plain">{{ row.providerType }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="模型" width="150">
          <template #default="{ row }">{{ row.modelName }}</template>
        </ElTableColumn>
        <ElTableColumn label="端点" min-width="220">
          <template #default="{ row }">
            <span class="endpoint-cell">{{ row.endpointUrl }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="Key" width="110">
          <template #default="{ row }">
            {{ row.apiKeyLast4 ? `•••• ${row.apiKeyLast4}` : '—' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="启用" width="80">
          <template #default="{ row }">
            <ElSwitch :model-value="row.enabled" disabled />
          </template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="130">
          <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="140" align="right">
          <template #default="{ row }">
            <ElButton text size="small" :icon="EditPen" @click="openEdit(row)">编辑</ElButton>
            <ElButton text size="small" type="danger" :icon="Delete" @click="remove(row)">
              删除
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-else description="还没有模型配置，点击右上角新增" />
    </section>

    <ElDialog
      v-model="dialogVisible"
      :title="editingId ? '编辑模型配置' : '新增模型配置'"
      width="min(560px, 92vw)"
    >
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
        <ElFormItem label="供应商类型" prop="providerType">
          <ElSelect v-model="form.providerType" class="full-width">
            <ElOption label="DeepSeek" value="DEEPSEEK" />
            <ElOption label="OpenAI" value="OPENAI" />
            <ElOption label="Anthropic" value="ANTHROPIC" />
            <ElOption label="自定义兼容端点" value="CUSTOM" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="显示名称" prop="displayName">
          <ElInput v-model="form.displayName" maxlength="80" placeholder="例如：DeepSeek 主配置" />
        </ElFormItem>
        <ElFormItem label="模型端点" prop="endpointUrl">
          <ElInput
            v-model="form.endpointUrl"
            maxlength="500"
            placeholder="https://api.deepseek.com/chat/completions"
          />
        </ElFormItem>
        <ElFormItem label="模型名称" prop="modelName">
          <ElInput v-model="form.modelName" maxlength="120" placeholder="deepseek-chat" />
        </ElFormItem>
        <ElFormItem label="API Key" prop="apiKey">
          <ElInput
            v-model="form.apiKey"
            type="password"
            show-password
            maxlength="2000"
            :placeholder="editingId ? '留空表示不修改' : 'sk-…'"
          />
        </ElFormItem>
        <ElFormItem label="模型参数（JSON，可选）">
          <ElInput
            v-model="form.parametersText"
            type="textarea"
            :rows="4"
            placeholder='例如：{"temperature": 0.2, "maxTokens": 3000}'
          />
        </ElFormItem>
        <ElFormItem label="启用">
          <ElSwitch v-model="form.enabled" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="save">保存</ElButton>
      </template>
    </ElDialog>
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
  font-size: 10px;
  letter-spacing: 0.14em;
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
  font-size: 13px;
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
  font-size: 12px;
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
  font-size: 12px;
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
  font-size: 11px;
  line-height: 1.5;
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
  font-size: 10px;
  line-height: 1.5;
}

.limit-grid strong {
  color: var(--ink-strong);
  font-family: var(--font-mono);
  font-size: 12px;
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

.provider-settings-card {
  overflow-x: auto;
}

.provider-settings-card :deep(.el-table__header-wrapper th) {
  height: 46px;
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.provider-settings-card :deep(.el-table__body-wrapper td) {
  height: 56px;
}

.provider-settings-card :deep(.el-table) {
  min-width: 920px;
}

.endpoint-cell {
  display: block;
  overflow: hidden;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.full-width {
  width: 100%;
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
  .section-heading {
    display: grid;
  }

  .limit-grid {
    grid-template-columns: 1fr;
  }
}
</style>
