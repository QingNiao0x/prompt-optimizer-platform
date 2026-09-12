<script setup lang="ts">
import { Check, CopyDocument, DataAnalysis, Right, WarningFilled } from '@element-plus/icons-vue';
import { ElButton, ElMessage, ElTag } from 'element-plus';
import { computed, toRefs } from 'vue';

import type { OptimizationResult, PromptSectionType } from '@/types/api';

interface Props {
  result?: OptimizationResult;
}

const props = defineProps<Props>();
const { result } = toRefs(props);

const displaySections = computed(() =>
  result.value?.sections.filter((section) => section.type !== 'CLARIFICATIONS') ?? [],
);

const SECTION_TONE: Record<PromptSectionType, string> = {
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
    ElMessage.success('优化后的提示词已复制，待确认事项未包含在剪贴板中。');
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制。');
  }
};
</script>

<template>
  <section class="result-panel">
    <div
      v-if="result?.ambiguities.length"
      class="ambiguity-note ambiguity-note--top"
      role="status"
      aria-live="polite"
    >
      <div class="ambiguity-heading">
        <div class="ambiguity-title">
          <WarningFilled aria-hidden="true" />
          <strong>待确认事项</strong>
          <span class="ambiguity-count">{{ result.ambiguities.length }} 项</span>
        </div>
        <span class="ambiguity-label">需要人工核对</span>
      </div>
      <p class="ambiguity-description">
        以下内容只用于复核，不属于可直接复制的核心提示词。
      </p>
      <ul class="ambiguity-list">
        <li v-for="item in result.ambiguities" :key="item">{{ item }}</li>
      </ul>
    </div>

    <div class="result-heading">
      <div>
        <span class="step-label">03 / Structured prompt</span>
        <h2>增强结果</h2>
      </div>
      <ElButton
        v-if="result"
        :icon="CopyDocument"
        round
        @click="copyPrompt(result.optimizedPrompt)"
      >
        复制全部
      </ElButton>
    </div>

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
      <p>输入一个简短需求并点击“一键增强”，系统会结合项目技术栈生成可直接交给 AI 执行的任务说明。</p>
    </div>

    <div
      v-else
      class="result-content result-content--scrollable"
      tabindex="0"
      aria-label="增强结果内容，可滚动查看完整提示词"
    >
      <div class="result-meta">
        <div>
          <span class="meta-label">Provider</span>
          <strong>{{ result.provider.provider }}</strong>
          <small>{{ result.provider.model }}</small>
        </div>
        <div>
          <span class="meta-label">Template</span>
          <strong>{{ result.templateCode }}</strong>
          <small>{{ result.sections.length }} 个结构段落</small>
        </div>
        <div>
          <span class="meta-label">Latency</span>
          <strong>{{ result.latencyMs }} ms</strong>
          <small>{{ result.provider.mock ? 'Mock 结果' : '模型生成' }}</small>
        </div>
      </div>

      <article class="section-list">
        <section
          v-for="(section, index) in displaySections"
          :key="section.type"
          class="prompt-section"
        >
          <div class="section-rail">
            <span>{{ String(index + 1).padStart(2, '0') }}</span>
            <i></i>
          </div>
          <div class="section-body">
            <div class="section-title">
              <div>
                <ElTag size="small" effect="plain" round>
                  {{ SECTION_TONE[section.type] }}
                </ElTag>
                <h3>{{ section.title }}</h3>
              </div>
              <Check aria-hidden="true" />
            </div>
            <div class="section-text">{{ section.content }}</div>
          </div>
        </section>
      </article>

    </div>
  </section>
</template>

<style scoped>
.result-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  max-width: 100%;
  max-height: min(760px, calc(100dvh - 148px));
  min-height: 100%;
  margin-top: 0;
  padding: clamp(22px, 3vw, 30px);
  border: 1px solid var(--line-subtle);
  border-radius: var(--radius-large);
  background: var(--surface-panel);
  box-shadow: var(--shadow-panel);
  overflow: hidden;
}

.result-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.step-label,
.meta-label {
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
  letter-spacing: -0.045em;
}

.empty-result {
  display: grid;
  flex: 1;
  min-height: 430px;
  place-content: center;
  justify-items: center;
  text-align: center;
}

.signal-flow {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 24px;
}

.signal-flow > svg {
  width: 20px;
  color: var(--accent-blue);
}

.signal-source {
  display: grid;
  grid-template-columns: repeat(3, auto);
  gap: 5px;
}

.signal-source span {
  padding: 7px 9px;
  border: 1px solid var(--line-subtle);
  border-radius: 8px;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 9px;
  background: var(--surface-code);
}

.signal-target {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 13px;
  border: 1px solid rgba(111, 124, 255, 0.38);
  border-radius: 10px;
  color: var(--ink-strong);
  font-size: 11px;
  background: rgba(111, 124, 255, 0.1);
}

.signal-target svg {
  width: 16px;
  color: var(--accent-blue);
}

.empty-result h3 {
  margin: 0 0 8px;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: 19px;
}

.empty-result p {
  max-width: 510px;
  margin: 0;
  color: var(--ink-soft);
  font-size: 13px;
  line-height: 1.8;
}

.result-content {
  min-width: 0;
  margin-top: 22px;
}

/* 长结果只在结果面板内部滚动，避免撑开工作台布局。 */
.result-content--scrollable {
  min-height: 0;
  flex: 1;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 0 6px 8px 0;
  scrollbar-gutter: stable;
}

.result-content--scrollable:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--accent-blue) 72%, transparent);
  outline-offset: 3px;
}

.result-meta {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  overflow: hidden;
  margin-bottom: 28px;
  border: 1px solid var(--line-subtle);
  border-radius: 10px;
  background: var(--surface-code);
}

.result-meta > div {
  padding: 13px 15px;
  border-right: 1px solid var(--line-subtle);
}

.result-meta > div:last-child {
  border-right: 0;
}

.result-meta strong,
.result-meta small {
  display: block;
}

.result-meta strong {
  overflow: hidden;
  margin-top: 5px;
  color: var(--ink-strong);
  font-family: var(--font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-meta small {
  margin-top: 3px;
  color: var(--ink-soft);
  font-size: 10px;
}

.section-list {
  display: grid;
  min-width: 0;
  gap: 0;
}

.prompt-section {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 10px;
}

.section-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.section-rail span {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
  color: var(--accent-blue);
  font-family: var(--font-mono);
  font-size: 9px;
  background: var(--surface-panel);
}

.section-rail i {
  width: 1px;
  min-height: 32px;
  flex: 1;
  background: var(--line-subtle);
}

.prompt-section:last-child .section-rail i {
  background: transparent;
}

.section-body {
  min-width: 0;
  margin-bottom: 15px;
  padding: 16px 18px;
  border: 1px solid var(--line-subtle);
  border-radius: 10px;
  background: var(--surface-code);
}

.section-title,
.section-title > div {
  display: flex;
  align-items: center;
}

.section-title {
  justify-content: space-between;
  gap: 16px;
}

.section-title > div {
  gap: 10px;
}

.section-title h3 {
  margin: 0;
  color: var(--ink-strong);
  font-size: 13px;
}

.section-title > svg {
  width: 15px;
  color: var(--success);
}

.section-text {
  max-width: 100%;
  margin-top: 12px;
  color: var(--ink-muted);
  font-size: 13px;
  line-height: 1.85;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.ambiguity-note {
  margin-top: 14px;
  padding: 13px 15px;
  border-left: 3px solid var(--warning);
  color: var(--ink-muted);
  font-size: 11px;
  background: rgba(232, 180, 92, 0.08);
}

.ambiguity-note--top {
  flex: 0 0 auto;
  margin: 0 0 18px;
  padding: 15px 16px;
  border: 1px solid color-mix(in srgb, var(--warning) 35%, var(--line-subtle));
  border-left: 4px solid var(--warning);
  border-radius: 10px;
  background: color-mix(in srgb, var(--warning) 9%, var(--surface-code));
}

.ambiguity-heading,
.ambiguity-title {
  display: flex;
  align-items: center;
}

.ambiguity-heading {
  justify-content: space-between;
  gap: 12px;
}

.ambiguity-title {
  gap: 8px;
}

.ambiguity-title svg {
  width: 16px;
  color: var(--warning);
}

.ambiguity-title strong {
  color: var(--ink-strong);
  font-size: 13px;
  font-weight: 600;
}

.ambiguity-count,
.ambiguity-label {
  color: var(--warning);
  font-family: var(--font-mono);
  font-size: 10px;
}

.ambiguity-count {
  padding: 3px 7px;
  border: 1px solid color-mix(in srgb, var(--warning) 38%, var(--line-subtle));
  border-radius: 999px;
}

.ambiguity-label {
  white-space: nowrap;
}

.ambiguity-description {
  margin: 9px 0 0;
  color: var(--ink-muted);
  line-height: 1.6;
}

.ambiguity-list {
  display: grid;
  width: 100%;
  gap: 6px;
  margin: 11px 0 0;
  padding: 10px 0 0 18px;
  border-top: 1px solid color-mix(in srgb, var(--warning) 24%, var(--line-subtle));
  color: var(--ink-muted);
  line-height: 1.65;
}

.ambiguity-list li::marker {
  color: var(--warning);
}

@media (max-width: 640px) {
  .ambiguity-heading {
    align-items: flex-start;
  }

  .ambiguity-label {
    white-space: normal;
    text-align: right;
  }

  .result-meta {
    grid-template-columns: 1fr;
  }

  .result-meta > div {
    border-right: 0;
    border-bottom: 1px solid var(--line-subtle);
  }

  .result-meta > div:last-child {
    border-bottom: 0;
  }

  .signal-source {
    grid-template-columns: 1fr;
  }
}
</style>
