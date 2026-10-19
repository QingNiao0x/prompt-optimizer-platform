import type { ConstraintGroups } from '@/types/api';

/**
 * 自然正文只恢复服务端明确归属的平台/权限边界，不重新推断任务、不生成四段。
 * 用户正文及独立条件保持原样；旧历史来源未知时不从扁平列表猜测条款归属。
 */
export const restoreNaturalBoundaries = (text: string, groups?: ConstraintGroups | null): string => {
  const rules = [...(groups?.platformMandatory ?? []), ...(groups?.additionalPermissions ?? [])];
  const missing = [...new Set(rules)].filter(rule => !text.includes(rule));
  return text.trim() + (missing.length ? '\n\n' + missing.join(' ') : '');
};
