<script setup lang="ts">
import { computed } from 'vue';

import type { OptimizationResult } from '@/types/api';

interface Props {
  result: OptimizationResult;
}

const props = defineProps<Props>();

const sectionCount = computed(() =>
  props.result.sections.filter((section) => section.type !== 'CLARIFICATIONS').length,
);
</script>

<template>
  <dl class="result-meta">
    <div>
      <dt>模型版本</dt>
      <dd :title="result.provider.modelVersion || '版本未记录'">{{ result.provider.modelVersion || '版本未记录' }}</dd>
      <small>{{ result.provider.provider }}</small>
    </div>
    <div>
      <dt>Template</dt>
      <dd>{{ result.templateCode }}</dd>
      <small>{{ sectionCount }} 个结构段落</small>
    </div>
    <div>
      <dt>Latency</dt>
      <dd>{{ result.latencyMs }} ms</dd>
      <small>{{ result.provider.mock ? 'Mock 结果' : '模型生成' }}</small>
    </div>
  </dl>
</template>

<style scoped>
.result-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  overflow: hidden;
  margin: 0 0 20px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 10px;
  background: var(--glass-border-subtle);
  gap: 1px;
}

.result-meta > div {
  min-width: 0;
  padding: 11px 12px;
  background: var(--glass-bg-subtle);
}

dt {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.8px;
  text-transform: uppercase;
}

dd,
small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

dd {
  margin: 4px 0 0;
  color: var(--text-primary);
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 500;
}

small {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
}

@media (max-width: 420px) {
  .result-meta {
    grid-template-columns: 1fr;
  }
}
</style>
