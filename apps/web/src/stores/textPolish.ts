import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { isAxiosError } from 'axios';
import { useAuthStore } from './auth';
import { textPolishApi as api } from '@/services/textPolishApi';
import { getApiErrorMessage } from '@/services/http';
import type { PolishCapabilities, PolishDepth, PolishDocument, PolishHistory, PolishInput, PolishStyle } from '@/types/textPolish';

/** 独立私有文章状态只保存在本页内存；身份变化后撤销迟到响应，不把正文写入localStorage。 */
export const useTextPolishStore = defineStore('text-polish', () => {
  const auth = useAuthStore();
  const sourceText = ref(''), style = ref<PolishStyle>('NATURAL'), depth = ref<PolishDepth>('LIGHT');
  const audience = ref(''), requirements = ref(''), lockedText = ref(''), modelId = ref('');
  const capabilities = ref<PolishCapabilities>();
  const result = ref<PolishDocument>(), pendingResult = ref<PolishDocument>(), previousResult = ref<PolishDocument>();
  const editDraft = ref(''), isEditing = ref(false), busy = ref(false), saving = ref(false), loading = ref(false);
  const error = ref(''), recoveryId = ref(''), recoveryPending = ref(false);
  const history = ref<PolishHistory>({ records: [], total: 0, current: 1, size: 10, pages: 0 });
  const historyLoading = ref(false);
  let identityEpoch = 0, historyRequest = 0;
  const selectedModel = computed(() => capabilities.value?.models.find(m => m.id === modelId.value));
  const dirty = computed(() => !!result.value && isEditing.value && editDraft.value !== result.value.polishedText);
  const currentText = computed(() => isEditing.value ? editDraft.value : result.value?.polishedText ?? '');
  const canGenerate = computed(() => !!sourceText.value.trim() && sourceText.value.length <= (capabilities.value?.maxInputCharacters ?? 12000)
    && !!selectedModel.value && !busy.value && !saving.value && !recoveryPending.value && !dirty.value);
  const input = (): PolishInput => ({ sourceText: sourceText.value, style: style.value, depth: depth.value, audience: audience.value,
    requirements: requirements.value, lockedTexts: lockedText.value.split('\n').filter(t => t.trim()), modelId: modelId.value });
  const viewSignature = (): string => JSON.stringify([input(), result.value?.id, currentText.value, isEditing.value]);
  /** 打开历史或返回上一稿时，同时恢复该稿的实际原文和设置，避免继续生成时混用不同版本。 */
  const restoreInput = (document: PolishDocument): void => {
    sourceText.value = document.input.sourceText; style.value = document.input.style; depth.value = document.input.depth;
    audience.value = document.input.audience; requirements.value = document.input.requirements;
    lockedText.value = document.input.lockedTexts.join('\n'); modelId.value = document.modelId;
  };
  const install = (document: PolishDocument): void => {
    previousResult.value = result.value;
    result.value = document; editDraft.value = document.polishedText; isEditing.value = false; pendingResult.value = undefined;
  };
  const reset = (): void => {
    sourceText.value = ''; style.value = 'NATURAL'; depth.value = 'LIGHT'; audience.value = ''; requirements.value = ''; lockedText.value = '';
    result.value = undefined; pendingResult.value = undefined; previousResult.value = undefined; editDraft.value = ''; isEditing.value = false;
    error.value = ''; recoveryId.value = ''; recoveryPending.value = false;
  };
  watch(() => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|'), () => {
    identityEpoch++; historyRequest++; reset(); capabilities.value = undefined; modelId.value = '';
    busy.value = false; saving.value = false; loading.value = false; historyLoading.value = false;
    history.value = { records: [], total: 0, current: 1, size: 10, pages: 0 };
  }, { flush: 'sync' });
  const load = async (): Promise<void> => {
    const epoch = identityEpoch; loading.value = true;
    try {
      const value = await api.capabilities(); if (epoch !== identityEpoch) return;
      capabilities.value = value;
      if (!value.models.some(m => m.id === modelId.value)) modelId.value = value.models.find(m => m.defaultModel)?.id ?? '';
    } catch (failure: unknown) { if (epoch === identityEpoch) error.value = getApiErrorMessage(failure); }
    finally { if (epoch === identityEpoch) loading.value = false; }
  };
  /** 原文重生始终从原文出发，继续润色只能使用已保存的当前稿；设置变化本身不调用模型。 */
  const generate = async (fromCurrent = false): Promise<void> => {
    if (!canGenerate.value || pendingResult.value) return;
    const source = result.value;
    if (fromCurrent && (!source?.historySaved || isEditing.value)) { error.value = '请先保存当前编辑稿，再继续润色。'; return; }
    const epoch = identityEpoch, signature = viewSignature(), requestId = crypto.randomUUID();
    const linked = !!source?.historySaved && (fromCurrent || sourceText.value === source.input.sourceText);
    busy.value = true; error.value = ''; recoveryId.value = requestId;
    try {
      const value = await api.generate(requestId, input(), linked ? fromCurrent ? 'CURRENT' : 'ORIGINAL' : 'RAW',
        linked ? source!.id : undefined, linked ? source!.editRevision : undefined);
      if (epoch !== identityEpoch) return;
      // 生成期间原文、设置或编辑稿变动时，结果作为候选保留，由用户主动查看，不能自动覆盖。
      if (viewSignature() !== signature) pendingResult.value = value;
      else {
        // 当前稿成为下一轮的实际原文，左栏随之展示，不能显示早先原文却暗中用编辑稿调用。
        if (fromCurrent) sourceText.value = value.input.sourceText;
        install(value);
      }
      recoveryId.value = ''; recoveryPending.value = false;
    } catch (failure: unknown) {
      if (epoch !== identityEpoch) return;
      error.value = getApiErrorMessage(failure);
      recoveryPending.value = isAxiosError(failure) && !failure.response;
      if (!recoveryPending.value) recoveryId.value = '';
    } finally { if (epoch === identityEpoch) busy.value = false; }
  };
  /** 恢复只查询服务端状态，不能把超时当失败并自动重发模型请求。 */
  const recover = async (): Promise<void> => {
    if (!recoveryId.value || busy.value) return;
    const epoch = identityEpoch; busy.value = true;
    try {
      const op = await api.operation(recoveryId.value); if (epoch !== identityEpoch) return;
      if (op.document) { pendingResult.value = op.document; recoveryPending.value = false; recoveryId.value = ''; error.value = ''; }
      else if (op.status === 'FAILED') { recoveryPending.value = false; recoveryId.value = ''; error.value = '该次处理未成功，原文和上一稿已保留，可重新发起。'; }
      else error.value = '该次处理仍在进行或状态尚未确认，请稍后查询；不要重复提交。';
    } catch (failure: unknown) { if (epoch === identityEpoch) error.value = getApiErrorMessage(failure); }
    finally { if (epoch === identityEpoch) busy.value = false; }
  };
  const save = async (): Promise<void> => {
    if (!result.value || saving.value) return;
    const epoch = identityEpoch, original = result.value, draft = editDraft.value; saving.value = true; error.value = '';
    try {
      const value = isEditing.value ? await api.edit(original.id, draft, original.editRevision) : await api.retrySave(original.id);
      if (epoch !== identityEpoch || result.value?.id !== original.id) return;
      result.value = value;
      if (editDraft.value === draft) { editDraft.value = value.polishedText; isEditing.value = false; }
    } catch (failure: unknown) { if (epoch === identityEpoch) error.value = getApiErrorMessage(failure); }
    finally { if (epoch === identityEpoch) saving.value = false; }
  };
  const loadHistory = async (current = 1): Promise<void> => {
    const epoch = identityEpoch, request = ++historyRequest; historyLoading.value = true;
    try { const value = await api.history(current); if (epoch === identityEpoch && request === historyRequest) history.value = value; }
    catch (failure: unknown) { if (epoch === identityEpoch && request === historyRequest) error.value = getApiErrorMessage(failure); }
    finally { if (epoch === identityEpoch && request === historyRequest) historyLoading.value = false; }
  };
  const restore = async (id: string): Promise<boolean> => {
    const epoch = identityEpoch;
    try {
      const value = await api.detail(id); if (epoch !== identityEpoch) return false;
      restoreInput(value); install(value); return true;
    } catch (failure: unknown) { if (epoch === identityEpoch) error.value = getApiErrorMessage(failure); return false; }
  };
  const remove = async (id: string): Promise<void> => {
    const epoch = identityEpoch;
    try {
      await api.remove(id); if (epoch !== identityEpoch) return;
      if (result.value?.id === id) { result.value = undefined; editDraft.value = ''; isEditing.value = false; }
      if (previousResult.value?.id === id) previousResult.value = undefined;
      if (pendingResult.value?.id === id) pendingResult.value = undefined;
      await loadHistory(history.value.current);
    } catch (failure: unknown) { if (epoch === identityEpoch) error.value = getApiErrorMessage(failure); }
  };
  /** 返回上一稿只恢复本页快照，不改变数据库；已删除快照由删除操作同步移除。 */
  const undo = (): void => { if (previousResult.value) { const previous = previousResult.value; previousResult.value = result.value; result.value = previous; restoreInput(previous); editDraft.value = previous.polishedText; isEditing.value = false; } };
  return { sourceText, style, depth, audience, requirements, lockedText, modelId, capabilities, selectedModel, result,
    pendingResult, previousResult, editDraft, isEditing, busy, saving, loading, error, recoveryId, recoveryPending,
    history, historyLoading, dirty, currentText, canGenerate, load, reset, generate, recover, save, loadHistory, restore, remove, install, undo };
});
