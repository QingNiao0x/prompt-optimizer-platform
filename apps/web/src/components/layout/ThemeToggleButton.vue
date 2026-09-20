<script setup lang="ts">
import { Moon, Sunny } from '@element-plus/icons-vue';
import { computed } from 'vue';

import { useUiTheme } from '@/composables/useUiTheme';

withDefaults(defineProps<{
  compact?: boolean;
}>(), {
  compact: false,
});

const { isDark, toggleTheme } = useUiTheme();

const buttonLabel = computed(() => isDark.value ? '切换为浅蓝主题' : '切换为深色主题');
</script>

<template>
  <button
    class="theme-toggle"
    :class="{ 'theme-toggle--compact': compact }"
    type="button"
    :aria-label="buttonLabel"
    :title="buttonLabel"
    @click="toggleTheme"
  >
    <Moon v-if="isDark" aria-hidden="true" />
    <Sunny v-else aria-hidden="true" />
  </button>
</template>

<style scoped>
.theme-toggle {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 300;
  display: grid;
  width: 48px;
  height: 48px;
  padding: 0;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--glass-border);
  border-radius: 50%;
  color: var(--text-secondary);
  background: var(--glass-bg-strong);
  box-shadow: var(--glass-shadow);
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
  cursor: pointer;
  transition:
    transform var(--duration-ui) var(--ease-standard),
    color var(--duration-ui) var(--ease-standard),
    box-shadow var(--duration-ui) var(--ease-standard);
}

.theme-toggle::before {
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.55), transparent);
}

.theme-toggle:hover {
  color: var(--accent);
  box-shadow: var(--glass-shadow-hover);
  transform: translateY(-2px);
}

.theme-toggle:active {
  transform: scale(0.96);
}

.theme-toggle svg {
  width: 20px;
  height: 20px;
}

.theme-toggle--compact {
  position: static;
  width: 40px;
  height: 40px;
  box-shadow: none;
}

@media (max-width: 600px) {
  .theme-toggle:not(.theme-toggle--compact) {
    right: max(16px, env(safe-area-inset-right, 0px));
    bottom: max(16px, env(safe-area-inset-bottom, 0px));
  }
}
</style>
