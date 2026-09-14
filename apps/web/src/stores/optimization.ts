import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';

import {
  buildOptimizationPlanRequest,
  buildOptimizationRequest,
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
import { getApiErrorMessage, getApiErrorRequestId } from '@/services/http';
import { deleteDocumentUpload } from '@/services/documentUploadApi';
import {
  analyzeContext,
  createOptimizationPlan as requestOptimizationPlan,
  optimizePrompt,
} from '@/services/promptOptimizerApi';
import { MAX_FILES } from '@/workers/fileReaderCore';
import type {
  ContextFileInput,
  ContextSnapshot,
  OptimizationHistoryDetail,
  OptimizationPlan,
  OptimizationResult,
  PlanConfirmation,
  PromptSection,
  ReoptimizationResult,
  TemplateCode,
} from '@/types/api';

export const useOptimizationStore = defineStore('optimization', () => {
  const rawPrompt = ref('');
  const customDescription = ref('');
  const files = ref<ContextFileInput[]>([]);
  const templateCode = ref<TemplateCode>('AUTO');
  const includePermissionBoundaries = ref(true);
  const includeExamples = ref(false);
  // 项目正文保存在 IndexedDB；Pinia 只持有轻量摘要和索引编号。
  const projectIndex = shallowRef<ProjectIndexSummary>();
  const contextRetrieval = shallowRef<ProjectContextRetrievalResult>();
  const activeFilePath = ref('');
  const contextSnapshot = ref<ContextSnapshot>();
  const plan = ref<OptimizationPlan>();
  const result = ref<OptimizationResult>();
  const resultUndoStack = ref<OptimizationResult[]>([]);
  const requestId = ref('');
  const errorMessage = ref('');
  const isAnalyzing = ref(false);
  const isPlanning = ref(false);
  const isOptimizing = ref(false);

  const canOptimize = computed(() => rawPrompt.value.trim().length > 0
    && !isPlanning.value
    && !isOptimizing.value);
  const canUndoResult = computed(() => resultUndoStack.value.length > 0);

  const setFiles = (selectedFiles: ContextFileInput[]): void => {
    if (projectIndex.value) {
      void projectIndexRepository.deleteProject(projectIndex.value.id).catch(() => undefined);
      projectIndex.value = undefined;
    }
    const nextFiles = selectedFiles.slice(0, MAX_FILES);
    cleanupUnusedDocumentReferences(files.value, nextFiles);
    files.value = nextFiles;
    activeFilePath.value = '';
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
  };

  const setProjectIndex = (summary: ProjectIndexSummary): void => {
    const keepsCurrentProject = projectIndex.value?.id === summary.id;
    if (projectIndex.value && !keepsCurrentProject) {
      void projectIndexRepository.deleteProject(projectIndex.value.id).catch(() => undefined);
    }
    projectIndex.value = summary;
    if (!keepsCurrentProject) {
      cleanupUnusedDocumentReferences(files.value, []);
      files.value = [];
      activeFilePath.value = '';
    }
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
  };

  const addFile = (file: ContextFileInput): void => {
    const existingIndex = files.value.findIndex((item) => item.path === file.path);
    if (existingIndex >= 0) {
      const existingFile = files.value[existingIndex];
      if (existingFile) {
        cleanupUnusedDocumentReferences([existingFile], [file]);
      }
      files.value.splice(existingIndex, 1, file);
    } else if (files.value.length < MAX_FILES) {
      files.value.push(file);
    }
    activeFilePath.value = file.path;
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
  };

  const removeFile = (path: string): void => {
    cleanupUnusedDocumentReferences(files.value.filter((file) => file.path === path), []);
    files.value = files.value.filter((file) => file.path !== path);
    if (activeFilePath.value === path) {
      activeFilePath.value = files.value.at(-1)?.path ?? '';
    }
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
  };

  const clearFiles = (): void => {
    // 本地索引的物理删除由 useProjectIndex 统一负责，避免同一项目被重复删除。
    projectIndex.value = undefined;
    cleanupUnusedDocumentReferences(files.value, []);
    files.value = [];
    activeFilePath.value = '';
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
  };

  const resolveContextFiles = async (query: string): Promise<ContextFileInput[]> => {
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
    const manualPaths = new Set(files.value.map((file) => normalizePath(file.path)));
    const indexedFiles = retrieval?.files.filter((file) =>
      !manualPaths.has(normalizePath(file.path.split('#chunk-')[0] ?? file.path))) ?? [];
    // 用户手动粘贴或通过兼容上传选择的文件优先于自动检索结果。
    const preparedFiles = fitFilesWithinBudget(
      [...files.value, ...indexedFiles],
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
    return preparedFiles;
  };

  const prepareContextFiles = async (query: string): Promise<ContextFileInput[]> =>
    resolveContextFiles(query);

  const runContextAnalysis = async (
    preparedFiles?: ContextFileInput[],
  ): Promise<boolean> => {
    isAnalyzing.value = true;
    errorMessage.value = '';
    try {
      const contextFiles = preparedFiles ?? await resolveContextFiles(
        `${customDescription.value} 技术栈 依赖 项目结构 配置`,
      );
      const response = await analyzeContext({
        customDescription: customDescription.value.trim(),
        files: contextFiles,
      });
      contextSnapshot.value = response.data;
      requestId.value = response.requestId;
      return true;
    } catch (error: unknown) {
      errorMessage.value = getApiErrorMessage(error);
      requestId.value = getApiErrorRequestId(error);
      return false;
    } finally {
      isAnalyzing.value = false;
    }
  };

  const createOptimizationPlan = async (sourcePrompt = rawPrompt.value): Promise<boolean> => {
    if (!sourcePrompt.trim()) {
      errorMessage.value = '请先输入需要增强的内容。';
      return false;
    }
    if (isPlanning.value || isOptimizing.value) {
      return false;
    }
    isPlanning.value = true;
    errorMessage.value = '';
    try {
      const response = await requestOptimizationPlan(
        buildOptimizationPlanRequest(sourcePrompt, customDescription.value),
      );
      plan.value = response.data;
      templateCode.value = response.data.templateCode;
      requestId.value = response.requestId;
      return true;
    } catch (error: unknown) {
      errorMessage.value = getApiErrorMessage(error);
      requestId.value = getApiErrorRequestId(error);
      return false;
    } finally {
      isPlanning.value = false;
    }
  };

  const runOptimization = async (
    preparedFiles?: ContextFileInput[],
    options: {
      rawPrompt?: string;
      planConfirmation?: PlanConfirmation;
    } = {},
  ): Promise<boolean> => {
    const sourcePrompt = options.rawPrompt ?? rawPrompt.value;
    if (!sourcePrompt.trim()) {
      errorMessage.value = '请先输入需要增强的内容。';
      return false;
    }
    if (isPlanning.value || isOptimizing.value) {
      return false;
    }

    isOptimizing.value = true;
    errorMessage.value = '';
    try {
      const contextFiles = preparedFiles ?? await resolveContextFiles(sourcePrompt);
      const response = await optimizePrompt(buildOptimizationRequest({
        rawPrompt: sourcePrompt,
        customDescription: customDescription.value,
        files: contextFiles,
        templateCode: templateCode.value,
        includePermissionBoundaries: true,
        includeExamples: includeExamples.value,
        planConfirmation: options.planConfirmation,
      }));
      // 直接展示增强结果；用户输入的原始提示词保持不变，不做覆盖。
      rememberCurrentResult();
      result.value = response.data;
      contextSnapshot.value = response.data.contextReport;
      requestId.value = response.requestId;
      return true;
    } catch (error: unknown) {
      errorMessage.value = getApiErrorMessage(error);
      requestId.value = getApiErrorRequestId(error);
      return false;
    } finally {
      isOptimizing.value = false;
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
      const requiredConstraints = result.value?.appliedConstraints ?? [];
      const missingConstraints = requiredConstraints.filter((constraint) =>
        !section.content.includes(constraint));
      if (missingConstraints.length === 0) {
        return { ...section };
      }
      const marker = '平台强制约束（不得删除或弱化）：';
      const separator = section.content.includes(marker) ? '\n' : `\n\n${marker}\n`;
      return {
        ...section,
        content: `${section.content.trim()}${separator}${missingConstraints
          .map((constraint) => `- ${constraint}`)
          .join('\n')}`,
      };
    });
    const optimizedPrompt = normalizedSections
      .filter((section) => section.type !== 'CLARIFICATIONS')
      .map((section) => `## ${section.title}\n${section.content}`)
      .join('\n\n');
    if (optimizedPrompt === result.value.optimizedPrompt) {
      return true;
    }
    rememberCurrentResult();
    result.value = { ...result.value, sections: normalizedSections, optimizedPrompt };
    errorMessage.value = '';
    return true;
  };

  const undoResult = (): boolean => {
    const previous = resultUndoStack.value.at(-1);
    if (!previous) {
      return false;
    }
    result.value = previous;
    contextSnapshot.value = previous.contextReport;
    templateCode.value = previous.templateCode;
    resultUndoStack.value = resultUndoStack.value.slice(0, -1);
    errorMessage.value = '';
    return true;
  };

  const rememberCurrentResult = (): void => {
    if (result.value) {
      resultUndoStack.value = [...resultUndoStack.value, result.value].slice(-20);
    }
  };

  // 从历史详情恢复工作台输入，让用户能在原需求基础上继续修改。
  // 历史记录不保存文件正文，因此文件列表会清空，只恢复描述和选项。
  const loadFromHistory = (detail: OptimizationHistoryDetail): void => {
    rawPrompt.value = detail.rawPrompt;
    customDescription.value =
      typeof detail.contextSummary?.customDescription === 'string'
        ? detail.contextSummary.customDescription
        : '';
    templateCode.value = detail.templateCode;
    includePermissionBoundaries.value = detail.includePermissionBoundaries;
    includeExamples.value = detail.includeExamples;
    if (projectIndex.value) {
      void projectIndexRepository.deleteProject(projectIndex.value.id).catch(() => undefined);
    }
    projectIndex.value = undefined;
    cleanupUnusedDocumentReferences(files.value, []);
    files.value = [];
    activeFilePath.value = '';
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
    plan.value = undefined;
    result.value = undefined;
    resultUndoStack.value = [];
    requestId.value = '';
    errorMessage.value = '';
  };

  // 服务端重新优化完成后，直接把新结果放回工作台结果区。
  const applyReoptimized = (payload: ReoptimizationResult): void => {
    rememberCurrentResult();
    result.value = payload.result;
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
    projectIndex,
    contextRetrieval,
    activeFilePath,
    contextSnapshot,
    plan,
    result,
    requestId,
    errorMessage,
    isAnalyzing,
    isPlanning,
    isOptimizing,
    canOptimize,
    canUndoResult,
    setFiles,
    setProjectIndex,
    addFile,
    removeFile,
    clearFiles,
    prepareContextFiles,
    runContextAnalysis,
    createOptimizationPlan,
    runOptimization,
    saveEditedSections,
    undoResult,
    loadFromHistory,
    applyReoptimized,
  };
});

const normalizePath = (path: string): string => path.replace(/\\/g, '/').toLowerCase();

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
    void deleteDocumentUpload(documentId).catch(() => undefined);
  });
};
