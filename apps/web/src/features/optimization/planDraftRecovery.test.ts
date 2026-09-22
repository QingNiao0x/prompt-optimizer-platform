import { describe, expect, it } from 'vitest';
import type { PlanQuestion } from '@/types/api';
import { recoverPlanDrafts } from './planDraftRecovery';

const question = (id: string, text = '研究地区是哪里？'): PlanQuestion => ({
  id, question: text, hint: '', type: 'FREE_TEXT', options: [], examples: [], allowCustomAnswer: true,
});

describe('expired plan drafts', () => {
  it('restores answers to unchanged questions even when IDs change', () => {
    const recovered = recoverPlanDrafts([question('old')], [question('new')], {
      old: { selectedOptionIds: [], customAnswer: '广东省' },
    });
    expect(recovered.answers.new.customAnswer).toBe('广东省');
    expect(recovered.unmatched).toEqual([]);
  });
  it('keeps changed questions unanswered and retains the previous answer for review', () => {
    const recovered = recoverPlanDrafts([question('same')], [question('same', '数据来源是什么？')], {
      same: { selectedOptionIds: [], customAnswer: '广东省' },
    });
    expect(recovered.answers.same.customAnswer).toBe('');
    expect(recovered.unmatched).toEqual(['研究地区是哪里？：广东省']);
  });
  it('retains all drafts when the replacement plan has no questions', () => {
    expect(recoverPlanDrafts([question('old')], [], {
      old: { selectedOptionIds: [], customAnswer: '广东省' },
    }).unmatched).toHaveLength(1);
  });
});
