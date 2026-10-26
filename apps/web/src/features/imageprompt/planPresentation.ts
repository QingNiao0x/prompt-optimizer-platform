import type { ImagePromptAnswer, ImagePromptPlan, ImagePromptQuestion } from '@/types/imagePrompt';

/** 与现有必答校验一致：选项或非空自填任意一种即可，不替用户预选答案。 */
export const isImagePromptAnswered = (answer?: ImagePromptAnswer): boolean => Boolean(answer && (answer.optionIds.length || answer.customText.trim()));

/** 只统计本次Plan的必答完成情况，选答与零问题均不制造阻塞；生成资格仍由store校验草稿、恢复及有效期。 */
export const imagePromptPlanProgress = (plan: ImagePromptPlan | undefined, answers: Record<string, ImagePromptAnswer>) => {
  const required = plan?.questions.filter(question => question.required) ?? [];
  const missing = required.filter(question => !isImagePromptAnswered(answers[question.id]));
  return { required: required.length, completed: required.length - missing.length, missing,
    optional: plan?.questions.filter(question => !question.required).length ?? 0 };
};

/** 缺少题答时给出未作选择的展示值，不预填或替用户确认任何选项。 */
export const imagePromptQuestionAnswer = (question: ImagePromptQuestion, answer?: ImagePromptAnswer): ImagePromptAnswer =>
  answer ?? { questionId: question.id, optionIds: [], customText: '' };
