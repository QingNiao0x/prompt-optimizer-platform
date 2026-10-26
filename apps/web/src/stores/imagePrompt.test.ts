import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useImagePromptStore } from './imagePrompt';
import { useAuthStore } from './auth';
import { useTrialStore } from './trial';
import { imagePromptApi } from '@/services/imagePromptApi';
import { getTrialStatus, initializeTrialSession } from '@/services/trialApi';
import type { ImagePromptDocument, ImagePromptPlan, ImagePromptMediaAsset } from '@/types/imagePrompt';
import { imagePromptPlanProgress } from '@/features/imageprompt/planPresentation';
import type { TrialStatus } from '@/types/trial';

vi.mock('@/services/imagePromptApi', () => ({ imagePromptApi: {
  capabilities: vi.fn(), upload: vi.fn(), removeAsset: vi.fn(), extract: vi.fn(), plan: vi.fn(), confirm: vi.fn(),
  operation: vi.fn(), edit: vi.fn(), retrySave: vi.fn(), rewrite: vi.fn(),
  uploadMedia: vi.fn(), media: vi.fn(), mediaPreview: vi.fn(), removeMedia: vi.fn(), mediaCapabilities: vi.fn(),
} }));
vi.mock('@/services/trialApi', () => ({ getTrialStatus: vi.fn(), initializeTrialSession: vi.fn() }));
const guestQuota = (remaining: number): TrialStatus => ({ authenticated: false, available: true, quotas: {
  imagePrompt: { limit: 1, used: 1 - remaining, remaining },
  enhancement: { limit: 3, used: 0, remaining: 3 }, textPolish: { limit: 3, used: 0, remaining: 3 },
} });
const expiresAt = '2099-01-01T00:00:00Z';
const mediaAsset = (id = 'media'): ImagePromptMediaAsset => ({ id, name: `${id}.pptx`, kind: 'PPTX', status: 'READY',
  units: [1, 2].map(page => ({ id: `${id}-page-${page}`, page, timestampMs: null, width: 800, height: 600, text: '合成页面', previewUrl: '/private' })),
  warnings: [], expiresAt, totalUnits: 2, durationMs: null });
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
    // 既有 Plan 和历史用例显式使用正式身份；访客用例单独移除身份并模拟服务端额度。
    useAuthStore().user = { userId: 'fixture-user', tenantId: 'fixture-tenant', workspaceId: 'fixture-workspace',
      displayName: '图像测试用户', email: 'fixture@example.test', platformAdmin: false };
    vi.mocked(initializeTrialSession).mockResolvedValue();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fixture');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.mocked(imagePromptApi.removeAsset).mockResolvedValue();
    vi.mocked(imagePromptApi.removeMedia).mockResolvedValue();
    vi.mocked(imagePromptApi.uploadMedia).mockResolvedValue(mediaAsset());
    vi.mocked(imagePromptApi.mediaCapabilities).mockResolvedValue({ maxAssets: 6, maxFileBytes: 52428800,
      maxSelectedUnits: 12, maxVideoDurationMs: 30000, maxSlides: 100, videoAvailable: true });
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

  it('parses media only on request and sends only explicitly selected units with actual masks', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '保留参考配色制作产品视频'; store.outputType = 'VIDEO';
    store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE');
    expect(imagePromptApi.uploadMedia).not.toHaveBeenCalled(); expect(imagePromptApi.plan).not.toHaveBeenCalled();
    const key = store.mediaFiles[0]!.key; await store.parseMedia(key);
    expect(store.mediaFiles[0]!.units).toEqual([]); expect(store.startDisabledReason).toContain('选择要发送');
    const masks = [{ x: .1, y: .2, width: .3, height: .4 }];
    store.updateMedia(key, { units: [{ unitId: 'media-page-2', masks }] }); store.consent = true;
    vi.mocked(imagePromptApi.plan).mockResolvedValue(plan); await store.start();
    expect(imagePromptApi.plan).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({ outputType: 'VIDEO',
      materials: [{ assetId: 'media', track: 'REFERENCE', role: 'GENERAL', units: [{ unitId: 'media-page-2', masks }] }],
    }), expect.any(AbortSignal));
    expect(imagePromptApi.upload).not.toHaveBeenCalled();
  });

  it.each(['READY', 'PARTIAL'] as const)('keeps parsing and explicit unit selection distinct when polling reaches %s', async status => {
    vi.useFakeTimers();
    try {
      const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '借鉴参考版式制作新海报';
      const asset = { ...mediaAsset(), status };
      vi.mocked(imagePromptApi.uploadMedia).mockResolvedValue({ ...asset, status: 'PROCESSING', units: [] });
      vi.mocked(imagePromptApi.media).mockResolvedValue(asset);
      store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE'); const key = store.mediaFiles[0]!.key;
      expect(store.startDisabledReason).toContain('上传到平台解析预览');
      const parsing = store.parseMedia(key);
      await vi.advanceTimersByTimeAsync(700); await parsing;
      expect(store.mediaFiles[0]!.asset?.status).toBe(status);
      expect(store.mediaFiles[0]!.asset?.units).toHaveLength(2);
      expect(store.mediaFiles[0]!.units).toEqual([]);
      expect(store.startDisabledReason).toContain('已解析出 2');
      expect(store.startDisabledReason).toContain('选择页／帧与遮挡');
      store.updateMedia(key, { units: [{ unitId: 'media-page-2', masks: [{ x: .1, y: .2, width: .3, height: .4 }] }] });
      store.consent = true; expect(store.canStart).toBe(true);
      store.updateMedia(key, { units: [] });
      expect(store.canStart).toBe(false); expect(store.startDisabledReason).toContain('选择要发送');
      expect(imagePromptApi.plan).not.toHaveBeenCalled();
    } finally { vi.useRealTimers(); }
  });

  it('refreshes existing ready metadata without uploading again or losing chosen masks', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '制作海报';
    store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE'); const key = store.mediaFiles[0]!.key;
    await store.parseMedia(key);
    const units = [{ unitId: 'media-page-2', masks: [{ x: .1, y: .2, width: .3, height: .4 }] }];
    store.updateMedia(key, { units });
    vi.mocked(imagePromptApi.media).mockResolvedValue({ ...mediaAsset(), status: 'PARTIAL', warnings: ['一页无法读取'] });
    await store.parseMedia(key);
    expect(imagePromptApi.uploadMedia).toHaveBeenCalledOnce();
    expect(imagePromptApi.media).toHaveBeenCalledWith('media', expect.any(AbortSignal));
    expect(store.mediaFiles[0]!.asset?.status).toBe('PARTIAL');
    expect(store.mediaFiles[0]!.units).toEqual(units);
    store.consent = true; expect(store.canStart).toBe(true);
  });

  it('keeps the existing draft asset when a cancelled refresh returns late', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '制作海报';
    store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE'); const key = store.mediaFiles[0]!.key;
    await store.parseMedia(key); store.updateMedia(key, { units: [{ unitId: 'media-page-1', masks: [] }] });
    const refreshing = deferred<ImagePromptMediaAsset>(); vi.mocked(imagePromptApi.media).mockReturnValue(refreshing.promise);
    const waiting = store.parseMedia(key); store.cancelPending(); refreshing.resolve({ ...mediaAsset(), status: 'PARTIAL' });
    await waiting;
    expect(store.mediaFiles[0]!.asset?.status).toBe('READY'); expect(store.mediaFiles[0]!.parsing).toBe(false);
    expect(store.mediaFiles[0]!.units).toEqual([{ unitId: 'media-page-1', masks: [] }]);
    expect(imagePromptApi.removeMedia).not.toHaveBeenCalled();
  });

  it('distinguishes an empty parse result from a missing selection and blocks stale unit ids', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '制作海报';
    vi.mocked(imagePromptApi.uploadMedia).mockResolvedValue({ ...mediaAsset(), units: [], totalUnits: 0 });
    store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE'); const key = store.mediaFiles[0]!.key;
    await store.parseMedia(key);
    expect(store.startDisabledReason).toContain('未解析出可选');
    store.mediaFiles[0]!.asset = mediaAsset();
    store.updateMedia(key, { units: [{ unitId: 'removed-page', masks: [] }] }); store.consent = true;
    expect(store.startDisabledReason).toContain('所选页／帧已失效');
    await store.start(); expect(imagePromptApi.plan).not.toHaveBeenCalled();
  });

  it('blocks Plan while media is being parsed and ignores a late upload after identity changes', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '新产品海报';
    store.addMediaFiles([new File(['pixels'], 'new-product.png', { type: 'image/png' })], 'REPLACEMENT');
    const pending = deferred<ImagePromptMediaAsset>(); vi.mocked(imagePromptApi.uploadMedia).mockReturnValue(pending.promise);
    const parsing = store.parseMedia(store.mediaFiles[0]!.key); expect(store.busy).toBe(true);
    store.consent = true; await store.start(); expect(imagePromptApi.plan).not.toHaveBeenCalled();
    useAuthStore().user = { userId: 'other', tenantId: 'other', workspaceId: 'other', displayName: 'other', email: 'other@example.test', platformAdmin: false };
    pending.resolve(mediaAsset('old-private')); await parsing;
    expect(store.mediaFiles).toEqual([]); expect(store.busy).toBe(false);
    expect(imagePromptApi.removeMedia).toHaveBeenCalledWith('old-private');
  });

  it('keeps each model batch capacity while allowing bounded material selection and invalidates changed mappings', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '将参考主体换为新内容';
    store.capabilities!.models[0]!.maxImages = 1;
    store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE'); const key = store.mediaFiles[0]!.key;
    await store.parseMedia(key); store.updateMedia(key, { units: mediaAsset().units.map(unit => ({ unitId: unit.id, masks: [] })) }); store.consent = true;
    expect(store.selectedModel?.maxImages).toBe(1); expect(store.modelInputError).toBe('');
    vi.mocked(imagePromptApi.plan).mockResolvedValue(plan); await store.start(); expect(store.plan).toBeDefined();
    store.relations = [{ id: 'relationship', operation: 'REPLACE', referenceAssetId: 'media', referenceObject: '主体',
      replacementObject: '新产品', attributes: ['主体'], instruction: '保持构图', scope: '中央' }];
    expect(store.plan).toBeUndefined(); expect(imagePromptApi.plan).toHaveBeenCalledOnce();
    store.consent = true; store.updateMedia(key, { units: [{ unitId: 'media-page-1', masks: [{ x: 0, y: 0, width: 1, height: 1 }] }] });
    expect(store.consent).toBe(false);
  });

  it('requires reattachment of historical relationships without pretending history restores files', async () => {
    const store = await ready();
    store.restore({ ...document(), input: { ...document().input, outputType: 'PPT', relations: [{ id: 'old', operation: 'PRESERVE', referenceAssetId: 'expired', referenceObject: '版式', replacementObject: '', attributes: [], instruction: '保持', scope: '全部' }] } });
    expect(store.outputType).toBe('PPT'); expect(store.startDisabledReason).toContain('素材已失效');
  });

  it.each(['png', 'pptx', 'mp4', 'mov'])('adds and extracts reference %s without Plan after explicit parsing, selection and consent', async extension => {
    const store = await ready();
    store.addMediaFiles([new File(['synthetic'], `reference.${extension}`)], 'REFERENCE');
    expect(imagePromptApi.uploadMedia).not.toHaveBeenCalled();
    expect(store.startDisabledReason).toContain('上传到平台解析预览');
    const key = store.mediaFiles[0]!.key; await store.parseMedia(key);
    expect(store.startDisabledReason).toContain('选择页／帧与遮挡');
    const units = [{ unitId: 'media-page-2', masks: [{ x: .1, y: .2, width: .3, height: .4 }] }];
    store.updateMedia(key, { units });
    expect(store.startDisabledReason).toContain('请先勾选');
    store.consent = true;
    vi.mocked(imagePromptApi.extract).mockImplementation(async (_id, input) => ({ ...document(), mode: input.mode, input }));
    await store.start();
    expect(imagePromptApi.extract).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({
      mode: 'REFERENCE_RECONSTRUCTION', images: [], creationIntent: '', exactText: '', context: '', outputType: 'IMAGE',
      materials: [{ assetId: 'media', track: 'REFERENCE', role: 'GENERAL', units }],
    }), expect.any(AbortSignal));
    expect(imagePromptApi.plan).not.toHaveBeenCalled(); expect(imagePromptApi.confirm).not.toHaveBeenCalled();
    expect(imagePromptApi.upload).not.toHaveBeenCalled(); expect(store.result?.mode).toBe('REFERENCE_RECONSTRUCTION');
  });

  it('retains parsed ids, selection and masks across Plan toggles while only sending active reference materials directly', async () => {
    const store = await ready();
    store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE');
    const key = store.mediaFiles[0]!.key; await store.parseMedia(key);
    const units = [{ unitId: 'media-page-2', masks: [{ x: .1, y: .2, width: .3, height: .4 }] }];
    store.updateMedia(key, { units });
    store.addMediaFiles([new File(['movie'], 'replacement.mp4')], 'REPLACEMENT');
    store.consent = true;
    expect(store.activeMediaFiles.map(file => file.name)).toEqual(['reference.pptx']);
    expect(store.canStart).toBe(true);
    store.mode = 'CUSTOM_CREATION'; store.creationIntent = '保留参考布局，替换主体'; store.outputType = 'VIDEO';
    expect(store.consent).toBe(false);
    expect(store.activeMediaFiles).toHaveLength(2);
    expect(store.startDisabledReason).toContain('replacement.mp4');
    const target = { ...mediaAsset(), id: 'target', units: mediaAsset().units.map(unit => ({ ...unit, id: `target-${unit.id}` })) };
    vi.mocked(imagePromptApi.uploadMedia).mockResolvedValueOnce(target);
    await store.parseMedia(store.mediaFiles[1]!.key);
    store.updateMedia(store.mediaFiles[1]!.key, { units: [{ unitId: 'target-media-page-2', masks: [] }] });
    store.relations = [{ id: 'swap', operation: 'REPLACE', referenceAssetId: 'media', referenceObject: '原主体',
      replacementAssetId: 'target', replacementObject: '新主体', instruction: '替换主体', attributes: ['主体'], scope: '中央' }];
    store.consent = true; vi.mocked(imagePromptApi.plan).mockResolvedValue(plan); await store.start();
    expect(vi.mocked(imagePromptApi.plan).mock.calls[0]![1].materials?.map(item => item.track)).toEqual(['REFERENCE', 'REPLACEMENT']);
    store.mode = 'REFERENCE_RECONSTRUCTION';
    expect(store.plan).toBeUndefined(); expect(store.consent).toBe(false);
    expect(store.mediaFiles[0]!.asset?.id).toBe('media'); expect(store.mediaFiles[0]!.units).toEqual(units);
    expect(store.mediaFiles[1]!.asset?.id).toBe('target'); expect(store.mediaFiles[1]!.units).toHaveLength(1);
    expect(store.selectedUnitCount).toBe(1); expect(store.relations).toHaveLength(1);
    store.consent = true; vi.mocked(imagePromptApi.extract).mockResolvedValue(document()); await store.start();
    const input = vi.mocked(imagePromptApi.extract).mock.calls[0]![1];
    expect(input.materials?.map(item => item.assetId)).toEqual(['media']);
    expect(input.outputType).toBe('IMAGE'); expect(input.creationIntent).toBe(''); expect(input.relations ?? []).toEqual([]);
    expect(imagePromptApi.uploadMedia).toHaveBeenCalledTimes(2);
  });

  it('counts legacy images against the direct media unit budget and does not let hidden replacement media bypass legacy model limits', async () => {
    const store = await ready();
    store.mode = 'CUSTOM_CREATION';
    store.addMediaFiles([new File(['movie'], 'replacement.mp4')], 'REPLACEMENT');
    store.addFiles(Array.from({ length: 2 }, (_, index) => new File(['pixels'], `${index}.png`, { type: 'image/png' })));
    store.mode = 'REFERENCE_RECONSTRUCTION';
    store.capabilities!.models[0]!.maxImages = 1;
    expect(store.modelInputError).toContain('最多支持 1 张');
    store.removeFile(store.files[1]!.key);
    store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE');
    const file = store.mediaFiles[1]!; await store.parseMedia(file.key);
    const asset = file.asset!;
    asset.units = Array.from({ length: 12 }, (_, index) => ({ ...asset.units[0]!, id: `page-${index}` }));
    store.updateMedia(file.key, { units: asset.units.map(unit => ({ unitId: unit.id, masks: [] })) }); store.consent = true;
    expect(store.startDisabledReason).toContain('超过本次上限');
    expect(imagePromptApi.extract).not.toHaveBeenCalled();
  });

  it('requires login for media parsing in either mode without changing the single-image guest trial', async () => {
    const store = await ready(); useAuthStore().user = undefined;
    expect(() => store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE')).toThrow(/登录/);
    expect(imagePromptApi.uploadMedia).not.toHaveBeenCalled(); expect(store.mediaFiles).toEqual([]);
  });

  it('shows required progress without treating optional questions or custom-only answers as missing', () => {
    const questions: ImagePromptPlan = { ...plan, questions: [
      { id: 'required', type: 'SINGLE_CHOICE', question: '风格', options: [{ id: 'a', label: '插画' }, { id: 'b', label: '摄影' }], required: true },
      { id: 'optional', type: 'TEXT', question: '其他', options: [], required: false },
    ] };
    expect(imagePromptPlanProgress(questions, {}).missing.map(question => question.id)).toEqual(['required']);
    expect(imagePromptPlanProgress(questions, { required: { questionId: 'required', optionIds: [], customText: '委托 AI 设计' } })).toMatchObject({ required: 1, completed: 1, optional: 1, missing: [] });
    expect(imagePromptPlanProgress(plan, {})).toMatchObject({ required: 0, completed: 0, missing: [] });
  });

  it('retains user requirements after a material is removed and refuses a relationship outside selected coverage', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '新版宣传页';
    store.addMediaFiles([new File(['slides'], 'reference.pptx')], 'REFERENCE'); const key = store.mediaFiles[0]!.key;
    await store.parseMedia(key); store.updateMedia(key, { units: [{ unitId: 'media-page-1', masks: [] }] });
    store.relations = [{ id: 'keep', operation: 'PRESERVE', referenceAssetId: 'media', referenceUnitId: 'media-page-2',
      referenceObject: '标题区', replacementObject: '', attributes: ['布局'], instruction: '保持左右结构', scope: '首页' }];
    store.consent = true; expect(store.startDisabledReason).toContain('未选择的页');
    store.removeMediaFile(key); expect(store.relations[0]!.instruction).toBe('保持左右结构');
    expect(store.startDisabledReason).toContain('素材已失效');
  });

  it('validates a selected video segment before sending file bytes', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '产品视频';
    store.addMediaFiles([new File(['video'], 'clip.mp4', { type: 'video/mp4' })], 'REFERENCE'); const key = store.mediaFiles[0]!.key;
    store.updateMedia(key, { startMs: 30000, endMs: 10000 }); await store.parseMedia(key);
    expect(store.mediaFiles[0]!.error).toContain('有效的视频起止时间'); expect(imagePromptApi.uploadMedia).not.toHaveBeenCalled();
  });

  it('keeps a suitable generic downstream tool for each output type and normalizes known generic aliases', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION';
    store.outputType = 'VIDEO'; expect(store.targetTool).toBe('通用视频创作工具');
    store.outputType = 'PPT'; expect(store.targetTool).toBe('通用PPT工具');
    store.outputType = 'IMAGE'; expect(store.targetTool).toBe('通用文生图');
    store.targetTool = '通用视频生成工具'; store.outputType = 'PPT'; expect(store.targetTool).toBe('通用PPT工具');
  });

  it('preserves a user supplied downstream tool across output and Plan-mode changes', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.targetTool = '用户指定创作工具';
    store.outputType = 'VIDEO'; store.outputType = 'PPT'; store.mode = 'REFERENCE_RECONSTRUCTION';
    expect(store.targetTool).toBe('用户指定创作工具');
    store.mode = 'CUSTOM_CREATION'; store.outputType = 'IMAGE'; expect(store.targetTool).toBe('用户指定创作工具');
  });

  it('restores the image generic tool for reconstruction without losing the saved Plan output preference', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.outputType = 'VIDEO';
    store.mode = 'REFERENCE_RECONSTRUCTION'; expect(store.targetTool).toBe('通用文生图');
    store.addFiles([new File(['pixels'], 'fixture.png', { type: 'image/png' })]); store.consent = true;
    vi.mocked(imagePromptApi.extract).mockResolvedValue(document()); await store.start();
    const payload = vi.mocked(imagePromptApi.extract).mock.calls[0]![1];
    expect(payload.targetTool).toBe('通用文生图'); expect(payload).not.toHaveProperty('outputType');
    store.mode = 'CUSTOM_CREATION'; expect(store.outputType).toBe('VIDEO'); expect(store.targetTool).toBe('通用视频创作工具');
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

  it('keeps signed-in in-memory history edits and save retries on the account API', async () => {
    const store = await ready();
    const original = { ...document(), persistentHistory: false };
    store.restore(original); store.isEditing = true; store.editDraft = '账号内存历史的修改正文';
    const edited = { ...original, optimizedPrompt: store.editDraft, editRevision: 1 };
    vi.mocked(imagePromptApi.edit).mockResolvedValue(edited);
    await store.saveEdit();
    expect(imagePromptApi.edit).toHaveBeenCalledWith(original, edited.optimizedPrompt);
    expect(store.result?.editRevision).toBe(1);
    store.restore(edited);
    expect(store.result?.optimizedPrompt).toBe(edited.optimizedPrompt);
    store.restore({ ...original, recordId: null, historySaved: false });
    vi.mocked(imagePromptApi.retrySave).mockResolvedValue(original);
    await store.retrySave();
    expect(imagePromptApi.retrySave).toHaveBeenCalledWith(original.operationId);
    expect(store.result?.historySaved).toBe(true);
    expect(store.result?.persistentHistory).toBe(false);
    expect(imagePromptApi.extract).not.toHaveBeenCalled();
    expect(getTrialStatus).not.toHaveBeenCalled();
  });

  it('checks unknown or exhausted visitor quota before uploading an image', async () => {
    useAuthStore().user = undefined;
    const store = await ready(); store.addFiles([new File(['pixels'], 'fixture.png', { type: 'image/png' })]); store.consent = true;
    const pending = deferred<TrialStatus>(); vi.mocked(getTrialStatus).mockReturnValue(pending.promise);
    expect(store.canStart).toBe(false);
    const work = store.start();
    await vi.waitFor(() => expect(getTrialStatus).toHaveBeenCalledOnce());
    expect(imagePromptApi.upload).not.toHaveBeenCalled();
    pending.resolve(guestQuota(0)); await work;
    expect(store.canStart).toBe(false); expect(store.error).toContain('免费体验已用完');
    expect(imagePromptApi.upload).not.toHaveBeenCalled(); expect(imagePromptApi.extract).not.toHaveBeenCalled();
  });

  it('refreshes server quota after a failed accepted attempt and never restores it by starting a new task', async () => {
    useAuthStore().user = undefined;
    const store = await ready();
    vi.mocked(getTrialStatus).mockResolvedValueOnce(guestQuota(1)).mockResolvedValue(guestQuota(0));
    vi.mocked(imagePromptApi.extract).mockRejectedValue(new Error('模型暂时不可用'));
    store.addFiles([new File(['pixels'], 'fixture.png', { type: 'image/png' })]); store.consent = true;
    await store.start();
    expect(getTrialStatus).toHaveBeenCalledTimes(2); expect(useTrialStore().remaining('IMAGE_TO_PROMPT')).toBe(0);
    store.reset(); store.addFiles([new File(['pixels'], 'another.png', { type: 'image/png' })]); store.consent = true;
    await store.start();
    expect(imagePromptApi.upload).toHaveBeenCalledOnce(); expect(imagePromptApi.extract).toHaveBeenCalledOnce();
  });

  it('keeps visitor edits local and blocks Plan, history restore, save and historical rewrites', async () => {
    useAuthStore().user = undefined;
    const store = await ready(); vi.mocked(getTrialStatus).mockResolvedValue(guestQuota(1));
    await useTrialStore().initialize();
    store.mode = 'CUSTOM_CREATION'; store.creationIntent = '测试海报';
    await store.start(); store.plan = plan; await store.confirm();
    expect(imagePromptApi.plan).not.toHaveBeenCalled(); expect(imagePromptApi.confirm).not.toHaveBeenCalled();
    store.restore(document()); expect(store.result).toBeUndefined();
    store.result = { ...document(), recordId: null, historySaved: false, persistentHistory: false };
    store.isEditing = true; store.editDraft = '本页修改'; await store.saveEdit();
    expect(store.result.optimizedPrompt).toBe('本页修改'); await store.undo();
    expect(store.result.optimizedPrompt).toBe(document().optimizedPrompt);
    await store.retrySave(); await store.rewrite('整理表达');
    expect(imagePromptApi.edit).not.toHaveBeenCalled(); expect(imagePromptApi.retrySave).not.toHaveBeenCalled();
    expect(imagePromptApi.rewrite).not.toHaveBeenCalled();
  });

  it('clears the old Plan mode and private result when the user signs out', async () => {
    const store = await ready(); store.mode = 'CUSTOM_CREATION'; store.creationIntent = '已登录的构想';
    store.plan = plan; store.result = document();
    useAuthStore().user = undefined;
    expect(store.mode).toBe('REFERENCE_RECONSTRUCTION'); expect(store.plan).toBeUndefined();
    expect(store.result).toBeUndefined(); expect(store.creationIntent).toBe('');
  });
});
