import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

import { buildOptimizationRequest } from '@/features/optimization/optimizationRequest';
import { getApiErrorMessage, getApiErrorRequestId } from '@/services/http';
import { analyzeContext, optimizePrompt } from '@/services/promptOptimizerApi';
import type {
  ContextFileInput,
  ContextSnapshot,
  OptimizationResult,
  TemplateCode,
} from '@/types/api';

const MAX_FILES = 200;

export const useOptimizationStore = defineStore('optimization', () => {
  const rawPrompt = ref('');
  const customDescription = ref('');
  const files = ref<ContextFileInput[]>([]);
  const templateCode = ref<TemplateCode>('AUTO');
  const includePermissionBoundaries = ref(true);
  const includeExamples = ref(false);
  const contextSnapshot = ref<ContextSnapshot>();
  const result = ref<OptimizationResult>();
  const requestId = ref('');
  const errorMessage = ref('');
  const isAnalyzing = ref(false);
  const isOptimizing = ref(false);

  const canOptimize = computed(() => rawPrompt.value.trim().length > 0 && !isOptimizing.value);

  const setFiles = (selectedFiles: ContextFileInput[]): void => {
    files.value = selectedFiles.slice(0, MAX_FILES);
    contextSnapshot.value = undefined;
  };

  const addFile = (file: ContextFileInput): void => {
    const existingIndex = files.value.findIndex((item) => item.path === file.path);
    if (existingIndex >= 0) {
      files.value.splice(existingIndex, 1, file);
    } else if (files.value.length < MAX_FILES) {
      files.value.push(file);
    }
    contextSnapshot.value = undefined;
  };

  const removeFile = (path: string): void => {
    files.value = files.value.filter((file) => file.path !== path);
    contextSnapshot.value = undefined;
  };

  const clearFiles = (): void => {
    files.value = [];
    contextSnapshot.value = undefined;
  };

  const runContextAnalysis = async (): Promise<boolean> => {
    isAnalyzing.value = true;
    errorMessage.value = '';
    try {
      const response = await analyzeContext({
        customDescription: customDescription.value.trim(),
        files: files.value,
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

  const runOptimization = async (): Promise<boolean> => {
    if (!canOptimize.value) {
      errorMessage.value = '请先输入需要增强的原始提示词。';
      return false;
    }

    isOptimizing.value = true;
    errorMessage.value = '';
    try {
      const response = await optimizePrompt(buildOptimizationRequest({
        rawPrompt: rawPrompt.value,
        customDescription: customDescription.value,
        files: files.value,
        templateCode: templateCode.value,
        includePermissionBoundaries: includePermissionBoundaries.value,
        includeExamples: includeExamples.value,
      }));
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

  return {
    rawPrompt,
    customDescription,
    files,
    templateCode,
    includePermissionBoundaries,
    includeExamples,
    contextSnapshot,
    result,
    requestId,
    errorMessage,
    isAnalyzing,
    isOptimizing,
    canOptimize,
    setFiles,
    addFile,
    removeFile,
    clearFiles,
    runContextAnalysis,
    runOptimization,
  };
});
