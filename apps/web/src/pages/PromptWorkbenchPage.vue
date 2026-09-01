<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue';
import { ElAlert, ElButton, ElDialog, ElMessage, ElMessageBox } from 'element-plus';
import { storeToRefs } from 'pinia';
import { computed, onMounted, ref } from 'vue';

import ContextPanel from '@/components/context/ContextPanel.vue';
import OptimizationResultPanel from '@/components/prompt/OptimizationResultPanel.vue';
import PromptComposer from '@/components/prompt/PromptComposer.vue';
import type { DroppedFileCollection } from '@/composables/fileDrop';
import { useProjectIndex } from '@/composables/useProjectIndex';
import { useProjectFiles } from '@/composables/useProjectFiles';
import { useOptimizationStore } from '@/stores/optimization';
import { useProjectContextSettingsStore } from '@/stores/projectContextSettings';
import type { ContextFileInput, TemplateCode } from '@/types/api';

const store = useOptimizationStore();
const projectContextSettingsStore = useProjectContextSettingsStore();
const {
  settings: projectContextSettings,
  effectiveIndexLimits,
} = storeToRefs(projectContextSettingsStore);
const {
  rawPrompt,
  customDescription,
  files,
  templateCode,
  includePermissionBoundaries,
  includeExamples,
  projectIndex,
  contextRetrieval,
  contextSnapshot,
  result,
  requestId,
  errorMessage,
  isAnalyzing,
  isOptimizing,
  canOptimize,
} = storeToRefs(store);

const { isReading, warnings, progress, selectFiles, selectFileArray } = useProjectFiles();
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
  restoreCurrentIndex,
  pauseIndexing,
  cancelIndexing,
} = useProjectIndex();

const reviewDialogVisible = ref(false);

const reviewSections = computed(() => {
  const currentResult = result.value;
  const sectionContent = (type: 'CONSTRAINTS' | 'ACCEPTANCE'): string =>
    currentResult?.sections.find((section) => section.type === type)?.content?.trim() ?? '';

  return {
    constraints: currentResult?.appliedConstraints ?? [],
    constraintsContent: sectionContent('CONSTRAINTS'),
    pendingItems: currentResult?.ambiguities ?? [],
    acceptanceContent: sectionContent('ACCEPTANCE'),
  };
});

const contextWarnings = computed(() => [
  ...warnings.value,
  ...(indexErrorMessage.value ? [indexErrorMessage.value] : []),
]);

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
    if (!summary) {
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
    ElMessage.error(error instanceof Error ? error.message : '本地项目索引失败，请重试。');
  }
};

const handleCancelIndex = async (): Promise<void> => {
  await cancelIndexing();
  store.clearFiles();
  ElMessage.info('本地项目索引已删除。');
};

const handleClearContextFiles = async (): Promise<void> => {
  if (projectIndex.value) {
    await cancelIndexing();
  }
  store.clearFiles();
};

const handleFilesSelected = async (fileList: FileList | null): Promise<void> => {
  try {
    const selectedFiles = await selectFiles(fileList);
    store.setFiles(selectedFiles);
    if (selectedFiles.length > 0) {
      ElMessage.success(`已读取 ${selectedFiles.length} 个项目文件。`);
    }
  } catch (error: unknown) {
    ElMessage.error(error instanceof Error ? error.message : '文件读取失败，请重新选择。');
  }
};

const handleDocumentsSelected = async (fileList: FileList | null): Promise<void> => {
  try {
    const selectedFiles = await selectFiles(fileList);
    selectedFiles.forEach((file) => store.addFile(file));
    if (selectedFiles.length > 0) {
      ElMessage.success(`已加入 ${selectedFiles.length} 个文档或辅助文件。`);
    }
  } catch (error: unknown) {
    ElMessage.error(error instanceof Error ? error.message : '文档读取失败，请重新选择。');
  }
};

const handleFilesDropped = async ({ files: droppedFiles, hasDirectory }: DroppedFileCollection): Promise<void> => {
  if (droppedFiles.length === 0) {
    ElMessage.warning('未读取到可处理的文件，请重新拖入文件或文件夹。');
    return;
  }
  try {
    const selectedFiles = await selectFileArray(droppedFiles);
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
    ElMessage.error(error instanceof Error ? error.message : '拖拽文件读取失败，请重试。');
  }
};

const handleAddManualFile = (file: ContextFileInput): void => {
  store.addFile(file);
};

const handleAnalyze = async (): Promise<void> => {
  const contextFiles = await prepareContextTransmission(
    `${customDescription.value} 技术栈 依赖 项目结构 配置`,
    '分析项目上下文',
    false,
  );
  if (contextFiles === undefined) {
    return;
  }
  const succeeded = await store.runContextAnalysis(contextFiles);
  if (succeeded) {
    ElMessage.success('项目上下文分析完成。');
  }
};

const handleOptimize = async (): Promise<void> => {
  if (!canOptimize.value) {
    await store.runOptimization();
    return;
  }
  const contextFiles = await prepareContextTransmission(
    rawPrompt.value,
    '一键增强提示词',
    true,
  );
  if (contextFiles === undefined) {
    return;
  }
  const succeeded = await store.runOptimization(contextFiles);
  if (succeeded) {
    reviewDialogVisible.value = true;
    ElMessage.success('提示词增强完成，结果已展示在下方。');
  }
};

const updateTemplateCode = (value: TemplateCode): void => {
  templateCode.value = value;
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
    const destination = willCallModel
      ? '本项目后端，并由后端转发给当前配置的大模型服务'
      : '本项目 Spring Boot 后端进行上下文分析';
    await ElMessageBox.confirm(
      `${operationName}将发送 ${contextFiles.length} 个相关代码片段，共 ${characters.toLocaleString('zh-CN')} 个字符，到${destination}。完整本地索引不会上传。`,
      '确认发送项目代码',
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
    ElMessage.error(error instanceof Error ? error.message : '读取本地项目上下文失败。');
    return undefined;
  }
};

onMounted(async () => {
  void projectContextSettingsStore.refreshStorageStatus();
  if (projectIndex.value) {
    return;
  }
  try {
    const restored = await restoreCurrentIndex();
    if (restored) {
      store.setProjectIndex(restored);
    }
  } catch {
    // 恢复失败不阻塞工作台，用户仍可重新选择项目目录。
  }
});
</script>

<template>
  <div class="workbench-page">
    <header class="page-intro">
      <div>
        <p class="intro-kicker">Context-aware prompt engineering</p>
        <p>让模型先理解你的工程，再理解你的要求。</p>
      </div>
      <div class="pipeline-note" aria-label="处理流程">
        <span>项目事实</span>
        <i></i>
        <span>需求明确化</span>
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
          :template-code="templateCode"
          :include-permission-boundaries="includePermissionBoundaries"
          :include-examples="includeExamples"
          :is-optimizing="isOptimizing"
          :can-optimize="canOptimize"
          @update:raw-prompt="rawPrompt = $event"
          @update:template-code="updateTemplateCode"
          @update:permission-boundaries="includePermissionBoundaries = $event"
          @update:include-examples="includeExamples = $event"
          @optimize="handleOptimize"
        />
        <OptimizationResultPanel :result="result" />
      </div>
    </div>

    <ElDialog
      v-model="reviewDialogVisible"
      class="optimization-review-dialog"
      width="min(920px, calc(100vw - 32px))"
      top="7vh"
      destroy-on-close
    >
      <template #header>
        <div class="review-dialog-heading">
          <div>
            <span class="review-dialog-kicker">04 / Review before use</span>
            <h2>请先复核这三个关键部分</h2>
          </div>
          <span class="review-dialog-status">ENHANCEMENT READY</span>
        </div>
      </template>

      <p class="review-dialog-intro">
        优化结果已经生成。请检查约束、待确认项和验收标准，再决定是否交给其他模型继续执行。
      </p>

      <div class="review-grid">
        <section class="review-card review-card--constraint">
          <div class="review-card-heading">
            <span class="review-card-index">01</span>
            <div>
              <span class="review-card-kicker">Constraint</span>
              <h3>约束条件</h3>
            </div>
          </div>
          <div v-if="reviewSections.constraints.length" class="review-list">
            <p v-for="constraint in reviewSections.constraints" :key="constraint">{{ constraint }}</p>
          </div>
          <p v-else class="review-empty">模型未单独返回约束条件，请查看增强结果中的“约束”段落。</p>
          <p v-if="reviewSections.constraintsContent" class="review-card-detail">
            {{ reviewSections.constraintsContent }}
          </p>
        </section>

        <section class="review-card review-card--pending">
          <div class="review-card-heading">
            <span class="review-card-index">02</span>
            <div>
              <span class="review-card-kicker">Pending Confirmation Items</span>
              <h3>待确认项</h3>
            </div>
          </div>
          <div v-if="reviewSections.pendingItems.length" class="review-list">
            <p v-for="item in reviewSections.pendingItems" :key="item">{{ item }}</p>
          </div>
          <p v-else class="review-empty">暂未识别到待确认项，但仍建议人工检查关键假设。</p>
        </section>

        <section class="review-card review-card--acceptance">
          <div class="review-card-heading">
            <span class="review-card-index">03</span>
            <div>
              <span class="review-card-kicker">Acceptance Criteria</span>
              <h3>验收标准</h3>
            </div>
          </div>
          <p v-if="reviewSections.acceptanceContent" class="review-card-detail review-card-detail--alone">
            {{ reviewSections.acceptanceContent }}
          </p>
          <p v-else class="review-empty">模型未单独返回验收标准，请查看增强结果中的结构化内容。</p>
        </section>
      </div>

      <template #footer>
        <ElButton type="primary" @click="reviewDialogVisible = false">我已查看，继续使用</ElButton>
      </template>
    </ElDialog>
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

.review-dialog-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.review-dialog-kicker,
.review-card-kicker {
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.review-dialog-heading h2 {
  margin: 8px 0 0;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: 24px;
  line-height: 1.25;
}

.review-dialog-status {
  padding: 6px 8px;
  border: 1px solid color-mix(in srgb, var(--accent-cyan) 35%, var(--line-subtle));
  border-radius: 7px;
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 9px;
  white-space: nowrap;
}

.review-dialog-intro {
  margin: 0 0 18px;
  color: var(--ink-muted);
  font-size: 13px;
  line-height: 1.7;
}

.review-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  max-height: 58vh;
  overflow-y: auto;
  padding: 2px;
}

.review-card {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--line-subtle);
  border-radius: 10px;
  background: var(--surface-code);
}

.review-card--constraint {
  border-color: color-mix(in srgb, var(--accent-blue) 35%, var(--line-subtle));
}

.review-card--pending {
  border-color: color-mix(in srgb, var(--warning) 35%, var(--line-subtle));
}

.review-card--acceptance {
  border-color: color-mix(in srgb, var(--success) 35%, var(--line-subtle));
}

.review-card-heading {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 16px;
}

.review-card-index {
  display: grid;
  width: 25px;
  height: 25px;
  flex: 0 0 25px;
  place-items: center;
  border: 1px solid var(--line-strong);
  border-radius: 7px;
  color: var(--accent-blue);
  font-family: var(--font-mono);
  font-size: 9px;
}

.review-card h3 {
  margin: 5px 0 0;
  color: var(--ink-strong);
  font-size: 15px;
}

.review-list {
  display: grid;
  gap: 8px;
}

.review-list p,
.review-card-detail,
.review-empty {
  margin: 0;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.75;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.review-list p {
  padding-left: 12px;
  border-left: 2px solid var(--accent-blue);
}

.review-card--pending .review-list p {
  border-left-color: var(--warning);
}

.review-card-detail {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line-subtle);
}

.review-card-detail--alone {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}

.review-empty {
  color: var(--ink-soft);
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

  .review-dialog-heading {
    display: block;
  }

  .review-dialog-status {
    display: inline-block;
    margin-top: 12px;
  }

  .review-grid {
    grid-template-columns: 1fr;
    max-height: 62vh;
  }
}
</style>
