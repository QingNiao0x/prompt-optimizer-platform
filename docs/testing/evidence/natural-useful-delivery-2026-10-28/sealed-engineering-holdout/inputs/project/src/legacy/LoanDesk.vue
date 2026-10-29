<script setup lang="ts">
import { computed } from 'vue';
import { availabilityFor, legacySummary } from './availability';
const rows = [
  { deviceCode: 'legacy-camera', pieces: 2, closed: false },
  { deviceCode: 'legacy-camera', pieces: 1, closed: true },
];
const remaining = computed(() => availabilityFor(5, 'legacy-camera', rows));
</script>

<template>
  <section class="legacy-panel"><h2>旧台账回看</h2><p>{{ legacySummary(rows) }}</p><p>原口径剩余 {{ remaining }} 件</p><p>旧台账不创建新的借用单。</p></section>
</template>
