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
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  type FormInstance,
  type FormRules,
} from 'element-plus';
import { onMounted, reactive, ref } from 'vue';

import { getApiErrorMessage } from '@/services/http';
import {
  createProviderConfig,
  deleteProviderConfig,
  listProviderConfigs,
  updateProviderConfig,
} from '@/services/providerConfigApi';
import type { ProviderConfigSummary, ProviderType } from '@/types/api';

const loading = ref(false);
const saving = ref(false);
const configs = ref<ProviderConfigSummary[]>([]);

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

onMounted(loadConfigs);
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

    <div class="settings-card">
      <ElTable v-loading="loading" :data="configs" row-key="id">
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
        <template #empty>
          <ElEmpty description="还没有模型配置，点击右上角新增" />
        </template>
      </ElTable>
    </div>

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
  padding: 8px;
  border: 1px solid var(--line-subtle);
  border-radius: var(--radius-large);
  background: var(--surface-panel);
  box-shadow: var(--shadow-panel);
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
</style>
