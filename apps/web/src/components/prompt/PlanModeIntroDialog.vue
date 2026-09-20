<script setup lang="ts">
import { ElButton, ElDialog } from 'element-plus';

interface Props {
  modelValue: boolean;
}

interface Emits {
  (event: 'update:modelValue', value: boolean): void;
  (event: 'accept'): void;
  (event: 'dismiss'): void;
}

defineProps<Props>();
const emit = defineEmits<Emits>();
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    title="先确认关键细节"
    class="plan-intro-dialog"
    width="min(560px, calc(100vw - 24px))"
    top="max(12px, env(safe-area-inset-top, 0px))"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    @close="emit('update:modelValue', false)"
  >
    <div class="intro-copy">
      <p class="intro-kicker">Plan 模式</p>
      <h2>系统会先询问关键细节，确保生成的提示词更精准</h2>
      <p>
        开启后，“先确认并增强”会先问少量会改变结果的问题，确认后再生成最终提示词，减少返工和歧义。
        需求已经写清楚时，也可能不问、直接生成。
      </p>
    </div>

    <template #footer>
      <div class="intro-actions">
        <ElButton @click="emit('dismiss')">不启用，直接增强</ElButton>
        <ElButton type="primary" @click="emit('accept')">立即体验</ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<style scoped>
.intro-kicker {
  margin: 0 0 8px;
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.intro-copy h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 22px;
  line-height: 1.35;
}

.intro-copy p:last-child {
  margin: 12px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.intro-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 600px) {
  .intro-actions {
    flex-direction: column-reverse;
  }

  .intro-actions :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }
}
</style>
