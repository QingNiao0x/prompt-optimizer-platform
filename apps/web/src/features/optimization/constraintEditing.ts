import type { ConstraintGroups, PromptFormat, PromptSection } from '@/types/api';

const PLATFORM_HEADING = '平台强制约束（不得删除或弱化）：';
const TASK_HEADING = '任务相关约束：';
const PERMISSION_HEADING = '用户补充权限边界：';
const GROUP_HEADINGS = new Set([PLATFORM_HEADING, TASK_HEADING, PERMISSION_HEADING]);
const trimBlankLines = (content: string): string => content
  .replace(/^(?:[ \t]*\r?\n)+/, '').replace(/(?:\r?\n[ \t]*)+$/, '');

/** 必要规则无法安全补回时保留编辑稿，由界面提示用户修正未闭合的代码围栏。 */
export class ConstraintRestorationError extends Error {}

/** 规则之前未闭合的代码块会跨段吞入后续规则；只在确有必要规则时拒绝这类不安全保存。 */
export const assertConstraintPlacement = (sections: readonly PromptSection[], hasRequiredRules: boolean): void => {
  if (!hasRequiredRules) return;
  const constraintsIndex = sections.findIndex(section => section.type === 'CONSTRAINTS');
  const unsafe = sections.slice(0, Math.max(0, constraintsIndex))
    .find(section => section.type !== 'CLARIFICATIONS' && ruleLines(section.content).openFence);
  if (unsafe) throw new ConstraintRestorationError(`“${unsafe.title}”段落中的代码围栏尚未闭合，后续必要规则可能被包入代码块。请补齐围栏后保存，其他修改已保留。`);
};

/**
 * 编辑补回沿用服务端规则归属，不在浏览器重新判断任务类型或接受正文自称的平台身份。
 * 新结果不需要可见平台标题；旧历史缺少元数据时继续使用原有标题兼容路径。
 */
export const restoreAppliedConstraints = (
  edited: string,
  original: string,
  appliedConstraints: readonly string[],
  constraintGroups?: ConstraintGroups | null,
  format: PromptFormat = 'STRUCTURED',
  otherEditedSections: readonly string[] = [],
): string => {
  if (constraintGroups) {
    return restoreGroupedConstraints(edited, appliedConstraints, constraintGroups, format, otherEditedSections);
  }
  return restoreLegacyConstraints(edited, original, appliedConstraints);
};

/** 只识别完整独立规则行；代码、引用、表格及条件标题下的文本不能证明全局要求已保留。 */
const ruleLines = (content: string): { rules: Map<number, string>; scoped: boolean; openFence: boolean } => {
  const lines = new Map<number, string>();
  let fence = '';
  let scoped = false;
  for (const [index, line] of content.split(/\r?\n/).entries()) {
    const text = line.trim();
    const marker = text.match(/^(`{3,}|~{3,})(.*)$/);
    if (fence) {
      if (marker && marker[1]![0] === fence[0] && marker[1]!.length >= fence.length && !marker[2]!.trim()) fence = '';
      continue;
    }
    if (marker) {
      fence = marker[1]!;
      continue;
    }
    if (/^[>|]/.test(text) || /^(?: {4}|\t)/.test(line)) continue;
    if (/^(?:#{1,6}\s)|[：:]$/.test(text)) {
      // 保守排除“仅调试时”等局部范围；既有分组标题只标记规则来源，不增加适用条件。
      scoped = !GROUP_HEADINGS.has(text) && !/^(?:#{1,6}\s*)?(?:约束|约束条件|安全约束)[：:]?$/.test(text);
      continue;
    }
    if (!scoped) lines.set(index, text.replace(/^(?:[-*+]\s+|\d+[.)、]\s*)/, ''));
  }
  return { rules: lines, scoped, openFence: Boolean(fence) };
};

/** 对缺失平台条款恢复服务端完整顺序，其他规则仅补真正缺失的完整句，不尝试语义等价去重。 */
const restoreGroupedConstraints = (
  edited: string,
  applied: readonly string[],
  groups: ConstraintGroups,
  format: PromptFormat,
  otherSections: readonly string[],
): string => {
  const coverage = ruleLines(edited);
  const existing = new Set(coverage.rules.values());
  const elsewhere = new Set(otherSections.flatMap(content => [...ruleLines(content).rules.values()]));
  const platform = [...new Set(groups.platformMandatory)];
  const required = [...new Set([...groups.taskSpecific, ...groups.additionalPermissions, ...applied])]
    .filter(rule => !platform.includes(rule));
  const missing = required.filter(rule => !existing.has(rule) && !elsewhere.has(rule));
  const repairPlatform = platform.some(rule => !existing.has(rule));
  if (!repairPlatform && missing.length === 0) return edited;
  // 自动补到未闭合代码块中会把安全规则变成示例文本；不擅自闭合或丢弃用户草稿。
  if (coverage.openFence) throw new ConstraintRestorationError('约束条件中的代码围栏尚未闭合，必要规则无法安全恢复。请补齐围栏后保存，其他修改已保留。');

  // 只裁空行；trim() 会抹掉首行代码缩进并改变它原本的引用/代码含义。
  let content = trimBlankLines(edited);
  if (repairPlatform) {
    const removable = ruleLines(content);
    // 只移除确定为全局规则的原行；相同文字出现在引用、代码或独立条件中仍须原样保留。
    content = trimBlankLines(content.split(/\r?\n/).filter((_line, index) =>
      !platform.includes(removable.rules.get(index) ?? '')).join('\n'));
  }
  const restored = [...missing, ...(repairPlatform ? platform : [])];
  const block = format === 'COMPACT' && restored.length === 1 ? restored[0]! : `- ${restored.join('\n- ')}`;
  // 局部条件后直接追加会继承原范围；仅此情形用通用标题恢复全局作用域，避免二次编辑继续补入。
  return [content, ruleLines(content).scoped ? '约束条件：' : '', block].filter(Boolean).join('\n\n');
};

/** 旧记录不猜测新的规则归属，也不就地改写已经保存的历史正文。 */
const restoreLegacyConstraints = (
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
