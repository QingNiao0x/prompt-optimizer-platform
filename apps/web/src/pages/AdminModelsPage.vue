<script setup lang="ts">
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { onMounted, reactive, ref } from 'vue';
import { formatModelVersion } from '@/features/models/modelVersion';

import { getApiErrorMessage } from '@/services/http';
import {
  createAdminModel,
  deleteAdminModel,
  listAdminModels,
  listModelRoutes,
  updateAdminModel,
} from '@/services/modelCatalogApi';
import type { AdminModel, AdminModelChange, ModelRoute } from '@/types/api';

const models = ref<AdminModel[]>([]);
const routes = ref<ModelRoute[]>([]);
const isLoading = ref(false);
const isSaving = ref(false);
const errorMessage = ref('');
const dialogVisible = ref(false);
const editingId = ref<string>();
const form = reactive<AdminModelChange>({
  routeKey: '', upstreamModel: '', displayName: '',
  enabled: true, defaultModel: false, sortOrder: 0,
});

const refresh = async (): Promise<void> => {
  isLoading.value = true;
  errorMessage.value = '';
  try {
    [models.value, routes.value] = await Promise.all([listAdminModels(), listModelRoutes()]);
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error);
  } finally {
    isLoading.value = false;
  }
};

const openCreate = (): void => {
  editingId.value = undefined;
  Object.assign(form, {
    routeKey: routes.value[0]?.key ?? '', upstreamModel: '', displayName: '',
    enabled: true, defaultModel: models.value.length === 0, sortOrder: models.value.length,
  });
  dialogVisible.value = true;
};

const openEdit = (model: AdminModel): void => {
  editingId.value = model.id;
  Object.assign(form, {
    routeKey: model.routeKey, upstreamModel: model.upstreamModel,
    displayName: model.displayName, enabled: model.enabled,
    defaultModel: model.defaultModel, sortOrder: model.sortOrder,
  });
  dialogVisible.value = true;
};

const save = async (): Promise<void> => {
  if (!form.routeKey || !form.upstreamModel.trim() || !form.displayName.trim()) {
    ElMessage.warning('请填写供应商路由、上游调用 ID 和模型版本。');
    return;
  }
  isSaving.value = true;
  try {
    const change = { ...form, upstreamModel: form.upstreamModel.trim(), displayName: form.displayName.trim() };
    if (editingId.value) {
      await updateAdminModel(editingId.value, change);
    } else {
      await createAdminModel(change);
    }
    dialogVisible.value = false;
    ElMessage.success('模型目录已更新。');
    await refresh();
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  } finally {
    isSaving.value = false;
  }
};

const setDefault = async (model: AdminModel): Promise<void> => {
  try {
    await updateAdminModel(model.id, {
      routeKey: model.routeKey,
      upstreamModel: model.upstreamModel,
      displayName: model.displayName,
      enabled: true,
      defaultModel: true,
      sortOrder: model.sortOrder,
    });
    ElMessage.success('默认模型已更新。');
    await refresh();
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  }
};

const remove = async (model: AdminModel): Promise<void> => {
  try {
    await ElMessageBox.confirm(
      `确认从用户可选列表移除“${model.displayName}”吗？历史调用记录不会删除。`,
      '移除模型',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await deleteAdminModel(model.id);
    ElMessage.success('模型已从可选列表移除。');
    await refresh();
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  }
};

onMounted(() => { void refresh(); });
</script>

<template>
  <section class="admin-models-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">PLATFORM ADMIN</p>
        <h1>用户可选模型</h1>
        <p>在已配置的供应商路由下发布模型。用户只能选择已启用的模型；端点与密钥不会显示在这里。</p>
      </div>
      <ElButton type="primary" :disabled="routes.length === 0" @click="openCreate">添加模型</ElButton>
    </div>

    <ElAlert v-if="errorMessage" type="error" :title="errorMessage" show-icon :closable="false" />
    <ElAlert
      v-if="routes.length === 0 && !isLoading"
      type="warning"
      title="没有可用供应商路由，请先检查服务端的模型配置。"
      show-icon
      :closable="false"
    />

    <div class="model-table-card">
      <ElTable v-if="models.length > 0" :data="models" v-loading="isLoading" row-key="id">
        <ElTableColumn label="模型版本" min-width="230">
          <template #default="{ row }">
            <div class="model-name"><strong>{{ formatModelVersion(row.displayName) }}</strong><small>调用 ID：{{ row.publicId }}</small></div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="routeKey" label="平台路由" min-width="120" />
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '可选择' : '已停用' }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="默认" width="90">
          <template #default="{ row }">{{ row.defaultModel ? '是' : '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" min-width="225" fixed="right">
          <template #default="{ row }">
            <ElButton link type="primary" @click="openEdit(row as AdminModel)">编辑</ElButton>
            <ElButton v-if="!row.defaultModel" link type="primary" @click="setDefault(row as AdminModel)">设为默认</ElButton>
            <ElButton v-if="!row.defaultModel" link type="danger" @click="remove(row as AdminModel)">移除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-else-if="!isLoading" description="尚无模型，请添加首个可用模型。" />
    </div>

    <ElDialog v-model="dialogVisible" :title="editingId ? '编辑模型' : '添加模型'" width="min(520px, 94vw)">
      <div class="model-form">
        <label>供应商路由
          <ElSelect v-model="form.routeKey" :disabled="Boolean(editingId)" aria-label="供应商路由">
            <ElOption v-for="route in routes" :key="route.key" :label="route.providerName" :value="route.key" />
          </ElSelect>
        </label>
        <label>上游调用 ID
          <ElInput v-model="form.upstreamModel" :disabled="Boolean(editingId)" maxlength="120" />
        </label>
        <label>模型版本
          <ElInput v-model="form.displayName" maxlength="120" placeholder="例如 DeepSeek-V4-Pro" />
        </label>
        <label>排序
          <ElInputNumber v-model="form.sortOrder" :min="0" :max="10000" />
        </label>
        <label class="inline-field">允许用户选择 <ElSwitch v-model="form.enabled" /></label>
        <label class="inline-field">设为默认模型 <ElSwitch v-model="form.defaultModel" /></label>
        <p class="form-note">模型版本用于用户页面展示，并保存到新调用的历史记录。上游调用 ID 与路由决定实际请求；修改版本名称不会切换上游模型。</p>
      </div>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="isSaving" @click="save">保存</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.admin-models-page { display: grid; gap: 22px; }
.page-heading { display: flex; justify-content: space-between; align-items: end; gap: 20px; }
.page-heading h1 { margin: 6px 0; color: var(--text-primary); }
.page-heading p { margin: 0; color: var(--text-secondary); line-height: 1.6; }
.page-heading .eyebrow { color: var(--accent); font: 600 12px var(--font-mono); letter-spacing: .12em; }
.model-table-card { overflow: auto; min-height: 180px; border: 1px solid var(--glass-border-subtle); border-radius: 16px; background: var(--glass-bg); }
.model-name { display: grid; gap: 4px; }
.model-name small { color: var(--text-muted); font-family: var(--font-mono); overflow-wrap: anywhere; }
.model-form { display: grid; gap: 16px; }
.model-form label { display: grid; gap: 7px; color: var(--text-secondary); font-size: 14px; }
.model-form .inline-field { display: flex; align-items: center; justify-content: space-between; }
.form-note { margin: 0; color: var(--text-muted); font-size: 12px; }
@media (max-width: 640px) { .page-heading { align-items: start; flex-direction: column; } }
</style>
