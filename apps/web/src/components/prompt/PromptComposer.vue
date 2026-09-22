<script setup lang="ts">
import {
  ElButton,
  ElInput,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTooltip,
} from 'element-plus';
import { computed } from 'vue';

import SparklesIcon from '@/components/brand/SparklesIcon.vue';
import { usePlanModePreference } from '@/composables/usePlanModePreference';
import type { AvailableModel } from '@/types/api';

interface Props {
  rawPrompt: string;
  includeExamples: boolean;
  model: string;
  modelOptions: AvailableModel[];
  isAnalyzing: boolean;
  isPlanning: boolean;
  isOptimizing: boolean;
  canOptimize: boolean;
}

interface Emits {
  (event: 'update:raw-prompt', value: string): void;
  (event: 'update:include-examples', value: boolean): void;
  (event: 'update:model', value: string): void;
  (event: 'optimize'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();
const { enabled: planModeEnabled, setEnabled: setPlanModeEnabled } = usePlanModePreference();

const rawPromptModel = computed({
  get: (): string => props.rawPrompt,
  set: (value: string): void => emit('update:raw-prompt', value),
});

const examplesModel = computed({
  get: (): boolean => props.includeExamples,
  set: (value: boolean): void => emit('update:include-examples', value),
});

const modelModel = computed({
  get: (): string => props.model,
  set: (value: string): void => emit('update:model', value),
});

const modelCode = (model: AvailableModel): string => {
  const separator = model.id.indexOf(':');
  const code = (separator >= 0 ? model.id.slice(separator + 1) : model.id).trim();
  return code || model.displayName;
};

const planModeModel = computed({
  get: (): boolean => planModeEnabled.value,
  set: (value: boolean): void => setPlanModeEnabled(value),
});
const isBusy = computed(() => props.isAnalyzing || props.isPlanning || props.isOptimizing);
</script>

<template>
  <section class="composer-card">
    <div class="composer-heading">
      <span class="step-label">02 / Intent</span>
      <h1 class="prompt-title">
        <span class="prompt-title-primary">把想法写下来。</span>
        <span class="prompt-title-secondary">工程细节，交给上下文。</span>
      </h1>
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
        resize="none"
        placeholder="请帮我查询全球使用AI最多的职业/行业"
        @keydown.ctrl.enter.prevent="emit('optimize')"
        @keydown.meta.enter.prevent="emit('optimize')"
      />

      <div class="plan-note" :class="{ 'is-active': planModeEnabled }">
        <strong>{{ planModeEnabled ? '下一步：方案确认' : '直接生成' }}</strong>
        <span>
          {{ planModeEnabled
            ? '系统只询问会明显影响结果的细节，再生成最终提示词。'
            : '将直接生成最终提示词。若关键事实不足，结果中会列出待确认事项。' }}
        </span>
      </div>

      <div class="composer-controls">
        <div class="composer-options">
          <ElTooltip
            content="生成前先确认会影响结果的关键细节。关闭后将直接生成，结果中可能出现待确认事项。"
            placement="top"
          >
            <label class="switch-control">
              <span>Plan 确认</span>
              <em v-if="planModeEnabled" class="plan-badge" aria-hidden="true">Plan</em>
              <ElSwitch v-model="planModeModel" aria-label="Plan 确认" :disabled="isBusy" />
            </label>
          </ElTooltip>
          <ElTooltip content="要求模型给出输入与预期输出示例" placement="top">
            <label class="switch-control">
              <span>示例参考</span>
              <ElSwitch v-model="examplesModel" :disabled="isBusy" />
            </label>
          </ElTooltip>
        </div>

        <div class="composer-actions">
          <label class="model-control">
            <span>模型</span>
            <ElSelect
              v-model="modelModel"
              class="model-select"
              size="small"
              :disabled="isBusy || modelOptions.length === 0"
              :aria-label="modelOptions.length > 0 ? '选择模型' : '当前无可用模型'"
            >
              <ElOption
                v-for="modelOption in modelOptions"
                :key="modelOption.id"
                :label="modelCode(modelOption)"
                :value="modelOption.id"
              >
                <span class="model-option">
                  <strong>{{ modelCode(modelOption) }}</strong>
                  <small v-if="modelOption.provider">{{ modelOption.provider }}</small>
                </span>
              </ElOption>
            </ElSelect>
          </label>
          <ElButton
            class="optimize-button"
            native-type="submit"
            type="primary"
            size="large"
            :icon="SparklesIcon"
            :loading="isAnalyzing || isPlanning || isOptimizing"
            :disabled="!canOptimize"
          >
            {{ isAnalyzing
              ? '正在分析上下文…'
              : isPlanning
                ? '正在理解需求…'
                : isOptimizing
                  ? '正在生成最终提示词…'
                  : planModeEnabled
                    ? '先确认并增强'
                    : '直接增强提示词' }}
          </ElButton>
        </div>
      </div>
    </form>
  </section>
</template>

<style scoped>
.composer-card {
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
  padding: 22px 22px 18px;
}

.composer-heading {
  flex: 0 0 auto;
  margin-bottom: 16px;
}

.step-label {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.8px;
  text-transform: uppercase;
}

.prompt-title {
  display: grid;
  gap: 2px;
  margin: 12px 0 0;
  font-family: var(--font-display);
  font-size: 28px;
  font-weight: 700;
  line-height: 1.28;
}

.prompt-title span {
  display: block;
}

.prompt-title-primary {
  color: var(--text-primary);
}

.prompt-title-secondary {
  color: var(--text-muted);
}

form {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

.prompt-input {
  display: flex;
  min-height: 0;
  flex: 1;
}

.prompt-input :deep(.el-textarea) {
  display: flex;
  min-height: 0;
  height: 100%;
  flex: 1;
}

.prompt-input :deep(.el-textarea__inner) {
  min-height: 0 !important;
  height: 100% !important;
  flex: 1;
  padding: 16px 18px 34px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 14px;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 1.75;
  background: var(--glass-bg-subtle);
  box-shadow: none;
}

.prompt-input :deep(.el-textarea__inner:focus) {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.prompt-input :deep(.el-input__count) {
  right: 12px;
  bottom: 8px;
  color: var(--text-muted);
  background: transparent;
}

.plan-note {
  display: grid;
  flex: 0 0 auto;
  gap: 3px;
  margin-top: 12px;
  padding: 10px 14px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 10px;
  background: var(--glass-bg-subtle);
}

.plan-note strong {
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
}

.plan-note.is-active {
  border-color: var(--accent-border);
  background: var(--accent-soft);
}

.plan-note.is-active strong {
  color: var(--accent);
}

.plan-note span {
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.6;
}

.composer-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: auto;
  padding-top: 16px;
}

.composer-options {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
}

.switch-control {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 14px;
  cursor: pointer;
}

.model-control {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 14px;
}

.composer-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.model-select {
  width: 220px;
}

.model-option {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}

.model-option strong {
  color: var(--text-primary);
  font-weight: 600;
}

.model-option small {
  color: var(--text-muted);
  font-size: 13px;
}

.model-select :deep(.el-input__wrapper) {
  border-radius: 999px;
  background: var(--glass-bg-subtle);
  box-shadow: 0 0 0 1px var(--glass-border-subtle) inset;
}

.plan-badge {
  padding: 4px 10px;
  border-radius: 999px;
  color: var(--accent);
  font-size: 12px;
  font-style: normal;
  font-weight: 700;
  letter-spacing: 0.04em;
  background: var(--accent-soft);
}

.optimize-button {
  min-width: 200px;
  min-height: 48px;
  border: 0;
  border-radius: var(--radius-pill);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  background: var(--accent);
  box-shadow: 0 4px 20px color-mix(in srgb, var(--accent) 28%, transparent);
  transition:
    transform var(--duration-ui) var(--ease-standard),
    box-shadow var(--duration-ui) var(--ease-standard),
    background-color var(--duration-ui) var(--ease-standard);
}

.optimize-button :deep(.el-icon) {
  font-size: 18px;
}

.optimize-button:hover,
.optimize-button:focus-visible {
  color: #fff;
  background: color-mix(in srgb, var(--accent) 88%, white);
  box-shadow: 0 6px 30px color-mix(in srgb, var(--accent) 38%, transparent);
  transform: translateY(-2px);
}

@media (max-width: 900px) {
  .composer-card {
    min-height: 0;
    padding: 18px 16px 16px;
  }

  .prompt-title {
    font-size: 24px;
  }
}

@media (max-width: 600px) {
  .composer-card {
    padding: 16px 14px 14px;
  }

  .composer-controls {
    align-items: stretch;
    flex-direction: column;
  }

  .composer-actions {
    width: 100%;
    margin-left: 0;
    align-items: stretch;
    flex-direction: column;
  }

  .composer-options,
  .switch-control,
  .model-control {
    width: 100%;
  }

  .switch-control,
  .model-control {
    justify-content: space-between;
    min-height: 44px;
  }

  .model-select {
    width: min(70%, 240px);
  }

  .optimize-button {
    width: 100%;
    min-height: 48px;
  }
}
</style>
