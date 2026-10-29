<script setup lang="ts">
import { computed } from 'vue';
import { ElButton, ElInput, ElOption, ElSelect, ElTag } from 'element-plus';
import type { SelectedMedia, SelectedSource } from '@/stores/imagePrompt';
import type { ImagePromptReplacementRelation } from '@/types/imagePrompt';

const props = defineProps<{ modelValue: ImagePromptReplacementRelation[]; files: SelectedMedia[]; sourceFiles?: SelectedSource[]; general?: boolean; disabled: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [relations: ImagePromptReplacementRelation[]] }>();
const operations = { PRESERVE: '保留', REPLACE: '替换', REMOVE: '移除', FUSE: '融合' };
const attributes = computed(() => props.general ? ['技术栈', '实现方式', '组件结构', '接口契约', '权限行为', '数据流程', '交互风格', '内容', '输出目标', '视觉布局'] : ['主体', '配色', '风格', '构图', '布局', '文字', '镜头节奏', '动作']);
// 两种来源只在关系选择器转换成展示定位，不把文本证据伪造成图像单元。
const choices = computed(() => [
  ...props.files.filter(file => file.asset && file.units.length).map(file => ({ key: file.key, name: file.name, track: file.track, asset: { id: file.asset!.id },
    units: file.asset!.units.filter(unit => file.units.some(selected => selected.unitId === unit.id)).map(unit => ({ id: unit.id, label: unit.page !== null ? `第 ${unit.page} 页` : unit.timestampMs !== null ? `${(unit.timestampMs / 1000).toFixed(2)} 秒` : '整张图片' })) })),
  ...(props.sourceFiles ?? []).filter(file => file.asset && file.unitIds.length).map(file => ({ key: file.key, name: file.name, track: file.track, asset: { id: file.asset!.id },
    units: file.asset!.units.filter(unit => file.unitIds.includes(unit.id)).map(unit => ({ id: unit.id, label: `${unit.path} · ${unit.label}` })) })),
]);
const update = (id: string, patch: Partial<ImagePromptReplacementRelation>): void => emit('update:modelValue', props.modelValue.map(item => item.id === id ? { ...item, ...patch } : item));
const add = (): void => emit('update:modelValue', [...props.modelValue, { id: crypto.randomUUID(), operation: 'REPLACE', referenceObject: '', replacementObject: '', instruction: '', attributes: [], scope: '' }]);
const remove = (id: string): void => emit('update:modelValue', props.modelValue.filter(item => item.id !== id));
const changeOperation = (id: string, operation: ImagePromptReplacementRelation['operation']): void => update(id, {
  operation, ...(['REPLACE', 'FUSE'].includes(operation) ? {} : { replacementAssetId: undefined, replacementUnitId: undefined, replacementObject: '' }),
});
const missingSource = (id?: string): boolean => Boolean(id && !choices.value.some(file => file.asset.id === id));
const sources = (track: SelectedMedia['track']) => choices.value.filter(file => file.track === track);
const label = (file: { name: string; track: string; key: string }): string => {
  const sourceIndex = (props.sourceFiles ?? []).findIndex(item => item.key === file.key);
  if (sourceIndex >= 0) return `S${sourceIndex + 1} · ${file.name}`;
  return `${file.track === 'REFERENCE' ? 'R' : 'T'}${props.files.filter(item => item.track === file.track).findIndex(item => item.key === file.key) + 1} · ${file.name}`;
};
const units = (assetId?: string) => choices.value.find(file => file.asset.id === assetId)?.units ?? [];
</script>

<template>
  <section data-testid="replacement-relations" class="relations-section" aria-labelledby="replacement-relations-title"><div class="section-heading"><h3 id="replacement-relations-title">保留与替换关系</h3><ElTag size="small" effect="plain">用户要求</ElTag></div>
    <p class="hint">明确保留什么、把什么换成什么，以及作用范围。没有替换要求可以不添加；新内容也可直接用文字描述。</p>
    <article v-for="(relation, index) in modelValue" :key="relation.id" class="relation-card" :aria-label="`第${index + 1}条替换关系`"><div class="section-heading"><strong>关系 {{ index + 1 }}</strong><ElButton text type="danger" :disabled="disabled" @click="remove(relation.id)">删除关系</ElButton></div>
      <label class="field">操作<ElSelect :model-value="relation.operation" :disabled="disabled" :aria-label="`关系${index + 1}操作`" @update:model-value="changeOperation(relation.id, $event as ImagePromptReplacementRelation['operation'])"><ElOption v-for="(name, value) in operations" :key="value" :value="value" :label="name" /></ElSelect></label>
      <label class="field">参考来源（选填）<ElSelect :model-value="relation.referenceAssetId ?? ''" clearable :disabled="disabled" :aria-label="`关系${index + 1}参考素材`" @update:model-value="update(relation.id, { referenceAssetId: $event || undefined, referenceUnitId: undefined })"><ElOption v-if="missingSource(relation.referenceAssetId)" :value="relation.referenceAssetId!" label="原素材已移除，请重新选择" disabled /><ElOption v-for="file in sources('REFERENCE')" :key="file.key" :value="file.asset!.id" :label="label(file)" /></ElSelect></label>
      <label v-if="units(relation.referenceAssetId).length" class="field">参考定位（选填）<ElSelect :model-value="relation.referenceUnitId ?? ''" clearable :disabled="disabled" :aria-label="`关系${index + 1}参考页帧`" @update:model-value="update(relation.id, { referenceUnitId: $event || undefined })"><ElOption v-for="unit in units(relation.referenceAssetId)" :key="unit.id" :value="unit.id" :label="unit.label" /></ElSelect></label>
      <label class="field">参考中的对象／内容<ElInput :model-value="relation.referenceObject" :disabled="disabled" maxlength="500" :aria-label="`关系${index + 1}参考对象`" :placeholder="general ? '例如：src/pages/Orders.vue中的提交按钮及校验逻辑' : '例如：画面中央的产品，或第2页的标题区'" @update:model-value="update(relation.id, { referenceObject: $event })" /></label>
      <template v-if="relation.operation === 'REPLACE' || relation.operation === 'FUSE'">
        <label class="field">新内容来源（选填）<ElSelect :model-value="relation.replacementAssetId ?? ''" clearable :disabled="disabled" :aria-label="`关系${index + 1}替换素材`" @update:model-value="update(relation.id, { replacementAssetId: $event || undefined, replacementUnitId: undefined })"><ElOption v-if="missingSource(relation.replacementAssetId)" :value="relation.replacementAssetId!" label="原素材已移除，请重新选择" disabled /><ElOption v-for="file in sources('REPLACEMENT')" :key="file.key" :value="file.asset!.id" :label="label(file)" /></ElSelect></label>
        <label v-if="units(relation.replacementAssetId).length" class="field">新内容定位（选填）<ElSelect :model-value="relation.replacementUnitId ?? ''" clearable :disabled="disabled" :aria-label="`关系${index + 1}替换页帧`" @update:model-value="update(relation.id, { replacementUnitId: $event || undefined })"><ElOption v-for="unit in units(relation.replacementAssetId)" :key="unit.id" :value="unit.id" :label="unit.label" /></ElSelect></label>
        <label class="field">新对象／新内容<ElInput :model-value="relation.replacementObject" :disabled="disabled" maxlength="500" :aria-label="`关系${index + 1}新内容`" :placeholder="general ? '例如：改用React实现，或选择新内容来源' : '例如：T1中的白色耳机，或直接填写新的标题'" @update:model-value="update(relation.id, { replacementObject: $event })" /></label>
      </template>
      <label class="field">涉及属性（最多8项，可自填）<ElSelect :model-value="relation.attributes" multiple :multiple-limit="8" filterable allow-create default-first-option :disabled="disabled" :aria-label="`关系${index + 1}属性`" @update:model-value="update(relation.id, { attributes: $event })"><ElOption v-for="attribute in attributes" :key="attribute" :value="attribute" :label="attribute" /></ElSelect></label>
      <label class="field">作用范围<ElInput :model-value="relation.scope" :disabled="disabled" maxlength="500" :aria-label="`关系${index + 1}作用范围`" :placeholder="general ? '例如：前端订单模块，保留服务端接口与鉴权' : '例如：海报中央、第2页、第1镜头0—3秒'" @update:model-value="update(relation.id, { scope: $event })" /></label>
      <label class="field">具体要求<ElInput :model-value="relation.instruction" type="textarea" :rows="2" :disabled="disabled" maxlength="1500" :aria-label="`关系${index + 1}具体要求`" :placeholder="general ? '说明要替换的属性、必须保留的行为及适用条件。' : '例如：保留参考构图，将中央主体换为新产品，保持新产品外形与标识。'" @update:model-value="update(relation.id, { instruction: $event })" /></label>
    </article>
    <ElButton :disabled="disabled || modelValue.length >= 12" @click="add">添加保留／替换关系</ElButton>
    <p v-if="modelValue.length" class="hint">修改素材选择、关系或创作要求后，需重新梳理 Plan。素材建议不会自动视为你的确认。</p>
  </section>
</template>

<style scoped>
.relations-section { padding: 16px; margin-top: 18px; border: 1px solid var(--line-strong); border-radius: var(--radius-md); }
.section-heading { display: flex; align-items: center; gap: 10px; justify-content: space-between; flex-wrap: wrap; } h3 { margin: 0; color: var(--ink-strong); font-size: 15px; } strong { font-size: 14px; color: var(--ink-strong); }
.hint { font-size: 12px; line-height: 1.75; color: var(--ink-muted); } .relation-card { padding: 12px; margin: 14px 0; border: 1px solid var(--accent-border); border-radius: var(--radius-sm); background: var(--surface-panel); }
.field { display: grid; gap: 6px; margin-top: 12px; font-size: 13px; color: var(--ink-strong); }
</style>
