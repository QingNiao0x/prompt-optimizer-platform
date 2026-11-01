<script setup lang="ts">
import { watch } from 'vue';
import { ElAlert, ElButton, ElCheckbox, ElDialog, ElOption, ElRadio, ElRadioGroup, ElSelect } from 'element-plus';
import { useContextVisionStore, type VisionBatchRow } from '@/stores/contextVision';
import { summarizeVisionPages } from '@/features/document-upload/contextVisionBatch';
import { formatModelVersion } from '@/features/models/modelVersion';

const store = useContextVisionStore();
// 更换接收模型后，原先的同意不再覆盖新的外发范围。
watch(() => store.modelId, () => { store.batchConsent = false; });

const inspect = async (row: VisionBatchRow): Promise<void> => {
  try {
    const saved = await store.review(row.status, undefined, store.modelId, true, row.pages);
    if (!saved.pages.length) {
      store.markRow(row.status.documentId, 'local');
      return;
    }
    row.mode = 'recognize';
    row.pages = saved.pages.map((page) => ({ page: page.page, masks: page.masks.map((mask) => ({ ...mask })) }));
    store.batchConsent = false;
  } catch (error: unknown) {
    if (!(error instanceof DOMException) || error.name !== 'AbortError') throw error;
  }
};
</script>

<template>
  <ElDialog :model-value="store.batchVisible" title="选择需要视觉识别的图片与 PDF" width="min(760px, calc(100vw - 24px))"
    append-to-body :close-on-click-modal="false"
    @update:model-value="value => { if (!value && !store.status) store.cancelBatch(); }">
    <template v-if="store.batch">
      <ElAlert title="文件已在本平台，尚未发送给视觉模型。默认只做本地提取。" type="info" :closable="false" />
      <p class="batch-hint">勾选「进入识别」的文件，需要在下方同意后才会按所选页码外发。未进入识别的文件只保留本地解析结果。</p>
      <ElAlert v-if="store.error" :title="store.error" type="error" :closable="false" />
      <label class="field-label">识别模型</label>
      <ElSelect v-model="store.modelId" aria-label="上下文视觉识别模型" :loading="store.loadingModels" placeholder="选择已开放视觉能力的模型">
        <ElOption v-for="model in store.models" :key="model.id" :value="model.id" :disabled="!model.vision"
          :label="`${formatModelVersion(model.displayName)}${model.mock ? '（演示）' : model.vision ? '（视觉）' : '（纯文字）'}`" />
      </ElSelect>
      <ul class="batch-list">
        <li v-for="row in store.batch" :key="row.status.documentId">
          <div class="batch-path">
            <strong>{{ row.status.path }}</strong>
            <span>{{ summarizeVisionPages(row.status) }}</span>
            <span v-if="row.mode === 'recognize'">将发送 {{ row.pages.length }} 页<span v-if="row.pages.some(page => page.masks.length)">，已设置遮挡</span></span>
          </div>
          <ElRadioGroup :model-value="row.mode" :aria-label="`${row.status.path} 的处理方式`"
            @update:model-value="store.markRow(row.status.documentId, $event as VisionBatchRow['mode'])">
            <ElRadio value="local">仅本地提取</ElRadio>
            <ElRadio value="recognize">进入识别</ElRadio>
          </ElRadioGroup>
          <ElButton link type="primary" @click="inspect(row)">检查页面</ElButton>
        </li>
      </ul>
      <div v-if="store.batch.some(row => row.mode === 'recognize')" class="vision-consent">
        <ElCheckbox v-model="store.batchConsent"><strong>我已检查所选页面隐私</strong>，同意将进入识别的文件按上述页码和遮挡发送至 {{ store.models.find(model => model.id === store.modelId)?.displayName ?? '所选模型服务' }}，并产生调用费用。</ElCheckbox>
        <p>系统不保证发现全部敏感信息。未勾选同意时，这些文件不会外发。</p>
      </div>
    </template>
    <template #footer>
      <ElButton @click="store.cancelBatch">取消本批</ElButton>
      <ElButton @click="store.applyLocalOnly">全部仅本地提取</ElButton>
      <ElButton type="primary" :disabled="!store.canApplyBatch" @click="store.applyBatch">确认本批处理</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.batch-hint, .field-label { color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.field-label { display: block; margin: 12px 0 8px; color: var(--ink-strong); font-size: 14px; }
.batch-list { display: grid; max-height: 360px; gap: 8px; margin: 12px 0 0; padding: 0; overflow: auto; list-style: none; }
.batch-list li { display: grid; gap: 6px; padding: 10px; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); }
.batch-path { display: grid; gap: 4px; min-width: 0; }
.batch-path strong { overflow-wrap: anywhere; color: var(--ink-strong); font-weight: 600; }
.batch-path span { color: var(--ink-muted); font-size: 12px; line-height: 1.6; }
.batch-list :deep(.el-radio-group) { display: flex; flex-wrap: wrap; gap: 8px; }
.vision-consent { margin-top: 16px; padding: 14px; border: 1px solid var(--warning); border-radius: var(--radius-sm); background: var(--orange-soft); }
.vision-consent .el-checkbox { height: auto; white-space: normal; align-items: flex-start; }
.vision-consent :deep(.el-checkbox__label) { white-space: normal; line-height: 1.7; }
.vision-consent :deep(.el-checkbox__input) { margin-top: 4px; }
.vision-consent p { margin-bottom: 0; color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
</style>
