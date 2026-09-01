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
  ElEmpty,
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
  return `已发现 ${progress.discoveredFiles} · 已索引 ${progress.indexedFiles} · 已忽略 ${progress.ignoredFiles}`;
});

const isProjectBusy = computed(() =>
  props.isReading || props.isIndexing || props.isSelectingDirectory || selecting.value);

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
            {{ isIndexing
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
              :percentage="isReading && progress?.phase === 'read' ? progress.percent : undefined"
              :indeterminate="isIndexing || !isReading || progress?.phase !== 'read'"
              :duration="3"
              :stroke-width="8"
              :show-text="!isIndexing && isReading && progress?.phase === 'read'"
              aria-label="项目文件处理进度"
            />
            <small class="reading-meta" aria-live="polite">
              {{ isIndexing
                ? indexingLabel
                : isReading
                  ? readingLabel
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
        <Search />
        <span>识别结果</span>
      </div>
      <div v-if="snapshot.technologyStack.length" class="tag-cloud">
        <ElTag
          v-for="item in snapshot.technologyStack"
          :key="`${item.name}-${item.source}`"
          effect="plain"
          round
        >
          {{ item.name }}
        </ElTag>
      </div>
      <ElEmpty v-else :image-size="48" description="暂未识别到明确技术栈" />
      <p class="analysis-meta">
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
  overflow: hidden;
  color: var(--accent-blue) !important;
  font-size: 11px;
  line-height: 1.55;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  grid-template-columns: 62px minmax(0, 1fr) 24px;
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
  gap: 7px;
  margin-bottom: 10px;
  color: var(--ink-strong);
  font-size: 12px;
  font-weight: 600;
}

.analysis-title svg {
  width: 14px;
  color: var(--success);
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
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
