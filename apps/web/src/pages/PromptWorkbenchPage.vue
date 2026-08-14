<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue';
import { ElAlert, ElMessage } from 'element-plus';
import { storeToRefs } from 'pinia';

import ContextPanel from '@/components/context/ContextPanel.vue';
import OptimizationResultPanel from '@/components/prompt/OptimizationResultPanel.vue';
import PromptComposer from '@/components/prompt/PromptComposer.vue';
import { useProjectFiles } from '@/composables/useProjectFiles';
import { useOptimizationStore } from '@/stores/optimization';
import type { ContextFileInput, TemplateCode } from '@/types/api';

const store = useOptimizationStore();
const {
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
} = storeToRefs(store);

const { isReading, warnings, selectFiles } = useProjectFiles();

const handleFilesSelected = async (fileList: FileList | null): Promise<void> => {
  const selectedFiles = await selectFiles(fileList);
  store.setFiles(selectedFiles);
  if (selectedFiles.length > 0) {
    ElMessage.success(`已读取 ${selectedFiles.length} 个项目文件。`);
  }
};

const handleAddManualFile = (file: ContextFileInput): void => {
  store.addFile(file);
};

const handleAnalyze = async (): Promise<void> => {
  const succeeded = await store.runContextAnalysis();
  if (succeeded) {
    ElMessage.success('项目上下文分析完成。');
  }
};

const handleOptimize = async (): Promise<void> => {
  const succeeded = await store.runOptimization();
  if (succeeded) {
    ElMessage.success('提示词增强完成。');
  }
};

const updateTemplateCode = (value: TemplateCode): void => {
  templateCode.value = value;
};
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
        :warnings="warnings"
        :snapshot="contextSnapshot"
        :is-reading="isReading"
        :is-analyzing="isAnalyzing"
        @update:custom-description="customDescription = $event"
        @files-selected="handleFilesSelected"
        @add-manual-file="handleAddManualFile"
        @remove-file="store.removeFile"
        @clear-files="store.clearFiles"
        @analyze="handleAnalyze"
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
  margin-bottom: 22px;
  padding: 0 4px;
}

.intro-kicker {
  margin: 0 0 6px;
  color: var(--accent-blue) !important;
  font-family: var(--font-mono);
  font-size: 9px !important;
  letter-spacing: 0.13em;
  text-transform: uppercase;
}

.page-intro p {
  margin: 0;
  color: var(--ink-muted);
  font-size: 13px;
}

.pipeline-note {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 9px;
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
  grid-template-columns: minmax(310px, 0.74fr) minmax(0, 1.8fr);
  align-items: start;
  gap: 22px;
}

.prompt-workspace {
  min-width: 0;
}

@media (max-width: 1080px) {
  .workbench-grid {
    grid-template-columns: 1fr;
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
