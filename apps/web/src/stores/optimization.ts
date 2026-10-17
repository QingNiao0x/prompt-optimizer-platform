import { defineStore } from 'pinia';
import { computed, ref, shallowRef, watch } from 'vue';
import { restoreAppliedConstraints } from '@/features/optimization/constraintEditing';
import { renderPromptSections } from '@/features/optimization/promptPresentation';
import { completeContextTransmission, createContextTransmission, failContextTransmission,
  type ContextTransmissionReport, type TransmissionPhase } from '@/features/context-analysis/contextTransmission';
import { useAuthStore } from '@/stores/auth';

import {
  buildOptimizationPlanRequest,
  buildOptimizationRequest,
  buildPlanningContextRequest,
} from '@/features/optimization/optimizationRequest';
import {
  loadProjectContextSettings,
  resolveProjectContextProfile,
} from '@/features/project-context-config/projectContextConfig';
import { projectIndexRepository } from '@/features/project-index/indexedDbProjectIndexRepository';
import {
  retrieveProjectContextWithReport,
  type ProjectContextRetrievalResult,
  type ProjectIndexSummary,
} from '@/features/project-index/projectIndexer';
import { getApiErrorCode, getApiErrorMessage, getApiErrorRequestId } from '@/services/http';
import { deleteDocumentUpload } from '@/services/documentUploadApi';
import {
  analyzeContext,
  createOptimizationPlan as requestOptimizationPlan,
  optimizePrompt,
  preparePlanningContext as requestPlanningContext,
  listAvailableModels,
} from '@/services/promptOptimizerApi';
import { MAX_FILES } from '@/workers/fileReaderCore';
import type {
  ContextFileInput,
  AvailableModel,
  ContextSnapshot,
  OptimizationHistoryDetail,
  OptimizationPlan,
  OptimizationResult,
  OptimizationRequest,
  PlanConfirmation,
  PlanningContextPreparation,
  PromptSection,
  PromptFormat,
  ReoptimizationResult,
  TemplateCode,
} from '@/types/api';

/** 任务切换和资料变化均会使异步操作失效，普通偏好不参与资料归属。 */
export interface OptimizationContextLease { task: number; context: number }

interface SuccessfulGenerationSource {
  requestJson: string;
  lease: OptimizationContextLease;
  modelId: string | null;
  includeExamples: boolean;
  rawPrompt: string;
}

export const useOptimizationStore = defineStore('optimization', () => {
  const rawPrompt = ref('');
  const customDescription = ref('');
  const files = ref<ContextFileInput[]>([]);
  // 通过“添加文档/文件”单独加入的资料独立于项目目录，重新选择或完成索引时仍应保留。
  const supplementalFiles = ref<ContextFileInput[]>([]);
  const templateCode = ref<TemplateCode>('AUTO');
  const includePermissionBoundaries = ref(true);
  const includeExamples = ref(false);
  // 仅随当前任务保留，不写入全局偏好；恢复历史时再采用该记录的实际形式。
  const promptFormat = ref<PromptFormat>('COMPACT');
  const availableModels = ref<AvailableModel[]>([]);
  const selectedModelId = ref('');
  const planModelId = ref('');
  const isLoadingModels = ref(false);
  const modelLoadError = ref('');
  // 项目正文保存在 IndexedDB；Pinia 只持有轻量摘要和索引编号。
  const projectIndex = shallowRef<ProjectIndexSummary>();
  const contextRetrieval = shallowRef<ProjectContextRetrievalResult>();
  const activeFilePath = ref('');
  const contextSnapshot = ref<ContextSnapshot>();
  const planningContext = ref<PlanningContextPreparation>();
  const plan = ref<OptimizationPlan>();
  const result = ref<OptimizationResult>();
  const resultUndoStack = ref<OptimizationResult[]>([]);
  // 请求快照只存在当前任务内存中；不写浏览器存储或历史，不从优化正文重建 Plan 题答。
  const generationSource = shallowRef<SuccessfulGenerationSource>();
  let resultUndoSources: Array<SuccessfulGenerationSource | undefined> = [];
  const requestId = ref('');
  const errorMessage = ref('');
  const planningSessionExpired = ref(false);
  const isAnalyzing = ref(false);
  const isPlanning = ref(false);
  const isOptimizing = ref(false);
  // 包含本地检索与发送确认，覆盖尚未发出 HTTP 请求的准备阶段。
  const isPreparingContext = ref(false);
  const taskRevision = ref(0);
  const contextRevision = ref(0);
  const contextTransmission = shallowRef<ContextTransmissionReport>();
  const contextCleanupWarning = ref('');
  // 草稿仅在当前 Pinia 实例内保留；不写浏览器存储或覆盖后端历史。
  const resultDraft = ref<PromptSection[]>();
  let preparedCandidates: ContextFileInput[] = [];
  const preparedLeases = new WeakMap<ContextFileInput[], OptimizationContextLease>();

  const captureContext = (): OptimizationContextLease => ({ task: taskRevision.value, context: contextRevision.value });
  const isCurrentContext = (lease: OptimizationContextLease): boolean =>
    lease.task === taskRevision.value && lease.context === contextRevision.value;
  const acceptsPrepared = (prepared?: ContextFileInput[]): boolean =>
    !prepared || !preparedLeases.has(prepared) || isCurrentContext(preparedLeases.get(prepared)!);

  /** 修改资料或输入后，旧请求的成功、失败和 finally 都不得改变新操作状态。 */
  const invalidateContext = (): void => {
    contextRevision.value += 1;
    generationSource.value = undefined;
    resultUndoSources = resultUndoSources.map(() => undefined);
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
    planningContext.value = undefined;
    plan.value = undefined;
    planModelId.value = '';
    planningSessionExpired.value = false;
    contextTransmission.value = undefined;
    preparedCandidates = [];
    isAnalyzing.value = false;
    isPlanning.value = false;
    isOptimizing.value = false;
    isPreparingContext.value = false;
  };

  const cleanupReferences = (previous: readonly ContextFileInput[], next: readonly ContextFileInput[]): void => {
    const task = taskRevision.value;
    cleanupUnusedDocumentReferences(previous, next, () => {
      if (task === taskRevision.value) contextCleanupWarning.value =
        '旧文档引用已从当前任务移除，但服务端临时资料清理失败；不会用于本次请求，到期清理由服务端执行。';
    });
  };

  /** 切换任务统一释放内存归属；删除失败也不恢复旧资料，身份切换同时移除旧输入和草稿。 */
  const resetTask = (): void => {
    const previous = [...files.value, ...supplementalFiles.value];
    const previousIndex = projectIndex.value;
    taskRevision.value += 1;
    invalidateContext();
    contextCleanupWarning.value = '';
    projectIndex.value = undefined;
    files.value = [];
    supplementalFiles.value = [];
    activeFilePath.value = '';
    result.value = undefined;
    resultDraft.value = undefined;
    resultUndoStack.value = [];
    resultUndoSources = [];
    rawPrompt.value = '';
    customDescription.value = '';
    templateCode.value = 'AUTO';
    promptFormat.value = 'COMPACT';
    requestId.value = '';
    errorMessage.value = '';
    cleanupReferences(previous, []);
    if (previousIndex) void projectIndexRepository.deleteProject(previousIndex.id).catch(() => undefined);
  };

  /** 页面离开时停止旧请求写回，但保留同一任务的资料和已保存结果。 */
  const cancelPendingOperations = (): void => invalidateContext();

  /** 已失效的上传即使返回了文档编号，也只清理引用，不能重新加入当前任务。 */
  const discardContextFiles = (discarded: readonly ContextFileInput[]): void =>
    cleanupReferences(discarded, files.value);

  const auth = useAuthStore();
  watch(() => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|'), resetTask, { flush: 'sync' });
  watch([rawPrompt, customDescription], invalidateContext, { flush: 'sync' });

  const beginTransmission = (prepared: ContextFileInput[], phase: TransmissionPhase): void => {
    contextTransmission.value = createContextTransmission(
      [...files.value, ...preparedCandidates], prepared, phase);
  };
  const finishTransmission = (snapshot: ContextSnapshot, id: string): void => {
    if (contextTransmission.value) contextTransmission.value = completeContextTransmission(contextTransmission.value, snapshot, id);
  };
  const failTransmission = (): void => {
    if (contextTransmission.value) contextTransmission.value = failContextTransmission(contextTransmission.value);
  };

  const canOptimize = computed(() => rawPrompt.value.trim().length > 0
    && !isPreparingContext.value
    && !isAnalyzing.value
    && !isPlanning.value
    && !isOptimizing.value);
  const canUndoResult = computed(() => resultUndoStack.value.length > 0);
  const hasPendingPromptFormat = computed(() => Boolean(result.value)
    && promptFormat.value !== (result.value?.promptFormat ?? 'STRUCTURED'));
  const canReuseGenerationSource = computed(() => Boolean(generationSource.value
    && isCurrentContext(generationSource.value.lease)
    && generationSource.value.modelId === (selectedModelId.value || null)
    && generationSource.value.includeExamples === includeExamples.value));
  const modeRegenerationRawPrompt = computed(() => generationSource.value?.rawPrompt ?? '');

  /** 更新模型目录时保留仍可用的用户选择，否则回到平台默认模型。 */
  const refreshAvailableModels = async (): Promise<void> => {
    isLoadingModels.value = true;
    modelLoadError.value = '';
    try {
      const response = await listAvailableModels();
      availableModels.value = response.data;
      if (!availableModels.value.some((model) => model.id === selectedModelId.value)) {
        selectedModelId.value = availableModels.value.find((model) => model.defaultModel)?.id
          ?? availableModels.value[0]?.id ?? '';
      }
    } catch (error: unknown) {
      availableModels.value = [];
      selectedModelId.value = '';
      modelLoadError.value = getApiErrorMessage(error);
    } finally {
      isLoadingModels.value = false;
    }
  };

  const setFiles = (selectedFiles: ContextFileInput[]): number => {
    if (projectIndex.value) {
      void projectIndexRepository.deleteProject(projectIndex.value.id).catch(() => undefined);
      projectIndex.value = undefined;
    }
    const supplementalPaths = new Set(supplementalFiles.value.map((file) => normalizePath(file.path)));
    const nextFiles = [
      ...supplementalFiles.value,
      ...selectedFiles.filter((file) => !supplementalPaths.has(normalizePath(file.path))),
    ].slice(0, MAX_FILES);
    cleanupReferences(files.value, nextFiles);
    files.value = nextFiles;
    activeFilePath.value = '';
    invalidateContext();
    return nextFiles.length - supplementalFiles.value.length;
  };

  const setProjectIndex = (summary: ProjectIndexSummary): void => {
    const keepsCurrentProject = projectIndex.value?.id === summary.id;
    if (projectIndex.value && !keepsCurrentProject) {
      void projectIndexRepository.deleteProject(projectIndex.value.id).catch(() => undefined);
    }
    projectIndex.value = summary;
    if (!keepsCurrentProject) {
      cleanupReferences(files.value, supplementalFiles.value);
      files.value = [...supplementalFiles.value];
      activeFilePath.value = files.value.at(-1)?.path ?? '';
    }
    invalidateContext();
  };

  const addFile = (file: ContextFileInput): boolean => {
    const supplementalIndex = supplementalFiles.value.findIndex((item) => normalizePath(item.path) === normalizePath(file.path));
    if (supplementalIndex < 0 && supplementalFiles.value.length >= MAX_FILES) {
      cleanupReferences([file], files.value);
      return false;
    }
    const nextSupplemental = [...supplementalFiles.value];
    if (supplementalIndex >= 0) nextSupplemental.splice(supplementalIndex, 1, file);
    else nextSupplemental.push(file);
    const supplementalPaths = new Set(nextSupplemental.map((item) => normalizePath(item.path)));
    const nextFiles = [
      ...nextSupplemental,
      ...files.value.filter((item) => !supplementalPaths.has(normalizePath(item.path))),
    ].slice(0, MAX_FILES);
    cleanupReferences(files.value, nextFiles);
    supplementalFiles.value = nextSupplemental;
    files.value = nextFiles;
    activeFilePath.value = file.path;
    invalidateContext();
    return true;
  };

  const removeFile = (path: string): void => {
    cleanupReferences(files.value.filter((file) => file.path === path), []);
    files.value = files.value.filter((file) => file.path !== path);
    supplementalFiles.value = supplementalFiles.value.filter((file) => file.path !== path);
    if (activeFilePath.value === path) {
      activeFilePath.value = files.value.at(-1)?.path ?? '';
    }
    invalidateContext();
  };

  const clearFiles = (): void => {
    // 本地索引的物理删除由 useProjectIndex 统一负责，避免同一项目被重复删除。
    projectIndex.value = undefined;
    cleanupReferences([...files.value, ...supplementalFiles.value], []);
    files.value = [];
    supplementalFiles.value = [];
    activeFilePath.value = '';
    invalidateContext();
  };

  const resolveContextFiles = async (query: string): Promise<ContextFileInput[]> => {
    const lease = captureContext();
    const manualFiles = [...files.value];
    const profile = resolveProjectContextProfile(loadProjectContextSettings().profile);
    const retrieval = projectIndex.value?.status === 'READY'
      ? await retrieveProjectContextWithReport(projectIndexRepository, {
          projectId: projectIndex.value.id,
          query,
          activeFilePath: activeFilePath.value || undefined,
          pinnedPaths: files.value.map((file) => file.path),
          changedPaths: projectIndex.value.changedPaths,
          maxCharacters: profile.limits.maxContextCharacters,
          maxChunks: profile.limits.maxContextChunks,
        })
      : undefined;
    if (!isCurrentContext(lease)) throw new Error('任务或资料已变更，本次上下文准备已取消。');
    const manualPaths = new Set(manualFiles.map((file) => normalizePath(file.path)));
    const indexedFiles = retrieval?.files.filter((file) =>
      !manualPaths.has(normalizePath(file.path.split('#chunk-')[0] ?? file.path))) ?? [];
    // 用户手动粘贴或通过兼容上传选择的文件优先于自动检索结果。
    const preparedFiles = fitFilesWithinBudget(
      [...manualFiles, ...indexedFiles],
      profile.limits.maxContextCharacters,
      MAX_FILES,
    );
    if (retrieval) {
      const transmittedPaths = new Set(preparedFiles.map((file) => file.path));
      const selectedIndexedFiles = retrieval.files.filter((file) => transmittedPaths.has(file.path));
      contextRetrieval.value = {
        files: selectedIndexedFiles,
        selections: retrieval.selections.filter((selection) =>
          transmittedPaths.has(`${selection.path}#chunk-${selection.chunkIndex + 1}`)),
        totalCharacters: selectedIndexedFiles.reduce(
          (total, file) => total + file.content.length,
          0,
        ),
      };
    } else {
      contextRetrieval.value = undefined;
    }
    preparedCandidates = [...manualFiles, ...indexedFiles];
    preparedLeases.set(preparedFiles, lease);
    return preparedFiles;
  };

  const prepareContextFiles = async (query: string): Promise<ContextFileInput[]> =>
    resolveContextFiles(query);

  const runContextAnalysis = async (
    preparedFiles?: ContextFileInput[],
  ): Promise<boolean> => {
    if (!acceptsPrepared(preparedFiles) || isAnalyzing.value || isPlanning.value || isOptimizing.value) return false;
    const lease = captureContext();
    isAnalyzing.value = true;
    errorMessage.value = '';
    try {
      const contextFiles = preparedFiles ?? await resolveContextFiles(
        `${customDescription.value} 技术栈 依赖 项目结构 配置`,
      );
      if (!isCurrentContext(lease)) return false;
      beginTransmission(contextFiles, 'ANALYSIS');
      const response = await analyzeContext({
        customDescription: customDescription.value.trim(),
        files: contextFiles,
      });
      if (!isCurrentContext(lease)) return false;
      finishTransmission(response.data, response.requestId);
      contextSnapshot.value = response.data;
      requestId.value = response.requestId;
      return true;
    } catch (error: unknown) {
      if (!isCurrentContext(lease)) return false;
      failTransmission();
      errorMessage.value = getApiErrorMessage(error);
      requestId.value = getApiErrorRequestId(error);
      return false;
    } finally {
      if (isCurrentContext(lease)) isAnalyzing.value = false;
    }
  };

  const preparePlanningContext = async (
    sourcePrompt: string,
    preparedFiles: ContextFileInput[],
  ): Promise<boolean> => {
    if (!acceptsPrepared(preparedFiles)) return false;
    if (!sourcePrompt.trim()) {
      planningContext.value = undefined;
      errorMessage.value = '请先输入需要增强的内容。';
      return false;
    }
    if (isAnalyzing.value || isPlanning.value || isOptimizing.value) {
      return false;
    }
    const lease = captureContext();
    isAnalyzing.value = true;
    errorMessage.value = '';
    try {
      beginTransmission(preparedFiles, 'PLAN_CONTEXT');
      const response = await requestPlanningContext(buildPlanningContextRequest(
        sourcePrompt,
        customDescription.value,
        preparedFiles,
      ));
      if (!isCurrentContext(lease)) return false;
      finishTransmission(response.data.contextReport, response.requestId);
      planningContext.value = response.data;
      contextSnapshot.value = response.data.contextReport;
      requestId.value = response.requestId;
      return true;
    } catch (error: unknown) {
      if (!isCurrentContext(lease)) return false;
      failTransmission();
      planningContext.value = undefined;
      errorMessage.value = getApiErrorMessage(error);
      requestId.value = getApiErrorRequestId(error);
      return false;
    } finally {
      if (isCurrentContext(lease)) isAnalyzing.value = false;
    }
  };

  const createOptimizationPlan = async (sourcePrompt = rawPrompt.value): Promise<boolean> => {
    planningSessionExpired.value = false;
    if (!sourcePrompt.trim()) {
      errorMessage.value = '请先输入需要增强的内容。';
      return false;
    }
    if (isAnalyzing.value || isPlanning.value || isOptimizing.value) {
      return false;
    }
    const lease = captureContext();
    const requestedModelId = selectedModelId.value;
    isPlanning.value = true;
    errorMessage.value = '';
    try {
      const response = await requestOptimizationPlan(
        buildOptimizationPlanRequest(
          sourcePrompt,
          customDescription.value,
          planningContext.value
            ? {
                contextId: planningContext.value.contextId,
                version: planningContext.value.version,
              }
            : undefined,
          requestedModelId,
        ),
      );
      if (!isCurrentContext(lease)) return false;
      plan.value = response.data;
      planModelId.value = requestedModelId;
      templateCode.value = response.data.templateCode;
      requestId.value = response.requestId;
      return true;
    } catch (error: unknown) {
      if (!isCurrentContext(lease)) return false;
      planningSessionExpired.value = getApiErrorCode(error) === 'PLANNING_SESSION_EXPIRED';
      errorMessage.value = getApiErrorMessage(error);
      requestId.value = getApiErrorRequestId(error);
      return false;
    } finally {
      if (isCurrentContext(lease)) isPlanning.value = false;
    }
  };

  /** 保存实际发送的 JSON 快照；成功前不替换当前结果及其可撤销来源。 */
  const submitOptimization = async (request: OptimizationRequest, lease: OptimizationContextLease): Promise<boolean> => {
    const requestJson = JSON.stringify(request);
    const response = await optimizePrompt(request);
    if (!isCurrentContext(lease)) return false;
    finishTransmission(response.data.contextReport, response.requestId);
    if (contextTransmission.value) contextTransmission.value = {
      ...contextTransmission.value, mock: response.data.provider.mock,
    };
    rememberCurrentResult();
    resultDraft.value = undefined;
    result.value = response.data;
    generationSource.value = { requestJson, lease, modelId: request.modelId ?? null,
      includeExamples: request.enhancement.includeExamples, rawPrompt: request.rawPrompt };
    promptFormat.value = response.data.promptFormat ?? 'STRUCTURED';
    contextSnapshot.value = response.data.contextReport;
    templateCode.value = response.data.templateCode;
    requestId.value = response.requestId;
    return true;
  };

  const runOptimization = async (
    preparedFiles?: ContextFileInput[],
    options: {
      rawPrompt?: string;
      planConfirmation?: PlanConfirmation;
    } = {},
  ): Promise<boolean> => {
    if (!acceptsPrepared(preparedFiles)) return false;
    planningSessionExpired.value = false;
    const sourcePrompt = options.rawPrompt ?? rawPrompt.value;
    if (!sourcePrompt.trim()) {
      errorMessage.value = '请先输入需要增强的内容。';
      return false;
    }
    if (isAnalyzing.value || isPlanning.value || isOptimizing.value) {
      return false;
    }

    const planConfirmed = options.planConfirmation !== undefined;
    const requestedFormat = promptFormat.value;
    const lease = captureContext();
    if (!planConfirmed) {
      // 直接增强必须让服务端重新推断策略，不能沿用上一次 Plan 或历史记录留下的模板。
      planningContext.value = undefined;
      plan.value = undefined;
      templateCode.value = 'AUTO';
    }

    isOptimizing.value = true;
    errorMessage.value = '';
    try {
      const contextFiles = preparedFiles ?? await resolveContextFiles(sourcePrompt);
      if (!isCurrentContext(lease)) return false;
      beginTransmission(contextFiles, 'ENHANCEMENT');
      return await submitOptimization(buildOptimizationRequest({
        rawPrompt: sourcePrompt,
        modelId: planConfirmed ? planModelId.value : selectedModelId.value,
        customDescription: customDescription.value,
        files: contextFiles,
        // 工作台没有显式模板选择器；Plan 返回的是解析结果，不能当成用户指定而锁定最终分类。
        templateCode: 'AUTO',
        includePermissionBoundaries: true,
        includeExamples: includeExamples.value,
        promptFormat: requestedFormat,
        planConfirmation: options.planConfirmation,
      }), lease);
    } catch (error: unknown) {
      if (!isCurrentContext(lease)) return false;
      failTransmission();
      planningSessionExpired.value = getApiErrorCode(error) === 'PLANNING_SESSION_EXPIRED';
      errorMessage.value = getApiErrorMessage(error);
      requestId.value = getApiErrorRequestId(error);
      return false;
    } finally {
      if (isCurrentContext(lease)) isOptimizing.value = false;
    }
  };

  /** 用户显式操作后，仅改变上次成功请求的增强模式；原始需求、资料和真实 Plan 确认逐字复用。 */
  const regenerateWithPromptFormat = async (): Promise<boolean> => {
    if (isAnalyzing.value || isPlanning.value || isOptimizing.value || isPreparingContext.value) return false;
    if (resultDraft.value !== undefined && !equalSections(resultDraft.value,
      result.value?.sections.filter(section => section.type !== 'CLARIFICATIONS') ?? [])) {
      errorMessage.value = '请先保存或取消结果中的修改，再按所选模式重新增强。';
      return false;
    }
    const source = generationSource.value;
    if (!source || !canReuseGenerationSource.value || !hasPendingPromptFormat.value) {
      errorMessage.value = '原始需求或选项已变化，请从原始提示词重新增强；原结果已保留。';
      return false;
    }
    // 快照来自本页面已成功发送的 JSON 协议，不接受优化正文或历史文字伪造确认。
    const request = JSON.parse(source.requestJson) as OptimizationRequest;
    request.enhancement.promptFormat = promptFormat.value;
    planningSessionExpired.value = false;
    isOptimizing.value = true;
    errorMessage.value = '';
    try {
      beginTransmission(request.context.files, 'ENHANCEMENT');
      return await submitOptimization(request, source.lease);
    } catch (error: unknown) {
      if (!isCurrentContext(source.lease)) return false;
      failTransmission();
      planningSessionExpired.value = getApiErrorCode(error) === 'PLANNING_SESSION_EXPIRED';
      errorMessage.value = getApiErrorMessage(error);
      requestId.value = getApiErrorRequestId(error);
      return false;
    } finally {
      if (isCurrentContext(source.lease)) isOptimizing.value = false;
    }
  };

  const saveEditedSections = (sections: PromptSection[]): boolean => {
    if (!result.value || sections.some((section) => !section.title.trim() || !section.content.trim())) {
      errorMessage.value = '结构段落的标题和内容不能为空。';
      return false;
    }
    const normalizedSections = sections.map((section) => {
      if (section.type !== 'CONSTRAINTS') {
        return { ...section };
      }
      return {
        ...section,
        content: restoreAppliedConstraints(section.content,
          result.value?.sections.find(item => item.type === 'CONSTRAINTS')?.content ?? '',
          result.value?.appliedConstraints ?? []),
      };
    });
    const optimizedPrompt = renderPromptSections(normalizedSections, result.value.promptFormat ?? 'STRUCTURED');
    if (optimizedPrompt === result.value.optimizedPrompt) {
      resultDraft.value = undefined;
      errorMessage.value = '';
      return true;
    }
    rememberCurrentResult();
    result.value = { ...result.value, sections: normalizedSections, optimizedPrompt };
    resultDraft.value = undefined;
    errorMessage.value = '';
    return true;
  };

  const undoResult = (): boolean => {
    const previous = resultUndoStack.value.at(-1);
    if (!previous) {
      return false;
    }
    result.value = previous;
    generationSource.value = resultUndoSources.at(-1);
    promptFormat.value = previous.promptFormat ?? 'STRUCTURED';
    resultDraft.value = undefined;
    contextSnapshot.value = previous.contextReport;
    templateCode.value = previous.templateCode;
    resultUndoStack.value = resultUndoStack.value.slice(0, -1);
    resultUndoSources = resultUndoSources.slice(0, -1);
    errorMessage.value = '';
    return true;
  };

  const rememberCurrentResult = (): void => {
    if (result.value) {
      resultUndoStack.value = [...resultUndoStack.value, result.value].slice(-20);
      resultUndoSources = [...resultUndoSources, generationSource.value].slice(-20);
    }
  };

  /** 开关只选择下次真实增强的模式；当前正文、手工稿和 Plan 状态不受偏好切换影响。 */
  const setPromptFormat = (format: PromptFormat): boolean => {
    if (isAnalyzing.value || isPlanning.value || isOptimizing.value || isPreparingContext.value) return false;
    promptFormat.value = format;
    return true;
  };

  // 从历史详情恢复工作台输入，让用户能在原需求基础上继续修改。
  // 历史记录不保存文件正文，因此文件列表会清空，只恢复描述和选项。
  const loadFromHistory = (detail: OptimizationHistoryDetail): void => {
    resetTask();
    rawPrompt.value = detail.rawPrompt;
    customDescription.value =
      typeof detail.contextSummary?.customDescription === 'string'
        ? detail.contextSummary.customDescription
        : '';
    templateCode.value = detail.templateCode;
    includePermissionBoundaries.value = detail.includePermissionBoundaries;
    includeExamples.value = detail.includeExamples;
    promptFormat.value = detail.promptFormat ?? 'STRUCTURED';
  };

  // 服务端重新优化完成后，直接把新结果放回工作台结果区。
  const applyReoptimized = (payload: ReoptimizationResult): void => {
    rememberCurrentResult();
    resultDraft.value = undefined;
    result.value = payload.result;
    generationSource.value = undefined;
    promptFormat.value = payload.result.promptFormat ?? 'STRUCTURED';
    contextSnapshot.value = payload.result.contextReport;
    requestId.value = '';
    errorMessage.value = '';
  };

  return {
    rawPrompt,
    customDescription,
    files,
    templateCode,
    includePermissionBoundaries,
    includeExamples,
    promptFormat,
    availableModels,
    selectedModelId,
    isLoadingModels,
    modelLoadError,
    projectIndex,
    contextRetrieval,
    activeFilePath,
    contextSnapshot,
    planningContext,
    plan,
    result,
    requestId,
    errorMessage,
    planningSessionExpired,
    isAnalyzing,
    isPlanning,
    isOptimizing,
    isPreparingContext,
    canOptimize,
    canUndoResult,
    hasPendingPromptFormat,
    canReuseGenerationSource,
    modeRegenerationRawPrompt,
    taskRevision,
    contextTransmission,
    contextCleanupWarning,
    resultDraft,
    captureContext,
    isCurrentContext,
    resetTask,
    cancelPendingOperations,
    discardContextFiles,
    refreshAvailableModels,
    setFiles,
    setProjectIndex,
    addFile,
    removeFile,
    clearFiles,
    prepareContextFiles,
    runContextAnalysis,
    preparePlanningContext,
    createOptimizationPlan,
    runOptimization,
    regenerateWithPromptFormat,
    saveEditedSections,
    undoResult,
    setPromptFormat,
    loadFromHistory,
    applyReoptimized,
  };
});

const normalizePath = (path: string): string => path.replace(/\\/g, '/').toLowerCase();
const equalSections = (left: readonly PromptSection[], right: readonly PromptSection[]): boolean =>
  JSON.stringify(left) === JSON.stringify(right);

const BINARY_CONTEXT_LANGUAGES = new Set([
  'bmp', 'doc', 'docx', 'dps', 'et', 'gif', 'jpeg', 'jpg', 'odt', 'ods', 'odp', 'pdf',
  'png', 'ppt', 'pptx', 'webp', 'wps',
]);

const fitFilesWithinBudget = (
  candidates: ContextFileInput[],
  maxCharacters: number,
  maxFiles: number,
): ContextFileInput[] => {
  const selected: ContextFileInput[] = [];
  let remainingCharacters = maxCharacters;
  for (const file of candidates) {
    if (selected.length >= maxFiles) {
      break;
    }
    if (file.documentId) {
      // 大型文档正文保存在后端临时索引中，请求只携带轻量引用，不消耗前端字符预算。
      selected.push({ ...file, content: '' });
      continue;
    }
    if (BINARY_CONTEXT_LANGUAGES.has(file.language.toLowerCase())) {
      // Base64 二进制必须完整发送给后端解析，截断会破坏 Office、PDF 和图片文件。
      selected.push(file);
      continue;
    }
    if (remainingCharacters <= 0) {
      continue;
    }
    const content = file.content.slice(0, remainingCharacters);
    if (!content) {
      continue;
    }
    selected.push({ ...file, content });
    remainingCharacters -= content.length;
  }
  return selected;
};

const cleanupUnusedDocumentReferences = (
  previousFiles: readonly ContextFileInput[],
  nextFiles: readonly ContextFileInput[],
  onFailure: () => void = () => undefined,
): void => {
  const retainedIds = new Set(
    nextFiles.map((file) => file.documentId).filter((id): id is string => Boolean(id)),
  );
  const staleIds = new Set(
    previousFiles
      .map((file) => file.documentId)
      .filter((id): id is string => id !== undefined && id.length > 0 && !retainedIds.has(id)),
  );
  staleIds.forEach((documentId) => {
    void deleteDocumentUpload(documentId).catch(onFailure);
  });
};
