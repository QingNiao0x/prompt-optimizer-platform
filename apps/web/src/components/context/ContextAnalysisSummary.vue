<script setup lang="ts">
import { Search } from '@element-plus/icons-vue';
import { ElScrollbar, ElTag } from 'element-plus';
import { computed } from 'vue';

import { buildContextPresentation } from '@/features/context-analysis/contextPresentation';
import type { ContextSnapshot } from '@/types/api';

interface Props {
  snapshot: ContextSnapshot;
}

const props = defineProps<Props>();

const contextPresentation = computed(() => buildContextPresentation(props.snapshot));
const isCodeProject = computed(() => contextPresentation.value.mode === 'CODE_PROJECT');

const formatConfidence = (confidence: number): string => {
  const normalizedConfidence = Math.max(0, Math.min(1, confidence));
  return `${Math.round(normalizedConfidence * 100)}%`;
};
</script>

<template>
  <section class="analysis-result">
    <div class="analysis-title">
      <span class="analysis-title-main">
        <Search />
        <span>识别结果</span>
      </span>
      <ElTag class="analysis-mode-tag" size="small" effect="plain" round>
        {{ contextPresentation.modeLabel }}
      </ElTag>
    </div>

    <div
      v-if="snapshot.fileCoverage?.length"
      class="analysis-coverage"
      :class="`is-${(snapshot.analysisStatus ?? 'complete').toLowerCase()}`"
    >
      <strong>{{ snapshot.analysisStatus === 'COMPLETE' ? '文件解析完整' : '部分内容需要留意' }}</strong>
      <span>已检查 {{ snapshot.fileCoverage.length }} 个文件；模型请求只使用相关片段。</span>
      <details>
        <summary>查看解析与选取范围</summary>
        <ul>
          <li v-for="coverage in snapshot.fileCoverage" :key="coverage.path">
            <span :title="coverage.path">{{ coverage.path }}</span>
            <small>
              {{ coverage.extractionStatus }} · 已提取
              {{ coverage.extractedCharacters.toLocaleString('zh-CN') }} 字符 · 本次选取
              {{ coverage.selectedChunks }}/{{ coverage.indexedChunks }} 段
            </small>
          </li>
        </ul>
      </details>
    </div>

    <template v-if="isCodeProject">
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
              <span>{{ dependency.ecosystem }}{{ dependency.version ? ` · ${dependency.version}` : '' }}</span>
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
      {{ snapshot.dependencies.length }} 个依赖 · {{ snapshot.directoryTree.length }} 个目录节点
    </p>
  </section>
</template>

<style scoped>
.analysis-result {
  margin-top: 16px;
  padding: 14px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 14px;
  background: var(--glass-bg-subtle);
}

.analysis-title,
.analysis-title-main,
.result-section-heading,
.module-summary-heading,
.technology-heading,
.file-summary-heading,
.file-summary-meta {
  display: flex;
  align-items: center;
}

.analysis-title {
  justify-content: space-between;
  gap: 10px;
}

.analysis-title-main {
  gap: 6px;
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 600;
}

.analysis-title-main svg {
  width: 14px;
  color: var(--accent);
}

.analysis-mode-tag {
  max-width: 130px;
}

.analysis-coverage {
  display: grid;
  gap: 4px;
  margin-top: 12px;
  padding: 10px;
  border: 1px solid color-mix(in srgb, var(--success) 24%, var(--glass-border-subtle));
  border-radius: 9px;
  background: color-mix(in srgb, var(--success) 7%, transparent);
}

.analysis-coverage.is-partial,
.analysis-coverage.is-failed {
  border-color: color-mix(in srgb, var(--warning) 28%, var(--glass-border-subtle));
  background: color-mix(in srgb, var(--warning) 7%, transparent);
}

.analysis-coverage > strong {
  color: var(--text-primary);
  font-size: 10px;
}

.analysis-coverage > span,
.analysis-coverage summary,
.analysis-coverage small {
  color: var(--text-muted);
  font-size: 9px;
  line-height: 1.55;
}

.analysis-coverage details {
  margin-top: 3px;
}

.analysis-coverage summary {
  cursor: pointer;
}

.analysis-coverage ul {
  display: grid;
  gap: 6px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.analysis-coverage li {
  display: grid;
  min-width: 0;
}

.analysis-coverage li > span {
  overflow: hidden;
  color: var(--text-secondary);
  font-family: var(--font-mono);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-overview-card,
.project-result-section,
.project-result-details,
.file-summary-section {
  margin-top: 12px;
}

.project-overview-card {
  padding: 11px;
  border: 1px solid var(--accent-border);
  border-radius: 9px;
  background: var(--accent-soft);
}

.project-overview-card h3 {
  margin: 0;
  color: var(--text-primary);
  font-size: 11px;
}

.project-overview-card p,
.module-summary-item p,
.technology-item p,
.file-summary-item > p {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: 10px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.result-section-heading,
.module-summary-heading,
.project-result-details summary,
.file-summary-heading {
  justify-content: space-between;
  gap: 10px;
}

.result-section-heading,
.file-summary-heading {
  margin-bottom: 8px;
  color: var(--text-primary);
  font-size: 10px;
  font-weight: 600;
}

.result-section-heading > span:last-child,
.module-summary-heading > span,
.project-result-details summary > span:last-child,
.file-summary-heading > span:last-child,
.technology-heading > span {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 8px;
  font-weight: 400;
}

.technology-list,
.module-summary-list,
.compact-result-list,
.directory-result-list,
.file-summary-list {
  display: grid;
  gap: 7px;
  margin: 0;
  padding: 0 7px 0 0;
  list-style: none;
}

.technology-item,
.module-summary-item,
.file-summary-item {
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 8px;
  background: var(--glass-bg-subtle);
}

.technology-heading,
.file-summary-meta {
  justify-content: space-between;
  gap: 8px;
}

.module-summary-heading strong,
.file-summary-meta strong {
  min-width: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.module-summary-item code {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.project-result-details {
  padding: 9px 10px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 8px;
}

.project-result-details summary {
  display: flex;
  color: var(--text-primary);
  font-size: 10px;
  cursor: pointer;
  list-style: none;
}

.project-result-details summary::-webkit-details-marker {
  display: none;
}

.project-result-details :deep(.el-scrollbar) {
  margin-top: 8px;
}

.compact-result-list li {
  display: grid;
  gap: 2px;
}

.compact-result-list strong {
  color: var(--text-primary);
  font-size: 9px;
}

.compact-result-list span,
.directory-result-list li {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 8px;
}

.analysis-empty,
.analysis-meta {
  margin: 11px 0 0;
  color: var(--text-muted);
  font-size: 10px;
}

.analysis-meta {
  font-family: var(--font-mono);
}

.truncated-hint {
  display: block;
  margin-top: 5px;
  color: var(--warning);
  font-size: 8px;
}
</style>
