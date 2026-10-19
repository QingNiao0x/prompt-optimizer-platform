import type { ConstraintGroups } from '@/types/api';

/**
 * 自然正文只恢复用户明确追加的权限边界，不自动补写平台固定条款或重建分节。
 * 旧自然结果也不再锁定固定条款；原有正文不主动删除，未知来源不从扁平列表反推。
 */
export const restoreNaturalBoundaries = (text: string, groups?: ConstraintGroups | null): string => {
  const rules = groups?.additionalPermissions ?? [];
  const missing = [...new Set(rules)].filter(rule => !text.includes(rule));
  return text.trim() + (missing.length ? '\n\n' + missing.join(' ') : '');
};
