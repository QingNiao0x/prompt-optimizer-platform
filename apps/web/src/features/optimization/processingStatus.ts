import type { OptimizationResult } from '@/types/api';

type ProcessingResult = Pick<OptimizationResult, 'processing' | 'provider' | 'generationPipeline' | 'editRevision'>;

/** 只描述当次生成事实；编辑稿和未登记阶段的旧记录均不推断质量或生成路径。 */
export const processingStatusLabel = (result: ProcessingResult): string => {
  if (result.provider.mock) return 'Mock 结果';
  if ((result.editRevision ?? 0) > 0) return '生成后已编辑';
  if (!result.processing) return result.generationPipeline === 'NATURAL' ? '处理阶段未记录' : '模型生成';
  if (!result.processing.completedStages.includes('BODY_GENERATION')) return '保留原请求，未执行正文改写';
  return result.processing.originalPreserved ? '已检查并保留原文' : '已生成优化正文';
};

/** 原文持平使用信息提示；请求成功不自动宣称存在实质增强收益。 */
export const optimizationCompletionNotice = (result?: ProcessingResult): { type: 'success' | 'info'; message: string } => {
  if (result?.provider.mock) return { type: 'info', message: '已生成离线 Mock 示例。' };
  if (result?.processing) {
    if (!result.processing.completedStages.includes('BODY_GENERATION')) {
      return { type: 'info', message: '已保留原请求，本次未执行正文改写。' };
    }
    if (result.processing.originalPreserved) {
      return { type: 'info', message: '已完成检查，正文与原始提示词一致。' };
    }
  }
  return { type: 'success', message: '最终提示词已生成。' };
};
