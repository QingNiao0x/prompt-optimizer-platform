import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';

import { buildOptimizationRequest } from '@/features/optimization/optimizationRequest';
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
import { analyzeContext, optimizePrompt } from '@/services/promptOptimizerApi';
import { MAX_FILES } from '@/workers/fileReaderCore';
import type {
  ContextFileInput,
  ContextSnapshot,
  OptimizationHistoryDetail,
  OptimizationResult,
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
  const result = ref<OptimizationResult>();
  const requestId = ref('');
  const errorMessage = ref('');
  const isAnalyzing = ref(false);
  const isOptimizing = ref(false);

  const canOptimize = computed(() => rawPrompt.value.trim().length > 0 && !isOptimizing.value);

  const setFiles = (selectedFiles: ContextFileInput[]): void => {
    if (projectIndex.value) {
      void projectIndexRepository.deleteProject(projectIndex.value.id).catch(() => undefined);
      projectIndex.value = undefined;
    }
    files.value = selectedFiles.slice(0, MAX_FILES);
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
      files.value = [];
      activeFilePath.value = '';
    }
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
  };

  const addFile = (file: ContextFileInput): void => {
    const existingIndex = files.value.findIndex((item) => item.path === file.path);
    if (existingIndex >= 0) {
      files.value.splice(existingIndex, 1, file);
    } else if (files.value.length < MAX_FILES) {
      files.value.push(file);
    }
    activeFilePath.value = file.path;
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
  };

  const removeFile = (path: string): void => {
    files.value = files.value.filter((file) => file.path !== path);
    if (activeFilePath.value === path) {
      activeFilePath.value = files.value.at(-1)?.path ?? '';
    }
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
  };

  const clearFiles = (): void => {
    if (projectIndex.value) {
      void projectIndexRepository.deleteProject(projectIndex.value.id).catch(() => undefined);
    }
    projectIndex.value = undefined;
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

  const runOptimization = async (
    preparedFiles?: ContextFileInput[],
  ): Promise<boolean> => {
    if (!canOptimize.value) {
      errorMessage.value = '请先输入需要增强的原始提示词。';
      return false;
    }

    isOptimizing.value = true;
    errorMessage.value = '';
    try {
      const contextFiles = preparedFiles ?? await resolveContextFiles(rawPrompt.value);
      const response = await optimizePrompt(buildOptimizationRequest({
        rawPrompt: rawPrompt.value,
        customDescription: customDescription.value,
        files: contextFiles,
        templateCode: templateCode.value,
        includePermissionBoundaries: includePermissionBoundaries.value,
        includeExamples: includeExamples.value,
      }));
      // 直接展示增强结果；用户输入的原始提示词保持不变，不做覆盖。
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
    files.value = [];
    activeFilePath.value = '';
    contextRetrieval.value = undefined;
    contextSnapshot.value = undefined;
    result.value = undefined;
    requestId.value = '';
    errorMessage.value = '';
  };

  // 服务端重新优化完成后，直接把新结果放回工作台结果区。
  const applyReoptimized = (payload: ReoptimizationResult): void => {
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
    result,
    requestId,
    errorMessage,
    isAnalyzing,
    isOptimizing,
    canOptimize,
    setFiles,
    setProjectIndex,
    addFile,
    removeFile,
    clearFiles,
    prepareContextFiles,
    runContextAnalysis,
    runOptimization,
    loadFromHistory,
    applyReoptimized,
  };
});

const normalizePath = (path: string): string => path.replace(/\\/g, '/').toLowerCase();

const fitFilesWithinBudget = (
  candidates: ContextFileInput[],
  maxCharacters: number,
  maxFiles: number,
): ContextFileInput[] => {
  const selected: ContextFileInput[] = [];
  let remainingCharacters = maxCharacters;
  for (const file of candidates) {
    if (selected.length >= maxFiles || remainingCharacters <= 0) {
      break;
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
