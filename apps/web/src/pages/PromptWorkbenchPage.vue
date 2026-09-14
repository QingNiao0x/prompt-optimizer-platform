<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue';
import { ElAlert, ElMessage, ElMessageBox } from 'element-plus';
import { storeToRefs } from 'pinia';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import ContextPanel from '@/components/context/ContextPanel.vue';
import OptimizationResultPanel from '@/components/prompt/OptimizationResultPanel.vue';
import PlanQuestionDialog from '@/components/prompt/PlanQuestionDialog.vue';
import PromptComposer from '@/components/prompt/PromptComposer.vue';
import type { DroppedFileCollection } from '@/composables/fileDrop';
import { useProjectIndex } from '@/composables/useProjectIndex';
import { useProjectFiles } from '@/composables/useProjectFiles';
import { useOptimizationStore } from '@/stores/optimization';
import { useProjectContextSettingsStore } from '@/stores/projectContextSettings';
import type { ContextFileInput, PlanConfirmation, PromptSection } from '@/types/api';

const store = useOptimizationStore();
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
  includeExamples,
  projectIndex,
  contextRetrieval,
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
} = storeToRefs(store);

const {
  isReading,
  warnings,
  progress,
  selectFiles,
  selectFileArray,
  reset: resetProjectFiles,
} = useProjectFiles();
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
const pendingPrompt = ref('');
const isClearingIndex = ref(false);
let pageLifecycleVersion = 0;

// 分析按钮需要抽取项目画像，而不是只围绕某个业务提示词检索。
// 将常见分层目录放在查询前部，可以让本地索引同时召回构建清单和代表性模块源码。
const PROJECT_OVERVIEW_RETRIEVAL_QUERY = [
  'pom package controller service repository domain entity view page component store router',
  '技术栈 依赖 项目结构 配置 功能模块 模块职责',
].join(' ');

const contextWarnings = computed(() => Array.from(new Set([
  ...warnings.value,
  ...(contextSnapshot.value?.warnings ?? []),
  ...(indexErrorMessage.value ? [indexErrorMessage.value] : []),
])));

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
  try {
    const options = {
      limits: effectiveIndexLimits.value,
      retention: projectContextSettings.value.retention,
      autoCleanupDays: projectContextSettings.value.autoCleanupDays,
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
  const deletion = cancelIndexing();
  store.clearFiles();
  try {
    await deletion;
    ElMessage.success('本地项目索引已删除。');
  } catch {
    ElMessage.error('索引已从当前上下文移除，但本地数据清理失败，请在设置页重试。');
  } finally {
    isClearingIndex.value = false;
  }
};

const handleClearContextFiles = async (): Promise<void> => {
  if (isClearingIndex.value) {
    return;
  }
  const shouldDeleteIndex = Boolean(projectIndex.value || isIndexing.value);
  const deletion = shouldDeleteIndex ? cancelIndexing() : Promise.resolve();
  store.clearFiles();
  if (!shouldDeleteIndex) {
    return;
  }
  isClearingIndex.value = true;
  try {
    await deletion;
  } catch {
    ElMessage.error('上下文已清空，但本地索引数据清理失败，请在设置页重试。');
  } finally {
    isClearingIndex.value = false;
  }
};

const handleFilesSelected = async (fileList: FileList | null): Promise<void> => {
  const currentLifecycle = pageLifecycleVersion;
  try {
    const selectedFiles = await selectFiles(fileList);
    if (currentLifecycle !== pageLifecycleVersion) {
      return;
    }
    store.setFiles(selectedFiles);
    if (selectedFiles.length > 0) {
      ElMessage.success(`已读取 ${selectedFiles.length} 个项目文件。`);
    }
  } catch (error: unknown) {
    if (currentLifecycle !== pageLifecycleVersion) {
      return;
    }
    ElMessage.error(error instanceof Error ? error.message : '文件读取失败，请重新选择。');
  }
};

const handleDocumentsSelected = async (fileList: FileList | null): Promise<void> => {
  const currentLifecycle = pageLifecycleVersion;
  try {
    const selectedFiles = await selectFiles(fileList);
    if (currentLifecycle !== pageLifecycleVersion) {
      return;
    }
    selectedFiles.forEach((file) => store.addFile(file));
    if (selectedFiles.length > 0) {
      ElMessage.success(`已加入 ${selectedFiles.length} 个文档或辅助文件。`);
    }
  } catch (error: unknown) {
    if (currentLifecycle !== pageLifecycleVersion) {
      return;
    }
    ElMessage.error(error instanceof Error ? error.message : '文档读取失败，请重新选择。');
  }
};

const handleFilesDropped = async ({ files: droppedFiles, hasDirectory }: DroppedFileCollection): Promise<void> => {
  if (droppedFiles.length === 0) {
    ElMessage.warning('未读取到可处理的文件，请重新拖入文件或文件夹。');
    return;
  }
  const currentLifecycle = pageLifecycleVersion;
  try {
    const selectedFiles = await selectFileArray(droppedFiles);
    if (currentLifecycle !== pageLifecycleVersion) {
      return;
    }
    if (hasDirectory) {
      store.setFiles(selectedFiles);
    } else {
      selectedFiles.forEach((file) => store.addFile(file));
    }
    if (selectedFiles.length > 0) {
      ElMessage.success(
        hasDirectory
          ? `已读取拖入文件夹中的 ${selectedFiles.length} 个文件。`
          : `已加入拖入的 ${selectedFiles.length} 个文件。`,
      );
    }
  } catch (error: unknown) {
    if (currentLifecycle !== pageLifecycleVersion) {
      return;
    }
    ElMessage.error(error instanceof Error ? error.message : '拖拽文件读取失败，请重试。');
  }
};

const handleAddManualFile = (file: ContextFileInput): void => {
  store.addFile(file);
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
  if (!canOptimize.value) {
    return;
  }
  pendingPrompt.value = rawPrompt.value.trim();
  const succeeded = await store.createOptimizationPlan(pendingPrompt.value);
  if (!succeeded) {
    return;
  }
  if ((plan.value?.questions.length ?? 0) > 0) {
    planDialogVisible.value = true;
    return;
  }
  await generateFinalPrompt({ answers: [] });
};

const generateFinalPrompt = async (confirmation: PlanConfirmation): Promise<void> => {
  const contextFiles = await prepareContextTransmission(
    pendingPrompt.value,
    '一键增强提示词',
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
    ElMessage.success('最终提示词已生成。');
  }
};

const handlePlanConfirmed = async (confirmation: PlanConfirmation): Promise<void> => {
  await generateFinalPrompt(confirmation);
};

const handleSaveResult = (sections: PromptSection[]): void => {
  if (store.saveEditedSections(sections)) {
    ElMessage.success('修改已保存。');
  }
};

const handleUndoResult = (): void => {
  if (store.undoResult()) {
    ElMessage.success('已撤销上一次修改。');
  }
};

const handleReEnhance = async (): Promise<void> => {
  if (!result.value || isPlanning.value || isOptimizing.value) {
    return;
  }
  pendingPrompt.value = result.value.optimizedPrompt;
  const succeeded = await store.createOptimizationPlan(pendingPrompt.value);
  if (!succeeded) {
    return;
  }
  if ((plan.value?.questions.length ?? 0) > 0) {
    planDialogVisible.value = true;
    return;
  }
  await generateFinalPrompt({ answers: [] });
};

const prepareContextTransmission = async (
  query: string,
  operationName: string,
  willCallModel: boolean,
): Promise<ContextFileInput[] | undefined> => {
  try {
    const contextFiles = await store.prepareContextFiles(query);
    if (!projectContextSettings.value.confirmBeforeSendingCode || contextFiles.length === 0) {
      return contextFiles;
    }
    const characters = contextFiles.reduce((total, file) => total + file.content.length, 0);
    const indexedDocuments = contextFiles.filter((file) => file.documentId);
    const indexedDocumentBytes = indexedDocuments.reduce(
      (total, file) => total + (file.sizeBytes ?? 0),
      0,
    );
    const destination = willCallModel
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
    return contextFiles;
  } catch (error: unknown) {
    if (error === 'cancel' || error === 'close') {
      return undefined;
    }
    ElMessage.error(error instanceof Error ? error.message : '读取本地上下文失败。');
    return undefined;
  }
};

const formatBytes = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

const handlePageHide = (): void => {
  pageLifecycleVersion += 1;
  resetProjectFiles();
  resetProjectIndexPageState();
  store.clearFiles();
};

onMounted(() => {
  clearPersistedProjectSelection();
  void projectContextSettingsStore.refreshStorageStatus();
  window.addEventListener('pagehide', handlePageHide);
  if (route.query.plan === '1') {
    void router.replace({ path: '/', query: {} }).then(() => handleOptimize());
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', handlePageHide);
});
</script>

<template>
  <div class="workbench-page">
    <header class="page-intro">
      <div>
        <p class="intro-kicker">Context-aware prompt engineering</p>
        <p>先补齐真正影响结果的细节，再一次生成可直接使用的提示词。</p>
      </div>
      <div class="pipeline-note" aria-label="处理流程">
        <span>原始目标</span>
        <i></i>
        <span>关键确认</span>
        <i></i>
        <span>结构化输出</span>
      </div>
    </header>

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

    <div class="workbench-grid">
      <ContextPanel
        :custom-description="customDescription"
        :files="files"
        :warnings="contextWarnings"
        :project-index="projectIndex"
        :context-retrieval="contextRetrieval"
        :snapshot="contextSnapshot"
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

      <div class="prompt-workspace">
        <PromptComposer
          :raw-prompt="rawPrompt"
          :include-examples="includeExamples"
          :is-planning="isPlanning"
          :is-optimizing="isOptimizing"
          :can-optimize="canOptimize"
          @update:raw-prompt="rawPrompt = $event"
          @update:include-examples="includeExamples = $event"
          @optimize="handleOptimize"
        />
        <OptimizationResultPanel
          :result="result"
          :busy="isPlanning || isOptimizing"
          :can-undo="canUndoResult"
          @save="handleSaveResult"
          @undo="handleUndoResult"
          @re-enhance="handleReEnhance"
        />
      </div>
    </div>

    <PlanQuestionDialog
      v-model="planDialogVisible"
      :plan="plan"
      :is-generating="isOptimizing"
      :error-message="errorMessage"
      @confirm="handlePlanConfirmed"
    />
  </div>
</template>

<style scoped>
.workbench-page {
  min-width: 0;
}

.page-intro {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 30px;
  margin-bottom: 24px;
  padding: 0 6px;
}

.intro-kicker {
  margin: 0 0 8px;
  color: var(--accent-cyan) !important;
  font-family: var(--font-mono);
  font-size: 9px !important;
  letter-spacing: 0.13em;
  text-transform: uppercase;
}

.page-intro p {
  margin: 0;
  color: var(--ink-muted);
  font-size: 14px;
}

.pipeline-note {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 10px;
  white-space: nowrap;
}

.pipeline-note i {
  width: 30px;
  height: 1px;
  background: linear-gradient(90deg, var(--line-strong), var(--accent-cyan));
}

.error-alert {
  margin-bottom: 18px;
}

.error-alert code {
  font-family: var(--font-mono);
  font-size: 10px;
}

.workbench-grid {
  display: grid;
  grid-template-columns: minmax(290px, 0.62fr) minmax(0, 1.8fr);
  align-items: start;
  gap: 16px;
}

.prompt-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: start;
  gap: 16px;
  min-width: 0;
}

@media (max-width: 1280px) {
  .prompt-workspace {
    grid-template-columns: 1fr;
  }

  .prompt-workspace :deep(.result-panel) {
    margin-top: 0;
  }
}

@media (max-width: 1080px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }

  :deep(.context-panel) {
    position: static;
  }
}

@media (max-width: 720px) {
  .page-intro {
    display: block;
  }

  .pipeline-note {
    margin-top: 12px;
  }
}

@media (max-width: 480px) {
  .pipeline-note {
    display: none;
  }
}
</style>
