import { computed, markRaw, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { imagePromptApi } from '@/services/imagePromptApi';
import { getApiErrorMessage } from '@/services/http';
import { useAuthStore } from './auth';
import { useTrialStore } from './trial';
import { isSafeRelativeFilePath, isSensitiveFile, getProjectFilePath } from '@/workers/fileReaderCore';
import type {
  ImagePromptAnswer, ImagePromptAsset, ImagePromptCapabilities, ImagePromptDocument,
  ImagePromptInput, ImagePromptMode, ImagePromptPlan, ImageReferenceRole,
  ImagePromptMediaAsset, ImagePromptMaterialRef, ImagePromptMaterialTrack, ImagePromptOutputType,
  ImagePromptReplacementRelation, ImagePromptMediaCapabilities,
  SourcePromptAsset, SourcePromptCapabilities, SourcePromptRole, SourcePromptTaskType,
} from '@/types/imagePrompt';

export interface SelectedImage {
  key: string; name: string; file: File | null; previewUrl: string; role: ImageReferenceRole; asset?: ImagePromptAsset;
}
export interface SelectedMedia {
  key: string; name: string; file: File | null; track: ImagePromptMaterialTrack; role: ImageReferenceRole;
  asset?: ImagePromptMediaAsset; units: ImagePromptMaterialRef['units']; parsing: boolean; error: string;
  startMs?: number; endMs?: number;
}

export interface SelectedSource {
  key: string; name: string; file: File | null; entries?: { path: string; content: string }[]; folderFiles?: File[];
  asset?: SourcePromptAsset; unitIds: string[]; track: ImagePromptMaterialTrack; role: SourcePromptRole;
  parsing: boolean; error: string;
}

/** 图像任务独立状态。文件仅在内存中；身份/输入版本变化后，旧成功、失败及 finally 都不能写回新任务。 */
export const useImagePromptStore = defineStore('imagePrompt', () => {
  const taskType = ref<'VISUAL' | SourcePromptTaskType>('VISUAL');
  const generalTask = computed(() => taskType.value !== 'VISUAL');
  const sourceFiles = ref<SelectedSource[]>([]);
  const sourceCapabilities = ref<SourcePromptCapabilities>();
  const mode = ref<ImagePromptMode>('REFERENCE_RECONSTRUCTION');
  const modelId = ref('');
  const creationIntent = ref('');
  const exactText = ref('');
  const context = ref('');
  const language = ref<'ZH' | 'EN'>('ZH');
  const targetTool = ref('通用文生图');
  const files = ref<SelectedImage[]>([]);
  const mediaFiles = ref<SelectedMedia[]>([]);
  const mediaCapabilities = ref<ImagePromptMediaCapabilities>();
  const outputType = ref<ImagePromptOutputType>('IMAGE');
  const relations = ref<ImagePromptReplacementRelation[]>([]);
  const capabilities = ref<ImagePromptCapabilities>();
  const plan = ref<ImagePromptPlan>();
  const answers = ref<Record<string, ImagePromptAnswer>>({});
  const result = ref<ImagePromptDocument>();
  const undoDocument = ref<ImagePromptDocument>();
  const editDraft = ref('');
  const isEditing = ref(false);
  const phase = ref<'IDLE' | 'UPLOADING' | 'PLANNING' | 'GENERATING' | 'SAVING' | 'RECOVERING'>('IDLE');
  const error = ref('');
  const cleanupWarning = ref('');
  const recoveryId = ref('');
  const recoveryPending = ref(false);
  const consent = ref(false);
  const isLoadingModels = ref(false);
  const auth = useAuthStore();
  const trial = useTrialStore();
  let revision = 0;
  let modelLoad = 0;
  let controller: AbortController | undefined;
  const mediaControllers = new Map<string, AbortController>();
  const mediaReading = computed(() => mediaFiles.value.some(file => file.parsing) || sourceFiles.value.some(file => file.parsing));
  const busy = computed(() => phase.value !== 'IDLE' || mediaReading.value);
  // 模式只改变本次使用范围，不清空临时素材；关闭Plan时保留替换素材草稿但不把它作为还原来源。
  const activeMediaFiles = computed(() => generalTask.value || mode.value === 'CUSTOM_CREATION' ? mediaFiles.value : mediaFiles.value.filter(file => file.track === 'REFERENCE'));
  const selectedUnitCount = computed(() => activeMediaFiles.value.reduce((count, file) => count + file.units.length, 0));
  const hasDraftChanges = computed(() => isEditing.value && editDraft.value !== result.value?.optimizedPrompt);
  const selectedModel = computed(() => capabilities.value?.models.find(m => m.id === modelId.value));
  // 两个工作台展示同一发布目录；能否处理当前图片单独校验，避免将文字模型藏成另一套模型列表。
  const availableModels = computed(() => capabilities.value?.models ?? []);
  const modelInputError = computed(() => {
    if (!selectedModel.value) return '';
    const selectedImages = files.value.length + selectedUnitCount.value;
    const images = !generalTask.value && mode.value === 'REFERENCE_RECONSTRUCTION' ? Math.max(1, selectedImages) : selectedImages;
    if (images && !selectedModel.value.vision) return '当前模型未开放图片输入，请选择视觉模型；无图创作可开启 Plan 并移除参考图。';
    // 多媒体按能力上限分批识别；旧图片接口仍保持单次模型图片数限制。
    if (!activeMediaFiles.value.length && images > selectedModel.value.maxImages) return `当前模型最多支持 ${selectedModel.value.maxImages} 张图片，请减少参考图或更换模型。`;
    return '';
  });
  /** 解析状态说明可用画面，units只代表用户已审阅并保存的外发范围；两者不能互相代替。 */
  const mediaInputError = computed(() => {
    for (const file of activeMediaFiles.value) {
      if (!file.asset) return `素材“${file.name}”尚未上传，请先点击“上传到平台解析预览”。`;
      if (file.asset.status === 'PROCESSING') return `素材“${file.name}”仍在解析，请点击“刷新解析状态”继续等待。`;
      if (!['READY', 'PARTIAL'].includes(file.asset.status)) return `素材“${file.name}”解析未完成或已取消，请刷新状态，或移除后重新选择。`;
    }
    for (const file of activeMediaFiles.value) {
      if (!file.asset!.units.length) return `素材“${file.name}”未解析出可选图片、页码或视频帧，请刷新解析状态，或移除后重新选择。`;
      if (!file.units.length) return `素材“${file.name}”已解析出 ${file.asset!.units.length} 个可选图片／页／帧；请点击“选择页／帧与遮挡”，选择要发送给模型的范围并保存。`;
      // 刷新可能改变可用范围；保留原选择供核对，但不把隐藏的失效单位发给模型。
      if (file.units.some(selected => !file.asset!.units.some(unit => unit.id === selected.unitId))) return `素材“${file.name}”所选页／帧已失效，请重新选择并保存识别范围。`;
    }
    return '';
  });
  /** 按处理/草稿保护、模型、输入、图片授权的顺序说明阻塞原因，与按钮可用条件保持同一来源。 */
  const startDisabledReason = computed(() => {
    if (busy.value) return '当前操作正在处理中，请稍候。';
    if (!auth.isAuthenticated) {
      if (generalTask.value) return '注册登录后可使用代码、文档与文件夹资料。';
      if (mode.value === 'CUSTOM_CREATION') return '注册登录后可开启 Plan 创作模式。';
      if (!trial.initialized) return trial.error || '正在确认免费体验额度，请稍候。';
      if (!trial.canUse('IMAGE_TO_PROMPT')) return trial.error || '图像转提示词的免费体验已用完，请注册登录后继续。';
    }
    if (hasDraftChanges.value) return '请先保存或取消当前结果的编辑，再生成新版本。';
    if (recoveryPending.value) return '上次请求的结果尚未确认，请先查询处理结果。';
    if (!selectedModel.value) return isLoadingModels.value ? '正在加载可用模型，请稍候。' : '请先选择一个可用模型。';
    if (modelInputError.value) return modelInputError.value;
    if (!generalTask.value && mode.value === 'REFERENCE_RECONSTRUCTION' && !activeMediaFiles.value.length && files.value.length !== 1) return '请先选择一张参考图片，或添加并审阅参考素材。';
    if ((generalTask.value || mode.value === 'CUSTOM_CREATION') && !creationIntent.value.trim()) return generalTask.value ? '请先填写本次任务目标，说明希望下游完成什么。' : '请先填写新图创作构想。';
    if (generalTask.value) {
      for (const file of sourceFiles.value) {
        if (!file.asset) return `资料“${file.name}”尚未解析，请先点击“解析并预览”。`;
        if (new Date(file.asset.expiresAt).getTime() <= Date.now()) return `资料“${file.name}”已过期，请移除后重新添加。`;
        if (!file.unitIds.length) return `资料“${file.name}”尚未选择内容，请点击“审阅与选择范围”并保存。`;
        if (file.unitIds.some(id => !file.asset!.units.some(unit => unit.id === id))) return `资料“${file.name}”所选内容已失效，请重新审阅。`;
      }
      const selected = sourceFiles.value.flatMap(file => file.asset?.units.filter(unit => file.unitIds.includes(unit.id)) ?? []);
      if (selected.length > (sourceCapabilities.value?.maxSelectedUnits ?? 40)) return '所选资料片段超过上限，请减少范围。';
      if (selected.reduce((count, unit) => count + unit.text.length, 0) > (sourceCapabilities.value?.maxSelectedCharacters ?? 120000)) return '所选资料文字超过上限，请减少范围。';
    }
    if (mediaInputError.value) return mediaInputError.value;
    if (activeMediaFiles.value.length && files.value.length + selectedUnitCount.value > (mediaCapabilities.value?.maxSelectedUnits ?? 12)) return '所选素材页/帧超过本次上限，请减少识别范围。';
    if ((generalTask.value || mode.value === 'CUSTOM_CREATION') && relations.value.some(relation => [relation.referenceAssetId, relation.replacementAssetId].some(id => id && ![...mediaFiles.value, ...(generalTask.value ? sourceFiles.value : [])].some(file => file.asset?.id === id)))) return '替换关系中的素材已失效，请重新选择来源或删除对应关系；历史正文仍可使用。';
    if ((generalTask.value || mode.value === 'CUSTOM_CREATION') && relations.value.some(relation => [[relation.referenceAssetId, relation.referenceUnitId], [relation.replacementAssetId, relation.replacementUnitId]].some(([assetId, unitId]) => unitId && !mediaFiles.value.find(file => file.asset?.id === assetId)?.units.some(unit => unit.unitId === unitId) && !(generalTask.value && sourceFiles.value.find(file => file.asset?.id === assetId)?.unitIds.includes(unitId))))) return '替换关系指向未选择的页／帧，请重新选择识别范围或调整关系。';
    if ((files.value.length || activeMediaFiles.value.length || generalTask.value) && !consent.value) return generalTask.value ? '请先勾选“我已检查资料隐私”确认框，再生成提示词。' : '请先勾选“我已检查图片隐私”确认框，再生成提示词。';
    return '';
  });
  const canStart = computed(() => !startDisabledReason.value);

  const dropPlan = (): void => { plan.value = undefined; answers.value = {}; };
  /** 变更只取消写回与旧确认，不自动发起模型调用，也不删除已成功结果。 */
  const invalidate = (): void => {
    revision += 1; controller?.abort(); controller = undefined; phase.value = 'IDLE';
    dropPlan(); error.value = ''; recoveryId.value = ''; recoveryPending.value = false;
  };
  watch([taskType, mode, modelId, creationIntent, exactText, context, language, targetTool, outputType], invalidate, { flush: 'sync' });
  watch([mode, outputType], () => {
    const defaults: Record<ImagePromptOutputType, string> = { IMAGE: '通用文生图', VIDEO: '通用视频创作工具', PPT: '通用PPT工具' };
    // 仅同步平台通用值（含本次开发早期别名）；自填工具保持原意，关闭Plan始终恢复图片通用值。
    const genericTools = [...Object.values(defaults), '通用视频生成工具', '通用 PPT 工具'];
    if (genericTools.includes(targetTool.value)) targetTool.value = defaults[mode.value === 'CUSTOM_CREATION' ? outputType.value : 'IMAGE'];
  }, { flush: 'sync' });
  // 模式或模型改变会改变外发范围/用途；保留素材与遮挡，授权须由用户重新核对。
  // 切回视觉任务也必须撤销通用任务的旧同意；不能用变更后的任务类型决定旧授权是否失效。
  watch([taskType, mode, modelId, creationIntent, exactText, context, language, targetTool], () => { consent.value = false; }, { flush: 'sync' });
  watch(relations, () => { invalidate(); if (generalTask.value) consent.value = false; }, { deep: true, flush: 'sync' });
  watch(taskType, () => {
    if (['通用文生图', '通用视频创作工具', '通用PPT工具', '通用开发工具', '通用文档工具', '通用 AI 工具'].includes(targetTool.value)) {
      targetTool.value = ({ VISUAL: '通用文生图', CODE: '通用开发工具', DOCUMENT: '通用文档工具', OTHER: '通用 AI 工具' })[taskType.value];
    }
  }, { flush: 'sync' });

  const releaseMedia = (file: SelectedMedia): void => {
    const current = revision, owner = identityKey();
    mediaControllers.get(file.key)?.abort(); mediaControllers.delete(file.key);
    if (file.asset) void imagePromptApi.removeMedia(file.asset.id).catch(() => { if (current === revision && owner === identityKey()) cleanupWarning.value = '临时素材撤销未确认，将按服务端期限清理。'; });
  };
  const addMediaFiles = (selected: File[], track: ImagePromptMaterialTrack): void => {
    if (!auth.isAuthenticated) throw new Error('请先登录后上传和解析多媒体素材。');
    if (files.value.length + mediaFiles.value.length + selected.length > (mediaCapabilities.value?.maxAssets ?? 6)) throw new Error('每次最多选择六份参考与替换素材。');
    for (const file of selected) {
      if (!/\.(png|jpe?g|mp4|mov|webm|pptx)$/i.test(file.name) || !file.size || file.size > (mediaCapabilities.value?.maxFileBytes ?? 50 * 1024 * 1024)) throw new Error('请选择不超过 50 MiB 的 JPEG、PNG、视频或 PPTX。');
      if (/\.(png|jpe?g)$/i.test(file.name) && file.size > 10 * 1024 * 1024) throw new Error('单张图片不能超过 10 MiB。');
    }
    invalidate(); consent.value = false;
    mediaFiles.value.push(...selected.map(file => ({ key: crypto.randomUUID(), name: file.name, file: markRaw(file), track,
      role: (track === 'REPLACEMENT' ? 'SUBJECT' : 'GENERAL') as ImageReferenceRole, units: [], parsing: false, error: '' })));
  };
  const removeMediaFile = (key: string): void => {
    const file = mediaFiles.value.find(item => item.key === key); if (!file) return;
    invalidate(); releaseMedia(file); mediaFiles.value = mediaFiles.value.filter(item => item.key !== key); consent.value = false;
    // 用户关系仍是有效创作要求；移除素材后保留原文，并要求重新绑定，避免悄悄丢失约束。
  };
  const updateMedia = (key: string, patch: Partial<Pick<SelectedMedia, 'role' | 'units' | 'startMs' | 'endMs'>>): void => {
    const file = mediaFiles.value.find(item => item.key === key); if (!file) return;
    invalidate(); Object.assign(file, patch); consent.value = false;
  };
  /** 有界轮询只查询本平台解析任务，不重复上传、不触发模型；取消后可以主动刷新继续查询。 */
  const parseMedia = async (key: string): Promise<void> => {
    const file = mediaFiles.value.find(item => item.key === key); if (!file || file.parsing || phase.value !== 'IDLE') return;
    if (/\.(mp4|mov|webm)$/i.test(file.name) && !file.asset) {
      const start = file.startMs ?? 0, end = file.endMs ?? 30000;
      if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end <= start || end - start > (mediaCapabilities.value?.maxVideoDurationMs ?? 30000)) {
        file.error = '请选择有效的视频起止时间，单次片段不超过 30 秒。'; return;
      }
    }
    invalidate(); consent.value = false; file.error = ''; file.parsing = true;
    const request = new AbortController(); mediaControllers.get(key)?.abort(); mediaControllers.set(key, request);
    const owner = identityKey();
    try {
      let asset = file.asset;
      const existingAsset = asset;
      if (!asset) {
        if (!file.file) throw new Error('素材已过期，请重新选择文件。');
        asset = await imagePromptApi.uploadMedia(file.file, request.signal, file.startMs, file.endMs);
      } else asset = await imagePromptApi.media(asset.id, request.signal);
      if (request.signal.aborted || owner !== identityKey() || !mediaFiles.value.includes(file)) {
        // 新上传的迟到资产需要撤销；已有资产的刷新取消不应撤销仍保留在草稿中的来源。
        if (!existingAsset) void imagePromptApi.removeMedia(asset.id).catch(() => undefined); return;
      }
      file.asset = asset;
      const deadline = Date.now() + 120_000;
      while (asset.status === 'PROCESSING') {
        if (Date.now() > deadline) throw new Error('素材仍在解析，可稍后刷新状态；不会自动重复上传。');
        await new Promise<void>((resolve, reject) => { const timer = setTimeout(done, 700);
          function done() { request.signal.removeEventListener('abort', abort); resolve(); }
          function abort() { clearTimeout(timer); request.signal.removeEventListener('abort', abort); reject(new DOMException('已取消等待', 'AbortError')); }
          request.signal.addEventListener('abort', abort, { once: true }); if (request.signal.aborted) abort(); });
        asset = await imagePromptApi.media(asset.id, request.signal);
        if (request.signal.aborted || owner !== identityKey() || !mediaFiles.value.includes(file)) return;
        file.asset = asset;
      }
      if (asset.status === 'FAILED' || asset.status === 'CANCELLED') throw new Error(asset.warnings.join('；') || '素材解析未完成，请移除后重新选择。');
    } catch (failure: unknown) {
      if (!request.signal.aborted && owner === identityKey() && mediaFiles.value.includes(file)) file.error = getApiErrorMessage(failure);
    } finally { if (mediaControllers.get(key) === request) { file.parsing = false; mediaControllers.delete(key); } }
  };
  const refreshMediaCapabilities = async (): Promise<void> => {
    if (!auth.isAuthenticated) return;
    const owner = identityKey();
    try { const value = await imagePromptApi.mediaCapabilities(); if (owner === identityKey()) mediaCapabilities.value = value; }
    catch { /* 旧部署仍可使用原有图片与纯文字能力，新素材解析会返回实际错误。 */ }
  };

  const releaseSource = (file: SelectedSource): void => {
    mediaControllers.get(file.key)?.abort(); mediaControllers.delete(file.key);
    const owner = identityKey();
    if (file.asset) void imagePromptApi.removeSource(file.asset.id).catch(() => { if (owner === identityKey()) cleanupWarning.value = '资料撤销未确认，将按临时有效期清理。'; });
  };
  /** 只加入浏览器草稿；文件原件在用户点击解析后才发送本平台。 */
  const addSourceFiles = (selected: File[], track: ImagePromptMaterialTrack = 'REFERENCE'): void => {
    if (!auth.isAuthenticated) throw new Error('请先登录后添加资料。');
    if (sourceFiles.value.length + selected.length > (sourceCapabilities.value?.maxAssets ?? 20)) throw new Error('每任务最多添加20份文字资料。');
    for (const file of selected) {
      if (!isSafeRelativeFilePath(file.name) || isSensitiveFile(file.name)) throw new Error('不能添加私钥、真实环境变量或受保护配置文件。');
      if (!file.size || file.size > (sourceCapabilities.value?.maxFileBytes ?? 50 * 1024 * 1024)) throw new Error('请选择非空且不超过50 MiB的资料。');
    }
    invalidate(); consent.value = false;
    sourceFiles.value.push(...selected.map(file => ({ key: crypto.randomUUID(), name: file.name, file: markRaw(file), track,
      role: (track === 'REPLACEMENT' ? 'REPLACEMENT' : 'BASELINE') as SourcePromptRole, unitIds: [], parsing: false, error: '' })));
  };
  const addSourceText = (name: string, entries: { path: string; content: string }[], folderFiles?: File[]): void => {
    if (!auth.isAuthenticated) throw new Error('请先登录后添加资料。');
    if (sourceFiles.value.length >= (sourceCapabilities.value?.maxAssets ?? 20)) throw new Error('每任务最多添加20份文字资料。');
    if (!entries.length && !folderFiles?.length) throw new Error('请先填写内容或勾选文件。');
    if (entries.some(entry => !isSafeRelativeFilePath(entry.path) || isSensitiveFile(entry.path))) throw new Error('资料中包含受保护或不安全的路径。');
    invalidate(); consent.value = false;
    sourceFiles.value.push({ key: crypto.randomUUID(), name, file: null, entries, folderFiles: folderFiles?.map(file => markRaw(file)),
      track: 'REFERENCE', role: 'BASELINE', unitIds: [], parsing: false, error: '' });
  };
  const updateSource = (key: string, patch: Partial<Pick<SelectedSource, 'unitIds' | 'role' | 'track'>>): void => {
    const source = sourceFiles.value.find(file => file.key === key); if (!source) return;
    invalidate(); Object.assign(source, patch); consent.value = false;
  };
  const removeSourceFile = (key: string): void => {
    const file = sourceFiles.value.find(item => item.key === key); if (!file) return;
    invalidate(); releaseSource(file); sourceFiles.value = sourceFiles.value.filter(item => item.key !== key); consent.value = false;
  };
  /** 文件夹仅发送用户明确勾选的文件；任何超限均拒绝整批，不以截断冒充完整解析。 */
  const parseSource = async (key: string): Promise<void> => {
    const file = sourceFiles.value.find(item => item.key === key); if (!file || file.parsing || phase.value !== 'IDLE') return;
    invalidate(); consent.value = false; file.error = ''; file.parsing = true;
    const request = new AbortController(), owner = identityKey(); mediaControllers.set(key, request);
    try {
      let entries = file.entries ?? [];
      if (file.folderFiles?.length) {
        entries = [];
        let characters = 0;
        for (const item of file.folderFiles) {
          if (request.signal.aborted) return;
          const path = getProjectFilePath(item);
          if (!isSafeRelativeFilePath(path) || isSensitiveFile(path)) throw new Error('目录中存在受保护路径，请重新选择范围。');
          const content = await item.text(); characters += content.length;
          if (content.includes('\0')) throw new Error(`文件“${path}”不是可读纯文本，请单独上传受支持的文档。`);
          if (content.length > 500000 || characters > 600000) throw new Error('所选文件超过单文件500000或集合600000字符限制，请减少范围；本次未上传任何内容。');
          entries.push({ path, content });
        }
      }
      const asset = file.file ? await imagePromptApi.uploadSource(file.file, request.signal)
        : await imagePromptApi.prepareSourceText(file.name, entries, request.signal);
      if (request.signal.aborted || owner !== identityKey() || !sourceFiles.value.includes(file)) {
        void imagePromptApi.removeSource(asset.id).catch(() => undefined); return;
      }
      file.asset = asset; file.unitIds = [];
    } catch (failure: unknown) { if (!request.signal.aborted && owner === identityKey() && sourceFiles.value.includes(file)) file.error = getApiErrorMessage(failure); }
    finally { if (mediaControllers.get(key) === request) { file.parsing = false; mediaControllers.delete(key); } }
  };
  const refreshSourceCapabilities = async (): Promise<void> => {
    if (!auth.isAuthenticated) return;
    const owner = identityKey();
    try { const value = await imagePromptApi.sourceCapabilities(); if (owner === identityKey()) sourceCapabilities.value = value; }
    catch { /* 旧部署保留视觉路径；新入口发起操作时展示实际错误。 */ }
  };

  const release = (file: SelectedImage): void => {
    const current = revision;
    if (file.previewUrl.startsWith('blob:')) URL.revokeObjectURL(file.previewUrl);
    if (file.asset) void imagePromptApi.removeAsset(file.asset.id).catch(() => {
      if (current === revision) cleanupWarning.value = '临时图片撤销未确认，将按服务端期限清理。';
    });
  };
  const addFiles = (selected: File[]): void => {
    const max = mode.value === 'REFERENCE_RECONSTRUCTION' ? 1 : 3;
    if (selected.length + files.value.length > max) throw new Error(mode.value === 'REFERENCE_RECONSTRUCTION' ? '直接增强只使用一张参考图，请先移除旧图。' : '最多选择三张参考图。');
    for (const file of selected) if (!['image/png', 'image/jpeg'].includes(file.type) || file.size === 0
      || file.size > (capabilities.value?.maxImageBytes ?? 10 * 1024 * 1024)) throw new Error('请选择不超过 10 MiB 的 JPEG 或 PNG 图片。');
    invalidate();
    files.value.push(...selected.map(file => ({ key: crypto.randomUUID(), name: file.name, file: markRaw(file), previewUrl: URL.createObjectURL(file), role: 'GENERAL' as const })));
    consent.value = false;
  };
  const removeFile = (key: string): void => {
    invalidate(); const file = files.value.find(f => f.key === key);
    files.value = files.value.filter(f => f.key !== key); if (file) release(file);
    consent.value = false;
  };
  const changeRole = (key: string, role: ImageReferenceRole): void => {
    invalidate(); const file = files.value.find(f => f.key === key); if (file) file.role = role;
    consent.value = false;
  };

  const refreshModels = async (): Promise<void> => {
    const load = ++modelLoad, identity = identityKey(); isLoadingModels.value = true;
    try {
      const value = await imagePromptApi.capabilities();
      if (load !== modelLoad || identity !== identityKey()) return;
      capabilities.value = value;
      if (!value.models.some(m => m.id === modelId.value)) modelId.value = value.models.find(m => m.vision)?.id ?? value.models[0]?.id ?? '';
    } catch (failure: unknown) { if (load === modelLoad && identity === identityKey()) error.value = getApiErrorMessage(failure); }
    finally { if (load === modelLoad) isLoadingModels.value = false; }
  };

  /** 上传只发生在明确生成/Plan 操作后；取消的上传即使返回资产，也只撤销，不重新加入任务。 */
  const prepare = async (current: number, signal: AbortSignal): Promise<ImagePromptInput> => {
    for (const file of [...files.value]) {
      if (file.asset && new Date(file.asset.expiresAt).getTime() > Date.now()) continue;
      if (!file.file) throw new Error('参考图已过期，请重新选择图片；已有正文仍可使用。');
      phase.value = 'UPLOADING';
      const asset = await imagePromptApi.upload(file.file, signal);
      if (current !== revision) { void imagePromptApi.removeAsset(asset.id).catch(() => undefined); throw new Error('本次输入已改变。'); }
      file.asset = asset;
    }
    const materials = activeMediaFiles.value.map(file => ({ assetId: file.asset!.id, track: file.track, role: file.role,
      units: file.units.map(unit => ({ unitId: unit.unitId, masks: unit.masks.map(mask => ({ ...mask })) })) }));
    const input: ImagePromptInput = {
      mode: mode.value, modelId: modelId.value,
      images: files.value.map(f => ({ assetId: f.asset!.id, role: f.role })),
      creationIntent: generalTask.value || mode.value === 'CUSTOM_CREATION' ? creationIntent.value : '',
      exactText: generalTask.value || mode.value === 'CUSTOM_CREATION' ? exactText.value : '',
      context: generalTask.value || mode.value === 'CUSTOM_CREATION' ? context.value : '', language: language.value, targetTool: targetTool.value,
      ...(generalTask.value || mode.value === 'CUSTOM_CREATION' ? { outputType: outputType.value, materials,
        relations: relations.value.map(relation => ({ ...relation, attributes: [...relation.attributes] })) }
        : materials.length ? { outputType: 'IMAGE' as const, materials } : {}),
    };
    if (generalTask.value) {
      input.sourceTask = { taskType: taskType.value as SourcePromptTaskType, sources: sourceFiles.value.map(file => ({
        assetId: file.asset!.id, unitIds: [...file.unitIds], track: file.track, role: file.role,
      })) };
      const receipt = await imagePromptApi.sourceConsent(input, signal);
      if (current !== revision || signal.aborted) throw new Error('本次输入已改变，请重新核对外发范围。');
      input.sourceTask.consentId = receipt.id;
    }
    return input;
  };

  const accept = (document: ImagePromptDocument): void => {
    // 撤销只属于同一记录的正文编辑，不能把另一个任务/模式的结果覆盖到当前记录。
    undoDocument.value = document.recordId && document.recordId === result.value?.recordId
      && document.editRevision > result.value.editRevision ? result.value : undefined;
    result.value = document;
    editDraft.value = document.optimizedPrompt; isEditing.value = false; dropPlan();
    recoveryId.value = ''; recoveryPending.value = false;
  };
  const failed = (failure: unknown, current: number, id: string): void => {
    if (current !== revision) return;
    error.value = getApiErrorMessage(failure);
    // 不自动重放模型操作；保留请求标识供查询，确认结果后再允许明确的新调用。
    if (recoveryId.value === id) recoveryPending.value = true;
  };
  /** 发送模型请求前记录幂等编号；导航取消等待不代表服务端取消或未计费。 */
  const dispatched = (id: string): void => { recoveryId.value = id; recoveryPending.value = true; };
  const acceptPlan = (value: ImagePromptPlan): void => {
    const previous = plan.value, retained = answers.value;
    plan.value = value;
    // 只复用完整题目相同的实际回答，题号相同但含义/选项变化时不冒充已确认。
    answers.value = Object.fromEntries(value.questions.map(q => [q.id,
      previous?.questions.some(old => JSON.stringify(old) === JSON.stringify(q)) && retained[q.id]
        ? retained[q.id] : { questionId: q.id, optionIds: [], customText: '' }]));
    recoveryId.value = ''; recoveryPending.value = false;
  };
  const start = async (): Promise<void> => {
    const identityRevision = revision;
    // 额度检查必须先于图片上传；页面重建和“新建任务”不会重置服务端额度。
    if (!auth.isAuthenticated) {
      await trial.initialize();
      if (identityRevision !== revision) return;
      if (!trial.canUse('IMAGE_TO_PROMPT')) {
        error.value = trial.error || '图像转提示词的免费体验已用完，请注册登录后继续。'; return;
      }
    }
    if (!canStart.value) return;
    const guestRequest = !auth.isAuthenticated;
    const current = revision, id = crypto.randomUUID();
    controller = new AbortController(); phase.value = 'UPLOADING'; error.value = '';
    try {
      const input = await prepare(current, controller.signal);
      dispatched(id);
      if (mode.value === 'CUSTOM_CREATION') {
        phase.value = 'PLANNING'; const value = await imagePromptApi.plan(id, input, controller.signal);
        if (current !== revision) return;
        acceptPlan(value);
      } else {
        phase.value = 'GENERATING'; const value = await imagePromptApi.extract(id, input, controller.signal);
        if (current === revision) accept(value);
      }
    } catch (failure: unknown) { failed(failure, current, id); }
    finally {
      // 成功、失败和网络结果不确定都重新读取后端计数，不在浏览器猜测扣额或退款。
      if (guestRequest) await trial.refresh();
      if (current === revision) phase.value = 'IDLE';
    }
  };
  const confirm = async (): Promise<void> => {
    if (!auth.isAuthenticated) { error.value = '注册登录后可开启 Plan 创作模式。'; return; }
    if (!plan.value || busy.value || hasDraftChanges.value || recoveryPending.value) return;
    if (new Date(plan.value.expiresAt).getTime() <= Date.now()) { error.value = '计划已过期，请重新梳理创作意图；当前回答可以先复制保留。'; return; }
    for (const q of plan.value.questions) {
      const answer = answers.value[q.id];
      if (q.required && (!answer || !answer.optionIds.length && !answer.customText.trim())) { error.value = '请回答必要问题，也可以明确委托 AI 提出方案。'; return; }
    }
    const current = revision, id = crypto.randomUUID(); controller = new AbortController(); phase.value = 'GENERATING'; error.value = '';
    dispatched(id);
    try {
      const value = await imagePromptApi.confirm(plan.value, id, Object.values(answers.value), controller.signal);
      if (current === revision) accept(value);
    } catch (failure: unknown) { failed(failure, current, id); }
    finally { if (current === revision) phase.value = 'IDLE'; }
  };
  const recover = async (): Promise<void> => {
    if (!recoveryId.value || busy.value) return;
    const current = revision; phase.value = 'RECOVERING';
    try {
      const value = await imagePromptApi.operation(recoveryId.value);
      if (current !== revision) return;
      if (value.document) accept(value.document);
      else if (value.plan) acceptPlan(value.plan);
      else if (value.status !== 'RUNNING') { recoveryPending.value = false; error.value = value.message || '请求未完成，请核对历史后再明确发起新的生成。'; }
      else error.value = '请求仍在处理，请稍后再次查询；不会自动重复调用模型。';
    } catch (failure: unknown) { if (current === revision) error.value = getApiErrorMessage(failure); }
    finally {
      if (!auth.isAuthenticated) await trial.refresh();
      if (current === revision) phase.value = 'IDLE';
    }
  };

  const saveEdit = async (text = editDraft.value): Promise<void> => {
    if (!result.value || busy.value || !text.trim()) return;
    // 只有访客正文留在本页；正式账号的内存历史仍须写回服务端并维护修订号。
    if (!auth.isAuthenticated) {
      undoDocument.value = result.value;
      result.value = { ...result.value, optimizedPrompt: text };
      editDraft.value = text; isEditing.value = false; return;
    }
    if (!result.value.recordId) return;
    const current = revision; phase.value = 'SAVING'; error.value = '';
    try {
      const value = await imagePromptApi.edit(result.value, text);
      if (current === revision) accept(value);
    } catch (failure: unknown) { if (current === revision) error.value = getApiErrorMessage(failure); }
    finally { if (current === revision) phase.value = 'IDLE'; }
  };
  const undo = async (): Promise<void> => {
    if (!undoDocument.value || !result.value || busy.value) return;
    isEditing.value = true; editDraft.value = undoDocument.value.optimizedPrompt;
    await saveEdit(editDraft.value);
  };
  const retrySave = async (): Promise<void> => {
    if (!result.value || busy.value || !auth.isAuthenticated) return;
    const current = revision; phase.value = 'SAVING';
    try { const value = await imagePromptApi.retrySave(result.value.operationId); if (current === revision) { result.value = value; error.value = ''; } }
    catch (failure: unknown) { if (current === revision) error.value = getApiErrorMessage(failure); }
    finally { if (current === revision) phase.value = 'IDLE'; }
  };
  const rewrite = async (instruction: string): Promise<void> => {
    if (!auth.isAuthenticated) { error.value = '注册登录后可使用历史正文再次优化。'; return; }
    if (!result.value?.recordId || busy.value || hasDraftChanges.value || !instruction.trim() || recoveryPending.value) return;
    const current = revision, id = crypto.randomUUID(); controller = new AbortController(); phase.value = 'GENERATING'; error.value = '';
    dispatched(id);
    try { const value = await imagePromptApi.rewrite(result.value, id, instruction, controller.signal); if (current === revision) accept(value); }
    catch (failure: unknown) { failed(failure, current, id); }
    finally { if (current === revision) phase.value = 'IDLE'; }
  };
  const restore = (document: ImagePromptDocument): void => {
    if (!auth.isAuthenticated) { error.value = '历史记录仅限注册登录后查看。'; return; }
    invalidate(); files.value.forEach(release); files.value = []; mediaFiles.value.forEach(releaseMedia); mediaFiles.value = []; sourceFiles.value.forEach(releaseSource); sourceFiles.value = [];
    taskType.value = document.input.sourceTask?.taskType ?? 'VISUAL';
    mode.value = document.mode; modelId.value = document.modelId;
    creationIntent.value = document.input.creationIntent; exactText.value = document.input.exactText;
    context.value = document.input.context; language.value = document.input.language; targetTool.value = document.input.targetTool;
    outputType.value = document.input.outputType ?? 'IMAGE'; relations.value = (document.input.relations ?? []).map(relation => ({ ...relation, attributes: [...relation.attributes] }));
    result.value = document; undoDocument.value = undefined; editDraft.value = document.optimizedPrompt; isEditing.value = false;
    // 历史仅恢复文字与来源说明，不把过期 imageId 或摘要伪装成再次读图。
    consent.value = false;
  };
  const reset = (): void => {
    invalidate(); files.value.forEach(release); files.value = []; mediaFiles.value.forEach(releaseMedia); mediaFiles.value = []; sourceFiles.value.forEach(releaseSource); sourceFiles.value = []; taskType.value = 'VISUAL'; relations.value = []; outputType.value = 'IMAGE'; result.value = undefined; undoDocument.value = undefined;
    isEditing.value = false; editDraft.value = ''; creationIntent.value = ''; exactText.value = ''; context.value = ''; consent.value = false;
  };
  const identityKey = (): string => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|');
  watch(identityKey, () => {
    modelLoad += 1; isLoadingModels.value = false;
    reset(); capabilities.value = undefined; mediaCapabilities.value = undefined; sourceCapabilities.value = undefined; modelId.value = ''; cleanupWarning.value = '';
    if (!auth.isAuthenticated) mode.value = 'REFERENCE_RECONSTRUCTION';
  }, { flush: 'sync' });
  /** 离开页面仅停止旧请求写回，保留当前构想、实际回答和编辑稿供返回时继续。 */
  const cancelPending = (): void => { revision += 1; controller?.abort(); controller = undefined; phase.value = 'IDLE'; mediaControllers.forEach(value => value.abort()); mediaControllers.clear(); [...mediaFiles.value, ...sourceFiles.value].forEach(file => { file.parsing = false; }); };
  return { taskType, generalTask, sourceFiles, sourceCapabilities, addSourceFiles, addSourceText, updateSource, removeSourceFile, parseSource, refreshSourceCapabilities, mode, modelId, creationIntent, exactText, context, language, targetTool, files, capabilities, selectedModel, availableModels, modelInputError,
    plan, answers, result, undoDocument, editDraft, isEditing, phase, error, cleanupWarning, recoveryId, recoveryPending, consent,
    isLoadingModels, busy, hasDraftChanges, canStart, startDisabledReason, addFiles, removeFile, changeRole, refreshModels, start, confirm,
    recover, saveEdit, retrySave, rewrite, undo, restore, reset, invalidate, cancelPending,
    outputType, mediaFiles, activeMediaFiles, mediaCapabilities, relations, selectedUnitCount, mediaReading, addMediaFiles, removeMediaFile, updateMedia, parseMedia, refreshMediaCapabilities };
});
