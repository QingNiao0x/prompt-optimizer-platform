const PLATFORM_HEADING = '平台强制约束（不得删除或弱化）：';
const TASK_HEADING = '任务相关约束：';
const PERMISSION_HEADING = '用户补充权限边界：';
const GROUP_HEADINGS = new Set([PLATFORM_HEADING, TASK_HEADING, PERMISSION_HEADING]);

/**
 * 编辑补回沿用后端结果中的规则归属，不在浏览器重新判断任务类型。
 * 旧历史若把所有规则放在平台块，继续按旧结果保护；新结果不会把工程规则补进固定平台块。
 */
export const restoreAppliedConstraints = (
  edited: string,
  original: string,
  appliedConstraints: readonly string[],
): string => {
  const groups = new Map<string, string>();
  let heading = TASK_HEADING;
  let fence = '';
  for (const line of original.split(/\r?\n/)) {
    const text = line.trim();
    if (/^(?:```|~~~)/.test(text)) {
      const marker = text.slice(0, 3);
      fence = fence === marker ? '' : fence || marker;
      continue;
    }
    if (fence || /^[>|]/.test(text)) continue;
    if (GROUP_HEADINGS.has(text)) heading = text;
    else if (/^(?:#{1,6}\s)|[：:]$/.test(text)) heading = TASK_HEADING;
    const rule = text.replace(/^[-*+]\s+/, '');
    if (appliedConstraints.includes(rule)) groups.set(rule, heading);
  }
  const missing = appliedConstraints.filter(rule => !edited.includes(rule));
  if (missing.length === 0) return edited;

  let content = edited.trim();
  // 对缺失的任务规则使用各自标题；固定平台块独立重建，恢复服务端顺序。
  for (const group of [TASK_HEADING, PERMISSION_HEADING]) {
    const rules = missing.filter(rule => (groups.get(rule) ?? TASK_HEADING) === group);
    if (rules.length) content += `\n\n${group}\n- ${rules.join('\n- ')}`;
  }
  const platform = appliedConstraints.filter(rule => groups.get(rule) === PLATFORM_HEADING);
  if (missing.some(rule => groups.get(rule) === PLATFORM_HEADING)) {
    fence = '';
    content = content.split(/\r?\n/).filter(line => {
      const text = line.trim();
      if (/^(?:```|~~~)/.test(text)) {
        const marker = text.slice(0, 3);
        fence = fence === marker ? '' : fence || marker;
        return true;
      }
      if (fence || /^[>|]/.test(text)) return true;
      return text !== PLATFORM_HEADING && !platform.includes(text.replace(/^[-*+]\s+/, ''));
    }).join('\n').trim();
    content += `\n\n${PLATFORM_HEADING}\n- ${platform.join('\n- ')}`;
  }
  return content;
};
