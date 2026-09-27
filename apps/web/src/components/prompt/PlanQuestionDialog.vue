<script setup lang="ts">
import { ArrowLeft, ArrowRight, Check, MagicStick } from '@element-plus/icons-vue';
import { ElButton, ElDialog, ElInput } from 'element-plus';
import { computed, ref, watch } from 'vue';
import { recoverPlanDrafts } from '@/features/optimization/planDraftRecovery';
import { recordPlanningEvent } from '@/services/planningMetrics';

import type {
  OptimizationPlan,
  PlanAnswer,
  PlanConfirmation,
  PlanOption,
  PlanQuestion,
} from '@/types/api';

interface Props {
  modelValue: boolean;
  plan?: OptimizationPlan;
  isGenerating: boolean;
  errorMessage?: string;
  recoveryRevision?: number;
}

interface Emits {
  (event: 'update:modelValue', value: boolean): void;
  (event: 'confirm', value: PlanConfirmation): void;
}

interface DraftAnswer {
  selectedOptionIds: string[];
  customAnswer: string;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const currentIndex = ref(0);
const draftAnswers = ref<Record<string, DraftAnswer>>({});
const showValidation = ref(false);
const unmatchedDrafts = ref<string[]>([]);
const reviewVisible = ref(false);
let previousQuestions: PlanQuestion[] = [];
let lastRecoveryRevision = 0;

const questions = computed(() => props.plan?.questions ?? []);
const currentQuestion = computed(() => questions.value[currentIndex.value]);
const currentDraft = computed(() => {
  const question = currentQuestion.value;
  return question ? draftAnswers.value[question.id] : undefined;
});
const isLastQuestion = computed(() => questions.value.length === 0 || currentIndex.value === questions.value.length - 1);
const completedCount = computed(() => questions.value.filter(isQuestionAnswered).length);
const currentAnswered = computed(() => {
  const question = currentQuestion.value;
  return question ? isQuestionAnswered(question) : false;
});

watch(
  () => [props.modelValue, props.plan] as const,
  ([visible]) => {
    if (!visible || !props.plan) {
      return;
    }
    currentIndex.value = 0;
    showValidation.value = false;
    reviewVisible.value = false;
    if ((props.recoveryRevision ?? 0) > lastRecoveryRevision) {
      const recovered = recoverPlanDrafts(previousQuestions, props.plan.questions, draftAnswers.value);
      draftAnswers.value = recovered.answers;
      unmatchedDrafts.value = [...unmatchedDrafts.value, ...recovered.unmatched];
    } else {
      unmatchedDrafts.value = [];
      draftAnswers.value = Object.fromEntries(props.plan.questions.map((question) => [
      question.id,
      { selectedOptionIds: [], customAnswer: '' },
      ]));
    }
    previousQuestions = props.plan.questions;
    lastRecoveryRevision = props.recoveryRevision ?? 0;
  },
);

const isSelected = (option: PlanOption): boolean =>
  currentDraft.value?.selectedOptionIds.includes(option.id) ?? false;

const chooseOption = (question: PlanQuestion, option: PlanOption): void => {
  const draft = draftAnswers.value[question.id];
  if (!draft || props.isGenerating) return;

  draft.customAnswer = '';
  if (question.type === 'SINGLE_CHOICE') {
    draft.selectedOptionIds = [option.id];
  } else {
    draft.selectedOptionIds = draft.selectedOptionIds.includes(option.id)
      ? draft.selectedOptionIds.filter((id) => id !== option.id)
      : [...draft.selectedOptionIds, option.id];
  }
  showValidation.value = false;
};

const updateCustomAnswer = (question: PlanQuestion, value: string): void => {
  const draft = draftAnswers.value[question.id];
  if (!draft || props.isGenerating) return;
  draft.customAnswer = value;
  if (value.trim()) {
    draft.selectedOptionIds = [];
  }
  showValidation.value = false;
};

const isQuestionAnswered = (question: PlanQuestion): boolean => {
  const draft = draftAnswers.value[question.id];
  if (!draft) return false;
  const length = toAnswer(question).answer.length;
  return length > 0 && length <= 1500;
};

const toAnswer = (question: PlanQuestion): PlanAnswer => {
  const draft = draftAnswers.value[question.id];
  const selectedAnswers = question.options
    .filter((option) => draft?.selectedOptionIds.includes(option.id))
    .map((option) => option.answer.trim());
  const customAnswer = draft?.customAnswer.trim();
  if (customAnswer) {
    selectedAnswers.push(customAnswer);
  }
  return {
    questionId: question.id,
    question: question.question,
    answer: selectedAnswers.join('；'),
  };
};

const continueFlow = (): void => {
  if (props.isGenerating) return;
  if (questions.value.length === 0) {
    emit('confirm', { planId: props.plan?.planId, planningContext: props.plan?.planningContext, answers: [] });
    return;
  }
  const question = currentQuestion.value;
  if (!question || !isQuestionAnswered(question)) {
    showValidation.value = true;
    return;
  }
  if (!isLastQuestion.value) {
    currentIndex.value += 1;
    showValidation.value = false;
    return;
  }

  const firstIncomplete = questions.value.findIndex((item) => !isQuestionAnswered(item));
  if (firstIncomplete >= 0) {
    currentIndex.value = firstIncomplete;
    showValidation.value = true;
    return;
  }
  recordPlanningEvent(props.plan?.planId, 'CONFIRMED');
  if (Object.values(draftAnswers.value).some((draft) => draft.customAnswer.trim())) {
    recordPlanningEvent(props.plan?.planId, 'CUSTOM_ANSWER');
  }
  emit('confirm', {
    planId: props.plan?.planId,
    planningContext: props.plan?.planningContext,
    answers: questions.value.map(toAnswer),
  });
};

const previousQuestion = (): void => {
  if (currentIndex.value > 0 && !props.isGenerating) {
    currentIndex.value -= 1;
    showValidation.value = false;
  }
};

/** 查看已填答案时允许直接返回对应问题，草稿保留，提交期间禁止修改。 */
const goToQuestion = (index: number): void => {
  if (props.isGenerating) return;
  currentIndex.value = index;
  showValidation.value = false;
  reviewVisible.value = false;
};

const close = (): void => {
  if (!props.isGenerating) {
    if (props.modelValue) recordPlanningEvent(props.plan?.planId, 'CANCELLED');
    emit('update:modelValue', false);
  }
};
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    title="确认关键细节"
    class="plan-question-dialog"
    width="min(760px, calc(100vw - 24px))"
    top="max(12px, env(safe-area-inset-top, 0px))"
    :close-on-click-modal="false"
    :close-on-press-escape="!isGenerating"
    :show-close="!isGenerating"
    destroy-on-close
    @close="close"
  >
    <template #header>
      <div class="dialog-heading">
        <p class="dialog-kicker">生成前确认</p>
        <h2>把关键细节确认清楚</h2>
        <p>{{ plan?.summary }}</p>
      </div>
    </template>

    <p v-if="recoveryRevision" role="status">确认问题已更新，请核对保留的答案后重新确认。</p>
    <details v-if="unmatchedDrafts.length">
      <summary>上次回答草稿（问题有变化，未自动填入）</summary>
      <p v-for="draft in unmatchedDrafts" :key="draft">{{ draft }}</p>
    </details>
    <template v-if="currentQuestion">
      <div class="question-progress" aria-label="回答进度">
        <div class="progress-copy">
          <span>问题 {{ currentIndex + 1 }} / {{ questions.length }}</span>
          <span>已回答 {{ completedCount }} 项</span>
        </div>
        <div class="progress-track" aria-hidden="true">
          <i
            v-for="(question, index) in questions"
            :key="question.id"
            :class="{
              'is-current': index === currentIndex,
              'is-complete': isQuestionAnswered(question),
            }"
          ></i>
        </div>
      </div>

      <section class="question-sheet" aria-live="polite">
        <div class="question-mark" aria-hidden="true">?</div>
        <div class="question-copy">
          <h3>{{ currentQuestion.question }}</h3>
          <p v-if="currentQuestion.hint">{{ currentQuestion.hint }}</p>
        </div>
        <p class="answer-instruction">
          {{ currentQuestion.type === 'FREE_TEXT' ? '请填写实际情况，示例不会自动作为答案。'
            : currentQuestion.type === 'MULTIPLE_CHOICE' ? '可多选；填写自定义回答会替换已选项。'
            : '请选择一项，或填写自己的答案。推荐项需要你主动确认。' }}
        </p>

        <div
          v-if="currentQuestion.options.length"
          class="answer-options"
          :class="{ 'answer-options--multiple': currentQuestion.type === 'MULTIPLE_CHOICE' }"
        >
          <ElButton
            v-for="option in currentQuestion.options"
            :key="option.id"
            class="answer-option"
            :class="{ 'is-selected': isSelected(option) }"
            :disabled="isGenerating"
            :aria-pressed="isSelected(option)"
            @click="chooseOption(currentQuestion, option)"
          >
            <span class="option-control">
              <Check v-if="isSelected(option)" />
            </span>
            <span class="option-copy">
              <span class="option-title">
                <strong>{{ option.label }}</strong>
                <em v-if="option.recommended">推荐</em>
              </span>
              <small>{{ option.description }}</small>
              <small v-if="option.recommended && option.recommendationReason" class="recommendation-reason">
                {{ option.recommendationReason }}
              </small>
            </span>
          </ElButton>
        </div>

        <div v-if="currentQuestion.type === 'FREE_TEXT'" class="free-answer">
          <ElInput
            :model-value="currentDraft?.customAnswer"
            :disabled="isGenerating"
            type="textarea"
            :rows="4"
            maxlength="1500"
            show-word-limit
            resize="none"
            placeholder="请填写你的实际情况"
            aria-label="填写回答"
            @update:model-value="updateCustomAnswer(currentQuestion, $event)"
          />
          <p v-if="currentQuestion.examples.length" class="answer-examples">
            <span>例如</span>
            {{ currentQuestion.examples.join('、') }}
          </p>
        </div>

        <div
          v-else-if="currentQuestion.allowCustomAnswer"
          class="custom-answer"
        >
          <label :for="`custom-answer-${currentQuestion.id}`">没有合适选项？直接填写</label>
          <ElInput
            :id="`custom-answer-${currentQuestion.id}`"
            :model-value="currentDraft?.customAnswer"
            :disabled="isGenerating"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 5 }"
            show-word-limit
            maxlength="1500"
            placeholder="输入更符合你实际情况的回答"
            @update:model-value="updateCustomAnswer(currentQuestion, $event)"
          />
        </div>

        <p v-if="showValidation && !currentAnswered" class="validation-message" role="alert">
          {{ currentQuestion && toAnswer(currentQuestion).answer.length > 1500
            ? '回答不能超过 1500 字，请减少选项或精简自定义回答。'
            : '请先回答这个问题，再继续生成最终提示词。' }}
        </p>
        <p v-if="errorMessage" class="generation-error" role="alert">
          {{ errorMessage }}
        </p>
      </section>
      <div v-if="completedCount > 0" class="answer-review">
        <ElButton text :disabled="isGenerating" :aria-expanded="reviewVisible" @click="reviewVisible = !reviewVisible">
          {{ reviewVisible ? '收起已填答案' : `核对已填答案（${completedCount}）` }}
        </ElButton>
        <ol v-if="reviewVisible">
          <li v-for="(question, index) in questions" :key="question.id">
            <ElButton text :disabled="isGenerating" @click="goToQuestion(index)">{{ index + 1 }}. {{ question.question }}</ElButton>
            <p>{{ toAnswer(question).answer || '尚未回答' }}</p>
          </li>
        </ol>
      </div>
    </template>

    <template #footer>
      <div class="dialog-actions">
        <ElButton :disabled="isGenerating" @click="close">返回修改需求</ElButton>
        <div>
          <ElButton
            v-if="currentIndex > 0"
            :icon="ArrowLeft"
            :disabled="isGenerating"
            @click="previousQuestion"
          >
            上一题
          </ElButton>
          <ElButton
            type="primary"
            :icon="isLastQuestion ? MagicStick : ArrowRight"
            :loading="isGenerating"
            @click="continueFlow"
          >
            {{ isLastQuestion ? '生成最终提示词' : '下一题' }}
          </ElButton>
        </div>
      </div>
    </template>
  </ElDialog>
</template>

<style scoped>
.dialog-heading {
  max-width: 630px;
}

.dialog-kicker {
  margin: 0 0 7px !important;
  color: var(--accent-cyan) !important;
  font-family: var(--font-mono);
  font-size: 12px !important;
  letter-spacing: 0.8px;
}

.dialog-heading h2 {
  margin: 0;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: clamp(23px, 3vw, 30px);
  letter-spacing: -0.035em;
}

.dialog-heading > p:last-child {
  margin: 11px 0 0;
  color: var(--ink-muted);
  font-size: 14px;
  line-height: 1.7;
}

.question-progress {
  margin: 2px 0 20px;
}

.progress-copy {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 12px;
}

.progress-track {
  display: grid;
  grid-template-columns: repeat(var(--question-count, 1), 1fr);
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: 5px;
}

.progress-track i {
  height: 3px;
  border-radius: 999px;
  background: var(--line-strong);
  transition: background 160ms ease, transform 160ms ease;
}

.progress-track i.is-current {
  background: var(--accent-blue);
  transform: scaleY(1.5);
}

.progress-track i.is-complete {
  background: var(--accent-cyan);
}

.question-sheet {
  position: relative;
  min-height: 330px;
  padding: clamp(20px, 3vw, 28px);
  overflow: hidden;
  border: 1px solid var(--line-strong);
  border-radius: 14px;
  background:
    radial-gradient(circle at 100% 0, color-mix(in srgb, var(--accent-blue) 13%, transparent), transparent 42%),
    var(--surface-code);
}

.question-mark {
  position: absolute;
  top: 16px;
  right: 22px;
  color: color-mix(in srgb, var(--accent-blue) 15%, transparent);
  font-family: var(--font-display);
  font-size: 92px;
  font-weight: 700;
  line-height: 1;
  pointer-events: none;
}

.question-copy {
  position: relative;
  max-width: 610px;
  padding-right: 36px;
}

.question-copy h3 {
  margin: 0;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: clamp(19px, 2.5vw, 24px);
  line-height: 1.45;
}

.question-copy p {
  margin: 9px 0 0;
  color: var(--ink-muted);
  font-size: 13px;
  line-height: 1.7;
}

.answer-options {
  position: relative;
  display: grid;
  gap: 9px;
  margin-top: 23px;
}

.answer-option {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: 12px;
  width: 100%;
  height: auto;
  margin: 0;
  white-space: normal;
  padding: 13px 15px;
  border: 1px solid var(--line-subtle);
  border-radius: 10px;
  color: inherit;
  text-align: left;
  background: color-mix(in srgb, var(--surface-panel) 75%, transparent);
  cursor: pointer;
  transition: border-color 150ms ease, background 150ms ease, transform 150ms ease;
}

.answer-option :deep(> span) {
  display: contents;
}

.answer-instruction,
.answer-review p {
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.answer-review {
  margin-top: 12px;
}

.answer-review ol {
  padding-left: 20px;
}

.answer-review .el-button {
  max-width: 100%;
  height: auto;
  white-space: normal;
  text-align: left;
}

.option-copy small.recommendation-reason {
  color: var(--accent-blue);
}

.answer-option:hover,
.answer-option:focus-visible {
  border-color: color-mix(in srgb, var(--accent-blue) 70%, var(--line-subtle));
  transform: translateY(-1px);
}

.answer-option.is-selected {
  border-color: var(--accent-blue);
  background: color-mix(in srgb, var(--accent-blue) 10%, var(--surface-panel));
}

.option-control {
  display: grid;
  width: 19px;
  height: 19px;
  margin-top: 1px;
  place-items: center;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
  color: var(--surface-code);
}

.answer-options--multiple .option-control {
  border-radius: 5px;
}

.is-selected .option-control {
  border-color: var(--accent-blue);
  background: var(--accent-blue);
}

.option-control svg {
  width: 12px;
}

.option-copy,
.option-title {
  display: flex;
}

.option-copy {
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.option-title {
  align-items: center;
  gap: 8px;
}

.option-title strong {
  color: var(--ink-strong);
  font-size: 14px;
  font-weight: 600;
}

.option-title em {
  padding: 4px 10px;
  border-radius: 999px;
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 12px;
  font-style: normal;
  background: color-mix(in srgb, var(--accent-cyan) 10%, transparent);
}

.option-copy small {
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.6;
}

.free-answer,
.custom-answer {
  position: relative;
  margin-top: 23px;
}

.free-answer :deep(.el-textarea__inner),
.custom-answer :deep(.el-input__wrapper) {
  border: 1px solid var(--line-strong);
  background: var(--surface-input);
  box-shadow: none;
}

.free-answer :deep(.el-textarea__inner:focus),
.custom-answer :deep(.el-input__wrapper.is-focus) {
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent-blue) 12%, transparent);
}

.answer-examples {
  margin: 10px 0 0;
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 1.65;
}

.answer-examples span {
  margin-right: 7px;
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.08em;
}

.custom-answer label {
  display: block;
  margin-bottom: 7px;
  color: var(--ink-soft);
  font-size: 12px;
}

.validation-message {
  margin: 12px 0 0;
  color: var(--danger, #ff7d8a);
  font-size: 12px;
}

.generation-error {
  margin: 12px 0 0;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--danger, #ff7d8a) 42%, var(--line-subtle));
  border-radius: 8px;
  color: var(--danger, #ff7d8a);
  font-size: 12px;
  line-height: 1.6;
  background: color-mix(in srgb, var(--danger, #ff7d8a) 8%, transparent);
}

.dialog-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.dialog-actions > div {
  display: flex;
  gap: 9px;
}

@media (max-width: 600px) {
  .question-sheet {
    min-height: 0;
    padding: 16px;
  }

  .dialog-actions {
    align-items: stretch;
    flex-direction: column-reverse;
  }

  .dialog-actions > div,
  .dialog-actions :deep(.el-button) {
    width: 100%;
  }

  .dialog-actions > div :deep(.el-button) {
    flex: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .answer-option,
  .progress-track i {
    transition: none;
  }
}
</style>
