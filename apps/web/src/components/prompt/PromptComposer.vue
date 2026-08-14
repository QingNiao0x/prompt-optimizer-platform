<script setup lang="ts">
import { MagicStick } from '@element-plus/icons-vue';
import {
  ElButton,
  ElInput,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTooltip,
} from 'element-plus';
import { computed } from 'vue';

import type { TemplateCode } from '@/types/api';

interface Props {
  rawPrompt: string;
  templateCode: TemplateCode;
  includePermissionBoundaries: boolean;
  includeExamples: boolean;
  isOptimizing: boolean;
  canOptimize: boolean;
}

interface Emits {
  (event: 'update:raw-prompt', value: string): void;
  (event: 'update:template-code', value: TemplateCode): void;
  (event: 'update:permission-boundaries', value: boolean): void;
  (event: 'update:include-examples', value: boolean): void;
  (event: 'optimize'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const rawPromptModel = computed({
  get: (): string => props.rawPrompt,
  set: (value: string): void => emit('update:raw-prompt', value),
});

const templateModel = computed({
  get: (): TemplateCode => props.templateCode,
  set: (value: TemplateCode): void => emit('update:template-code', value),
});

const permissionModel = computed({
  get: (): boolean => props.includePermissionBoundaries,
  set: (value: boolean): void => emit('update:permission-boundaries', value),
});

const examplesModel = computed({
  get: (): boolean => props.includeExamples,
  set: (value: boolean): void => emit('update:include-examples', value),
});
</script>

<template>
  <section class="composer-card">
    <div class="composer-heading">
      <div>
        <span class="step-label">02 / Intent</span>
        <h1>把想法写下来，工程细节交给上下文。</h1>
      </div>
      <span class="keyboard-hint">⌘ / Ctrl + Enter</span>
    </div>

    <form @submit.prevent="emit('optimize')">
      <label class="visually-hidden" for="raw-prompt">原始提示词</label>
      <ElInput
        id="raw-prompt"
        v-model="rawPromptModel"
        class="prompt-input"
        type="textarea"
        :rows="8"
        maxlength="8000"
        show-word-limit
        resize="vertical"
        placeholder="例如：给用户模块增加登录功能"
        @keydown.ctrl.enter.prevent="emit('optimize')"
        @keydown.meta.enter.prevent="emit('optimize')"
      />

      <div class="composer-controls">
        <div class="control-group">
          <label for="template-code">任务模板</label>
          <ElSelect id="template-code" v-model="templateModel" class="template-select">
            <ElOption label="自动识别" value="AUTO" />
            <ElOption label="新功能开发" value="FEATURE_DEVELOPMENT" />
            <ElOption label="Bug 修复" value="BUG_FIX" />
            <ElOption label="代码重构" value="REFACTORING" />
            <ElOption label="测试补充" value="TESTING" />
          </ElSelect>
        </div>

        <div class="switches">
          <ElTooltip content="自动加入文件保护、密钥安全和人工确认要求" placement="top">
            <label class="switch-control">
              <span>权限红线</span>
              <ElSwitch v-model="permissionModel" />
            </label>
          </ElTooltip>
          <ElTooltip content="要求模型给出输入与预期输出示例" placement="top">
            <label class="switch-control">
              <span>示例参考</span>
              <ElSwitch v-model="examplesModel" />
            </label>
          </ElTooltip>
        </div>

        <ElButton
          class="optimize-button"
          native-type="submit"
          type="primary"
          size="large"
          :icon="MagicStick"
          :loading="isOptimizing"
          :disabled="!canOptimize"
        >
          {{ isOptimizing ? '正在增强…' : '一键增强提示词' }}
        </ElButton>
      </div>
    </form>
  </section>
</template>

<style scoped>
.composer-card {
  position: relative;
  overflow: hidden;
  padding: clamp(26px, 4vw, 42px);
  border: 1px solid var(--line-subtle);
  border-radius: var(--radius-large);
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--accent-blue) 5%, transparent), transparent 44%),
    var(--surface-panel);
  box-shadow: var(--shadow-panel);
}

.composer-card::after {
  position: absolute;
  top: -88px;
  right: -64px;
  width: 220px;
  height: 220px;
  border: 1px solid color-mix(in srgb, var(--accent-cyan) 24%, transparent);
  border-radius: 50%;
  content: '';
  pointer-events: none;
}

.composer-heading {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 28px;
  margin-bottom: 28px;
}

.step-label {
  color: var(--accent-blue);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

h1 {
  max-width: 680px;
  margin: 10px 0 0;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: clamp(30px, 4vw, 48px);
  font-weight: 600;
  line-height: 1.08;
  letter-spacing: -0.06em;
}

.keyboard-hint {
  flex: 0 0 auto;
  padding: 6px 9px;
  border: 1px solid var(--line-subtle);
  border-radius: 7px;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 9px;
  background: var(--surface-elevated);
}

.prompt-input {
  position: relative;
  z-index: 1;
}

.prompt-input :deep(.el-textarea__inner) {
  min-height: 190px !important;
  padding: 20px;
  border: 1px solid var(--line-strong);
  border-radius: 15px;
  color: var(--ink-strong);
  font-size: 16px;
  line-height: 1.75;
  background: var(--surface-elevated);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.55);
}

.prompt-input :deep(.el-textarea__inner:focus) {
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-blue) 12%, transparent);
}

.composer-controls {
  display: grid;
  grid-template-columns: minmax(150px, 0.7fr) minmax(240px, 1fr) auto;
  align-items: end;
  gap: 20px;
  margin-top: 20px;
}

.control-group label {
  display: block;
  margin-bottom: 7px;
  color: var(--ink-soft);
  font-size: 10px;
}

.template-select {
  width: 100%;
}

.switches {
  display: flex;
  align-items: center;
  gap: 18px;
  min-height: 40px;
}

.switch-control {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-muted);
  font-size: 11px;
  cursor: pointer;
}

.optimize-button {
  min-width: 180px;
  border: 0;
  border-radius: 12px;
  background: var(--ink-strong);
  box-shadow: 0 10px 24px rgba(19, 31, 54, 0.16);
}

.optimize-button:hover,
.optimize-button:focus-visible {
  background: var(--accent-blue);
}

@media (max-width: 760px) {
  .composer-heading {
    display: block;
  }

  .keyboard-hint {
    display: none;
  }

  .composer-controls {
    grid-template-columns: 1fr;
  }

  .switches {
    justify-content: space-between;
  }

  .optimize-button {
    width: 100%;
  }
}
</style>
