<script setup lang="ts">
import { ElButton, ElCheckbox, ElCheckboxGroup, ElDialog } from 'element-plus';
import { computed, ref, watch } from 'vue';

export interface UploadCandidate {
  path: string;
  file: File;
}

const props = defineProps<{
  modelValue: boolean;
  candidates: UploadCandidate[];
  omittedCount: number;
}>();
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'confirm', indices: number[]): void;
}>();

const selected = ref<number[]>([]);
watch(() => props.candidates, (candidates) => {
  selected.value = candidates.map((_, index) => index);
}, { immediate: true });

const selectedBytes = computed(() => selected.value.reduce(
  (total, index) => total + (props.candidates[index]?.file.size ?? 0), 0,
));
const exceedsBudget = computed(() => selected.value.length > 100
  || selectedBytes.value > 200 * 1024 * 1024);
const formatSize = (size: number): string => `${(size / 1024 / 1024).toFixed(1)} MB`;

const confirm = (): void => {
  if (exceedsBudget.value) return;
  emit('confirm', [...selected.value]);
  emit('update:modelValue', false);
};
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    title="确认上传需要解析的文档"
    width="min(620px, 94vw)"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p>项目代码已在浏览器中处理。以下文档只有勾选并确认后才会发送到本项目后端解析，供 Plan Mode 和最终提示词检索使用。</p>
    <p v-if="omittedCount > 0" role="status">另有 {{ omittedCount }} 个文档因大小、安全规则或数量限制未加入清单。</p>
    <ElCheckboxGroup v-model="selected" class="candidate-list" aria-label="待上传文档">
      <ElCheckbox
        v-for="(candidate, index) in candidates"
        :key="`${candidate.path}-${index}`"
        :value="index"
        class="candidate-item"
      >
        <span class="candidate-path">{{ candidate.path }}</span>
        <small>{{ formatSize(candidate.file.size) }}</small>
      </ElCheckbox>
    </ElCheckboxGroup>
    <p class="budget-note" :class="{ 'is-invalid': exceedsBudget }">
      已选 {{ selected.length }} 个，{{ formatSize(selectedBytes) }}；每批最多 100 个、合计 200 MiB，单个最多 50 MiB。
    </p>
    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">暂不上传</ElButton>
      <ElButton type="primary" :disabled="selected.length === 0 || exceedsBudget" @click="confirm">
        确认上传所选文档
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.candidate-list {
  display: grid;
  max-height: 300px;
  gap: 4px;
  overflow: auto;
  padding: 8px;
  border: 1px solid var(--glass-border);
  border-radius: 8px;
}

.candidate-item {
  display: flex;
  min-width: 0;
  margin: 0;
}

.candidate-path { overflow-wrap: anywhere; }
.candidate-item small { margin-left: 8px; color: var(--text-muted); }
.budget-note { color: var(--text-secondary); font-size: 12px; }
.budget-note.is-invalid { color: var(--el-color-danger); }
</style>
