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
        <h1 class="prompt-title">
          <span class="prompt-title-primary">把想法写下来。</span>
          <span class="prompt-title-secondary">工程细节，交给上下文。</span>
        </h1>
      </div>
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
  min-height: 100%;
  padding: clamp(22px, 3vw, 30px);
  border: 1px solid var(--line-subtle);
  border-radius: var(--radius-large);
  background:
    linear-gradient(135deg, rgba(111, 124, 255, 0.12), transparent 40%),
    var(--surface-panel);
  box-shadow: var(--shadow-panel);
}

.composer-card::after {
  position: absolute;
  top: -88px;
  right: -64px;
  width: 220px;
  height: 220px;
  border: 1px solid rgba(101, 216, 208, 0.22);
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
  display: grid;
  max-width: 560px;
  gap: 7px;
  margin: 10px 0 0;
  font-family: var(--font-display);
  font-size: clamp(28px, 3vw, 36px);
  font-weight: 600;
  line-height: 1.16;
  letter-spacing: 0;
}

.prompt-title span {
  display: block;
}

.prompt-title-primary,
.prompt-title-secondary {
  -webkit-text-stroke-width: 0.45px;
  text-shadow: 0 1px 0 rgba(255, 255, 255, 0.05);
}

.prompt-title-primary {
  color: var(--ink-strong);
  -webkit-text-stroke-color: var(--title-stroke-primary);
  text-shadow:
    0 1px 0 rgba(255, 255, 255, 0.05),
    0 0 18px var(--title-glow);
}

.prompt-title-secondary {
  color: var(--ink-muted);
  -webkit-text-stroke-color: var(--title-stroke-secondary);
  text-shadow: 0 0 14px color-mix(in srgb, var(--title-stroke-secondary) 32%, transparent);
}

.prompt-input {
  position: relative;
  z-index: 1;
}

.prompt-input :deep(.el-textarea__inner) {
  min-height: 260px !important;
  padding: 18px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  color: var(--ink-strong);
  font-size: 15px;
  line-height: 1.75;
  background: var(--surface-code);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.025);
}

.prompt-input :deep(.el-textarea__inner:focus) {
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-blue) 12%, transparent);
}

.composer-controls {
  display: grid;
  grid-template-columns: minmax(130px, 0.8fr) minmax(210px, 1fr);
  align-items: end;
  gap: 14px;
  margin-top: 16px;
}

.control-group label {
  display: block;
  margin-bottom: 7px;
  color: var(--ink-soft);
  font-size: 11px;
}

.template-select {
  width: 100%;
}

.switches {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 38px;
  grid-column: 1 / 2;
}

.switch-control {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-muted);
  font-size: 12px;
  cursor: pointer;
}

.optimize-button {
  grid-column: 2 / 3;
  grid-row: 1 / 3;
  min-width: 160px;
  min-height: 76px;
  border: 0;
  border-radius: 10px;
  background: linear-gradient(145deg, var(--accent-blue), #5364f5);
  box-shadow: 0 14px 30px rgba(83, 100, 245, 0.22);
}

.optimize-button:hover,
.optimize-button:focus-visible {
  background: linear-gradient(145deg, #8290ff, #6878ff);
}

@media (max-width: 760px) {
  .composer-heading {
    display: block;
  }

  .composer-controls {
    grid-template-columns: 1fr;
  }

  .switches,
  .optimize-button {
    grid-column: auto;
    grid-row: auto;
  }

  .switches {
    justify-content: space-between;
  }

  .optimize-button {
    width: 100%;
  }
}
</style>
