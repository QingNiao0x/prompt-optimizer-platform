<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElDialog, ElInputNumber, ElOption, ElSelect, ElTag } from 'element-plus';
import { useContextVisionStore } from '@/stores/contextVision';
import { getContextPagePreview } from '@/services/documentUploadApi';
import { getApiErrorMessage } from '@/services/http';
import { formatModelVersion } from '@/features/models/modelVersion';
import type { ContextVisionMask } from '@/types/api';

const store = useContextVisionStore();
const selected = ref<number[]>([]);
const page = ref(1);
const consent = ref(false);
const masks = ref<Record<number, ContextVisionMask[]>>({});
const preview = ref('');
const previewLoading = ref(false);
const previewError = ref('');
const surface = ref<SVGSVGElement>();
const dragStart = ref<{ x: number; y: number }>();
const dragEnd = ref<{ x: number; y: number }>();
const exact = ref({ x: 0, y: 0, width: 25, height: 10 });
let previewSequence = 0;
let previewController: AbortController | undefined;
const review = computed(() => store.status?.visionReview);
const currentMasks = computed(() => masks.value[page.value] ?? []);
const selectedModel = computed(() => store.models.find(model => model.id === store.modelId));
const maxPages = computed(() => Math.min(review.value?.maxPages ?? 0, review.value?.maxCalls ?? 0));
const canConfirm = computed(() => consent.value && selected.value.length > 0 && selected.value.length <= maxPages.value
  && selectedModel.value?.vision && !store.loadingModels && !previewLoading.value && !previewError.value);

const clearPreview = (): void => { if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ''; };
/** 预览只以临时 Blob URL 留在内存中，页码或身份变化后撤销迟到响应及旧 URL。 */
const loadPreview = async (): Promise<void> => {
  const documentId = store.status?.documentId;
  const sequence = ++previewSequence;
  previewController?.abort(); clearPreview(); previewError.value = '';
  if (!documentId) { previewLoading.value = false; return; }
  previewController = new AbortController();
  previewLoading.value = true;
  try {
    const blob = await getContextPagePreview(documentId, page.value, previewController.signal);
    if (sequence === previewSequence) preview.value = URL.createObjectURL(blob);
  } catch (failure: unknown) { if (sequence === previewSequence) previewError.value = getApiErrorMessage(failure); }
  finally { if (sequence === previewSequence) previewLoading.value = false; }
};
watch(() => store.status?.documentId, () => {
  selected.value = review.value?.pages.filter(item => item.suggested && !item.blocked).slice(0, maxPages.value).map(item => item.page) ?? [];
  page.value = selected.value[0] ?? 1;
  masks.value = {}; consent.value = false; dragStart.value = undefined; dragEnd.value = undefined;
});
watch([() => store.status?.documentId, page], () => { void loadPreview(); }, { flush: 'post' });
// 更换接收模型、页码集合或遮挡内容后重新确认；旧勾选不授权新的数据范围。
watch([selected, masks, () => store.modelId], () => { consent.value = false; }, { deep: true });

const rectangle = (start: { x: number; y: number }, end: { x: number; y: number }): ContextVisionMask => ({
  x: Math.min(start.x, end.x), y: Math.min(start.y, end.y), width: Math.abs(end.x - start.x), height: Math.abs(end.y - start.y),
});
const pendingMask = computed(() => dragStart.value && dragEnd.value ? rectangle(dragStart.value, dragEnd.value) : undefined);
const point = (event: PointerEvent): { x: number; y: number } => {
  const bounds = surface.value!.getBoundingClientRect();
  return { x: Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width)), y: Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height)) };
};
const startMask = (event: PointerEvent): void => {
  if (!surface.value || !preview.value || event.button !== 0) return;
  event.preventDefault(); surface.value.setPointerCapture(event.pointerId);
  dragStart.value = point(event); dragEnd.value = point(event);
};
const moveMask = (event: PointerEvent): void => { if (dragStart.value) dragEnd.value = point(event); };
const addMask = (mask: ContextVisionMask): void => {
  if (mask.width <= 0 || mask.height <= 0 || mask.x + mask.width > 1 || mask.y + mask.height > 1 || currentMasks.value.length >= 30) return;
  masks.value = { ...masks.value, [page.value]: [...currentMasks.value, mask] };
};
const finishMask = (): void => {
  if (pendingMask.value && pendingMask.value.width > 0.002 && pendingMask.value.height > 0.002) addMask(pendingMask.value);
  dragStart.value = undefined; dragEnd.value = undefined;
};
const addExactMask = (): void => addMask({ x: exact.value.x / 100, y: exact.value.y / 100,
  width: Math.min(exact.value.width / 100, 1 - exact.value.x / 100), height: Math.min(exact.value.height / 100, 1 - exact.value.y / 100) });
const confirm = (): void => {
  if (!canConfirm.value) return;
  store.confirm({ modelId: store.modelId, consent: true, pages: [...selected.value].sort((a, b) => a - b)
    .map(number => ({ page: number, masks: (masks.value[number] ?? []).map(mask => ({ ...mask })) })) });
};
onBeforeUnmount(() => { previewSequence++; previewController?.abort(); clearPreview(); });
</script>

<template>
  <ElDialog :model-value="store.visible" title="确认图片与 PDF 识别范围" width="min(1040px, calc(100vw - 24px))"
    :close-on-click-modal="false" @update:model-value="value => { if (!value) store.cancel(); }">
    <template v-if="store.status && review">
      <p class="file-name">{{ store.status.path }} · 共 {{ review.pages.length }} 页</p>
      <ElAlert title="文件已上传到本平台，尚未发送给视觉模型。请检查预览、选择页码并遮挡敏感区域。" type="info" :closable="false" />
      <p class="review-hint">仅向所选模型发送勾选页的处理后图片，按实际用量计费。本次最多 {{ maxPages }} 页；其余可读文字层仍在本地提取，未识别页面会明确标注。</p>
      <ElAlert v-if="store.error" :title="store.error" type="error" :closable="false" />
      <div class="vision-review-grid">
        <div class="review-options">
          <label class="field-label">识别模型</label>
          <ElSelect v-model="store.modelId" aria-label="上下文视觉识别模型" :loading="store.loadingModels" placeholder="选择已开放视觉能力的模型">
            <ElOption v-for="model in store.models" :key="model.id" :value="model.id" :disabled="!model.vision"
              :label="`${formatModelVersion(model.displayName)}${model.mock ? '（演示）' : model.vision ? '（视觉）' : '（纯文字）'}`" />
          </ElSelect>
          <p class="review-hint">与模型管理共用目录。纯文字模型不能识图；演示模型不代表真实识别。</p>
          <h3>选择发送的页码 <ElTag size="small">{{ selected.length }} / {{ maxPages }}</ElTag></h3>
          <ElCheckboxGroup v-model="selected" :max="maxPages" class="page-selection" aria-label="发送给视觉模型的页码">
            <div v-for="item in review.pages" :key="item.page" class="page-option">
              <ElCheckbox :value="item.page" :disabled="item.blocked">第 {{ item.page }} 页</ElCheckbox>
              <span :class="{ blocked: item.blocked }">{{ item.reason }}</span>
              <ElButton link type="primary" @click="page = item.page">预览第 {{ item.page }} 页</ElButton>
            </div>
          </ElCheckboxGroup>
        </div>
        <div class="review-preview">
          <div class="preview-heading"><h3>页面预览</h3><ElSelect v-model="page" aria-label="当前预览页" class="preview-page-select">
            <ElOption v-for="item in review.pages" :key="item.page" :value="item.page" :label="`第 ${item.page} 页`" />
          </ElSelect></div>
          <p class="review-hint">在画面上拖动可添加黑色遮挡；也可使用下方精确设置。遮挡会在服务端施加到实际外发图片。</p>
          <ElAlert v-if="previewError" :title="previewError" type="error" :closable="false" />
          <div v-loading="previewLoading" class="preview-container">
            <div v-if="preview" class="mask-surface">
              <img :src="preview" :alt="`第 ${page} 页私有预览`" @error="previewError = '预览图片无法显示，请重试。'" />
              <svg ref="surface" viewBox="0 0 1 1" preserveAspectRatio="none" aria-label="拖动遮挡敏感区域"
                @pointerdown="startMask" @pointermove="moveMask" @pointerup="finishMask" @pointercancel="dragStart = undefined; dragEnd = undefined">
                <rect v-for="(mask, index) in currentMasks" :key="index" v-bind="mask" fill="black" />
                <rect v-if="pendingMask" v-bind="pendingMask" fill="black" opacity="0.75" />
              </svg>
            </div>
          </div>
          <div class="mask-actions"><span>本页遮挡 {{ currentMasks.length }} 处</span>
            <ElButton size="small" :disabled="!preview" @click="addMask({ x: 0, y: 0, width: 1, height: 1 })">遮挡整页</ElButton>
            <ElButton size="small" :disabled="!currentMasks.length" @click="masks = { ...masks, [page]: currentMasks.slice(0, -1) }">撤销遮挡</ElButton>
            <ElButton size="small" :disabled="!previewError" @click="loadPreview">重试预览</ElButton>
          </div>
          <details class="exact-mask"><summary>精确设置遮挡区域（百分比）</summary>
            <div><label v-for="(label, key) in { x: '左边距', y: '上边距', width: '宽度', height: '高度' }" :key="key">{{ label }}
              <ElInputNumber v-model="exact[key]" :min="0" :max="100" :step="1" :aria-label="`遮挡${label}`" />
            </label></div><ElButton size="small" :disabled="!preview" @click="addExactMask">添加遮挡</ElButton>
          </details>
        </div>
      </div>
      <div class="vision-consent">
        <ElCheckbox v-model="consent"><strong>我已检查所选页面隐私</strong>，同意将遮挡后的页面发送至 {{ selectedModel?.displayName ?? '所选模型服务' }} 进行识别并产生调用费用。</ElCheckbox>
        <p>识别后脱敏无法阻止原图中的隐私首次被供应商看到。系统不保证自动发现全部敏感信息；禁止外发的材料请仅使用本地提取。</p>
      </div>
    </template>
    <template #footer>
      <ElButton @click="store.cancel">取消此文件</ElButton>
      <ElButton @click="store.confirm({ modelId: '', consent: false, pages: [] })">仅本地提取</ElButton>
      <ElButton type="primary" :disabled="!canConfirm" @click="confirm">确认识别所选页面</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.file-name { overflow-wrap: anywhere; color: var(--ink-strong); }
.review-hint { color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.vision-review-grid { display: grid; grid-template-columns: minmax(240px, 0.8fr) minmax(0, 1.2fr); gap: 20px; margin-top: 16px; }
.review-options, .review-preview { min-width: 0; }
.field-label { display: block; margin-bottom: 8px; color: var(--ink-strong); font-size: 14px; }
h3 { margin: 14px 0 10px; color: var(--ink-strong); font-size: 14px; }
.page-selection { display: grid; max-height: 400px; overflow: auto; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); }
.page-option { display: grid; gap: 4px; padding: 10px; border-bottom: 1px solid var(--line-subtle); }
.page-option > span { color: var(--ink-muted); font-size: 12px; line-height: 1.6; }
.page-option > span.blocked { color: var(--warning); }
.page-option .el-button { justify-self: start; }
.preview-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.preview-page-select { width: 130px; }
.preview-container { min-height: 150px; max-height: 520px; overflow: auto; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); background: var(--surface-code); }
.mask-surface { position: relative; line-height: 0; }
.mask-surface img { display: block; width: 100%; height: auto; }
.mask-surface svg { position: absolute; inset: 0; width: 100%; height: 100%; cursor: crosshair; touch-action: none; }
.mask-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 10px; }
.mask-actions > span { color: var(--ink-muted); font-size: 12px; }
.mask-actions .el-button + .el-button { margin-left: 0; }
.exact-mask { margin-top: 12px; color: var(--ink-muted); font-size: 12px; }
.exact-mask > div { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 10px 0; }
.exact-mask label { display: grid; gap: 6px; }
.exact-mask .el-input-number { width: 100%; }
.vision-consent { margin-top: 18px; padding: 14px; border: 1px solid var(--warning); border-radius: var(--radius-sm); background: var(--orange-soft); }
.vision-consent .el-checkbox { height: auto; white-space: normal; align-items: flex-start; }
.vision-consent :deep(.el-checkbox__label) { white-space: normal; line-height: 1.7; }
.vision-consent :deep(.el-checkbox__input) { margin-top: 4px; }
.vision-consent p { margin-bottom: 0; color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
@media (max-width: 720px) { .vision-review-grid { grid-template-columns: minmax(0, 1fr); } .page-selection { max-height: 220px; } }
</style>
