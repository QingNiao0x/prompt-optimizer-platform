import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { useAuthStore } from './auth';
import { listContextVisionModels } from '@/services/documentUploadApi';
import { getApiErrorMessage } from '@/services/http';
import { suggestedVisionPages } from '@/features/document-upload/contextVisionBatch';
import type { ContextVisionConfirmation, ContextVisionMask, ContextVisionModel, DocumentUploadStatus } from '@/types/api';

export interface VisionBatchDecision {
  documentId: string;
  confirmation: ContextVisionConfirmation;
}

export interface VisionBatchRow {
  status: DocumentUploadStatus;
  /** local 不会外发；recognize 仍要等本批同意勾选后才提交。 */
  mode: 'local' | 'recognize';
  pages: { page: number; masks: ContextVisionMask[] }[];
}

const emptyConfirmation = (): ContextVisionConfirmation => ({ modelId: '', consent: false, pages: [] });

/** 只在当前浏览器内存中保留本次待审阅会话；身份变化、取消和上传生命周期结束都会撤回确认。 */
export const useContextVisionStore = defineStore('contextVision', () => {
  const auth = useAuthStore();
  const status = ref<DocumentUploadStatus>();
  const models = ref<ContextVisionModel[]>([]);
  const modelId = ref('');
  const error = ref('');
  const loadingModels = ref(false);
  const drafting = ref(false);
  const draftPages = ref<ContextVisionConfirmation['pages']>([]);
  const batch = ref<VisionBatchRow[] | null>(null);
  const batchConsent = ref(false);
  const visible = computed(() => Boolean(status.value));
  const batchVisible = computed(() => batch.value !== null && !status.value);
  let sequence = 0;
  let settle: ((value: ContextVisionConfirmation) => void) | undefined;
  let fail: ((reason: unknown) => void) | undefined;
  let detach: (() => void) | undefined;
  let batchSettle: ((value: VisionBatchDecision[] | null) => void) | undefined;
  let detachBatch: (() => void) | undefined;

  const clearDetail = (): void => {
    detach?.();
    detach = undefined;
    settle = undefined;
    fail = undefined;
    status.value = undefined;
    error.value = '';
    drafting.value = false;
    draftPages.value = [];
    sequence += 1;
    loadingModels.value = false;
  };
  const cancel = (): void => {
    const reject = fail;
    clearDetail();
    reject?.(new DOMException('已取消此文件识别', 'AbortError'));
  };
  const confirm = (value: ContextVisionConfirmation): void => {
    const resolve = settle;
    clearDetail();
    resolve?.(value);
  };
  const finishBatch = (value: VisionBatchDecision[] | null): void => {
    const resolve = batchSettle;
    detachBatch?.();
    detachBatch = undefined;
    batchSettle = undefined;
    batch.value = null;
    batchConsent.value = false;
    cancel();
    resolve?.(value);
  };
  const loadModels = (preferredModel: string, signal?: AbortSignal): void => {
    const current = sequence;
    loadingModels.value = true;
    error.value = '';
    void listContextVisionModels(signal).then((items) => {
      if (current !== sequence) return;
      models.value = items;
      modelId.value = items.find((item) => item.id === preferredModel && item.vision)?.id
        ?? items.find((item) => item.vision)?.id
        ?? '';
    }).catch((failure: unknown) => {
      if (current === sequence) error.value = getApiErrorMessage(failure);
    }).finally(() => {
      if (current === sequence) loadingModels.value = false;
    });
  };
  const review = (
    value: DocumentUploadStatus,
    signal?: AbortSignal,
    preferredModel = '',
    draft = false,
    seedPages: ContextVisionConfirmation['pages'] = [],
  ): Promise<ContextVisionConfirmation> => {
    cancel();
    if (signal?.aborted) return Promise.reject(new DOMException('文件处理已取消', 'AbortError'));
    drafting.value = draft;
    draftPages.value = seedPages.map((page) => ({ page: page.page, masks: page.masks.map((mask) => ({ ...mask })) }));
    status.value = value;
    modelId.value = '';
    loadModels(preferredModel, signal);
    return new Promise((resolve, reject) => {
      settle = resolve;
      fail = reject;
      signal?.addEventListener('abort', cancel, { once: true });
      detach = () => signal?.removeEventListener('abort', cancel);
    });
  };
  const recognizingRows = computed(() => (batch.value ?? []).filter((row) => row.mode === 'recognize'));
  const canApplyBatch = computed(() => {
    if (!batch.value?.length) return false;
    if (!recognizingRows.value.length) return true;
    const selectedModel = models.value.find((model) => model.id === modelId.value);
    return batchConsent.value
      && Boolean(selectedModel?.vision)
      && !loadingModels.value
      && recognizingRows.value.every((row) => row.pages.length > 0);
  });
  const decisionsFromBatch = (): VisionBatchDecision[] => (batch.value ?? []).map((row) => ({
    documentId: row.status.documentId,
    confirmation: row.mode === 'recognize'
      ? { modelId: modelId.value, consent: true, pages: row.pages.map((page) => ({
        page: page.page,
        masks: page.masks.map((mask) => ({ ...mask })),
      })) }
      : emptyConfirmation(),
  }));
  const applyBatch = (): void => {
    if (!canApplyBatch.value) return;
    finishBatch(decisionsFromBatch());
  };
  const applyLocalOnly = (): void => {
    for (const row of batch.value ?? []) {
      row.mode = 'local';
      row.pages = [];
    }
    batchConsent.value = false;
    finishBatch(decisionsFromBatch());
  };
  const cancelBatch = (): void => {
    if (!batchSettle) return;
    finishBatch(null);
  };
  /** 整批先停在清单上。默认每份都只做本地提取，身份切换或取消会丢弃尚未提交的选择。 */
  const settleBatch = (
    statuses: readonly DocumentUploadStatus[],
    preferredModel = '',
    signal?: AbortSignal,
  ): Promise<VisionBatchDecision[] | null> => {
    finishBatch(null);
    if (signal?.aborted) return Promise.resolve(null);
    batch.value = statuses.map((item) => ({ status: item, mode: 'local', pages: [] }));
    batchConsent.value = false;
    modelId.value = '';
    loadModels(preferredModel, signal);
    return new Promise((resolve) => {
      batchSettle = resolve;
      const abort = (): void => finishBatch(null);
      signal?.addEventListener('abort', abort, { once: true });
      detachBatch = () => signal?.removeEventListener('abort', abort);
    });
  };
  const markRow = (documentId: string, mode: VisionBatchRow['mode']): void => {
    const row = batch.value?.find((item) => item.status.documentId === documentId);
    if (!row) return;
    row.mode = mode;
    row.pages = mode === 'recognize' ? suggestedVisionPages(row.status) : [];
    batchConsent.value = false;
  };
  watch(() => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|'), () => {
    finishBatch(null);
    models.value = [];
    modelId.value = '';
  }, { flush: 'sync' });
  return {
    status, models, modelId, error, loadingModels, visible, drafting, draftPages, review, confirm, cancel,
    batch, batchConsent, batchVisible, canApplyBatch, settleBatch, applyBatch, applyLocalOnly, cancelBatch, markRow,
  };
});
