<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElDialog, ElMessage, ElOption, ElSelect, ElTag } from 'element-plus';
import type { AdminModel } from '@/types/api';
import { textPolishApi } from '@/services/textPolishApi';
import { getApiErrorMessage } from '@/services/http';
import { formatModelVersion } from '@/features/models/modelVersion';

const props = defineProps<{ visible: boolean; models: AdminModel[] }>();
const emit = defineEmits<{ close: [] }>();
const ids = ref<string[]>([]), defaultId = ref<string | null>(null), error = ref(''), loading = ref(false), saving = ref(false);
let epoch = 0;
watch(() => props.visible, async visible => {
  const request = ++epoch; if (!visible) return;
  loading.value = true; error.value = '';
  try { const policy = await textPolishApi.policy(); if (request === epoch) { ids.value = policy.enabledModelIds.filter(id => props.models.some(m => m.publicId === id)); defaultId.value = policy.defaultModelId; } }
  catch (failure: unknown) { if (request === epoch) error.value = getApiErrorMessage(failure); }
  finally { if (request === epoch) loading.value = false; }
});
const save = async (): Promise<void> => {
  saving.value = true; error.value = '';
  try { await textPolishApi.savePolicy({ enabledModelIds: ids.value, defaultModelId: ids.value.length ? defaultId.value : null }); ElMessage.success('文章润色模型设置已保存。'); emit('close'); }
  catch (failure: unknown) { error.value = getApiErrorMessage(failure); }
  finally { saving.value = false; }
};
</script>
<template>
  <ElDialog :model-value="visible" title="文章祛AI味 · 模型设置" width="min(620px, calc(100vw - 24px))" @update:model-value="value => { if (!value) emit('close'); }">
    <p class="hint">从现有模型目录选择本模块可用项及默认模型。全局停用、移除仍生效；新添加的模型需在这里加入。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <div v-loading="loading" class="settings-form">
      <ElCheckboxGroup v-model="ids" aria-label="文章润色可用模型"><ElCheckbox v-for="model in models" :key="model.publicId" :value="model.publicId">{{ formatModelVersion(model.displayName) }} <ElTag v-if="!model.enabled" type="info" size="small">全局停用</ElTag></ElCheckbox></ElCheckboxGroup>
      <label><span>文章润色默认模型</span><ElSelect v-model="defaultId" aria-label="文章润色默认模型" placeholder="选择默认模型" :disabled="!ids.length"><ElOption v-for="model in models.filter(m => ids.includes(m.publicId) && m.enabled)" :key="model.publicId" :label="formatModelVersion(model.displayName)" :value="model.publicId" /></ElSelect></label>
      <p class="hint">取消所有选项后，本模块将不提供模型调用。</p>
    </div>
    <template #footer><ElButton @click="emit('close')">取消</ElButton><ElButton type="primary" :loading="saving" :disabled="loading || (!!ids.length && !ids.includes(defaultId ?? ''))" @click="save">保存模型设置</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.settings-form { display: grid; gap: 16px; margin-top: 18px; } .settings-form :deep(.el-checkbox-group) { display: grid; gap: 6px; } .settings-form :deep(.el-checkbox) { height: auto; min-height: 32px; white-space: normal; } .settings-form :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; }
label { display: grid; gap: 8px; font-size: 13px; color: var(--ink-strong); } .hint { color: var(--ink-muted); font-size: 13px; line-height: 1.7; }
</style>
