import type { PlanQuestion } from '@/types/api';

export interface PlanDraftAnswer {
  selectedOptionIds: string[];
  customAnswer: string;
}

const signature = (question: PlanQuestion): string => JSON.stringify([
  question.question.trim(), question.hint.trim(), question.type, question.allowCustomAnswer,
  question.options.map((option) => [option.label, option.description, option.answer]).sort(),
]);

export const recoverPlanDrafts = (
  previous: readonly PlanQuestion[],
  next: readonly PlanQuestion[],
  drafts: Record<string, PlanDraftAnswer>,
): { answers: Record<string, PlanDraftAnswer>; unmatched: string[] } => {
  const used = new Set<string>();
  const answers = Object.fromEntries(next.map((question) => {
    const old = previous.find((candidate) => !used.has(candidate.id) && signature(candidate) === signature(question));
    const draft = old ? drafts[old.id] : undefined;
    if (!old || !draft) return [question.id, { selectedOptionIds: [], customAnswer: '' }];
    used.add(old.id);
    const selected = old.options.filter((option) => draft.selectedOptionIds.includes(option.id));
    return [question.id, {
      customAnswer: draft.customAnswer,
      selectedOptionIds: question.options.filter((option) => selected.some((item) =>
        item.answer === option.answer && item.label === option.label)).map((option) => option.id),
    }];
  }));
  const unmatched = previous.filter((question) => !used.has(question.id)).flatMap((question) => {
    const draft = drafts[question.id];
    if (!draft) return [];
    const answer = draft.customAnswer.trim() || question.options
      .filter((option) => draft.selectedOptionIds.includes(option.id)).map((option) => option.answer).join('；');
    return answer ? [`${question.question}：${answer}`] : [];
  });
  return { answers, unmatched };
};
