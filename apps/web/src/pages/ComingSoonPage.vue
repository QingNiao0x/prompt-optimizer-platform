<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue';
import { ElButton } from 'element-plus';

interface Props {
  eyebrow: string;
  title: string;
  description: string;
}

defineProps<Props>();
</script>

<template>
  <section class="coming-soon">
    <span class="eyebrow">{{ eyebrow }}</span>
    <h1>{{ title }}</h1>
    <p>{{ description }}</p>
    <RouterLink to="/">
      <ElButton :icon="ArrowLeft" round>返回工作台</ElButton>
    </RouterLink>
  </section>
</template>

<style scoped>
.coming-soon {
  display: grid;
  min-height: calc(100vh - 180px);
  place-content: center;
  justify-items: center;
  text-align: center;
}

.eyebrow {
  color: var(--accent-blue);
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

h1 {
  max-width: 720px;
  margin: 18px 0 12px;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: clamp(34px, 5vw, 58px);
  letter-spacing: -0.055em;
}

p {
  max-width: 580px;
  margin: 0 0 28px;
  color: var(--ink-muted);
  font-size: 15px;
  line-height: 1.8;
}
</style>
