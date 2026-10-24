<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { ElAlert, ElButton, ElDialog, ElInput, ElInputNumber, ElMessage, ElSwitch } from 'element-plus';
import { imagePromptApi } from '@/services/imagePromptApi';
import { getApiErrorMessage } from '@/services/http';
import type { AdminModel } from '@/types/api';
import type { ImagePromptModel } from '@/types/imagePrompt';

const props = defineProps<{ model?: AdminModel }>();
const emit = defineEmits<{ close: [] }>();
const draft = ref<ImagePromptModel>();
const loading = ref(false);
const saving = ref(false);
const error = ref('');
let loadVersion = 0;

/** 对应模型行按需读取能力；切换或关闭后，迟到响应不能写进另一模型的编辑稿。 */
watch(() => props.model?.publicId, async id => {
  const version = ++loadVersion;
  draft.value = undefined; error.value = ''; loading.value = Boolean(id);
  if (!id) return;
  try {
    const models = await imagePromptApi.adminModels();
    if (version !== loadVersion) return;
    const model = models.find(item => item.id === id);
    if (!model) { error.value = '模型已移除，请刷新模型目录后重试。'; return; }
    draft.value = { ...model, maxImages: Math.max(1, model.maxImages) };
  } catch (failure: unknown) { if (version === loadVersion) error.value = getApiErrorMessage(failure); }
  finally { if (version === loadVersion) loading.value = false; }
}, { immediate: true });
onBeforeUnmount(() => { loadVersion += 1; });

/** 只更新同一目录项的图片准入，保留其全局启停、默认模型和排序配置。 */
const save = async (): Promise<void> => {
  const model = draft.value, version = loadVersion;
  if (!model || saving.value) return;
  if (!model.evidence.trim()) { ElMessage.warning('请填写官方能力依据或受控验证记录。'); return; }
  saving.value = true;
  try {
    await imagePromptApi.configureModel(model.id, model.vision, model.maxImages, model.evidence.trim());
    if (version === loadVersion) { ElMessage.success('模型图像能力已保存。'); emit('close'); }
  } catch (failure: unknown) { if (version === loadVersion) error.value = getApiErrorMessage(failure); }
  finally { saving.value = false; }
};
</script>

<template>
  <ElDialog :model-value="Boolean(model)" :title="`图像输入能力：${model?.displayName ?? ''}`" width="min(560px, 94vw)"
    :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" @update:model-value="!$event && !saving && emit('close')">
    <div v-loading="loading" class="vision-form">
      <p>提示词增强与图像转提示词共用此模型。请依据官方文档或受控验证登记图片能力。</p>
      <ElAlert v-if="!model?.enabled" title="该模型当前已停用。可先配置图像能力，启用后再供两个工作台选择。" type="info" :closable="false" />
      <ElAlert v-if="error" :title="error" type="error" :closable="false" />
      <template v-if="draft && !draft.mock">
        <label class="inline-field">允许图片输入 <ElSwitch v-model="draft.vision" aria-label="允许图片输入" :disabled="saving" /></label>
        <label>最多图片数 <ElInputNumber v-model="draft.maxImages" :min="1" :max="3" aria-label="最多图片数" :disabled="saving" /></label>
        <label>能力依据 <ElInput v-model="draft.evidence" maxlength="400" aria-label="视觉能力依据" placeholder="官方文档地址或已完成的真实验证记录" :disabled="saving" /></label>
      </template>
      <p v-else-if="draft?.mock">演示模型仅验证流程，不代表真实视觉能力。</p>
    </div>
    <template #footer><ElButton :disabled="saving" @click="emit('close')">取消</ElButton><ElButton v-if="!draft?.mock" type="primary" :disabled="!draft || loading" :loading="saving" @click="save">保存图像能力</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.vision-form { display: grid; gap: 16px; min-height: 100px; }
.vision-form p { margin: 0; color: var(--text-secondary); line-height: 1.7; }
.vision-form label { display: grid; gap: 7px; color: var(--text-secondary); font-size: 14px; }
.vision-form .inline-field { display: flex; align-items: center; justify-content: space-between; }
</style>
