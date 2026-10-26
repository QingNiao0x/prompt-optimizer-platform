<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton, ElInputNumber, ElOption, ElSelect, ElTag } from 'element-plus';
import type { SelectedMedia } from '@/stores/imagePrompt';
import type { ImagePromptMaterialRef, ImageReferenceRole } from '@/types/imagePrompt';
import ImagePromptMediaReviewDialog from './ImagePromptMediaReviewDialog.vue';

const props = defineProps<{ file: SelectedMedia; label: string; busy: boolean; maxUnits: number }>();
const emit = defineEmits<{ parse: []; remove: []; update: [patch: Partial<Pick<SelectedMedia, 'role' | 'units' | 'startMs' | 'endMs'>>] }>();
const reviewVisible = ref(false);
const video = computed(() => /\.(mp4|mov|webm)$/i.test(props.file.name));
const parsed = computed(() => props.file.asset && ['READY', 'PARTIAL'].includes(props.file.asset.status));
const ready = computed(() => parsed.value && Boolean(props.file.asset?.units.length));
/** 解析成功不等于用户已确认外发范围；空结果仍可主动刷新，不进入无法操作的选择框。 */
const statusHint = computed(() => {
  if (props.file.parsing) return '正在本平台解析，尚未发送模型。';
  if (!props.file.asset) return '仅在浏览器中选择，尚未上传。';
  if (parsed.value && !props.file.asset.units.length) return '未解析出可选画面，请刷新解析状态，或移除后重新选择。';
  if (ready.value) return `可选 ${props.file.asset.units.length} 个图片／页／帧，已选 ${props.file.units.length} 个，临时素材约一小时后清理。`;
  return '解析尚未完成，可刷新状态。';
});
const startSeconds = computed({ get: () => (props.file.startMs ?? 0) / 1000, set: (value: number) => emit('update', { startMs: value * 1000 }) });
const endSeconds = computed({ get: () => (props.file.endMs ?? 30000) / 1000, set: (value: number) => emit('update', { endMs: value * 1000 }) });
const save = (units: ImagePromptMaterialRef['units']): void => { emit('update', { units }); reviewVisible.value = false; };
</script>

<template>
  <article class="media-card"><div class="media-heading"><strong>{{ label }} · {{ file.name }}</strong><ElTag size="small" effect="plain">{{ file.asset?.kind ?? '待解析' }}</ElTag></div>
    <p class="hint">{{ statusHint }}</p>
    <p v-if="ready && !file.units.length" class="hint selection-hint">下一步：选择并保存识别范围，才会发送给所选模型。</p>
    <ElSelect :model-value="file.role" :aria-label="`${file.name}的素材用途`" :disabled="busy" @update:model-value="emit('update', { role: $event as ImageReferenceRole })"><ElOption label="整体灵感" value="GENERAL" /><ElOption label="主体／内容" value="SUBJECT" /><ElOption label="风格与配色" value="STYLE" /><ElOption label="布局与结构" value="LAYOUT" /></ElSelect>
    <div v-if="video && !file.asset" class="video-range"><label>片段开始（秒）<ElInputNumber v-model="startSeconds" :min="0" :max="86400" :disabled="busy" :aria-label="`${file.name}片段开始秒数`" /></label><label>片段结束（秒）<ElInputNumber v-model="endSeconds" :min="0" :max="86430" :disabled="busy" :aria-label="`${file.name}片段结束秒数`" /></label><p class="hint">每段最多 30 秒。首版理解可见画面与镜头变化，不分析音轨。</p></div>
    <p v-if="file.error" class="warning" role="alert">{{ file.error }}</p>
    <ul v-if="file.asset?.warnings.length" class="warning"><li v-for="warning in file.asset.warnings" :key="warning">{{ warning }}</li></ul>
    <div class="media-actions"><ElButton v-if="!ready" size="small" :loading="file.parsing" :disabled="busy && !file.parsing" @click="emit('parse')">{{ file.asset ? '刷新解析状态' : '上传到平台解析预览' }}</ElButton><ElButton v-if="ready" size="small" type="primary" plain :disabled="busy" @click="reviewVisible = true">选择页／帧与遮挡</ElButton><ElButton size="small" text type="danger" :disabled="busy && !file.parsing" @click="emit('remove')">{{ file.parsing ? '取消并移除' : '移除素材' }}</ElButton></div>
    <p v-if="!file.asset" class="hint">点击解析仅上传至本平台；审阅后，还需单独同意发送给所选模型。</p>
    <ImagePromptMediaReviewDialog v-if="file.asset && ready" :visible="reviewVisible" :asset="file.asset" :units="file.units" :max-units="maxUnits" @close="reviewVisible = false" @save="save" />
  </article>
</template>

<style scoped>
.media-card { padding: 12px; margin: 12px 0; border: 1px solid var(--line-strong); border-radius: var(--radius-sm); background: var(--surface-panel); min-width: 0; }
.media-heading { display: flex; gap: 8px; align-items: flex-start; justify-content: space-between; } strong { color: var(--ink-strong); font-size: 14px; overflow-wrap: anywhere; min-width: 0; }
.hint, .warning { font-size: 12px; line-height: 1.75; color: var(--ink-muted); overflow-wrap: anywhere; } .warning { color: var(--warning); padding-left: 0; } ul.warning { padding-left: 18px; }
.selection-hint { color: var(--accent); font-weight: 600; }
.media-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; } .media-actions .el-button + .el-button { margin-left: 0; }
.video-range { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 12px; } .video-range label { display: grid; gap: 6px; font-size: 12px; color: var(--ink-muted); } .video-range .el-input-number { width: 100%; } .video-range p { grid-column: 1 / -1; margin: 0; }
</style>
