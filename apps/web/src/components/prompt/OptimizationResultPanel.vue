<script setup lang="ts">
import {
  ArrowDown,
  CopyDocument,
  DataAnalysis,
  EditPen,
  RefreshRight,
  Right,
  WarningFilled,
} from '@element-plus/icons-vue';
import { ElButton, ElInput, ElMessage } from 'element-plus';
import { computed, ref, toRefs, watch } from 'vue';

import ResultCard from '@/components/prompt/ResultCard.vue';
import ResultMetaBar from '@/components/prompt/ResultMetaBar.vue';
import { reportClientAnalyticsEventBestEffort } from '@/services/adminAnalyticsApi';
import type { OptimizationResult, PromptSection, PromptSectionType } from '@/types/api';

interface Props {
  result?: OptimizationResult;
  busy: boolean;
  planModeEnabled: boolean;
}

interface Emits {
  (event: 'save', sections: PromptSection[]): void;
  (event: 're-enhance'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();
const { result } = toRefs(props);
const editing = ref(false);
const ambiguitiesOpen = ref(false);
const draftSections = ref<PromptSection[]>([]);

const displaySections = computed(() =>
  result.value?.sections.filter((section) => section.type !== 'CLARIFICATIONS') ?? [],
);

const ambiguityPreview = computed(() => result.value?.ambiguities[0] ?? '');

const SECTION_LABELS: Record<PromptSectionType, string> = {
  BACKGROUND: '背景',
  TASK: '任务',
  OUTPUT: '输出',
  CONSTRAINTS: '约束',
  CLARIFICATIONS: '待确认',
  ACCEPTANCE: '验收',
  EXAMPLES: '示例',
};

const copyPrompt = async (content: string): Promise<void> => {
  try {
    await navigator.clipboard.writeText(content);
    ElMessage.success('最终提示词已复制。');
    reportClientAnalyticsEventBestEffort('RESULT_EXPORTED');
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制。');
  }
};

watch(result, () => {
  editing.value = false;
  ambiguitiesOpen.value = false;
  draftSections.value = [];
});

const toggleAmbiguities = (): void => {
  ambiguitiesOpen.value = !ambiguitiesOpen.value;
};

const startEditing = (): void => {
  if (!result.value) {
    return;
  }
  draftSections.value = result.value.sections
    .filter((section) => section.type !== 'CLARIFICATIONS')
    .map((section) => ({ ...section }));
  ambiguitiesOpen.value = false;
  editing.value = true;
};

const cancelEditing = (): void => {
  editing.value = false;
  draftSections.value = [];
};

const saveEditing = (): void => {
  emit('save', draftSections.value.map((section) => ({ ...section })));
  editing.value = false;
};
</script>

<template>
  <section class="result-panel">
    <div class="result-heading">
      <span class="step-label">03 / Structured prompt</span>
      <div v-if="result" class="result-actions">
        <template v-if="editing">
          <ElButton size="small" :disabled="busy" @click="cancelEditing">取消</ElButton>
          <ElButton size="small" type="primary" :disabled="busy" @click="saveEditing">
            保存修改
          </ElButton>
        </template>
        <template v-else>
          <ElButton size="small" :icon="CopyDocument" @click="copyPrompt(result.optimizedPrompt)">
            复制
          </ElButton>
          <ElButton size="small" :icon="EditPen" :disabled="busy" @click="startEditing">
            编辑
          </ElButton>
        </template>
      </div>
    </div>

    <h2>增强结果</h2>

    <ElButton
      v-if="result && !editing"
      class="re-enhance-button"
      :icon="RefreshRight"
      size="small"
      :loading="busy"
      @click="emit('re-enhance')"
    >
      {{ planModeEnabled ? '先确认并再次增强' : '直接再次增强' }}
    </ElButton>

    <div v-if="!result" class="empty-result">
      <div class="signal-flow" aria-hidden="true">
        <div class="signal-source">
          <span>意图</span>
          <span>上下文</span>
          <span>约束</span>
        </div>
        <Right />
        <div class="signal-target">
          <DataAnalysis />
          <span>结构化提示词</span>
        </div>
      </div>
      <h3>结果会在这里展开</h3>
      <p>
        输入一个简短需求并点击“{{ planModeEnabled ? '先确认并增强' : '直接增强提示词' }}”，
        系统会结合背景与资料生成可直接使用的任务说明。
      </p>
    </div>

    <div v-else class="result-stage">
      <div v-if="result.warnings?.length && !editing" class="context-warning" role="status">
        <strong><WarningFilled aria-hidden="true" /> 上下文分析提醒</strong>
        <ul>
          <li v-for="(warning, index) in result.warnings" :key="`${index}-${warning}`">{{ warning }}</li>
        </ul>
      </div>

      <div
        v-if="result.ambiguities.length && !editing"
        class="ambiguity-note"
        :class="{ 'is-open': ambiguitiesOpen }"
      >
        <button
          type="button"
          class="ambiguity-toggle"
          :aria-expanded="ambiguitiesOpen"
          aria-controls="ambiguity-details"
          :aria-label="`待确认事项，${result.ambiguities.length} 项，需要人工核对`"
          @click="toggleAmbiguities"
        >
          <span class="ambiguity-title" role="status">
            <WarningFilled aria-hidden="true" />
            <strong>待确认事项</strong>
            <span>{{ result.ambiguities.length }} 项</span>
          </span>
          <span class="ambiguity-meta">
            <small>需要人工核对</small>
            <ArrowDown class="ambiguity-chevron" :class="{ 'is-open': ambiguitiesOpen }" aria-hidden="true" />
          </span>
          <span v-if="!ambiguitiesOpen" class="ambiguity-preview">{{ ambiguityPreview }}</span>
        </button>
        <div v-show="ambiguitiesOpen" id="ambiguity-details" class="ambiguity-body">
          <p class="ambiguity-summary">
            这些信息尚未经过方案确认。开启 Plan 确认后再次增强，系统会逐项向你提问。
          </p>
          <ul class="ambiguity-list">
            <li v-for="(item, index) in result.ambiguities" :key="`${index}-${item}`">{{ item }}</li>
          </ul>
        </div>
      </div>

      <div
        class="result-content"
        tabindex="0"
        aria-label="增强结果内容，可滚动查看完整提示词"
      >
        <ResultMetaBar :result="result" />

        <article v-if="!editing" class="section-list">
          <ResultCard
            v-for="(section, index) in displaySections"
            :key="section.type"
            :section="section"
            :index="index"
            :is-last="index === displaySections.length - 1"
          />
        </article>

        <div v-else class="edit-section-list">
          <section v-for="section in draftSections" :key="section.type" class="edit-section">
            <label :for="`section-${section.type}`">
              {{ SECTION_LABELS[section.type] }} · {{ section.title }}
            </label>
            <ElInput
              :id="`section-${section.type}`"
              v-model="section.content"
              type="textarea"
              :rows="6"
              resize="vertical"
              maxlength="12000"
              show-word-limit
            />
          </section>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.result-panel {
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
  padding: 22px 20px 18px;
}

.result-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.step-label {
  padding-top: 4px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.8px;
  text-transform: uppercase;
}

.result-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 5px;
}

.result-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.result-actions :deep(.el-button) {
  padding: 5px 9px;
  border-color: var(--glass-border-subtle);
  color: var(--text-secondary);
  background: var(--glass-bg-subtle);
}

h2 {
  margin: 8px 0 12px;
  color: var(--text-primary);
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
}

.re-enhance-button {
  align-self: flex-start;
  margin-bottom: 18px;
  border-color: var(--accent-border);
  border-radius: 999px;
  color: var(--accent);
  background: var(--accent-soft);
}

.empty-result {
  display: grid;
  min-height: 0;
  flex: 1;
  place-content: center;
  justify-items: center;
  padding: 12px 6px 8px;
  text-align: center;
}

.signal-flow {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}

.signal-flow > svg {
  width: 18px;
  color: var(--accent);
}

.signal-source {
  display: grid;
  grid-template-columns: repeat(3, auto);
  gap: 4px;
}

.signal-source span {
  padding: 6px 10px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 8px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--glass-bg-subtle);
}

.signal-target {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 11px;
  border: 1px solid var(--accent-border);
  border-radius: 10px;
  color: var(--text-primary);
  font-size: 12px;
  background: var(--accent-soft);
}

.signal-target svg {
  width: 15px;
  color: var(--accent);
}

.empty-result h3 {
  margin: 0 0 8px;
  color: var(--text-primary);
  font-size: 17px;
}

.empty-result p {
  max-width: 380px;
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.result-stage {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

.result-content {
  min-width: 0;
  min-height: 0;
  flex: 1 1 58%;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 0 5px 8px 0;
  scrollbar-gutter: stable;
  overscroll-behavior: contain;
}

.result-content:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--accent) 72%, transparent);
  outline-offset: 3px;
}

.section-list,
.edit-section-list {
  display: grid;
  min-width: 0;
}

.edit-section-list {
  gap: 14px;
}

.edit-section label {
  display: block;
  margin-bottom: 7px;
  color: var(--text-secondary);
  font-size: 12px;
}

.edit-section :deep(.el-textarea__inner) {
  border: 1px solid var(--glass-border-subtle);
  color: var(--text-primary);
  line-height: 1.7;
  background: var(--glass-bg-subtle);
  box-shadow: none;
}

.ambiguity-note {
  display: flex;
  flex: 0 1 auto;
  flex-direction: column;
  min-height: 0;
  margin-bottom: 12px;
  padding: 10px 12px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--warning) 34%, var(--glass-border-subtle));
  border-left: 4px solid var(--warning);
  border-radius: 10px;
  color: var(--text-secondary);
  font-size: 12px;
  background: color-mix(in srgb, var(--warning) 8%, var(--glass-bg-subtle));
}

.context-warning {
  margin: 0 0 14px;
  padding: 12px 16px;
  border: 1px solid color-mix(in srgb, var(--warning) 42%, transparent);
  border-left: 3px solid var(--warning);
  border-radius: 10px;
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--warning) 7%, var(--glass-bg-subtle));
  font-size: 13px;
}

.context-warning strong {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--text-primary);
}

.context-warning strong :deep(svg) {
  color: var(--warning);
}

.context-warning ul {
  margin: 8px 0 0;
  padding-left: 19px;
}

.context-warning li + li {
  margin-top: 5px;
}

.ambiguity-note.is-open {
  max-height: 42%;
}

.ambiguity-toggle {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  width: 100%;
  min-height: 44px;
  align-items: center;
  column-gap: 8px;
  row-gap: 6px;
  padding: 0;
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
  touch-action: manipulation;
}

.ambiguity-toggle:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--warning) 72%, transparent);
  outline-offset: 2px;
  border-radius: 6px;
}

.ambiguity-title,
.ambiguity-meta {
  display: flex;
  align-items: center;
}

.ambiguity-title {
  min-width: 0;
  gap: 7px;
}

.ambiguity-meta {
  gap: 6px;
}

.ambiguity-title svg,
.ambiguity-chevron {
  width: 15px;
  flex: none;
  color: var(--warning);
}

.ambiguity-chevron {
  transition: transform 0.18s ease;
}

.ambiguity-chevron.is-open {
  transform: rotate(180deg);
}

.ambiguity-title strong {
  min-width: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ambiguity-title span,
.ambiguity-meta small {
  flex: none;
  color: var(--warning);
  font-family: var(--font-mono);
  font-size: 12px;
}

.ambiguity-preview {
  grid-column: 1 / -1;
  grid-row: 2;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.6;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ambiguity-body {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.ambiguity-note.is-open .ambiguity-body {
  flex: 1 1 auto;
}

.ambiguity-summary {
  margin: 8px 0 0;
  line-height: 1.6;
}

.ambiguity-list {
  display: grid;
  gap: 6px;
  margin: 9px 0 0;
  padding: 9px 2px 2px 17px;
  border-top: 1px solid color-mix(in srgb, var(--warning) 22%, var(--glass-border-subtle));
  line-height: 1.6;
}

@media (max-width: 900px) {
  .result-panel {
    min-height: 0;
    padding: 18px 16px 16px;
  }

  .re-enhance-button {
    margin-bottom: 10px;
  }

  .ambiguity-note.is-open {
    max-height: min(40vh, 42%);
  }

  .ambiguity-list {
    gap: 8px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ambiguity-chevron {
    transition: none;
  }
}

@media (max-width: 600px) {
  .result-panel {
    padding: 16px 14px 14px;
  }

  .result-heading {
    display: grid;
  }

  .result-actions {
    justify-content: flex-start;
  }

  .signal-source {
    grid-template-columns: repeat(3, auto);
  }

  .signal-flow {
    flex-wrap: wrap;
    justify-content: center;
  }
}
</style>
