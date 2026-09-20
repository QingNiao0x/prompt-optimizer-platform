<script setup lang="ts">
import { Check } from '@element-plus/icons-vue';
import { computed } from 'vue';

import type { PromptSection, PromptSectionType } from '@/types/api';

interface Props {
  section: PromptSection;
  index: number;
  isLast: boolean;
}

const props = defineProps<Props>();

const SECTION_LABELS: Record<PromptSectionType, string> = {
  BACKGROUND: '背景',
  TASK: '任务',
  OUTPUT: '输出',
  CONSTRAINTS: '约束',
  CLARIFICATIONS: '待确认',
  ACCEPTANCE: '验收',
  EXAMPLES: '示例',
};

const sectionLabel = computed(() => SECTION_LABELS[props.section.type]);
const toneClass = computed(() => `is-${props.section.type.toLowerCase()}`);
const displayIndex = computed(() => String(props.index + 1).padStart(2, '0'));
</script>

<template>
  <section
    class="prompt-section"
    :class="{ 'is-last': isLast }"
    :style="{ animationDelay: `${(index + 1) * 0.1}s` }"
  >
    <div class="section-rail" aria-hidden="true">
      <span>{{ displayIndex }}</span>
      <i></i>
    </div>
    <div class="section-card">
      <header class="section-title">
        <div>
          <span class="section-tag" :class="toneClass">{{ sectionLabel }}</span>
          <h3>{{ section.title }}</h3>
        </div>
        <Check aria-hidden="true" />
      </header>
      <div class="section-text">{{ section.content }}</div>
    </div>
  </section>
</template>

<style scoped>
.prompt-section {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 8px;
  animation: fade-in-up 0.6s ease-out both;
}

.section-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.section-rail span {
  display: grid;
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  place-items: center;
  border-radius: 50%;
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 600;
  background: var(--accent-soft);
}

.section-rail i {
  width: 1px;
  min-height: 16px;
  flex: 1;
  background: var(--glass-border-subtle);
}

.prompt-section.is-last .section-rail i {
  background: transparent;
}

.section-card {
  min-width: 0;
  margin-bottom: 14px;
  padding: 15px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 14px;
  background: var(--glass-bg-subtle);
  transition: border-color 180ms ease, background 180ms ease;
}

.section-card:hover {
  border-color: var(--glass-border);
  background: var(--glass-bg-strong);
}

.section-title,
.section-title > div {
  display: flex;
  align-items: center;
}

.section-title {
  justify-content: space-between;
  gap: 12px;
}

.section-title > div {
  min-width: 0;
  gap: 8px;
}

.section-title h3 {
  margin: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-title > svg {
  width: 14px;
  flex: 0 0 auto;
  color: var(--success);
}

.section-tag {
  flex: 0 0 auto;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  color: var(--accent);
  font-size: 11px;
  font-weight: 500;
  background: var(--accent-soft);
}

.section-tag.is-task {
  color: var(--green);
  background: var(--green-soft);
}

.section-tag.is-output {
  color: var(--blue);
  background: var(--blue-soft);
}

.section-tag.is-constraints {
  color: var(--orange);
  background: var(--orange-soft);
}

.section-tag.is-acceptance {
  color: var(--pink);
  background: var(--pink-soft);
}

.section-tag.is-examples {
  color: var(--blue);
  background: var(--blue-soft);
}

.section-text {
  max-width: 100%;
  margin-top: 10px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.75;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
