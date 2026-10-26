<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue';
import { CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue';
import { ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElDialog, ElInputNumber, ElOption, ElSelect, ElTag } from 'element-plus';
import { imagePromptApi } from '@/services/imagePromptApi';
import { getApiErrorMessage } from '@/services/http';
import type { ImagePromptMask, ImagePromptMaterialRef, ImagePromptMediaAsset, ImagePromptMediaUnit } from '@/types/imagePrompt';

const props = defineProps<{ visible: boolean; asset: ImagePromptMediaAsset; units: ImagePromptMaterialRef['units']; maxUnits: number }>();
const emit = defineEmits<{ close: []; save: [units: ImagePromptMaterialRef['units']] }>();
const selected = ref<string[]>([]), unitId = ref(''), preview = ref(''), previewError = ref(''), loading = ref(false);
const previewRatio = ref(1);
const selectionPanel = ref<HTMLElement>(), selectionHeading = ref<HTMLElement>();
const selectionHintId = `media-selection-hint-${useId()}`;
const masks = ref<Record<string, ImagePromptMask[]>>({});
const surface = ref<SVGSVGElement>();
const dragStart = ref<{ x: number; y: number }>(), dragEnd = ref<{ x: number; y: number }>();
const exact = ref({ x: 0, y: 0, width: 20, height: 10 });
const downloading = ref(false), downloadError = ref('');
let downloadSequence = 0, downloadController: AbortController | undefined;
const downloadUrls = new Set<string>();
let sequence = 0, controller: AbortController | undefined;
const currentMasks = computed(() => masks.value[unitId.value] ?? []);
const maxMasks = 20;
/** 仅解释原有保存条件，不把预览、遮挡或保存视为模型外发同意。 */
const saveDisabledReason = computed(() => {
  if (!selected.value.length) return props.maxUnits > 0 ? '请先勾选至少一项识别范围。' : '本次可选范围已用尽，请返回减少其他素材的选择。';
  if (selected.value.length > props.maxUnits) return `当前最多可选 ${props.maxUnits} 项，请减少勾选范围。`;
  if (loading.value) return '正在加载预览，请稍候再保存。';
  if (previewError.value) return '预览尚未就绪，请先重试预览。';
  return '';
});
/** 用户主动定位勾选入口；只移动视口与标题焦点，保留所有选择并尊重减少动画设置。 */
const focusSelection = (): void => {
  selectionPanel.value?.scrollIntoView({ block: 'start', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  selectionHeading.value?.focus({ preventScroll: true });
};
const unit = computed(() => props.asset.units.find(item => item.id === unitId.value));
const label = (item: ImagePromptMediaUnit): string => item.page !== null ? `第 ${item.page} 页` : item.timestampMs !== null ? `${(item.timestampMs / 1000).toFixed(2)} 秒` : '图片';
const clear = (): void => { if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ''; };
const cancelDownload = (): void => { downloadSequence++; downloadController?.abort(); downloadController = undefined; downloading.value = false; };
/** 用户主动导出点击时的选中单位和遮挡快照，临时下载URL仅保留到浏览器接管下载。 */
const download = async (): Promise<void> => {
  const currentUnit = unit.value;
  if (!currentUnit || downloading.value || !preview.value || loading.value || previewError.value) return;
  const current = ++downloadSequence;
  const selectedMasks = currentMasks.value.map(mask => ({ ...mask }));
  downloadController = new AbortController(); downloading.value = true; downloadError.value = '';
  try {
    const blob = await imagePromptApi.exportMediaUnit(props.asset.id, currentUnit.id, selectedMasks, downloadController.signal);
    if (current !== downloadSequence || !props.visible) return;
    const url = URL.createObjectURL(blob); downloadUrls.add(url);
    const link = document.createElement('a'); link.href = url;
    const filename = props.asset.name.replace(/\.[^.]+$/, '').replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_');
    link.download = `${filename}-${label(currentUnit).replace(/\s/g, '')}-静态画面.png`;
    document.body.appendChild(link); link.click(); link.remove();
    setTimeout(() => { URL.revokeObjectURL(url); downloadUrls.delete(url); }, 1000);
  } catch (failure: unknown) { if (current === downloadSequence) downloadError.value = getApiErrorMessage(failure); }
  finally { if (current === downloadSequence) { downloading.value = false; downloadController = undefined; } }
};
/** 私有预览通过已认证HTTP获取，页/帧和身份变更后撤销旧Blob与迟到响应。 */
const load = async (): Promise<void> => {
  const current = ++sequence; controller?.abort(); clear(); previewError.value = '';
  if (!props.visible || !unitId.value) { loading.value = false; return; }
  previewRatio.value = unit.value && unit.value.width > 0 && unit.value.height > 0 ? unit.value.width / unit.value.height : 1;
  controller = new AbortController(); loading.value = true;
  try { const blob = await imagePromptApi.mediaPreview(props.asset.id, unitId.value, controller.signal); if (current === sequence) preview.value = URL.createObjectURL(blob); }
  catch (failure: unknown) { if (current === sequence) previewError.value = getApiErrorMessage(failure); }
  finally { if (current === sequence) loading.value = false; }
};
/** 同一单位再次点击也应主动重读，避免首次预览失败后因选择值未变而没有响应。 */
const previewUnit = (id: string): void => {
  if (id === unitId.value) void load();
  else unitId.value = id;
};
/** 以实际预览像素校正比例；缩放图片及其SVG共同容器，避免留白或裁切造成遮挡偏移。 */
const fitPreview = (event: Event): void => {
  const image = event.target;
  if (image instanceof HTMLImageElement && image.naturalWidth > 0 && image.naturalHeight > 0) previewRatio.value = image.naturalWidth / image.naturalHeight;
};
watch(() => props.visible, visible => {
  if (visible) {
    // 解析刷新后只恢复仍属于当前素材的选区，不把失效页帧隐藏在已选计数中，也不自动勾选新范围。
    const available = new Set(props.asset.units.map(item => item.id));
    const saved = props.units.filter(item => available.has(item.unitId));
    downloadError.value = ''; selected.value = saved.map(item => item.unitId);
    masks.value = Object.fromEntries(saved.map(item => [item.unitId, item.masks.map(mask => ({ ...mask }))]));
    unitId.value = selected.value[0] ?? props.asset.units[0]?.id ?? '';
  }
  else { sequence++; controller?.abort(); clear(); cancelDownload(); }
});
watch([() => props.visible, unitId], () => { dragStart.value = undefined; dragEnd.value = undefined; void load(); }, { flush: 'post' });
const rectangle = (start: { x: number; y: number }, end: { x: number; y: number }): ImagePromptMask => ({ x: Math.min(start.x, end.x), y: Math.min(start.y, end.y), width: Math.abs(end.x - start.x), height: Math.abs(end.y - start.y) });
const pendingMask = computed(() => dragStart.value && dragEnd.value ? rectangle(dragStart.value, dragEnd.value) : undefined);
const point = (event: PointerEvent): { x: number; y: number } => { const bounds = surface.value!.getBoundingClientRect(); return { x: Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width)), y: Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height)) }; };
const startMask = (event: PointerEvent): void => { if (!preview.value || !surface.value || event.button !== 0 || currentMasks.value.length >= maxMasks) return; event.preventDefault(); surface.value.setPointerCapture(event.pointerId); dragStart.value = point(event); dragEnd.value = point(event); };
const addMask = (value: ImagePromptMask): void => { if (value.width <= 0 || value.height <= 0 || currentMasks.value.length >= maxMasks) return; masks.value = { ...masks.value, [unitId.value]: [...currentMasks.value, value] }; };
const finishMask = (): void => { if (pendingMask.value && pendingMask.value.width > .002 && pendingMask.value.height > .002) addMask(pendingMask.value); dragStart.value = undefined; dragEnd.value = undefined; };
const addExact = (): void => addMask({ x: exact.value.x / 100, y: exact.value.y / 100, width: Math.min(exact.value.width / 100, 1 - exact.value.x / 100), height: Math.min(exact.value.height / 100, 1 - exact.value.y / 100) });
const save = (): void => emit('save', selected.value.map(id => ({ unitId: id, masks: (masks.value[id] ?? []).map(mask => ({ ...mask })) })));
onBeforeUnmount(() => { sequence++; controller?.abort(); clear(); cancelDownload(); downloadUrls.forEach(url => URL.revokeObjectURL(url)); downloadUrls.clear(); });
</script>

<template>
  <!-- 玻璃素材栏有filter与overflow边界，弹窗必须挂到body，避免被三栏布局裁切。 -->
  <ElDialog :model-value="visible" class="image-prompt-media-review" append-to-body align-center title="审阅素材与选择识别范围" width="min(1000px, calc(100vw - 24px))" :close-on-click-modal="false" @update:model-value="value => { if (!value) emit('close'); }">
    <p class="asset-name">{{ asset.name }}</p><ElAlert title="素材已上传，尚未发给模型。仅发送勾选范围，遮挡会用于实际外发图片。" type="info" :closable="false" />
    <div class="review-grid">
      <section ref="selectionPanel" class="selection-panel" :class="{ 'needs-selection': !selected.length }" aria-label="选择识别范围" data-testid="media-range-selection">
        <div class="selection-heading"><component :is="selected.length ? CircleCheckFilled : WarningFilled" class="selection-icon" aria-hidden="true" />
          <div><span class="step-label">第 1 步 · 必选</span><h3 ref="selectionHeading" tabindex="-1">选择识别范围</h3></div>
          <ElTag :type="selected.length ? 'success' : 'warning'" effect="plain" size="small">{{ selected.length ? '已勾选' : '待勾选' }}</ElTag>
        </div>
        <p :id="selectionHintId" class="selection-guidance">请先勾选要识别的内容。预览不会自动勾选。</p>
        <p class="selection-progress" role="status" aria-live="polite">已选 <strong>{{ selected.length }}</strong> 项，当前最多可选 {{ maxUnits }} 项。</p>
        <ElCheckboxGroup v-model="selected" :max="maxUnits" class="unit-list" aria-label="素材识别页帧" :aria-describedby="selectionHintId">
          <div v-for="item in asset.units" :key="item.id" class="unit-row" :class="{ 'is-selected': selected.includes(item.id) }"><ElCheckbox :value="item.id">{{ label(item) }}</ElCheckbox><span v-if="selected.includes(item.id)" class="unit-selected" aria-hidden="true">已选</span><ElButton link type="primary" @click="previewUnit(item.id)">预览{{ label(item) }}</ElButton></div>
        </ElCheckboxGroup>
        <p class="hint">可选范围 {{ asset.units.length }} / {{ asset.totalUnits }}。未选择或未覆盖的部分不视为已识别。</p>
        <p v-for="warning in asset.warnings" :key="warning" class="warning">{{ warning }}</p>
      </section>
      <div><div class="preview-heading"><div><span class="step-label">第 2 步 · 检查隐私</span><h3>私有预览与隐私遮挡</h3></div><ElSelect v-model="unitId" aria-label="预览素材单位"><ElOption v-for="item in asset.units" :key="item.id" :value="item.id" :label="label(item)" /></ElSelect></div>
        <p class="hint">核对画面，按需添加遮挡；已勾选画面的遮挡随选择保存。有遮挡的页/帧，其原始结构文字也不会外发。</p>
        <ElAlert v-if="previewError" :title="previewError" type="error" :closable="false" />
        <p v-if="loading" role="status">正在加载预览……</p>
        <div v-if="preview" class="preview-stage"><div class="mask-surface" :style="{ '--media-preview-ratio': previewRatio }"><img :src="preview" :alt="`${asset.name}${unit ? label(unit) : ''}私有预览`" @load="fitPreview" @error="previewError = '预览无法显示，请重试。'" />
          <svg ref="surface" viewBox="0 0 1 1" preserveAspectRatio="none" aria-label="拖动遮挡素材敏感区域" @pointerdown="startMask" @pointermove="event => { if (dragStart) dragEnd = point(event); }" @pointerup="finishMask" @pointercancel="dragStart = undefined; dragEnd = undefined">
            <rect v-for="(mask, index) in currentMasks" :key="index" v-bind="mask" fill="black" /><rect v-if="pendingMask" v-bind="pendingMask" fill="black" opacity=".75" />
          </svg>
        </div></div>
        <div class="mask-actions"><span>遮挡 {{ currentMasks.length }} / {{ maxMasks }} 处</span><ElButton size="small" :disabled="!preview || currentMasks.length >= maxMasks" @click="addMask({ x: 0, y: 0, width: 1, height: 1 })">遮挡整页／帧</ElButton><ElButton size="small" :disabled="!currentMasks.length" @click="masks = { ...masks, [unitId]: currentMasks.slice(0, -1) }">撤销遮挡</ElButton><ElButton v-if="previewError" size="small" @click="load">重试预览</ElButton></div>
        <p v-if="currentMasks.length >= maxMasks" class="hint" role="status">本页／帧已达到 {{ maxMasks }} 处遮挡上限，可撤销后重新调整。</p>
        <ElButton :loading="downloading" :disabled="!preview || loading || !!previewError" @click="download">下载供下游使用的 PNG</ElButton>
        <p class="hint">下载当前预览页／帧及点击时的遮挡，得到 PNG 静态画面；不包含视频音轨或 PPT 可编辑结构，不触发模型调用。</p>
        <ElAlert v-if="downloadError" :title="downloadError" type="error" :closable="false" />
        <details><summary>精确设置遮挡区域（百分比）</summary><p class="hint">填写数值后点击“添加遮挡”，核对画面上的黑色区域，再保存选择与遮挡。</p><div class="exact-mask"><label v-for="(name, key) in { x: '左边距', y: '上边距', width: '宽度', height: '高度' }" :key="key">{{ name }}<ElInputNumber v-model="exact[key]" :min="0" :max="100" :aria-label="`素材遮挡${name}`" /></label></div><ElButton :disabled="!preview || currentMasks.length >= maxMasks" @click="addExact">添加遮挡</ElButton></details>
      </div>
    </div>
    <template #footer><div class="review-footer">
      <div class="footer-guidance"><p class="save-feedback" role="status" aria-live="polite"><component :is="saveDisabledReason ? WarningFilled : CircleCheckFilled" aria-hidden="true" />{{ saveDisabledReason || `已选 ${selected.length} 项，可以保存。` }}</p>
        <ElButton v-if="!selected.length && asset.units.length && maxUnits > 0" link type="primary" @click="focusSelection">前往勾选识别范围</ElButton>
        <p class="hint">保存后，仍需在页面勾选隐私同意。</p>
      </div>
      <div class="footer-actions"><ElButton @click="emit('close')">取消修改</ElButton><ElButton type="primary" :disabled="!!saveDisabledReason" @click="save">保存选择与遮挡</ElButton></div>
    </div></template>
  </ElDialog>
</template>

<style scoped>
/* 仅约束本弹窗；正文滚动时保留页脚，窄屏和短屏均可完成保存。 */
:global(.image-prompt-media-review.el-dialog) { display: flex; flex-direction: column; max-height: calc(100dvh - 32px - env(safe-area-inset-top, 0px) - env(safe-area-inset-bottom, 0px)); }
:global(.image-prompt-media-review > .el-dialog__body) { min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
:global(.image-prompt-media-review > .el-dialog__header), :global(.image-prompt-media-review > .el-dialog__footer) { flex-shrink: 0; }
p { line-height: 1.75; overflow-wrap: anywhere; } h3 { color: var(--ink-strong); font-size: 15px; margin: 14px 0; }
.asset-name { margin: 4px 0 10px; color: var(--ink-muted); font-size: 13px; }
.review-grid { display: grid; grid-template-columns: minmax(240px, .8fr) minmax(0, 1.2fr); align-items: start; gap: 20px; margin-top: 14px; }
.review-grid > * { min-width: 0; } .hint, .warning, details { font-size: 12px; color: var(--ink-muted); line-height: 1.75; } .warning { color: var(--warning); }
.selection-panel { padding: 16px; border: 2px solid var(--accent); border-radius: var(--radius-md); background: var(--accent-soft); scroll-margin-top: 8px; }
.selection-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.selection-heading > div { flex: 1; min-width: 0; }
.selection-icon { width: 26px; height: 26px; flex-shrink: 0; color: var(--success); }
.needs-selection .selection-icon { color: var(--warning); }
.step-label { color: var(--accent); font-size: 12px; font-weight: 600; }
.selection-heading h3, .preview-heading h3 { margin: 4px 0 0; font-size: 16px; line-height: 1.5; }
.selection-heading h3:focus-visible { outline: 2px solid var(--accent); outline-offset: 4px; border-radius: 2px; }
.selection-guidance { margin: 12px 0 8px; color: var(--ink-strong); font-size: 14px; font-weight: 600; }
.selection-progress { margin: 8px 0; color: var(--ink-normal); font-size: 13px; }
.selection-progress strong { color: var(--accent); font-size: 17px; }
.unit-list { display: grid; gap: 8px; max-height: 360px; overflow: auto; padding: 2px; }
.unit-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; padding: 4px 10px; gap: 8px; border: 1px solid var(--line-strong); border-radius: var(--radius-sm); background: var(--glass-fallback); }
.unit-row.is-selected { border-color: var(--accent); background: var(--accent-soft); }
.unit-row:focus-within { outline: 2px solid var(--accent); outline-offset: 1px; }
.unit-row :deep(.el-checkbox) { flex: 1; min-width: 64px; height: auto; min-height: 44px; margin: 0; white-space: normal; }
.unit-row :deep(.el-checkbox__label) { white-space: normal; color: var(--ink-strong); font-size: 14px; font-weight: 600; line-height: 1.6; padding-left: 10px; }
.unit-row :deep(.el-checkbox__inner) { width: 20px; height: 20px; border-width: 2px; border-color: var(--accent); }
.unit-row :deep(.el-checkbox__inner::after) { left: 6px; top: 2px; }
.unit-row :deep(.el-checkbox__input.is-disabled .el-checkbox__inner) { border-color: var(--el-border-color); }
.unit-row .el-button { min-height: 40px; }
.unit-selected { color: var(--accent); font-size: 12px; font-weight: 600; }
.preview-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; justify-content: space-between; } .preview-heading .el-select { width: 130px; }
.preview-stage { display: flex; justify-content: center; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); background: var(--el-fill-color-light); }
.mask-surface { position: relative; line-height: 0; width: min(100%, calc(min(52dvh, 560px) * var(--media-preview-ratio))); flex-shrink: 0; } .mask-surface img { display: block; width: 100%; height: auto; }
.mask-surface svg { position: absolute; inset: 0; width: 100%; height: 100%; touch-action: none; cursor: crosshair; }
.mask-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 12px 0; font-size: 12px; color: var(--ink-muted); } .mask-actions .el-button + .el-button { margin-left: 0; }
.exact-mask { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 12px 0; } .exact-mask label { display: grid; gap: 6px; } .exact-mask .el-input-number { width: 100%; }
.review-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; text-align: left; }
.footer-guidance { flex: 1; min-width: 0; }
.footer-guidance p { margin: 4px 0; }
.save-feedback { display: flex; align-items: flex-start; gap: 6px; font-size: 13px; font-weight: 600; color: var(--ink-strong); }
.save-feedback svg { width: 16px; height: 16px; margin-top: 3px; flex-shrink: 0; color: var(--accent); }
.footer-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.footer-actions .el-button + .el-button { margin-left: 0; }
@media (max-width: 720px) { .review-grid { grid-template-columns: minmax(0, 1fr); gap: 18px; } .selection-panel { padding: 12px; } .unit-list { max-height: 180px; } .exact-mask { grid-template-columns: minmax(0, 1fr); } .review-footer { flex-direction: column; align-items: stretch; gap: 10px; } .footer-actions { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.5fr); } .footer-actions .el-button { min-height: 42px; height: auto; white-space: normal; } }
</style>
