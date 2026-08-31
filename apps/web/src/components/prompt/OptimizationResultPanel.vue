<script setup lang="ts">
import { Check, CopyDocument, DataAnalysis, Right } from '@element-plus/icons-vue';
import { ElButton, ElMessage, ElTag } from 'element-plus';

import type { OptimizationResult, PromptSectionType } from '@/types/api';

interface Props {
  result?: OptimizationResult;
}

defineProps<Props>();

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
    ElMessage.success('优化后的提示词已复制。');
  } catch {
    ElMessage.error('复制失败，请手动选择文本复制。');
  }
};
</script>

<template>
  <section class="result-panel">
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

    <div v-else class="result-content">
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
          v-for="(section, index) in result.sections"
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

      <div v-if="result.ambiguities.length" class="ambiguity-note">
        <strong>系统识别到 {{ result.ambiguities.length }} 个待确认点</strong>
        <span>这些内容已写入增强提示词，执行前仍建议人工确认。</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.result-panel {
  min-height: 100%;
  margin-top: 0;
  padding: clamp(22px, 3vw, 30px);
  border: 1px solid var(--line-subtle);
  border-radius: var(--radius-large);
  background: var(--surface-panel);
  box-shadow: var(--shadow-panel);
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
  margin-top: 22px;
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
  margin-top: 12px;
  color: var(--ink-muted);
  font-size: 13px;
  line-height: 1.85;
  white-space: pre-wrap;
}

.ambiguity-note {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 14px;
  padding: 13px 15px;
  border-left: 3px solid var(--warning);
  color: var(--ink-muted);
  font-size: 11px;
  background: rgba(232, 180, 92, 0.08);
}

.ambiguity-note strong {
  color: var(--ink-strong);
}

@media (max-width: 640px) {
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
