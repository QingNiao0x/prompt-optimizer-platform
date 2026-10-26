<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton, ElCheckbox, ElCheckboxGroup, ElInput, ElRadio, ElRadioGroup, ElTag } from 'element-plus';
import { ChatLineRound, ArrowDown } from '@element-plus/icons-vue';
import type { ImagePromptAnswer, ImagePromptPlan, ImagePromptQuestion } from '@/types/imagePrompt';
import { imagePromptPlanProgress, imagePromptQuestionAnswer, isImagePromptAnswered } from '@/features/imageprompt/planPresentation';

const props = defineProps<{ plan: ImagePromptPlan; answers: Record<string, ImagePromptAnswer>; busy: boolean;
  recoveryPending: boolean; hasDraftChanges: boolean; outputLabel: string; highlight?: boolean }>();
const emit = defineEmits<{ answer: [id: string, value: ImagePromptAnswer]; confirm: []; editRequirements: []; position: [element: HTMLElement] }>();
const heading = ref<HTMLElement>();
const questionElements = new Map<string, HTMLElement>();
const bindQuestion = (id: string, element: unknown): void => { if (element instanceof HTMLElement) questionElements.set(id, element); else questionElements.delete(id); };
const progress = computed(() => imagePromptPlanProgress(props.plan, props.answers));
const showMissingValidation = ref(false);
watch(() => props.plan.id, () => { showMissingValidation.value = false; });
const message = computed(() => !props.plan.questions.length ? `创作要求已充分，无需补充问题；确认后即可生成${props.outputLabel}提示词。`
  : progress.value.missing.length ? `还有 ${progress.value.missing.length} 项必答，请在下方回答。`
    : !progress.value.required ? '可补充偏好，也可直接确认生成。' : '必答项已完成，可补充其他偏好，或确认生成。');
const answer = (question: ImagePromptQuestion) => imagePromptQuestionAnswer(question, props.answers[question.id]);
const update = (question: ImagePromptQuestion, patch: Partial<ImagePromptAnswer>): void => emit('answer', question.id, { ...answer(question), ...patch });
const position = (element?: HTMLElement): void => {
  if (!element) return;
  emit('position', element);
};
const focus = (): void => position(heading.value);
const firstMissing = (): void => position(questionElements.get(progress.value.missing[0]?.id ?? ''));
// 缺答反馈只属于当前问答展示，不把可随填写消除的提示写成全局模型错误；store与后端继续独立校验。
const confirm = (): void => {
  if (progress.value.missing.length) { showMissingValidation.value = true; firstMissing(); return; }
  showMissingValidation.value = false; emit('confirm');
};
defineExpose({ focus, firstMissing });
</script>

<template>
  <section data-testid="image-plan-panel" data-position-anchor class="plan-section" :class="{ 'plan-highlight': highlight }" aria-labelledby="image-plan-title">
    <div class="plan-heading"><ChatLineRound aria-hidden="true" /><div><span class="plan-eyebrow">下一步 · Plan 创作确认</span>
      <h3 id="image-plan-title" ref="heading" tabindex="-1">请确认创作细节</h3></div>
      <ElTag :type="progress.missing.length ? 'warning' : 'success'" effect="plain">{{ progress.missing.length ? '待回答' : '待确认' }}</ElTag>
    </div>
    <p class="plan-guidance" role="status" aria-live="polite">{{ message }}</p>
    <p class="plan-summary">{{ plan.summary }}</p>
    <div v-if="plan.questions.length" class="plan-progress"><span>必答已完成 {{ progress.completed }} / {{ progress.required }}<template v-if="progress.optional">，另有 {{ progress.optional }} 项选答</template></span>
      <ElButton v-if="progress.missing.length" link type="primary" @click="firstMissing"><ArrowDown class="action-icon" />定位未答问题</ElButton>
      <ElButton v-else link type="primary" @click="focus">回到问题顶部</ElButton></div>
    <fieldset v-for="(question, index) in plan.questions" :key="question.id" :ref="element => bindQuestion(question.id, element)"
      :disabled="busy" tabindex="-1" data-position-anchor class="plan-question" :class="{ 'is-answered': isImagePromptAnswered(answers[question.id]), 'is-missing': showMissingValidation && question.required && !isImagePromptAnswered(answers[question.id]) }">
      <legend><span class="question-number">{{ index + 1 }}</span>{{ question.question }}</legend>
      <div class="question-status"><ElTag size="small" :type="question.required ? 'warning' : 'info'" effect="plain">{{ question.required ? '必答' : '选答' }}</ElTag><span>{{ isImagePromptAnswered(answers[question.id]) ? '已回答' : question.required ? '待回答' : '可跳过' }}</span></div>
      <ElRadioGroup v-if="question.type === 'SINGLE_CHOICE'" :model-value="answer(question).optionIds[0] ?? ''" :disabled="busy" :aria-label="question.question" @update:model-value="update(question, { optionIds: [$event as string] })">
        <ElRadio v-for="option in question.options" :key="option.id" :value="option.id" :class="{ 'selected-answer': answer(question).optionIds.includes(option.id) }">{{ option.label }}</ElRadio>
      </ElRadioGroup>
      <ElCheckboxGroup v-else-if="question.type === 'MULTIPLE_CHOICE'" :model-value="answer(question).optionIds" :disabled="busy" :aria-label="question.question" @update:model-value="update(question, { optionIds: $event as string[] })">
        <ElCheckbox v-for="option in question.options" :key="option.id" :value="option.id" :class="{ 'selected-answer': answer(question).optionIds.includes(option.id) }">{{ option.label }}</ElCheckbox>
      </ElCheckboxGroup>
      <ElInput :model-value="answer(question).customText" type="textarea" :rows="2" maxlength="2000" :disabled="busy" :aria-label="`${question.question}的补充回答`"
        :placeholder="question.type === 'TEXT' ? '填写你的要求，也可以明确委托 AI 设计。' : '可直接自填，也可补充说明，与已选选项一起保留。'" @update:model-value="update(question, { customText: $event })" />
    </fieldset>
    <ul v-if="plan.warnings.length" class="plan-warnings"><li v-for="warning in plan.warnings" :key="warning">{{ warning }}</li></ul>
    <p v-if="showMissingValidation && progress.missing.length" class="missing-feedback" role="alert">请完成剩余 {{ progress.missing.length }} 项必答，也可以自填要求或明确委托 AI 设计。</p>
    <p v-if="hasDraftChanges || recoveryPending" class="plan-blocked" role="status">{{ hasDraftChanges ? '请先保存或取消当前结果编辑，再确认生成。' : '上次请求的结果尚未确认，请先查询处理结果。' }}</p>
    <ElButton type="primary" class="confirm-action" :loading="busy" :disabled="busy || recoveryPending || hasDraftChanges" @click="confirm">确认并生成{{ outputLabel }}提示词</ElButton>
    <ElButton link type="primary" :disabled="busy" @click="emit('editRequirements')">返回修改创作要求</ElButton>
  </section>
</template>

<style scoped>
.plan-section { min-width: 0; margin: 14px 0 24px; padding: 18px; border: 2px solid var(--accent); border-radius: var(--radius-md); background: var(--glass-fallback); box-shadow: 0 5px 22px var(--accent-soft); }
.plan-heading { display: flex; align-items: center; gap: 10px; }
.plan-heading > svg { width: 28px; height: 28px; color: var(--accent); flex-shrink: 0; }
.plan-heading > div { min-width: 0; flex: 1; }
.plan-eyebrow { color: var(--accent); font-size: 12px; font-weight: 600; }
h3 { margin: 4px 0 0; color: var(--ink-strong); font-size: 18px; }
h3:focus-visible, .plan-question:focus-visible { outline: 3px solid var(--accent); outline-offset: 4px; }
p { margin: 12px 0; line-height: 1.75; overflow-wrap: anywhere; }
.plan-guidance { padding: 10px 12px; border-radius: var(--radius-sm); background: var(--accent-soft); color: var(--ink-strong); font-size: 14px; font-weight: 600; }
.plan-summary { font-size: 14px; color: var(--ink-normal); }
.plan-progress { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12px; color: var(--ink-muted); }
.action-icon { width: 14px; height: 14px; margin-right: 4px; }
.plan-question { margin: 18px 0; padding: 12px; min-width: 0; border: 1px solid var(--accent-border); border-radius: var(--radius-sm); background: var(--surface-panel); }
.plan-question.is-answered { border-color: var(--success); }
.plan-question.is-missing { border-color: var(--danger); }
legend { padding: 0 4px; color: var(--ink-strong); font-size: 15px; font-weight: 600; line-height: 1.7; overflow-wrap: anywhere; max-width: 100%; }
.question-number { display: inline-flex; justify-content: center; align-items: center; width: 24px; height: 24px; margin-right: 8px; border-radius: 50%; color: var(--accent); background: var(--accent-soft); }
.question-status { display: flex; gap: 8px; align-items: center; margin: 2px 0 12px; font-size: 12px; color: var(--ink-muted); }
.plan-question :deep(.el-radio-group), .plan-question :deep(.el-checkbox-group) { display: grid; gap: 8px; margin-bottom: 12px; }
.plan-question :deep(.el-radio), .plan-question :deep(.el-checkbox) { margin: 0; padding: 10px; min-height: 44px; height: auto; white-space: normal; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); }
.plan-question :deep(.selected-answer) { background: var(--accent-soft); border-color: var(--accent); }
.plan-question :deep(.el-radio__label), .plan-question :deep(.el-checkbox__label) { white-space: normal; line-height: 1.65; overflow-wrap: anywhere; }
.confirm-action { width: 100%; margin: 8px 0 12px; height: auto; min-height: 40px; white-space: normal; }
.plan-blocked, .plan-warnings { color: var(--warning); font-size: 12px; line-height: 1.75; }
.missing-feedback { color: var(--danger); font-size: 13px; }
.plan-warnings { padding-left: 18px; }
.plan-highlight { animation: plan-arrival 650ms ease-out 1; }
@keyframes plan-arrival { from { opacity: .55; box-shadow: 0 0 0 7px var(--accent-soft); } to { opacity: 1; box-shadow: 0 5px 22px var(--accent-soft); } }
@media (max-width: 640px) { .plan-section { padding: 14px; } .plan-heading { flex-wrap: wrap; } h3 { font-size: 17px; } }
@media (prefers-reduced-motion: reduce) { .plan-highlight { animation: none; } }
</style>
