<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElDialog, ElInput } from 'element-plus';
import { buildPromptHandoff, handoffPurposeLabel, preparePromptHandoff } from '@/features/optimization/promptHandoff';
import type { OptimizationResult } from '@/types/api';

const props = defineProps<{ modelValue: boolean; result: OptimizationResult; disabled: boolean; copying: boolean }>();
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'copy', content: string, source: OptimizationResult): void;
}>();
const selected = ref<string[]>([]);
const includeFileChecklist = ref(false);
const handoff = computed(() => preparePromptHandoff(props.result.contextReport));
const preview = computed(() => buildPromptHandoff(props.result, handoff.value, selected.value, includeFileChecklist.value));
const availableFiles = computed(() => handoff.value.materials.filter(item => item.available && item.isFile).length);
// 资料选择只属于当前保存稿，重新打开、编辑或撤销结果后重新选择。
watch([() => props.result, () => props.modelValue], () => {
  selected.value = [];
  includeFileChecklist.value = false;
}, { flush: 'sync' });
</script>

<template>
  <ElDialog :model-value="modelValue" title="交给执行 AI" class="prompt-handoff-dialog"
    width="min(860px, calc(100vw - 24px))" append-to-body align-center
    :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)">
    <p class="handoff-hint">勾选当前结果保留的片段，可将资料随正文一起复制给执行 AI。执行端需要修改文件、参照版式或画面时，请另行提供所需原文件，并保留已确认的选取及脱敏范围。</p>
    <p class="handoff-hint">当前保存稿 {{ result.recordId?.slice(0, 8) ?? '当前会话' }} · 编辑版本 {{ result.editRevision ?? 0 }}。选择材料后可在下方核对完整交接内容。</p>
    <ElAlert v-if="!availableFiles" title="此结果没有可携带的文件原文片段。需要原文件时，请在执行端重新提供。" type="info" :closable="false" />
    <ElAlert v-if="handoff.excludedFiles" :title="`${handoff.excludedFiles} 份受保护或被服务端过滤的资料已排除。`" type="info" :closable="false" />
    <ElCheckboxGroup v-model="selected" :disabled="disabled || copying" class="handoff-materials" aria-label="选择随正文携带的资料片段">
      <div v-for="item in handoff.materials" :key="item.id" class="handoff-material">
        <ElCheckbox :value="item.id" :disabled="!item.available">{{ item.path }}</ElCheckbox>
        <p>{{ handoffPurposeLabel(item.purpose) }} · {{ item.coverage }}</p>
        <details v-if="item.available"><summary>查看此片段 · {{ item.content.length.toLocaleString('zh-CN') }} 字符</summary><pre>{{ item.content }}</pre></details>
      </div>
    </ElCheckboxGroup>
    <div v-if="handoff.files.length" class="file-checklist">
      <ElCheckbox v-model="includeFileChecklist" :disabled="disabled || copying">附带原文件核对清单（{{ handoff.files.length }} 个文件名及用途）</ElCheckbox>
      <ul><li v-for="file in handoff.files" :key="file.path"><strong>{{ file.path }}</strong> · {{ handoffPurposeLabel(file.purpose) }}<br />{{ file.guidance }}</li></ul>
    </div>
    <p class="handoff-hint">已选 {{ selected.length }} 个片段，交接内容共 {{ preview.length.toLocaleString('zh-CN') }} 字符。</p>
    <ElInput :model-value="preview" readonly type="textarea" :rows="9" aria-label="完整交接内容预览" />
    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">关闭</ElButton>
      <ElButton type="primary" :disabled="disabled" :loading="copying" @click="emit('copy', preview, result)">复制交接内容</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
/* 长资料仅滚动正文，手机上仍能看到标题和复制操作。 */
/* ElDialog挂载到body，专属全局类只匹配本弹窗。 */
:global(.prompt-handoff-dialog) { display: flex; flex-direction: column; max-height: calc(100dvh - 48px); }
:global(.prompt-handoff-dialog .el-dialog__body) { min-height: 0; overflow-y: auto; }
:global(.prompt-handoff-dialog .el-dialog__header), :global(.prompt-handoff-dialog .el-dialog__footer) { flex-shrink: 0; }
.handoff-hint, .handoff-material p, .file-checklist { color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.handoff-materials { display: grid; max-height: 300px; overflow: auto; gap: 8px; margin: 12px 0; }
.handoff-material { min-width: 0; padding: 10px; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); }
.handoff-material .el-checkbox, .file-checklist .el-checkbox { max-width: 100%; height: auto; align-items: flex-start; }
.handoff-material :deep(.el-checkbox__label), .file-checklist :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; line-height: 1.6; }
.handoff-material :deep(.el-checkbox__input), .file-checklist :deep(.el-checkbox__input) { margin-top: 3px; }
.handoff-material p { margin: 4px 0; }
.handoff-material summary { cursor: pointer; color: var(--ink-muted); font-size: 12px; }
.handoff-material pre { max-height: 220px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--ink-strong); background: var(--surface-code); padding: 8px; border-radius: var(--radius-sm); }
.file-checklist ul { max-height: 160px; overflow: auto; padding-left: 20px; overflow-wrap: anywhere; }
</style>
