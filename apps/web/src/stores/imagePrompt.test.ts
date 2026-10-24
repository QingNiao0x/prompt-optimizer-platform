import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useImagePromptStore } from './imagePrompt';
import { useAuthStore } from './auth';
import { imagePromptApi } from '@/services/imagePromptApi';
import type { ImagePromptDocument, ImagePromptPlan } from '@/types/imagePrompt';

vi.mock('@/services/imagePromptApi', () => ({ imagePromptApi: {
  capabilities: vi.fn(), upload: vi.fn(), removeAsset: vi.fn(), extract: vi.fn(), plan: vi.fn(), confirm: vi.fn(),
  operation: vi.fn(), edit: vi.fn(), retrySave: vi.fn(), rewrite: vi.fn(),
} }));
const expiresAt = '2099-01-01T00:00:00Z';
const plan: ImagePromptPlan = { id: 'plan', fingerprint: 'binding', summary: '新图创作', questions: [], analysis: [], warnings: [], expiresAt, mock: false };
const document = (id = 'record'): ImagePromptDocument => ({
  operationId: id, recordId: id, mode: 'CUSTOM_CREATION', optimizedPrompt: '创作全新的植物海报。', analysis: [], warnings: [],
  input: { mode: 'CUSTOM_CREATION', modelId: 'vision', images: [], creationIntent: '植物海报', exactText: '', context: '', language: 'ZH', targetTool: '通用' },
  sources: [], questions: [], answers: [], modelId: 'vision', modelName: '视觉模型', mock: false,
  editRevision: 0, historySaved: true, persistentHistory: true, createdAt: '2026-10-24T00:00:00Z',
});
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const ready = async () => {
  const store = useImagePromptStore(); await store.refreshModels(); return store;
};

/** 失败、导航和身份变更使用迟到响应验证；不会调用真实模型或保存用户图片。 */
describe('image prompt task state', () => {
  beforeEach(() => {
    vi.resetAllMocks(); setActivePinia(createPinia());
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fixture');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.mocked(imagePromptApi.removeAsset).mockResolvedValue();
    vi.mocked(imagePromptApi.capabilities).mockResolvedValue({ models: [
      { id: 'vision', displayName: '视觉模型', vision: true, maxImages: 3, evidence: '受控验证', mock: false },
      { id: 'text', displayName: '文字模型', vision: false, maxImages: 0, evidence: '', mock: false },
    ], maxImageBytes: 10485760, maxPixels: 15000000, maxEdge: 8192, maxImages: 3, assetTtlSeconds: 3600, persistentHistory: true });
    vi.mocked(imagePromptApi.upload).mockResolvedValue({ id: 'asset', name: 'fixture.png', mimeType: 'image/png', width: 2,
      height: 2, bytes: 100, hash: 'hash', expiresAt, previewUrl: '/private', processing: [] });
  });

  it('selecting an image or toggling Plan never uploads or calls a model; consent is required', async () => {
    const store = await ready(); store.addFiles([new File(['pixels'], 'fixture.png', { type: 'image/png' })]);
    expect(store.modelInputError).toBe('');
    expect(store.startDisabledReason).toContain('我已检查图片隐私');
    expect(store.canStart).toBe(false); await store.start();
    store.mode = 'CUSTOM_CREATION'; store.creationIntent = '植物海报';
    expect(imagePromptApi.upload).not.toHaveBeenCalled(); expect(imagePromptApi.plan).not.toHaveBeenCalled();
    store.consent = true; expect(store.canStart).toBe(true); expect(store.startDisabledReason).toBe('');
    store.modelId = 'text'; expect(store.canStart).toBe(false);
  });

  it('shows the same published directory in both modes while text-only models cannot receive images', async () => {
    const store = await ready();
    expect(store.availableModels.map(model => model.id)).toEqual(['vision', 'text']);
    store.modelId = 'text'; expect(store.modelInputError).toContain('未开放图片输入');
    store.mode = 'CUSTOM_CREATION'; store.creationIntent = '社区海报';
    expect(store.canStart).toBe(true); expect(store.modelInputError).toBe('');
    store.addFiles([new File(['pixels'], 'fixture.png', { type: 'image/png' })]); store.consent = true;
    expect(store.availableModels.map(model => model.id)).toEqual(['vision', 'text']);
    expect(store.canStart).toBe(false); await store.start();
    expect(imagePromptApi.upload).not.toHaveBeenCalled(); expect(imagePromptApi.plan).not.toHaveBeenCalled();
    store.removeFile(store.files[0]!.key); expect(store.canStart).toBe(true);
  });

  it('keeps an over-capacity model visible and rejects its images before upload', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '海报';
    store.capabilities!.models[0]!.maxImages = 1;
    store.addFiles(['a','b'].map(name => new File(['pixels'], name + '.png', { type: 'image/png' }))); store.consent = true;
    expect(store.availableModels.map(model => model.id)).toEqual(['vision', 'text']);
    expect(store.modelInputError).toContain('最多支持 1 张'); expect(store.canStart).toBe(false);
    await store.start(); expect(imagePromptApi.upload).not.toHaveBeenCalled();
  });

  it('keeps dispatched operation id across navigation, ignores late response, and queries without replay', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '植物海报';
    const pending = deferred<ImagePromptPlan>(); vi.mocked(imagePromptApi.plan).mockReturnValue(pending.promise);
    const waiting = store.start(); await vi.waitFor(() => expect(imagePromptApi.plan).toHaveBeenCalledOnce());
    const id = store.recoveryId; expect(id).not.toBe(''); store.cancelPending();
    pending.resolve(plan); await waiting;
    expect(store.plan).toBeUndefined(); expect(store.recoveryPending).toBe(true); expect(store.canStart).toBe(false);
    vi.mocked(imagePromptApi.operation).mockRejectedValueOnce(new Error('offline'));
    await store.recover(); expect(store.recoveryPending).toBe(true);
    vi.mocked(imagePromptApi.operation).mockResolvedValueOnce({ status: 'COMPLETED', plan, document: null, message: '', startedAt: '' });
    await store.recover(); expect(store.plan?.id).toBe('plan'); expect(store.recoveryPending).toBe(false);
    expect(imagePromptApi.plan).toHaveBeenCalledOnce(); expect(imagePromptApi.operation).toHaveBeenCalledWith(id);
  });

  it('pure text Plan skips upload and accepts zero questions with explicit confirmation', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '植物海报'; store.exactText = '春日·2026';
    vi.mocked(imagePromptApi.plan).mockResolvedValue(plan); vi.mocked(imagePromptApi.confirm).mockResolvedValue(document());
    await store.start(); expect(store.plan?.questions).toEqual([]); expect(imagePromptApi.upload).not.toHaveBeenCalled();
    expect(imagePromptApi.confirm).not.toHaveBeenCalled(); await store.confirm();
    expect(imagePromptApi.confirm).toHaveBeenCalledWith(plan, expect.any(String), [], expect.any(AbortSignal));
    expect(store.result?.recordId).toBe('record');
  });

  it('preserves both chosen options and custom answer in the confirm payload', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '植物海报';
    const questionPlan: ImagePromptPlan = { ...plan, questions: [{ id: 'style', type: 'SINGLE_CHOICE', question: '什么风格？',
      options: [{ id: 'a', label: '插画' }, { id: 'b', label: '摄影' }], required: true }] };
    vi.mocked(imagePromptApi.plan).mockResolvedValue(questionPlan); vi.mocked(imagePromptApi.confirm).mockResolvedValue(document());
    await store.start(); await store.confirm(); expect(imagePromptApi.confirm).not.toHaveBeenCalled();
    store.answers.style = { questionId: 'style', optionIds: ['a'], customText: '保留纸张纹理' }; await store.confirm();
    expect(imagePromptApi.confirm).toHaveBeenCalledWith(questionPlan, expect.any(String), [
      { questionId: 'style', optionIds: ['a'], customText: '保留纸张纹理' },
    ], expect.any(AbortSignal));
  });

  it('allows undo only for edits of the same record, never the previous generation or mode', async () => {
    const store = await ready(); store.restore(document('A')); store.isEditing = true; store.editDraft = '人工修订A';
    vi.mocked(imagePromptApi.edit).mockResolvedValue({ ...document('A'), optimizedPrompt: '人工修订A', editRevision: 1 });
    await store.saveEdit(); expect(store.undoDocument?.optimizedPrompt).toBe(document('A').optimizedPrompt);
    vi.mocked(imagePromptApi.rewrite).mockResolvedValue({ ...document('B'), optimizedPrompt: '新的生成B' });
    await store.rewrite('整理表达'); expect(store.undoDocument).toBeUndefined();
    await store.undo(); expect(store.result?.optimizedPrompt).toBe('新的生成B');
    expect(imagePromptApi.edit).toHaveBeenCalledOnce();
  });

  it('clears private state on identity change and ignores late model successes', async () => {
    const store = await ready(); const auth = useAuthStore();
    store.mode = 'CUSTOM_CREATION'; store.creationIntent = '旧身份构想';
    const pending = deferred<ImagePromptPlan>(); vi.mocked(imagePromptApi.plan).mockReturnValue(pending.promise);
    const waiting = store.start(); await vi.waitFor(() => expect(imagePromptApi.plan).toHaveBeenCalledOnce());
    auth.user = { userId: 'other', tenantId: 'other', workspaceId: 'other', displayName: 'other', email: 'other@example.test', platformAdmin: false };
    pending.resolve(plan); await waiting;
    expect(store.creationIntent).toBe(''); expect(store.plan).toBeUndefined(); expect(store.result).toBeUndefined();
    expect(store.capabilities).toBeUndefined(); expect(store.recoveryId).toBe('');
  });

  it('history save retry never invokes generation and edit conflicts retain the current draft', async () => {
    const store = await ready(); store.restore({ ...document(), recordId: null, historySaved: false });
    vi.mocked(imagePromptApi.retrySave).mockResolvedValue(document()); await store.retrySave();
    expect(store.result?.historySaved).toBe(true); expect(imagePromptApi.confirm).not.toHaveBeenCalled();
    store.isEditing = true; store.editDraft = '未保存的修改'; vi.mocked(imagePromptApi.edit).mockRejectedValue(new Error('版本冲突'));
    await store.saveEdit(); expect(store.editDraft).toBe('未保存的修改'); expect(store.isEditing).toBe(true);
    expect(store.result?.optimizedPrompt).toBe(document().optimizedPrompt);
  });
});
