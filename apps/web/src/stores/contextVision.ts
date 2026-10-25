import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { useAuthStore } from './auth';
import { listContextVisionModels } from '@/services/documentUploadApi';
import { getApiErrorMessage } from '@/services/http';
import type { ContextVisionConfirmation, ContextVisionModel, DocumentUploadStatus } from '@/types/api';

/** 只在当前浏览器内存中保留本次待审阅会话；身份变化、取消和上传生命周期结束都会撤回确认。 */
export const useContextVisionStore = defineStore('contextVision', () => {
  const auth = useAuthStore();
  const status = ref<DocumentUploadStatus>();
  const models = ref<ContextVisionModel[]>([]);
  const modelId = ref('');
  const error = ref('');
  const loadingModels = ref(false);
  const visible = computed(() => Boolean(status.value));
  let sequence = 0;
  let settle: ((value: ContextVisionConfirmation) => void) | undefined;
  let fail: ((reason: unknown) => void) | undefined;
  let detach: (() => void) | undefined;
  const clear = (): void => { detach?.(); detach = undefined; settle = undefined; fail = undefined; status.value = undefined; error.value = ''; sequence++; loadingModels.value = false; };
  const cancel = (): void => { const reject = fail; clear(); reject?.(new DOMException('已取消此文件识别', 'AbortError')); };
  const confirm = (value: ContextVisionConfirmation): void => { const resolve = settle; clear(); resolve?.(value); };
  const review = (value: DocumentUploadStatus, signal?: AbortSignal, preferredModel = ''): Promise<ContextVisionConfirmation> => {
    cancel();
    if (signal?.aborted) return Promise.reject(new DOMException('文件处理已取消', 'AbortError'));
    status.value = value; modelId.value = '';
    const current = sequence;
    loadingModels.value = true;
    void listContextVisionModels(signal).then(items => {
      if (current !== sequence) return;
      models.value = items;
      modelId.value = items.find(item => item.id === preferredModel && item.vision)?.id ?? items.find(item => item.vision)?.id ?? '';
    }).catch((failure: unknown) => { if (current === sequence) error.value = getApiErrorMessage(failure); })
      .finally(() => { if (current === sequence) loadingModels.value = false; });
    return new Promise((resolve, reject) => {
      settle = resolve; fail = reject;
      signal?.addEventListener('abort', cancel, { once: true });
      detach = () => signal?.removeEventListener('abort', cancel);
    });
  };
  watch(() => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|'), () => {
    cancel(); models.value = []; modelId.value = '';
  }, { flush: 'sync' });
  return { status, models, modelId, error, loadingModels, visible, review, confirm, cancel };
});
