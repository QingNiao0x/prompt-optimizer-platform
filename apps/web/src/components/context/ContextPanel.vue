<script setup lang="ts">
import {
  CircleCheck,
  Close,
  DocumentAdd,
  FolderOpened,
  Search,
} from '@element-plus/icons-vue';
import {
  ElButton,
  ElInput,
  ElMessage,
  ElOption,
  ElProgress,
  ElScrollbar,
  ElSelect,
  ElTag,
} from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { collectDroppedFiles, type DroppedFileCollection } from '@/composables/fileDrop';
import {
  UNSUPPORTED_EXTENSIONS,
  type FileProcessingProgress,
} from '@/composables/useProjectFiles';
import type {
  ProjectContextRetrievalResult,
  ProjectIndexProgress,
  ProjectIndexSummary,
} from '@/features/project-index/projectIndexer';
import { buildContextPresentation } from '@/features/context-analysis/contextPresentation';
import type { ContextFileInput, ContextSnapshot } from '@/types/api';

interface Props {
  customDescription: string;
  files: ContextFileInput[];
  warnings: readonly string[];
  snapshot?: ContextSnapshot;
  isReading: boolean;
  isAnalyzing: boolean;
  progress?: FileProcessingProgress | null;
  supportsDirectoryPicker: boolean;
  isSelectingDirectory: boolean;
  isIndexing: boolean;
  isClearingIndex?: boolean;
  isPausing: boolean;
  indexProgress?: ProjectIndexProgress;
  projectIndex?: ProjectIndexSummary;
  contextRetrieval?: ProjectContextRetrievalResult;
}

interface Emits {
  (event: 'update:custom-description', value: string): void;
  (event: 'files-selected', value: FileList | null): void;
  (event: 'documents-selected', value: FileList | null): void;
  (event: 'files-dropped', value: DroppedFileCollection): void;
  (event: 'add-manual-file', value: ContextFileInput): void;
  (event: 'remove-file', path: string): void;
  (event: 'clear-files'): void;
  (event: 'analyze'): void;
  (event: 'index-project'): void;
  (event: 'pause-index'): void;
  (event: 'resume-index'): void;
  (event: 'refresh-index'): void;
  (event: 'cancel-index'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const folderInput = ref<HTMLInputElement>();
const documentInput = ref<HTMLInputElement>();
const selecting = ref(false);
const manualPath = ref('src/example.ts');
const manualLanguage = ref('typescript');
const manualContent = ref('');
const isDragActive = ref(false);

const contextPresentation = computed(() =>
  props.snapshot ? buildContextPresentation(props.snapshot) : undefined);

const isCodeProject = computed(() =>
  contextPresentation.value?.mode === 'CODE_PROJECT');

const descriptionModel = computed({
  get: (): string => props.customDescription,
  set: (value: string): void => emit('update:custom-description', value),
});

const readingLabel = computed(() => {
  if (!props.progress) {
    return '正在读取文件…';
  }
  if (props.progress.phase === 'scan') {
    return `正在扫描目录（${props.progress.current}/${props.progress.total}）· 已选 ${props.progress.accepted}`;
  }
  if (props.progress.phase === 'upload') {
    return `正在分片上传 ${props.progress.fileName} · ${formatBytes(props.progress.uploadedBytes ?? 0)} / ${formatBytes(props.progress.totalBytes ?? 0)}`;
  }
  if (props.progress.phase === 'queue') {
    return `${props.progress.fileName} 已上传，正在等待解析任务…`;
  }
  if (props.progress.phase === 'extract') {
    return `正在解析 ${props.progress.fileName} · 已建立 ${props.progress.indexedChunks ?? 0} 个文本片段`;
  }
  if (props.progress.phase === 'index') {
    return `正在建立全文索引 ${props.progress.fileName} · ${props.progress.indexedChunks ?? 0} 个片段`;
  }
  if (props.progress.phase === 'summarize') {
    return `正在生成分层摘要 ${props.progress.fileName}…`;
  }
  if (props.progress.total === 0) {
    return '正在读取文件…';
  }
  const summary = `已读取 ${props.progress.accepted} · 已跳过 ${props.progress.skipped}`;
  return `正在读取 ${props.progress.fileName}（${props.progress.current}/${props.progress.total}）· ${summary}`;
});

const indexingLabel = computed(() => {
  if (props.isPausing) {
    return '正在保存当前批次和暂停检查点…';
  }
  if (!props.indexProgress) {
    return '正在初始化本地索引…';
  }
  const progress = props.indexProgress;
  const speed = progress.filesPerSecond > 0
    ? `${Math.round(progress.filesPerSecond).toLocaleString()} 文件/秒`
    : '正在计算速度';
  if (progress.phase === 'SCANNING') {
    return `正在统计文件 · 已发现 ${progress.discoveredFiles.toLocaleString()} · ${speed}`;
  }
  const total = progress.totalFiles === undefined
    ? progress.processedFiles.toLocaleString()
    : `${progress.processedFiles.toLocaleString()} / ${progress.totalFiles.toLocaleString()}`;
  const eta = progress.etaMs === undefined
    ? '剩余时间计算中'
    : `预计剩余 ${formatDuration(progress.etaMs)}`;
  return `${total} · 已索引 ${progress.indexedFiles.toLocaleString()} · ${speed} · ${eta}`;
});

const isProjectBusy = computed(() =>
  props.isReading
  || props.isIndexing
  || props.isClearingIndex
  || props.isSelectingDirectory
  || selecting.value);

const displayedProgressPercentage = computed(() => {
  if (props.isIndexing) {
    return props.indexProgress?.percent;
  }
  return props.isReading && props.progress && props.progress.phase !== 'scan'
    ? props.progress.percent
    : undefined;
});

const isProgressIndeterminate = computed(() => displayedProgressPercentage.value === undefined);

const formatDuration = (milliseconds: number): string => {
  const totalSeconds = Math.max(1, Math.round(milliseconds / 1_000));
  if (totalSeconds < 60) {
    return `${totalSeconds} 秒`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return seconds > 0 ? `${minutes} 分 ${seconds} 秒` : `${minutes} 分钟`;
};

const formatBytes = (bytes: number): string => {
  if (bytes <= 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB'];
  const unitIndex = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const value = bytes / (1024 ** unitIndex);
  return `${value >= 10 || unitIndex === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[unitIndex]}`;
};

const formatConfidence = (confidence: number): string => {
  const normalizedConfidence = Math.max(0, Math.min(1, confidence));
  return `${Math.round(normalizedConfidence * 100)}%`;
};

const openFolderPicker = (): void => {
  if (props.supportsDirectoryPicker) {
    emit('index-project');
    return;
  }
  selecting.value = true;
  folderInput.value?.click();
};

const handleFolderChange = (event: Event): void => {
  selecting.value = false;
  const input = event.target as HTMLInputElement;
  emit('files-selected', input.files);
};

const handleFolderCancel = (): void => {
  selecting.value = false;
};

const openDocumentPicker = (): void => {
  documentInput.value?.click();
};

const handleDocumentChange = (event: Event): void => {
  const input = event.target as HTMLInputElement;
  emit('documents-selected', input.files);
};

const isFileDrag = (event: DragEvent): boolean =>
  Array.from(event.dataTransfer?.types ?? []).includes('Files');

const handleDragOver = (event: DragEvent): void => {
  if (!isFileDrag(event) || isProjectBusy.value) {
    return;
  }
  event.preventDefault();
  event.stopPropagation();
  isDragActive.value = true;
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy';
  }
};

const handleDragLeave = (event: DragEvent): void => {
  if (!event.currentTarget || (event.relatedTarget instanceof Node
    && (event.currentTarget as HTMLElement).contains(event.relatedTarget))) {
    return;
  }
  isDragActive.value = false;
};

const handleDrop = async (event: DragEvent): Promise<void> => {
  event.preventDefault();
  event.stopPropagation();
  isDragActive.value = false;
  if (isProjectBusy.value || !event.dataTransfer) {
    return;
  }
  try {
    emit('files-dropped', await collectDroppedFiles(event.dataTransfer));
  } catch {
    ElMessage.error('拖拽文件读取失败，请改用选择文件或文件夹。');
  }
};

const preventFileNavigation = (event: DragEvent): void => {
  if (isFileDrag(event)) {
    event.preventDefault();
  }
};

onMounted(() => {
  window.addEventListener('dragover', preventFileNavigation);
  window.addEventListener('drop', preventFileNavigation);
});

onBeforeUnmount(() => {
  window.removeEventListener('dragover', preventFileNavigation);
  window.removeEventListener('drop', preventFileNavigation);
});

// 读取是异步分片执行的，不能在事件处理器里立即清空 FileList；
// 等 isReading 结束后再清理输入框，既保证 FileList 有效，也允许下次选择同一目录。
watch(
  () => props.isReading,
  (reading) => {
    if (!reading && folderInput.value) {
      folderInput.value.value = '';
    }
    if (!reading && documentInput.value) {
      documentInput.value.value = '';
    }
  },
);

const addManualFile = (): void => {
  if (!manualPath.value.trim() || !manualContent.value.trim()) {
    ElMessage.warning('请填写文件相对路径和代码内容。');
    return;
  }
  emit('add-manual-file', {
    path: manualPath.value.trim(),
    content: manualContent.value,
    language: manualLanguage.value,
  });
  manualContent.value = '';
  ElMessage.success('代码片段已加入本次上下文。');
};
</script>

<template>
  <aside class="context-panel">
    <div class="panel-heading">
      <div>
        <span class="step-label">01 / Context</span>
        <h2>项目上下文</h2>
      </div>
      <ElTag v-if="snapshot" type="success" effect="plain" round>
        <CircleCheck /> 已分析
      </ElTag>
    </div>

    <section class="context-section">
      <label class="field-label" for="project-description">自定义项目描述</label>
      <p class="field-help">描述当前项目、架构偏好或团队约束，不是产品公告。</p>
      <ElInput
        id="project-description"
        v-model="descriptionModel"
        type="textarea"
        :rows="4"
        maxlength="4000"
        show-word-limit
        resize="none"
        placeholder="例如：Spring Boot 3 模块化单体，使用 PostgreSQL；优先保证可读性和自动化测试。"
      />
    </section>

    <section class="context-section">
      <div class="section-title-row">
        <div>
          <span class="field-label">项目与文档上下文</span>
          <p class="field-help">可建立项目索引，也可单独上传报告、论文、表格、演示文稿和图片。</p>
        </div>
        <ElButton
          v-if="files.length || projectIndex"
          text
          size="small"
          type="danger"
          :loading="isClearingIndex"
          :disabled="isClearingIndex"
          @click="emit('clear-files')"
        >
          清空
        </ElButton>
      </div>

      <input
        ref="folderInput"
        class="visually-hidden"
        type="file"
        multiple
        webkitdirectory
        @change="handleFolderChange"
        @cancel="handleFolderCancel"
      />
      <input
        ref="documentInput"
        class="visually-hidden"
        type="file"
        multiple
        accept=".txt,.md,.rst,.tex,.csv,.tsv,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.pdf,.wps,.et,.dps,.odt,.ods,.odp,.png,.jpg,.jpeg,.gif,.webp,.bmp,.svg"
        @change="handleDocumentChange"
      />
      <button
        class="folder-dropzone"
        :class="{ 'is-drag-active': isDragActive }"
        type="button"
        :disabled="isProjectBusy"
        @click="openFolderPicker"
        @dragenter.prevent.stop="isDragActive = true"
        @dragover.prevent.stop="handleDragOver"
        @dragleave.prevent.stop="handleDragLeave"
        @drop.prevent.stop="handleDrop"
      >
        <span class="dropzone-icon"><FolderOpened /></span>
        <span class="dropzone-copy">
          <strong>
            {{ isClearingIndex
              ? '正在清理本地索引'
              : isIndexing
              ? '正在建立本地项目索引'
              : isReading
                ? '正在处理项目文件'
                : isSelectingDirectory || selecting
                  ? '正在等待目录授权…'
                   : '选择本地项目文件夹，或拖入文件/文件夹' }}
          </strong>
          <small v-if="!isProjectBusy">
             支持点击选择，也支持将单个文件或文件夹拖到这里
          </small>
          <template v-else>
            <ElProgress
              class="read-progress"
              :percentage="displayedProgressPercentage"
              :indeterminate="isProgressIndeterminate"
              :duration="3"
              :stroke-width="8"
              :show-text="displayedProgressPercentage !== undefined"
              aria-label="项目文件处理进度"
            />
            <small class="reading-meta" aria-live="polite">
              {{ isIndexing
                ? indexingLabel
                : isReading
                  ? readingLabel
                  : isClearingIndex
                    ? '上下文已清空，正在删除浏览器中的本地数据…'
                    : '请选择需要授权的项目目录…' }}
            </small>
          </template>
        </span>
      </button>

      <ElButton
        class="document-upload-button"
        :icon="DocumentAdd"
        :disabled="isProjectBusy"
        plain
        @click="openDocumentPicker"
      >
        添加文档、表格、演示稿或图片
      </ElButton>
      <p class="document-upload-help">
        大型文档将按 1 MiB 分片发送至本项目后端，解析后只保留最长 2 小时的临时全文索引；
        一键增强时仅选取与当前任务相关的片段。
      </p>

      <div v-if="isIndexing" class="index-actions">
        <ElButton
          data-testid="pause-index"
          plain
          size="small"
          :loading="isPausing"
          @click="emit('pause-index')"
        >
          {{ isPausing ? '正在暂停' : '暂停索引' }}
        </ElButton>
        <ElButton text size="small" type="danger" @click="emit('cancel-index')">
          取消并删除索引
        </ElButton>
      </div>

      <div v-else-if="projectIndex" class="index-actions">
        <ElButton
          v-if="projectIndex.status === 'PAUSED'"
          plain
          size="small"
          type="primary"
          @click="emit('resume-index')"
        >
          继续索引
        </ElButton>
        <ElButton
          v-if="projectIndex.status === 'READY'"
          plain
          size="small"
          @click="emit('refresh-index')"
        >
          增量更新
        </ElButton>
        <ElButton text size="small" type="danger" @click="emit('cancel-index')">
          删除本地索引
        </ElButton>
      </div>

      <div class="unsupported-note" role="note" aria-label="暂不支持的文件格式">
        <span class="unsupported-title">暂不支持</span>
        <div class="unsupported-tags">
          <ElTag
            v-for="suffix in UNSUPPORTED_EXTENSIONS"
            :key="suffix"
            size="small"
            type="info"
            effect="plain"
            round
          >
            {{ suffix }}
          </ElTag>
        </div>
      </div>

      <ul v-if="warnings.length" class="warning-list" aria-live="polite">
        <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
      </ul>

      <div v-if="projectIndex" class="project-index-summary">
        <strong>{{ projectIndex.rootName }}</strong>
        <span v-if="projectIndex.status === 'PAUSED'">
          已暂停，检查点位于 {{ projectIndex.lastCheckpointPath || '目录起点' }}
        </span>
        <span v-else>{{ projectIndex.indexedFiles }} 个源码文件已建立本地索引</span>
        <small>
          {{ projectIndex.chunkCount }} 个代码块 ·
          {{ projectIndex.ignoredFiles }} 个文件已忽略 ·
          {{ projectIndex.metadataOnlyFiles }} 个超大文件仅保留元数据
        </small>
        <small v-if="projectIndex.status === 'READY'">
          本轮新增 {{ projectIndex.addedFiles }} · 更新 {{ projectIndex.updatedFiles }} ·
          未变化 {{ projectIndex.unchangedFiles }} · 删除 {{ projectIndex.removedFiles }}
        </small>
        <details
          v-if="contextRetrieval?.selections.length"
          class="retrieval-report"
        >
          <summary>
            查看本次代码选择依据（{{ contextRetrieval.selections.length }} 段，
            {{ contextRetrieval.totalCharacters.toLocaleString('zh-CN') }} 字符）
          </summary>
          <ul>
            <li
              v-for="selection in contextRetrieval.selections.slice(0, 8)"
              :key="`${selection.path}-${selection.chunkIndex}`"
            >
              <span :title="selection.path">{{ selection.path }}</span>
              <small>{{ selection.reasons.join('、') }}</small>
            </li>
          </ul>
        </details>
      </div>

      <div v-if="files.length" class="file-summary">
        <div class="file-count">
          <span>{{ files.length }}</span>
          <small>个文件已加入上下文</small>
        </div>
        <ElScrollbar max-height="184px">
          <ul class="file-list">
            <li v-for="file in files" :key="file.path">
              <span class="file-language">{{ file.language }}</span>
              <span class="file-path" :title="file.path">{{ file.path }}</span>
              <span v-if="file.documentId" class="indexed-document-badge">全文已索引</span>
              <button
                type="button"
                :aria-label="`移除 ${file.path}`"
                @click="emit('remove-file', file.path)"
              >
                <Close />
              </button>
            </li>
          </ul>
        </ElScrollbar>
      </div>
    </section>

    <details class="manual-snippet">
      <summary>
        <DocumentAdd />
        <span>粘贴当前打开文件</span>
      </summary>
      <div class="snippet-form">
        <div class="snippet-meta">
          <ElInput v-model="manualPath" aria-label="文件相对路径" placeholder="src/example.ts" />
          <ElSelect v-model="manualLanguage" aria-label="代码语言">
            <ElOption label="TypeScript" value="typescript" />
            <ElOption label="JavaScript" value="javascript" />
            <ElOption label="Vue" value="vue" />
            <ElOption label="Java" value="java" />
            <ElOption label="Python" value="python" />
            <ElOption label="其他文本" value="text" />
          </ElSelect>
        </div>
        <ElInput
          v-model="manualContent"
          type="textarea"
          :rows="6"
          maxlength="300000"
          resize="vertical"
          placeholder="粘贴与当前任务相关的代码片段…"
        />
        <ElButton :icon="DocumentAdd" plain @click="addManualFile">加入上下文</ElButton>
      </div>
    </details>

    <section v-if="snapshot" class="analysis-result">
      <div class="analysis-title">
        <span class="analysis-title-main">
          <Search />
          <span>识别结果</span>
        </span>
        <ElTag class="analysis-mode-tag" size="small" effect="plain" round>
          {{ contextPresentation?.modeLabel }}
        </ElTag>
      </div>

      <div
        v-if="snapshot.fileCoverage?.length"
        class="analysis-coverage"
        :class="`is-${(snapshot.analysisStatus ?? 'complete').toLowerCase()}`"
      >
        <strong>
          {{ snapshot.analysisStatus === 'COMPLETE' ? '文件解析完整' : '部分内容需要留意' }}
        </strong>
        <span>
          已检查 {{ snapshot.fileCoverage.length }} 个文件；模型请求只使用相关片段，不代表源文件未完整索引。
        </span>
        <details>
          <summary>查看解析与选取范围</summary>
          <ul>
            <li v-for="coverage in snapshot.fileCoverage" :key="coverage.path">
              <span :title="coverage.path">{{ coverage.path }}</span>
              <small>
                {{ coverage.extractionStatus }} · 已提取 {{ coverage.extractedCharacters.toLocaleString('zh-CN') }} 字符 ·
                本次选取 {{ coverage.selectedChunks }}/{{ coverage.indexedChunks }} 段
              </small>
            </li>
          </ul>
        </details>
      </div>

      <template v-if="isCodeProject && contextPresentation">
        <section class="project-overview-card">
          <h3>项目概要</h3>
          <p>{{ contextPresentation.overview }}</p>
        </section>

        <section v-if="snapshot.technologyStack.length" class="project-result-section">
          <div class="result-section-heading">
            <span>技术栈</span>
            <span>{{ snapshot.technologyStack.length }} 项</span>
          </div>
          <div class="technology-list">
            <article
              v-for="item in snapshot.technologyStack"
              :key="`${item.name}-${item.source}`"
              class="technology-item"
            >
              <div class="technology-heading">
                <ElTag effect="plain" round>{{ item.name }}</ElTag>
                <span>{{ formatConfidence(item.confidence) }}</span>
              </div>
              <p :title="item.source">来源：{{ item.source }}</p>
            </article>
          </div>
        </section>

        <section v-if="contextPresentation.modules.length" class="project-result-section">
          <div class="result-section-heading">
            <span>功能模块</span>
            <span>{{ contextPresentation.modules.length }} 个</span>
          </div>
          <ElScrollbar max-height="260px" always>
            <ul class="module-summary-list">
              <li
                v-for="module in contextPresentation.modules"
                :key="module.id"
                class="module-summary-item"
              >
                <div class="module-summary-heading">
                  <strong>{{ module.name }}</strong>
                  <span>{{ module.sourceFileCount }} 个来源文件</span>
                </div>
                <code :title="module.path">{{ module.path }}</code>
                <p>{{ module.description }}</p>
              </li>
            </ul>
          </ElScrollbar>
        </section>

        <details v-if="snapshot.dependencies.length" class="project-result-details">
          <summary>
            <span>依赖信息</span>
            <span>{{ snapshot.dependencies.length }} 项</span>
          </summary>
          <ElScrollbar max-height="180px" always>
            <ul class="compact-result-list">
              <li
                v-for="dependency in snapshot.dependencies"
                :key="`${dependency.ecosystem}-${dependency.name}-${dependency.source}`"
              >
                <strong>{{ dependency.name }}</strong>
                <span>
                  {{ dependency.ecosystem }}{{ dependency.version ? ` · ${dependency.version}` : '' }}
                </span>
              </li>
            </ul>
          </ElScrollbar>
        </details>

        <details v-if="snapshot.directoryTree.length" class="project-result-details">
          <summary>
            <span>目录结构</span>
            <span>{{ snapshot.directoryTree.length }} 个节点</span>
          </summary>
          <ElScrollbar max-height="180px" always>
            <ul class="directory-result-list">
              <li v-for="path in snapshot.directoryTree" :key="path">{{ path }}</li>
            </ul>
          </ElScrollbar>
        </details>
      </template>

      <div v-if="snapshot.fileSnippets.length" class="file-summary-section">
        <div class="file-summary-heading">
          <span>{{ isCodeProject ? '代码文件摘要' : '文件内容概要' }}</span>
          <span>{{ snapshot.fileSnippets.length }} 个文件</span>
        </div>
        <ElScrollbar max-height="320px" always>
          <ul class="file-summary-list">
            <li
              v-for="snippet in snapshot.fileSnippets"
              :key="snippet.path"
              class="file-summary-item"
            >
              <div class="file-summary-meta">
                <strong :title="snippet.path">{{ snippet.path }}</strong>
                <ElTag size="small" effect="plain">{{ snippet.language }}</ElTag>
              </div>
              <p>{{ snippet.summary || '未提取到可概括的文本内容。' }}</p>
              <span v-if="snippet.truncated" class="truncated-hint">内容已按当前分析预算截断</span>
            </li>
          </ul>
        </ElScrollbar>
      </div>
      <p v-else class="analysis-empty">当前没有可展示的文件内容摘要。</p>

      <p v-if="isCodeProject" class="analysis-meta">
        {{ snapshot.dependencies.length }} 个依赖 ·
        {{ snapshot.directoryTree.length }} 个目录节点
      </p>
    </section>

    <ElButton
      class="analyze-button"
      :icon="Search"
      :loading="isAnalyzing"
      plain
      @click="emit('analyze')"
    >
      {{ snapshot ? '重新分析上下文' : '分析项目上下文' }}
    </ElButton>
  </aside>
</template>

<style scoped>
.context-panel {
  position: sticky;
  top: 108px;
  align-self: start;
  padding: 20px;
  border: 1px solid var(--line-subtle);
  border-radius: var(--radius-large);
  background: var(--surface-panel);
  box-shadow: var(--shadow-panel);
}

.panel-heading,
.section-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.step-label {
  color: var(--accent-blue);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

h2 {
  margin: 6px 0 0;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: 23px;
  letter-spacing: -0.04em;
}

.panel-heading :deep(.el-tag) {
  gap: 4px;
}

.panel-heading :deep(.el-tag svg) {
  width: 13px;
}

.context-section {
  margin-top: 26px;
}

.field-label {
  display: block;
  color: var(--ink-strong);
  font-size: 13px;
  font-weight: 600;
}

.field-help {
  margin: 6px 0 11px;
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.65;
}

.folder-dropzone {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 14px;
  padding: 16px;
  border: 1px dashed var(--line-strong);
  border-radius: 10px;
  color: var(--ink-muted);
  text-align: left;
  background: var(--surface-code);
  cursor: pointer;
  transition: border-color 160ms ease, background 160ms ease, transform 160ms ease;
}

.folder-dropzone:hover {
  border-color: var(--accent-blue);
  background: rgba(111, 124, 255, 0.08);
  transform: translateY(-1px);
}

.folder-dropzone.is-drag-active {
  border-color: var(--accent-cyan);
  background: color-mix(in srgb, var(--accent-cyan) 12%, var(--surface-code));
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-cyan) 16%, transparent);
}

.folder-dropzone:disabled {
  cursor: progress;
  opacity: 0.75;
}

.dropzone-icon {
  display: grid;
  flex: 0 0 38px;
  height: 38px;
  place-items: center;
  border-radius: 9px;
  color: var(--accent-blue);
  background: rgba(111, 124, 255, 0.12);
}

.dropzone-icon svg {
  width: 19px;
}

.folder-dropzone strong,
.folder-dropzone small {
  display: block;
}

.dropzone-copy {
  min-width: 0;
  flex: 1;
}

.folder-dropzone strong {
  color: var(--ink-strong);
  font-size: 12px;
}

.folder-dropzone small {
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.55;
}

.read-progress {
  width: 100%;
  margin-top: 8px;
}

.document-upload-button {
  width: 100%;
  margin-top: 8px;
}

.document-upload-help {
  margin: 6px 2px 0;
  color: var(--ink-soft);
  font-size: 10px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.index-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 6px;
}

.index-actions :deep(.el-button) {
  flex: 1;
  margin-left: 0;
}

.unsupported-note {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 4px 8px;
  margin: 10px 0 0;
  padding: 8px 10px;
  max-height: 96px;
  overflow-x: hidden;
  overflow-y: auto;
  border: 1px solid var(--line-subtle);
  border-radius: 10px;
  background: var(--surface-code);
  word-break: break-word;
  overflow-wrap: anywhere;
}

.unsupported-title {
  color: var(--ink-soft);
  font-size: 11px;
  line-height: 1.6;
  white-space: nowrap;
}

.unsupported-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  min-width: 0;
}

.read-progress :deep(.el-progress__text) {
  min-width: 38px;
  color: var(--ink-muted);
  font-family: var(--font-mono);
  font-size: 9px;
}

.reading-meta {
  color: var(--accent-blue) !important;
  display: block;
  font-size: 11px;
  line-height: 1.55;
  overflow-wrap: anywhere;
  white-space: normal;
}

.warning-list {
  margin: 10px 0 0;
  padding-left: 18px;
  color: var(--warning);
  font-size: 12px;
  line-height: 1.65;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.project-index-summary {
  display: grid;
  gap: 4px;
  margin-top: 12px;
  padding: 12px;
  border: 1px solid rgba(81, 201, 154, 0.3);
  border-radius: 10px;
  background: rgba(81, 201, 154, 0.07);
}

.project-index-summary strong {
  overflow: hidden;
  color: var(--ink-strong);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-index-summary span {
  color: var(--success);
  font-size: 11px;
}

.project-index-summary small {
  color: var(--ink-soft);
  font-size: 10px;
  line-height: 1.65;
}

.retrieval-report {
  margin-top: 4px;
  padding-top: 7px;
  border-top: 1px solid var(--line-subtle);
}

.retrieval-report summary {
  color: var(--accent-blue);
  font-size: 11px;
  cursor: pointer;
}

.retrieval-report ul {
  display: grid;
  gap: 6px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.retrieval-report li {
  display: grid;
  min-width: 0;
}

.retrieval-report li > span {
  overflow: hidden;
  color: var(--ink-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.retrieval-report li > small {
  color: var(--ink-soft);
  font-size: 10px;
}

.file-summary {
  margin-top: 12px;
  overflow: hidden;
  border: 1px solid var(--line-subtle);
  border-radius: 10px;
}

.file-count {
  display: flex;
  align-items: baseline;
  gap: 7px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line-subtle);
  background: var(--surface-code);
}

.file-count span {
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: 18px;
}

.file-count small {
  color: var(--ink-soft);
  font-size: 11px;
}

.file-list {
  margin: 0;
  padding: 5px;
  list-style: none;
}

.file-list li {
  display: grid;
  grid-template-columns: 62px minmax(0, 1fr) auto 24px;
  align-items: center;
  min-height: 32px;
  padding: 0 5px;
  border-radius: 7px;
}

.file-list li:hover {
  background: rgba(111, 124, 255, 0.08);
}

.file-language,
.file-path {
  font-family: var(--font-mono);
  font-size: 10px;
}

.file-language {
  color: var(--accent-blue);
  text-transform: uppercase;
}

.file-path {
  overflow: hidden;
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-list button {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 0;
  border-radius: 6px;
  color: var(--ink-soft);
  background: transparent;
  cursor: pointer;
}

.file-list button:hover {
  color: var(--danger);
  background: rgba(241, 123, 138, 0.1);
}

.file-list button svg {
  width: 12px;
}

.manual-snippet {
  margin-top: 16px;
  border-top: 1px solid var(--line-subtle);
  border-bottom: 1px solid var(--line-subtle);
}

.manual-snippet summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 13px 2px;
  color: var(--ink-muted);
  font-size: 12px;
  cursor: pointer;
  list-style: none;
}

.manual-snippet summary::-webkit-details-marker {
  display: none;
}

.manual-snippet summary svg {
  width: 15px;
  color: var(--accent-blue);
}

.snippet-form {
  display: grid;
  gap: 10px;
  padding-bottom: 14px;
}

.snippet-meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 126px;
  gap: 8px;
}

.analysis-result {
  margin-top: 18px;
  padding: 14px;
  border: 1px solid rgba(81, 201, 154, 0.3);
  border-radius: 10px;
  background: rgba(81, 201, 154, 0.06);
}

.analysis-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 7px;
  margin-bottom: 10px;
  color: var(--ink-strong);
  font-size: 12px;
  font-weight: 600;
}

.indexed-document-badge {
  margin-left: 6px;
  color: var(--success);
  font-family: var(--font-mono);
  font-size: 9px;
  white-space: nowrap;
}

.analysis-title-main {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.analysis-title-main svg {
  width: 14px;
  color: var(--success);
}

.analysis-coverage {
  display: grid;
  gap: 5px;
  margin-bottom: 11px;
  padding: 9px 10px;
  border: 1px solid color-mix(in srgb, var(--success) 34%, var(--line-subtle));
  border-radius: 8px;
  background: color-mix(in srgb, var(--success) 7%, var(--surface-input));
}

.analysis-coverage.is-partial,
.analysis-coverage.is-failed {
  border-color: color-mix(in srgb, var(--warning) 45%, var(--line-subtle));
  background: color-mix(in srgb, var(--warning) 7%, var(--surface-input));
}

.analysis-coverage > strong {
  color: var(--ink-strong);
  font-size: 11px;
}

.analysis-coverage > span,
.analysis-coverage summary,
.analysis-coverage small {
  color: var(--ink-soft);
  font-size: 10px;
  line-height: 1.6;
}

.analysis-coverage details {
  min-width: 0;
}

.analysis-coverage summary {
  width: fit-content;
  color: var(--accent-blue);
  cursor: pointer;
}

.analysis-coverage ul {
  display: grid;
  gap: 6px;
  margin: 7px 0 0;
  padding: 0;
  list-style: none;
}

.analysis-coverage li {
  display: grid;
  min-width: 0;
}

.analysis-coverage li > span {
  overflow: hidden;
  color: var(--ink-normal);
  font-family: var(--font-mono);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analysis-mode-tag {
  flex: 0 0 auto;
}

.project-overview-card {
  padding: 10px;
  border: 1px solid color-mix(in srgb, var(--accent-blue) 26%, var(--line-subtle));
  border-radius: 8px;
  background: color-mix(in srgb, var(--accent-blue) 7%, var(--surface-input));
}

.project-overview-card h3 {
  margin: 0;
  color: var(--ink-strong);
  font-size: 11px;
  font-weight: 650;
}

.project-overview-card p {
  margin: 7px 0 0;
  color: var(--ink-normal);
  font-size: 11px;
  line-height: 1.75;
  overflow-wrap: anywhere;
}

.project-result-section,
.project-result-details {
  margin-top: 13px;
  padding-top: 12px;
  border-top: 1px solid var(--line-subtle);
}

.result-section-heading,
.module-summary-heading,
.project-result-details summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.result-section-heading {
  margin-bottom: 8px;
  color: var(--ink-strong);
  font-size: 11px;
  font-weight: 600;
}

.result-section-heading > span:last-child,
.module-summary-heading > span,
.project-result-details summary > span:last-child {
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 400;
}

.technology-list {
  display: grid;
  gap: 7px;
}

.module-summary-list,
.compact-result-list,
.directory-result-list {
  display: grid;
  gap: 7px;
  margin: 0;
  padding: 0 8px 0 0;
  list-style: none;
}

.module-summary-item {
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--line-subtle);
  border-radius: 8px;
  background: var(--surface-input);
}

.module-summary-heading strong {
  color: var(--ink-strong);
  font-size: 10px;
  font-weight: 600;
}

.module-summary-item code {
  display: block;
  margin-top: 5px;
  color: var(--accent-blue);
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.module-summary-item p {
  margin: 5px 0 0;
  color: var(--ink-normal);
  font-size: 10px;
  line-height: 1.65;
}

.project-result-details summary {
  color: var(--ink-strong);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  list-style: none;
}

.project-result-details summary::-webkit-details-marker {
  display: none;
}

.project-result-details summary::before {
  content: '▸';
  margin-right: 5px;
  color: var(--accent-blue);
}

.project-result-details[open] summary::before {
  content: '▾';
}

.project-result-details summary > span:first-child {
  margin-right: auto;
}

.project-result-details :deep(.el-scrollbar) {
  margin-top: 8px;
}

.compact-result-list li {
  display: grid;
  gap: 3px;
  padding: 7px 8px;
  border-radius: 7px;
  background: var(--surface-input);
}

.compact-result-list strong {
  color: var(--ink-normal);
  font-family: var(--font-mono);
  font-size: 9px;
  overflow-wrap: anywhere;
}

.compact-result-list span,
.directory-result-list li {
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.directory-result-list li {
  padding: 5px 7px;
  border-left: 2px solid color-mix(in srgb, var(--accent-blue) 40%, transparent);
  background: color-mix(in srgb, var(--surface-input) 78%, transparent);
}

.technology-item {
  min-width: 0;
  padding: 8px 9px;
  border: 1px solid var(--line-subtle);
  border-radius: 8px;
  background: color-mix(in srgb, var(--surface-input) 76%, transparent);
}

.technology-heading,
.file-summary-heading,
.file-summary-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.technology-heading > span {
  flex: 0 0 auto;
  color: var(--success);
  font-family: var(--font-mono);
  font-size: 10px;
}

.technology-item p {
  margin: 6px 0 0;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.analysis-empty {
  margin: 0;
  color: var(--ink-soft);
  font-size: 11px;
  line-height: 1.65;
}

.file-summary-section {
  margin-top: 13px;
  padding-top: 12px;
  border-top: 1px solid var(--line-subtle);
}

.file-summary-heading {
  margin-bottom: 8px;
  color: var(--ink-strong);
  font-size: 11px;
  font-weight: 600;
}

.file-summary-heading > span:last-child {
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 400;
}

.file-summary-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0 8px 0 0;
  list-style: none;
}

.file-summary-item {
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--line-subtle);
  border-radius: 8px;
  background: var(--surface-input);
}

.file-summary-meta strong {
  min-width: 0;
  color: var(--ink-strong);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-summary-meta :deep(.el-tag) {
  flex: 0 0 auto;
  max-width: 88px;
}

.file-summary-item > p {
  margin: 7px 0 0;
  color: var(--ink-normal);
  font-size: 11px;
  line-height: 1.7;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.truncated-hint {
  display: block;
  margin-top: 6px;
  color: var(--warning);
  font-size: 9px;
}

.analysis-meta {
  margin: 11px 0 0;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 10px;
}

.analyze-button {
  width: 100%;
  margin-top: 18px;
}

@media (max-width: 1080px) {
  .context-panel {
    position: static;
  }
}
</style>
