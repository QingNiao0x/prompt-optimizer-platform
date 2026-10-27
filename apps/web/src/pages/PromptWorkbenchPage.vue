<script setup lang="ts">
import { optimizationCompletionNotice } from '@/features/optimization/processingStatus';
import ContextVisionReviewDialog from '@/components/context/ContextVisionReviewDialog.vue';
import { useContextVisionStore } from '@/stores/contextVision';
import { WarningFilled } from '@element-plus/icons-vue';
import { ElAlert, ElButton, ElInput, ElMessage, ElMessageBox } from 'element-plus';
import { storeToRefs } from 'pinia';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';

import ContextPanel from '@/components/context/ContextPanel.vue';
import ContextUploadConfirmDialog, { type UploadCandidate } from '@/components/context/ContextUploadConfirmDialog.vue';
import PlanModeIntroDialog from '@/components/prompt/PlanModeIntroDialog.vue';
import PlanQuestionDialog from '@/components/prompt/PlanQuestionDialog.vue';
import IntentComposer from '@/components/prompt/IntentComposer.vue';
import ResultPanel from '@/components/prompt/ResultPanel.vue';
import WorkbenchFlowHeader from '@/components/prompt/WorkbenchFlowHeader.vue';
import GuestTrialBanner from '@/components/auth/GuestTrialBanner.vue';
import { useAuthStore } from '@/stores/auth';
import { useTrialStore } from '@/stores/trial';
import { withRelativePath, type DroppedFileCollection } from '@/composables/fileDrop';
import { usePlanModePreference } from '@/composables/usePlanModePreference';
import { useProjectIndex } from '@/composables/useProjectIndex';
import { useProjectFiles } from '@/composables/useProjectFiles';
import { buildRefinedContextQuery } from '@/features/optimization/optimizationRequest';
import { useOptimizationStore } from '@/stores/optimization';
import { recordPlanningEvent } from '@/services/planningMetrics';
import { useProjectContextSettingsStore } from '@/stores/projectContextSettings';
import type { ContextFileInput, PlanConfirmation, PromptSection } from '@/types/api';
import {
  collectCandidateFiles,
  getProjectFilePath,
  MAX_FILES,
  MAX_READ_CHARS_PER_FILE,
  MAX_TOTAL_CHARACTERS,
  shouldUseTemporaryDocumentIndex,
} from '@/workers/fileReaderCore';

const store = useOptimizationStore();
const auth = useAuthStore();
const trial = useTrialStore();
const route = useRoute();
const router = useRouter();
const projectContextSettingsStore = useProjectContextSettingsStore();
const {
  settings: projectContextSettings,
  effectiveIndexLimits,
} = storeToRefs(projectContextSettingsStore);
const {
  rawPrompt,
  customDescription,
  files,
  promptFormat,
  availableModels,
  selectedModelId,
  isLoadingModels,
  modelLoadError,
  projectIndex,
  contextRetrieval,
  contextSnapshot,
  contextTransmission,
  contextCleanupWarning,
  resultDraft,
  plan,
  result,
  requestId,
  errorMessage,
  isAnalyzing,
  isPlanning,
  isOptimizing,
  isPreparingContext,
  canOptimize,
  canUndoResult,
  canReuseGenerationSource,
} = storeToRefs(store);

const contextVision = useContextVisionStore();
const {
  isReading,
  warnings,
  progress,
  selectFileArray,
  reset: resetProjectFiles,
} = useProjectFiles({ reviewVision: (status, signal) => contextVision.review(status, signal, store.selectedModelId) });
const {
  isSupported: supportsDirectoryPicker,
  isSelecting: isSelectingDirectory,
  isIndexing,
  isPausing,
  progress: indexProgress,
  errorMessage: indexErrorMessage,
  indexDirectory,
  resumeIndexing,
  refreshIndex,
  clearPersistedProjectSelection,
  resetPageState: resetProjectIndexPageState,
  pauseIndexing,
  cancelIndexing,
} = useProjectIndex();

const planDialogVisible = ref(false);
const planIntroVisible = ref(false);
const pendingPrompt = ref('');
const recoveryRevision = ref(0);
const isRecoveringPlan = ref(false);
const isClearingIndex = ref(false);
const uploadCandidates = ref<UploadCandidate[]>([]);
const omittedUploadCandidates = ref(0);
const uploadConfirmVisible = ref(false);
const selectionWarnings = ref<string[]>([]);
let pageLifecycleVersion = 0;
let contextSelectionVersion = 0;
const {
  enabled: preferredPlanMode,
  introSeen: planIntroSeen,
  acceptIntro: acceptPlanIntro,
  dismissIntro: dismissPlanIntro,
} = usePlanModePreference();
const planModeEnabled = computed(() => auth.isAuthenticated && preferredPlanMode.value);

// 分析按钮需要抽取项目画像，而不是只围绕某个业务提示词检索。
// 将常见分层目录放在查询前部，可以让本地索引同时召回构建清单和代表性模块源码。
const PROJECT_OVERVIEW_RETRIEVAL_QUERY = [
  'pom package controller service repository domain entity view page component store router',
  '技术栈 依赖 项目结构 配置 功能模块 模块职责',
].join(' ');

const contextWarnings = computed(() => Array.from(new Set([
  ...warnings.value,
  ...selectionWarnings.value,
  ...(contextSnapshot.value?.warnings ?? []),
  ...(indexErrorMessage.value ? [indexErrorMessage.value] : []),
  ...(contextCleanupWarning.value ? [contextCleanupWarning.value] : []),
  ...(contextRetrieval.value?.references?.flatMap((reference) => {
    if (reference.status === 'NO_MATCHING_CHUNKS') return [
      `本次索引未召回引用路径 ${reference.reference} 的可用片段；这不代表该路径不存在。`,
    ];
    if (reference.status === 'OUTSIDE_SELECTION_BUDGET') return [
      `已找到引用路径 ${reference.reference}，但本次上下文预算未包含其片段。`,
    ];
    return [];
  }) ?? []),
])));
const hasContextSource = computed(() => files.value.length > 0
  || projectIndex.value?.status === 'READY');
type WorkbenchStage = 'context' | 'clarify' | 'result';
type WorkbenchPane = 'context' | 'intent' | 'result';

const MOBILE_PANE_QUERY = '(max-width: 900px)';
const mobilePanes = [
  { id: 'context', label: '上下文', ariaLabel: '项目上下文' },
  { id: 'intent', label: '写想法', ariaLabel: '写想法' },
  { id: 'result', label: '结果', ariaLabel: '增强结果' },
] as const;

const isNarrowWorkbench = ref(false);
const activeMobilePane = ref<WorkbenchPane>('intent');
let mobilePaneQuery: MediaQueryList | undefined;

const syncNarrowWorkbench = (): void => {
  isNarrowWorkbench.value = Boolean(mobilePaneQuery?.matches);
};

const showContextPane = computed(() => !isNarrowWorkbench.value || activeMobilePane.value === 'context');
const showIntentPane = computed(() => !isNarrowWorkbench.value || activeMobilePane.value === 'intent');
const showResultPane = computed(() => !isNarrowWorkbench.value || activeMobilePane.value === 'result');

const workbenchStage = computed<WorkbenchStage>(() => {
  if (result.value) {
    return 'result';
  }
  if (isPlanning.value || isOptimizing.value || plan.value || planDialogVisible.value) {
    return 'clarify';
  }
  return 'context';
});

const handleIndexProject = async (): Promise<void> => {
  await runProjectIndexOperation('FULL');
};

const handleResumeProject = async (): Promise<void> => {
  await runProjectIndexOperation('RESUME');
};

const handleRefreshProject = async (): Promise<void> => {
  await runProjectIndexOperation('INCREMENTAL');
};

const runProjectIndexOperation = async (
  mode: 'FULL' | 'INCREMENTAL' | 'RESUME',
): Promise<void> => {
  const currentLifecycle = pageLifecycleVersion;
  uploadCandidates.value = [];
  omittedUploadCandidates.value = 0;
  selectionWarnings.value = [];
  try {
    const options = {
      limits: effectiveIndexLimits.value,
      retention: projectContextSettings.value.retention,
      autoCleanupDays: projectContextSettings.value.autoCleanupDays,
      onDocumentsDiscovered: (documents: UploadCandidate[], omitted: number): void => {
        if (currentLifecycle !== pageLifecycleVersion) return;
        // Worker 仅交回附件引用；先显示索引结果，再请用户决定上传哪些文档。
        uploadCandidates.value = documents
          .filter((document) => !files.value.some((file) => file.path === document.path))
          .map((document) => ({
            path: document.path,
            file: withRelativePath(document.file, document.path),
          }));
        omittedUploadCandidates.value = omitted;
      },
    };
    const summary = mode === 'FULL'
      ? await indexDirectory(options)
      : mode === 'RESUME'
        ? await resumeIndexing(options)
        : await refreshIndex(options);
    if (!summary || currentLifecycle !== pageLifecycleVersion) {
      return;
    }
    store.setProjectIndex(summary);
    if (uploadCandidates.value.length > 0) uploadConfirmVisible.value = true;
    if (omittedUploadCandidates.value > 0) {
      selectionWarnings.value = [`已跳过 ${omittedUploadCandidates.value} 个超出大小、数量限制或安全规则的文档。`];
    }
    if (summary.status === 'PAUSED') {
      ElMessage.info(`索引已暂停，已保存 ${summary.discoveredFiles} 个文件的扫描检查点。`);
      return;
    }
    ElMessage.success(
      mode === 'FULL'
        ? `已为 ${summary.indexedFiles} 个源码文件建立本地索引。`
        : `增量索引完成：新增 ${summary.addedFiles}，更新 ${summary.updatedFiles}，删除 ${summary.removedFiles}。`,
    );
    if (summary.scanLimitReached || summary.storageLimitReached) {
      ElMessage.warning(
        summary.scanLimitReached
          ? '项目已达到当前模式的扫描文件上限，请检查项目上下文设置。'
          : '本地索引已达到可用容量上限，后续文件只保留了元数据。',
      );
    }
  } catch (error: unknown) {
    if (currentLifecycle !== pageLifecycleVersion) {
      return;
    }
    ElMessage.error(error instanceof Error ? error.message : '本地项目索引失败，请重试。');
  }
};

const handleCancelIndex = async (): Promise<void> => {
  if (isClearingIndex.value) {
    return;
  }
  isClearingIndex.value = true;
  const currentLifecycle = pageLifecycleVersion;
  cancelFileSelection();
  const deletion = cancelIndexing();
  store.clearFiles();
  try {
    await deletion;
    if (currentLifecycle !== pageLifecycleVersion) return;
    ElMessage.success('本地项目索引已删除。');
  } catch {
    if (currentLifecycle !== pageLifecycleVersion) return;
    ElMessage.error('索引已从当前上下文移除，但本地数据清理失败，请在设置页重试。');
  } finally {
    if (currentLifecycle === pageLifecycleVersion) isClearingIndex.value = false;
  }
};

const handleClearContextFiles = async (): Promise<void> => {
  if (isClearingIndex.value) {
    return;
  }
  const shouldDeleteIndex = Boolean(projectIndex.value || isIndexing.value);
  const currentLifecycle = pageLifecycleVersion;
  cancelFileSelection();
  const deletion = shouldDeleteIndex ? cancelIndexing() : Promise.resolve();
  store.clearFiles();
  if (!shouldDeleteIndex) {
    return;
  }
  isClearingIndex.value = true;
  try {
    await deletion;
  } catch {
    if (currentLifecycle !== pageLifecycleVersion) return;
    ElMessage.error('上下文已清空，但本地索引数据清理失败，请在设置页重试。');
  } finally {
    if (currentLifecycle === pageLifecycleVersion) isClearingIndex.value = false;
  }
};

/** 所有文件选择与拖入共用安全预检；后一次选择会取消尚未完成的前一次，确认后的上传不受影响。需后端解析的文件在确认前绝不上传。 */
const handleSelectedContextFiles = async (selected: readonly File[]): Promise<void> => {
  if (selected.length === 0) return;
  const currentLifecycle = pageLifecycleVersion;
  const selectionVersion = ++contextSelectionVersion;
  const isCurrentSelection = (): boolean =>
    currentLifecycle === pageLifecycleVersion && selectionVersion === contextSelectionVersion;
  try {
    const { files: candidates, stats } = await collectCandidateFiles(selected, MAX_FILES);
    if (!isCurrentSelection()) {
      return;
    }
    const skipped = stats.pathIgnored + stats.sensitive + stats.unsupported + stats.oversized;
    selectionWarnings.value = skipped > 0
      ? [`已跳过 ${skipped} 个不支持、超限或敏感文件；这些文件不会上传。`]
      : [];
    const inlineBudget = Math.min(MAX_READ_CHARS_PER_FILE,
      Math.max(1_024, Math.floor(MAX_TOTAL_CHARACTERS / Math.max(1, candidates.length))));
    const immediate = candidates.filter((file) => !shouldUseTemporaryDocumentIndex(file, inlineBudget));
    const documents = candidates.filter((file) => shouldUseTemporaryDocumentIndex(file, inlineBudget));
    if (immediate.length > 0) await addSelectedFiles(immediate, currentLifecycle, selectionVersion);
    if (documents.length > 0 && isCurrentSelection()) {
      uploadCandidates.value = documents.map((file) => ({ path: getProjectFilePath(file), file }));
      omittedUploadCandidates.value = 0;
      uploadConfirmVisible.value = true;
    }
    if (candidates.length === 0 && isCurrentSelection()) {
      ElMessage.warning('没有找到可读取的文件。');
    }
  } catch (error: unknown) {
    if (!isCurrentSelection()) {
      return;
    }
    ElMessage.error(error instanceof Error ? error.message : '文件读取失败，请重新选择。');
  }
};

const addSelectedFiles = async (
  selected: readonly File[],
  currentLifecycle: number,
  selectionVersion?: number,
): Promise<void> => {
  const selectedFiles = await selectFileArray(selected);
  if (currentLifecycle !== pageLifecycleVersion
      || (selectionVersion !== undefined && selectionVersion !== contextSelectionVersion)) {
    store.discardContextFiles(selectedFiles);
    return;
  }
  let added = 0;
  for (const file of selectedFiles) {
    if (store.addFile(file)) added += 1;
  }
  if (added > 0) ElMessage.success(`已加入 ${added} 个上下文文件。`);
  if (added < selectedFiles.length) ElMessage.warning('上下文文件数量已达上限，部分文件未加入。');
};

const handleFilesSelected = (fileList: FileList | null): Promise<void> =>
  handleSelectedContextFiles(Array.from(fileList ?? []));

const handleDocumentsSelected = (fileList: FileList | null): Promise<void> =>
  handleSelectedContextFiles(Array.from(fileList ?? []));

const handleFilesDropped = async ({ files: droppedFiles }: DroppedFileCollection): Promise<void> => {
  if (droppedFiles.length === 0) {
    ElMessage.warning('未读取到可处理的文件，请重新拖入文件或文件夹。');
    return;
  }
  await handleSelectedContextFiles(droppedFiles);
};

const handleUploadConfirmed = async (indices: number[]): Promise<void> => {
  const currentLifecycle = pageLifecycleVersion;
  const selected = indices.map((index) => uploadCandidates.value[index]?.file)
    .filter((file): file is File => Boolean(file));
  uploadCandidates.value = [];
  try {
    await addSelectedFiles(selected, currentLifecycle);
  } catch (error: unknown) {
    if (currentLifecycle !== pageLifecycleVersion) {
      return;
    }
    ElMessage.error(error instanceof Error ? error.message : '文档上传或解析失败，请重试。');
  }
};

const handleUploadDialogVisibility = (visible: boolean): void => {
  if (!visible && uploadCandidates.value.length > 0) {
    selectionWarnings.value = [
      `已发现 ${uploadCandidates.value.length} 个文档但未上传，Plan Mode 和最终增强不会使用其正文。`,
    ];
    uploadCandidates.value = [];
  }
  uploadConfirmVisible.value = visible;
};

const handleAddManualFile = (file: ContextFileInput): void => {
  if (!store.addFile(file)) ElMessage.warning('上下文文件数量已达上限，请先移除一个文件。');
};

const handleAnalyze = async (): Promise<void> => {
  const contextFiles = await prepareContextTransmission(
    `${PROJECT_OVERVIEW_RETRIEVAL_QUERY} ${customDescription.value}`,
    '分析上下文资料',
    false,
  );
  if (contextFiles === undefined) {
    return;
  }
  const succeeded = await store.runContextAnalysis(contextFiles);
  if (succeeded) {
    ElMessage.success('上下文资料分析完成。');
  }
};

const handleOptimize = async (): Promise<void> => {
  if (!beginEnhancement('optimize')) {
    return;
  }
  await continueEnhancement();
};

const handleReEnhance = async (): Promise<void> => {
  if (!beginEnhancement('reenhance')) {
    return;
  }
  await continueEnhancement();
};

/** 模式变更须显式重新生成；保留原请求绑定，不能把已优化正文当作新 raw 复用旧 Plan。 */
const handleRegenerateWithFormat = async (): Promise<void> => {
  if (isReading.value || isPreparingContext.value || isRecoveringPlan.value || planDialogVisible.value) return;
  if (hasUnsavedResultDraft.value) {
    ElMessage.warning('请先保存或取消结果中的修改，再按所选模式重新增强。');
    return;
  }
  const sourcePrompt = store.modeRegenerationRawPrompt;
  if (await store.regenerateWithPromptFormat()) {
    ElMessage.success('已按所选模式重新生成；原结果可通过撤销找回。');
  } else if (store.planningSessionExpired && sourcePrompt) {
    pendingPrompt.value = sourcePrompt;
    await recoverExpiredPlan();
  }
};

const beginEnhancement = (intent: 'optimize' | 'reenhance'): boolean => {
  if (isPreparingContext.value || isRecoveringPlan.value || planDialogVisible.value) return false;
  if (!trial.canUse('PROMPT_ENHANCEMENT')) {
    ElMessage.warning(trial.error || '免费体验次数不足，请注册或登录后继续使用。');
    return false;
  }
  if (hasUnsavedResultDraft.value) {
    ElMessage.warning('请先保存或取消结果中的修改，再进行增强。');
    return false;
  }
  if (intent === 'optimize') {
    if (!canOptimize.value) {
      return false;
    }
    pendingPrompt.value = rawPrompt.value.trim();
  } else if (!result.value || isPlanning.value || isOptimizing.value) {
    return false;
  } else {
    pendingPrompt.value = result.value.optimizedPrompt;
  }
  if (projectIndex.value && projectIndex.value.status !== 'READY') {
    ElMessage.warning('本地项目索引尚未完成，请等待索引完成后再增强提示词。');
    return false;
  }
  if (planModeEnabled.value && !planIntroSeen.value) {
    planIntroVisible.value = true;
    return false;
  }
  return true;
};

const continueEnhancement = async (): Promise<void> => {
  if (planModeEnabled.value) {
    await runPlannedEnhancement();
    return;
  }
  await runDirectEnhancement();
};

const handlePlanIntroAccepted = async (): Promise<void> => {
  acceptPlanIntro();
  planIntroVisible.value = false;
  await runPlannedEnhancement();
};

const handlePlanIntroDismissed = async (): Promise<void> => {
  dismissPlanIntro();
  planIntroVisible.value = false;
  await runDirectEnhancement();
};

const runPlannedEnhancement = async (): Promise<void> => {
  recoveryRevision.value = 0;
  const contextPrepared = await prepareContextForPlan(pendingPrompt.value);
  if (!contextPrepared) {
    return;
  }
  const succeeded = await store.createOptimizationPlan(pendingPrompt.value);
  if (!succeeded) {
    if (store.planningSessionExpired) await recoverExpiredPlan();
    return;
  }
  if ((plan.value?.questions.length ?? 0) > 0) {
    planDialogVisible.value = true;
    return;
  }
  await generateFinalPrompt(createPlanConfirmation([]));
};

const runDirectEnhancement = async (): Promise<void> => {
  const contextFiles = await prepareContextTransmission(
    pendingPrompt.value,
    '直接增强提示词',
    true,
  );
  if (contextFiles === undefined) {
    return;
  }
  const succeeded = await store.runOptimization(contextFiles, {
    rawPrompt: pendingPrompt.value,
  });
  if (succeeded) {
    planDialogVisible.value = false;
    ElMessage(optimizationCompletionNotice(result.value));
  }
};

const prepareContextForPlan = async (sourcePrompt: string): Promise<boolean> => {
  if (projectIndex.value && projectIndex.value.status !== 'READY') {
    ElMessage.warning('本地项目索引尚未完成，请等待索引完成后再生成确认问题。');
    return false;
  }
  if (!hasContextSource.value) {
    return true;
  }
  const contextFiles = await prepareContextTransmission(
    `${sourcePrompt}\n${PROJECT_OVERVIEW_RETRIEVAL_QUERY}`,
    '生成确认问题前分析上下文',
    true,
    true,
  );
  if (contextFiles === undefined) {
    return false;
  }
  return store.preparePlanningContext(sourcePrompt, contextFiles);
};

const generateFinalPrompt = async (confirmation: PlanConfirmation): Promise<void> => {
  const contextFiles = await prepareContextTransmission(
    buildRefinedContextQuery(pendingPrompt.value, confirmation),
    '生成最终提示词',
    true,
  );
  if (contextFiles === undefined) {
    return;
  }
  const succeeded = await store.runOptimization(contextFiles, {
    rawPrompt: pendingPrompt.value,
    planConfirmation: confirmation,
  });
  if (succeeded) {
    planDialogVisible.value = false;
    ElMessage(optimizationCompletionNotice(result.value));
  } else if (store.planningSessionExpired) {
    await recoverExpiredPlan();
  }
};

const recoverExpiredPlan = async (): Promise<void> => {
  if (isRecoveringPlan.value) return;
  const currentLifecycle = pageLifecycleVersion;
  isRecoveringPlan.value = true;
  try {
    // 重新经过文件发送确认；用户取消时保留旧对话框和草稿，不绕过发送许可。
    if (!await prepareContextForPlan(pendingPrompt.value)) return;
    recoveryRevision.value += 1;
    if (!await store.createOptimizationPlan(pendingPrompt.value)) return;
    planDialogVisible.value = true;
    recordPlanningEvent(plan.value?.planId, 'EXPIRED_RECOVERED');
    ElMessage.info('确认已过期，已重新生成问题。请核对答案后再次确认。');
  } finally {
    if (currentLifecycle === pageLifecycleVersion) isRecoveringPlan.value = false;
  }
};

const handlePlanConfirmed = async (confirmation: PlanConfirmation): Promise<void> => {
  await generateFinalPrompt(confirmation);
};

const createPlanConfirmation = (answers: PlanConfirmation['answers']): PlanConfirmation => ({
  planId: plan.value?.planId,
  planningContext: plan.value?.planningContext,
  answers,
});

const handleSaveResult = async (
  draft: PromptSection[] | string, complete: (saved: boolean, message?: string) => void,
): Promise<void> => {
  const saved = typeof draft === 'string' ? await store.saveEditedText(draft) : store.saveEditedSections(draft);
  complete(saved, saved ? undefined : store.errorMessage);
  if (saved) {
    recordPlanningEvent(plan.value?.planId, 'RESULT_EDITED');
    ElMessage.success('修改已保存。');
  }
};

const prepareContextTransmission = async (
  query: string,
  operationName: string,
  willCallModel: boolean,
  planningDigestOnly = false,
): Promise<ContextFileInput[] | undefined> => {
  // 临时索引只有处理完成才加入文件集合，提前提交会静默遗漏正在识别的材料。
  if (isReading.value) {
    ElMessage.info('上下文文件仍在上传、审阅或识别，请完成后再继续。');
    return undefined;
  }
  if (isPreparingContext.value) return undefined;
  const lease = store.captureContext();
  store.isPreparingContext = true;
  try {
    const contextFiles = await store.prepareContextFiles(query);
    if (!store.isCurrentContext(lease)) return undefined;
    if (!projectContextSettings.value.confirmBeforeSendingCode || contextFiles.length === 0) {
      return contextFiles;
    }
    const characters = contextFiles.reduce((total, file) => total + file.content.length, 0);
    const indexedDocuments = contextFiles.filter((file) => file.documentId);
    const indexedDocumentBytes = indexedDocuments.reduce(
      (total, file) => total + (file.sizeBytes ?? 0),
      0,
    );
    const destination = planningDigestOnly
      ? '本项目后端进行安全分析，并仅将裁剪后的上下文摘要提供给当前配置的大模型服务'
      : willCallModel
      ? '本项目后端，并由后端转发给当前配置的大模型服务'
      : '本项目 Spring Boot 后端进行上下文分析';
    await ElMessageBox.confirm(
      `${operationName}将发送 ${contextFiles.length} 项上下文到${destination}：${characters.toLocaleString('zh-CN')} 个内联字符${indexedDocuments.length > 0
        ? `，另引用 ${indexedDocuments.length} 份已解析文档（原文件共 ${formatBytes(indexedDocumentBytes)}）`
        : ''}。项目的完整浏览器本地索引不会上传；已解析文档只会选取与本次任务相关的片段。`,
      '确认发送上下文',
      {
        type: 'warning',
        confirmButtonText: '确认发送',
        cancelButtonText: '暂不发送',
      },
    );
    return store.isCurrentContext(lease) ? contextFiles : undefined;
  } catch (error: unknown) {
    if (!store.isCurrentContext(lease)) return undefined;
    if (error === 'cancel' || error === 'close') {
      return undefined;
    }
    ElMessage.error(error instanceof Error ? error.message : '读取本地上下文失败。');
    return undefined;
  } finally {
    if (store.isCurrentContext(lease)) store.isPreparingContext = false;
  }
};

const formatBytes = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

/** 取消选择与上传回调；已添加的独立资料仍由 Store 管理。 */
const cancelFileSelection = (): void => {
  contextSelectionVersion += 1;
  resetProjectFiles();
  uploadCandidates.value = [];
  omittedUploadCandidates.value = 0;
  uploadConfirmVisible.value = false;
};

/** 任务切换或卸载同时失效页面回调，避免旧 Worker、上传与弹窗回到新任务。 */
const resetPageOperations = (): void => {
  pageLifecycleVersion += 1;
  cancelFileSelection();
  resetProjectIndexPageState();
  selectionWarnings.value = [];
  planDialogVisible.value = false;
  planIntroVisible.value = false;
  pendingPrompt.value = '';
  recoveryRevision.value = 0;
  isRecoveringPlan.value = false;
  isClearingIndex.value = false;
};

const handlePageHide = (): void => {
  resetPageOperations();
  store.clearFiles();
};

const hasUnsavedResultDraft = computed(() => store.hasUnsavedResultDraft);

onBeforeRouteLeave(async () => {
  if (!hasUnsavedResultDraft.value) return true;
  try {
    await ElMessageBox.confirm('结果中的修改尚未保存，离开将放弃这些修改。', '尚未保存的修改', {
      type: 'warning', confirmButtonText: '放弃修改并离开', cancelButtonText: '继续编辑',
    });
    store.resultDraft = undefined;
    return true;
  } catch {
    return false;
  }
});

watch(() => store.taskRevision, resetPageOperations, { flush: 'sync' });
watch(plan, value => { if (!value) planDialogVisible.value = false; });

watch(result, (value) => {
  if (value && isNarrowWorkbench.value) {
    activeMobilePane.value = 'result';
  }
});

onMounted(() => {
  clearPersistedProjectSelection();
  void store.refreshAvailableModels();
  if (auth.isAuthenticated) void projectContextSettingsStore.refreshStorageStatus();
  window.addEventListener('pagehide', handlePageHide);
  mobilePaneQuery = window.matchMedia(MOBILE_PANE_QUERY);
  syncNarrowWorkbench();
  mobilePaneQuery.addEventListener('change', syncNarrowWorkbench);
  if (route.query.enhance === '1') {
    void router.replace({ path: '/workbench', query: {} }).then(() => handleOptimize());
  }
});

onBeforeUnmount(() => {
  resetPageOperations();
  store.cancelPendingOperations();
  window.removeEventListener('pagehide', handlePageHide);
  mobilePaneQuery?.removeEventListener('change', syncNarrowWorkbench);
});
</script>

<template>
  <div class="workbench-page">
    <WorkbenchFlowHeader :stage="workbenchStage" />
    <GuestTrialBanner feature="PROMPT_ENHANCEMENT" />

    <ElAlert
      v-if="errorMessage"
      class="error-alert"
      type="error"
      :title="errorMessage"
      :closable="false"
      show-icon
    >
      <template #icon><WarningFilled /></template>
      <template v-if="requestId" #default>
        请求标识：<code>{{ requestId }}</code>
      </template>
    </ElAlert>

    <nav
      v-show="isNarrowWorkbench"
      class="workbench-pane-switch"
      role="tablist"
      aria-label="工作台分区"
    >
      <button
        v-for="pane in mobilePanes"
        :key="pane.id"
        type="button"
        role="tab"
        :aria-label="pane.ariaLabel"
        :aria-selected="activeMobilePane === pane.id"
        :tabindex="activeMobilePane === pane.id ? 0 : -1"
        :class="{ 'is-active': activeMobilePane === pane.id }"
        @click="activeMobilePane = pane.id"
      >
        {{ pane.label }}
      </button>
    </nav>

    <div class="workbench-grid" :data-mobile-pane="activeMobilePane">
      <ContextPanel
        v-if="auth.isAuthenticated"
        v-show="showContextPane"
        class="glass-panel context-column"
        :custom-description="customDescription"
        :files="files"
        :warnings="contextWarnings"
        :project-index="projectIndex"
        :context-retrieval="contextRetrieval"
        :snapshot="contextSnapshot"
        :transmission="contextTransmission"
        :is-reading="isReading"
        :is-analyzing="isAnalyzing"
        :progress="progress"
        :supports-directory-picker="supportsDirectoryPicker"
        :is-selecting-directory="isSelectingDirectory"
        :is-indexing="isIndexing"
        :is-clearing-index="isClearingIndex"
        :is-pausing="isPausing"
        :index-progress="indexProgress"
        @update:custom-description="customDescription = $event"
        @files-selected="handleFilesSelected"
        @documents-selected="handleDocumentsSelected"
        @files-dropped="handleFilesDropped"
        @add-manual-file="handleAddManualFile"
        @remove-file="store.removeFile"
        @clear-files="handleClearContextFiles"
        @analyze="handleAnalyze"
        @index-project="handleIndexProject"
        @pause-index="pauseIndexing"
        @resume-index="handleResumeProject"
        @refresh-index="handleRefreshProject"
        @cancel-index="handleCancelIndex"
      />

      <section v-else v-show="showContextPane" class="glass-panel context-column guest-context">
        <h2>背景与补充说明</h2>
        <p>访客可用文字补充背景。上传资料、项目索引与 Plan 确认需注册登录后使用。</p>
        <ElInput v-model="customDescription" type="textarea" :rows="8" maxlength="4000" show-word-limit
          aria-label="访客背景说明" placeholder="例如：面向谁、需要什么风格、哪些事实和限制必须保留。" />
        <ElButton type="primary" plain @click="router.push({ path: '/login', query: { mode: 'register', redirect: '/workbench' } })">注册后使用完整上下文功能</ElButton>
      </section>

      <IntentComposer
        v-show="showIntentPane"
        class="glass-panel intent-column"
        :raw-prompt="rawPrompt"
        :structured-prompt="promptFormat === 'STRUCTURED'"
        :available-models="availableModels"
        :selected-model-id="selectedModelId"
        :is-loading-models="isLoadingModels"
        :model-load-error="modelLoadError"
        :is-analyzing="isAnalyzing"
        :is-planning="isPlanning"
        :is-optimizing="isOptimizing || isPreparingContext"
        :can-optimize="canOptimize && !isReading && trial.canUse('PROMPT_ENHANCEMENT')"
        :allow-plan="auth.isAuthenticated"
        @update:raw-prompt="rawPrompt = $event"
        @update:structured-prompt="store.setPromptFormat($event ? 'STRUCTURED' : 'COMPACT')"
        @update:selected-model-id="selectedModelId = $event"
        @optimize="handleOptimize"
      />
      <ResultPanel
        v-show="showResultPane"
        class="glass-panel result-column"
        :result="result"
        :edit-draft="resultDraft"
        :can-undo="canUndoResult"
        :selected-prompt-format="promptFormat"
        :can-regenerate-with-format="canReuseGenerationSource && trial.canUse('PROMPT_ENHANCEMENT')"
        :busy="isAnalyzing || isPlanning || isOptimizing || isPreparingContext"
        :plan-mode-enabled="planModeEnabled"
        @save="handleSaveResult"
        @update:edit-draft="resultDraft = $event"
        @re-enhance="handleReEnhance"
        @undo="store.undoResultAndPersist()"
        @regenerate-with-format="handleRegenerateWithFormat"
      />
    </div>

    <ContextUploadConfirmDialog
      :model-value="uploadConfirmVisible"
      :candidates="uploadCandidates"
      :omitted-count="omittedUploadCandidates"
      @confirm="handleUploadConfirmed"
      @update:model-value="handleUploadDialogVisibility"
    />
    <ContextVisionReviewDialog />
    <PlanQuestionDialog
      v-model="planDialogVisible"
      :plan="plan"
      :recovery-revision="recoveryRevision"
      :is-generating="isOptimizing || isPlanning || isAnalyzing || isRecoveringPlan || isPreparingContext"
      :error-message="errorMessage"
      @confirm="handlePlanConfirmed"
    />
    <PlanModeIntroDialog
      v-model="planIntroVisible"
      @accept="handlePlanIntroAccepted"
      @dismiss="handlePlanIntroDismissed"
    />
  </div>
</template>

<style scoped>
.guest-context { display: flex; flex-direction: column; gap: 16px; padding: 22px; overflow-y: auto; }
.guest-context h2 { margin: 0; font-size: 16px; }
.guest-context p { margin: 0; color: var(--text-secondary); line-height: 1.7; }
.workbench-page > :deep(.guest-trial-banner) { margin: 8px 20px 0; flex-shrink: 0; }
.workbench-page {
  display: flex;
  min-width: 0;
  height: calc(100vh - 56px);
  height: calc(100dvh - 56px);
  flex-direction: column;
  overflow: hidden;
}

.error-alert {
  margin: 8px 24px 0;
}

.error-alert code {
  font-family: var(--font-mono);
  font-size: 12px;
}

.workbench-grid {
  display: grid;
  grid-template-columns:
    minmax(248px, 0.72fr)
    minmax(420px, 1.48fr)
    minmax(300px, 1.04fr);
  min-height: 0;
  flex: 1;
  align-items: stretch;
  gap: 16px;
  padding: 10px 20px 16px;
}

.context-column,
.intent-column,
.result-column {
  min-width: 0;
  min-height: 0;
  height: 100%;
}

@media (max-width: 1440px) {
  .workbench-grid {
    grid-template-columns:
      minmax(240px, 0.76fr)
      minmax(380px, 1.4fr)
      minmax(280px, 1fr);
  }
}

@media (max-width: 1100px) {
  .workbench-grid {
    grid-template-columns: 260px minmax(0, 1fr) 300px;
    gap: 16px;
    padding-inline: 16px;
  }
}

.workbench-pane-switch {
  display: none;
}

@media (max-width: 900px) {
  .workbench-page {
    height: calc(100vh - 56px);
    height: calc(100dvh - 56px);
    overflow: hidden;
  }

  .workbench-pane-switch {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    flex: 0 0 auto;
    gap: 4px;
    margin: 8px 16px 0;
    padding: 4px;
    border: 1px solid var(--glass-border-subtle);
    border-radius: 14px;
    background: var(--glass-bg-subtle);
  }

  .workbench-pane-switch button {
    min-height: 44px;
    padding: 0 8px;
    border: 0;
    border-radius: 10px;
    color: var(--text-secondary);
    font-size: 14px;
    font-weight: 600;
    background: transparent;
    cursor: pointer;
  }

  .workbench-pane-switch button.is-active {
    color: var(--text-primary);
    background: var(--glass-bg-strong);
    box-shadow: 0 1px 0 var(--glass-border-subtle);
  }

  .workbench-grid {
    grid-template-columns: 1fr;
    gap: 0;
    padding: 8px 16px 16px;
  }

  .context-column,
  .intent-column,
  .result-column {
    height: 100%;
    min-height: 0;
  }
}

@media (max-width: 640px) {
  .workbench-page {
    height: calc(100vh - 52px - env(safe-area-inset-top, 0px));
    height: calc(100dvh - 52px - env(safe-area-inset-top, 0px));
  }
}

@media (max-width: 600px) {
  .error-alert {
    margin-inline: 12px;
  }

  .workbench-pane-switch,
  .workbench-grid {
    margin-inline: 12px;
  }

  .workbench-grid {
    padding: 8px 12px calc(12px + env(safe-area-inset-bottom, 0px));
  }
}
</style>
