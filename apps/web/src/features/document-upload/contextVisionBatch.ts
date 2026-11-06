import type { ContextVisionConfirmation, ContextVisionReview, DocumentUploadStatus } from '@/types/api';

/** 会进入视觉审阅的语言代码。jpg 在分流时已规范为 jpeg，仍保留原始后缀以便核对文件名。 */
export const CONTEXT_VISION_LANGUAGES = new Set(['jpeg', 'jpg', 'png', 'pdf', 'docx', 'pptx', 'xlsx', 'odt', 'odp', 'ods']);

export const isContextVisionLanguage = (language: string): boolean => CONTEXT_VISION_LANGUAGES.has(language);

/** 与上传结果区分开。判断放在这里，避免只模拟上传函数的测试丢失这个守卫。 */
export const isDeferredVisionUpload = (value: object): value is { readonly deferred: true } =>
  'deferred' in value && value.deferred === true;

/** 单文件外发页数同时受服务端页数和调用次数限制，取更小的一个。 */
export const visionPageLimit = (review: ContextVisionReview): number => Math.min(review.maxPages, review.maxCalls);

/** Office 项是内嵌图片，编号用于选择；PDF 保留实际页码的显示方式。 */
export const visionItemLabel = (page: ContextVisionReview['pages'][number]): string => page.label ?? `第 ${page.page} 页`;

/** 建议且未被阻止的页面，作为用户明确选择「进入识别」后的初始页码；不表示已经同意外发。 */
export const suggestedVisionPages = (status: DocumentUploadStatus): ContextVisionConfirmation['pages'] => {
  const review = status.visionReview;
  if (!review) return [];
  return review.pages
    .filter((page) => page.suggested && !page.blocked)
    .slice(0, visionPageLimit(review))
    .map((page) => ({ page: page.page, masks: [] }));
};

/** 汇总清单只展示页数和建议原因，不包含预览图。 */
export const summarizeVisionPages = (status: DocumentUploadStatus): string => {
  const review = status.visionReview;
  if (!review?.pages.length) return '没有可审阅的图片或页面';
  const suggested = review.pages.filter((page) => page.suggested && !page.blocked);
  if (!suggested.length) return `${review.pages.length} 项，没有建议识别的内容`;
  const preview = suggested.slice(0, 3).map((page) => `${visionItemLabel(page)}　${page.reason}`).join('；');
  const extra = suggested.length > 3 ? `；另有 ${suggested.length - 3} 项` : '';
  return `${review.pages.length} 项，建议识别 ${suggested.length} 项。${preview}${extra}`;
};
