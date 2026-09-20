<script setup lang="ts">
withDefaults(
  defineProps<{
    compact?: boolean;
  }>(),
  { compact: false },
);
</script>

<template>
  <span class="brand-mark" :class="{ 'brand-mark--compact': compact }" aria-hidden="true">
    <!-- Lucide wand-sparkles，ISC 许可：https://lucide.dev/icons/wand-sparkles -->
    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path
        d="m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <path d="m14 7 3 3" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
      <path d="M5 6v4" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
      <path d="M19 14v4" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
      <path d="M10 2v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
      <path d="M7 8H3" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
      <path d="M21 16h-4" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
      <path d="M11 3H9" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
    </svg>
  </span>
</template>

<style scoped>
.brand-mark {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 10px;
  color: #fff;
  background: linear-gradient(135deg, var(--accent), var(--accent-cyan-bright));
  box-shadow: 0 4px 12px color-mix(in srgb, var(--accent) 30%, transparent);
}

.brand-mark--compact {
  width: 28px;
  height: 28px;
  border-radius: 9px;
}

.brand-mark svg {
  width: 18px;
  height: 18px;
}

.brand-mark--compact svg {
  width: 16px;
  height: 16px;
}
</style>
