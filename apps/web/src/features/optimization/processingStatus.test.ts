import { describe, expect, it } from 'vitest';
import { optimizationCompletionNotice, processingStatusLabel } from './processingStatus';
import type { OptimizationResult } from '@/types/api';

const base = { provider: { mock: false, provider: 'fixture', model: 'fixture' }, generationPipeline: 'NATURAL' as const };
type Processing = NonNullable<OptimizationResult['processing']>;

describe('natural processing status', () => {
  it('distinguishes preparation-only from a real body call even if a model was used', () => {
    const result = { ...base, processing: { completedStages: ['SCOPE_ANALYSIS'], originalPreserved: true } satisfies Processing };
    expect(processingStatusLabel(result)).toBe('保留原请求，未执行正文改写');
    expect(optimizationCompletionNotice(result).type).toBe('info');
  });
  it('reports unchanged generated text as a tie without claiming substantive benefit', () => {
    const result = { ...base, processing: { completedStages: ['SCOPE_ANALYSIS', 'BODY_GENERATION'], originalPreserved: true } satisfies Processing };
    expect(processingStatusLabel(result)).toBe('已检查并保留原文');
    expect(optimizationCompletionNotice(result)).toEqual({ type: 'info', message: '已完成检查，正文与原始提示词一致。' });
    expect(processingStatusLabel({ ...result, editRevision: 1 })).toBe('生成后已编辑');
  });
  it('labels a different body as generated and keeps unknown history and mock explicit', () => {
    const result = { ...base, processing: { completedStages: ['SCOPE_ANALYSIS', 'BODY_GENERATION'], originalPreserved: false } satisfies Processing };
    expect(processingStatusLabel(result)).toBe('已生成优化正文');
    expect(optimizationCompletionNotice(result).type).toBe('success');
    expect(processingStatusLabel(base)).toBe('处理阶段未记录');
    expect(processingStatusLabel({ ...base, generationPipeline: 'LEGACY' })).toBe('模型生成');
    expect(processingStatusLabel({ ...result, provider: { ...base.provider, mock: true } })).toBe('Mock 结果');
  });
});
