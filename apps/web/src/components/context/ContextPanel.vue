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
  ElScrollbar,
  ElSelect,
  ElTag,
} from 'element-plus';
import { computed, ref } from 'vue';

import type { ContextFileInput, ContextSnapshot } from '@/types/api';

interface Props {
  customDescription: string;
  files: ContextFileInput[];
  warnings: readonly string[];
  snapshot?: ContextSnapshot;
  isReading: boolean;
  isAnalyzing: boolean;
}

interface Emits {
  (event: 'update:custom-description', value: string): void;
  (event: 'files-selected', value: FileList | null): void;
  (event: 'add-manual-file', value: ContextFileInput): void;
  (event: 'remove-file', path: string): void;
  (event: 'clear-files'): void;
  (event: 'analyze'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const folderInput = ref<HTMLInputElement>();
const manualPath = ref('src/example.ts');
const manualLanguage = ref('typescript');
const manualContent = ref('');

const descriptionModel = computed({
  get: (): string => props.customDescription,
  set: (value: string): void => emit('update:custom-description', value),
});

const openFolderPicker = (): void => {
  folderInput.value?.click();
};

const handleFolderChange = (event: Event): void => {
  const input = event.target as HTMLInputElement;
  emit('files-selected', input.files);
  input.value = '';
};

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
          <span class="field-label">选择项目文件夹</span>
          <p class="field-help">文件只在本次请求中读取，不会获取电脑上的任意路径。</p>
        </div>
        <ElButton
          v-if="files.length"
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
      />
      <button class="folder-dropzone" type="button" @click="openFolderPicker">
        <span class="dropzone-icon"><FolderOpened /></span>
        <span>
          <strong>{{ isReading ? '正在读取文件…' : '选择本地项目文件夹' }}</strong>
          <small>最多 200 个文本或 Excel 文件，单文件不超过 300 KB</small>
        </span>
      </button>

      <ul v-if="warnings.length" class="warning-list" aria-live="polite">
        <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
      </ul>

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
  padding: 24px;
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
  font-size: 24px;
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
  margin: 5px 0 10px;
  color: var(--ink-soft);
  font-size: 11px;
  line-height: 1.55;
}

.folder-dropzone {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 14px;
  padding: 16px;
  border: 1px dashed var(--line-strong);
  border-radius: 13px;
  color: var(--ink-muted);
  text-align: left;
  background: var(--surface-elevated);
  cursor: pointer;
  transition: border-color 160ms ease, background 160ms ease, transform 160ms ease;
}

.folder-dropzone:hover {
  border-color: var(--accent-blue);
  background: color-mix(in srgb, var(--accent-blue) 5%, var(--surface-elevated));
  transform: translateY(-1px);
}

.dropzone-icon {
  display: grid;
  flex: 0 0 38px;
  height: 38px;
  place-items: center;
  border-radius: 11px;
  color: var(--accent-blue);
  background: color-mix(in srgb, var(--accent-blue) 10%, transparent);
}

.dropzone-icon svg {
  width: 19px;
}

.folder-dropzone strong,
.folder-dropzone small {
  display: block;
}

.folder-dropzone strong {
  color: var(--ink-strong);
  font-size: 12px;
}

.folder-dropzone small {
  margin-top: 4px;
  font-size: 10px;
}

.warning-list {
  margin: 10px 0 0;
  padding-left: 18px;
  color: var(--warning);
  font-size: 11px;
  line-height: 1.6;
}

.file-summary {
  margin-top: 12px;
  overflow: hidden;
  border: 1px solid var(--line-subtle);
  border-radius: 12px;
}

.file-count {
  display: flex;
  align-items: baseline;
  gap: 7px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line-subtle);
  background: var(--surface-elevated);
}

.file-count span {
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: 18px;
}

.file-count small {
  color: var(--ink-soft);
  font-size: 10px;
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
  background: var(--surface-elevated);
}

.file-language,
.file-path {
  font-family: var(--font-mono);
  font-size: 9px;
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
  background: color-mix(in srgb, var(--danger) 8%, transparent);
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
  border: 1px solid color-mix(in srgb, var(--success) 30%, var(--line-subtle));
  border-radius: 13px;
  background: color-mix(in srgb, var(--success) 4%, var(--surface-elevated));
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
  font-size: 9px;
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
