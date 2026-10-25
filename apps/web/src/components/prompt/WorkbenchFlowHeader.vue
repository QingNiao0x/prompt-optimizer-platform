<script setup lang="ts">
import { computed } from 'vue';

type WorkbenchStage = 'context' | 'clarify' | 'result';

interface Props {
  stage: WorkbenchStage;
}

const props = defineProps<Props>();

const stages = [
  { id: 'context', label: '项目事实' },
  { id: 'clarify', label: '需求明确化' },
  { id: 'result', label: '优化结果' },
] as const;

const activeIndex = computed(() => stages.findIndex((stage) => stage.id === props.stage));
</script>

<template>
  <header class="workbench-header">
    <div>
      <h1 class="header-title">提示词优化工作台</h1>
      <p class="header-description">把需求表达清楚，让 AI 更好执行。</p>
    </div>

    <ol class="flow-indicator" aria-label="提示词处理流程">
      <li
        v-for="(item, index) in stages"
        :key="item.id"
        :class="{
          'is-active': index === activeIndex,
          'is-complete': index < activeIndex,
        }"
        :aria-current="index === activeIndex ? 'step' : undefined"
      >
        <span>{{ item.label }}</span>
        <i v-if="index < stages.length - 1" aria-hidden="true"></i>
      </li>
    </ol>
  </header>
</template>

<style scoped>
.workbench-header {
  display: flex;
  min-height: 52px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 8px 20px 0;
}

.workbench-header p {
  margin: 0;
}

.header-title {
  margin: 0;
  color: var(--text-primary);
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 600;
}

.header-description {
  margin-top: 2px !important;
  color: var(--text-secondary);
  font-size: 13px;
}

.flow-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 0;
  color: var(--text-muted);
  font-size: 12px;
  list-style: none;
}

.flow-indicator li {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
  transition: color 180ms ease;
}

.flow-indicator i {
  width: 24px;
  height: 1px;
  background: var(--glass-border-subtle);
}

.flow-indicator li.is-active {
  color: var(--accent);
  font-weight: 600;
}

.flow-indicator li.is-complete {
  color: var(--success);
}

.flow-indicator li.is-complete i {
  background: color-mix(in srgb, var(--success) 46%, var(--glass-border-subtle));
}

@media (max-width: 900px) {
  .workbench-header {
    min-height: 40px;
    padding: 8px 16px 0;
  }

  .header-description {
    display: none;
  }

  .header-title {
    font-size: 16px;
  }

  .flow-indicator {
    justify-content: flex-end;
    font-size: 13px;
  }
}

@media (max-width: 480px) {
  .flow-indicator {
    display: none;
  }
}
</style>
