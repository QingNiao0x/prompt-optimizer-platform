import { describe, expect, it } from 'vitest';
import type { ConstraintGroups } from '@/types/api';
import { ConstraintRestorationError, restoreAppliedConstraints } from './constraintEditing';

const truth = '明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。';
const credential = '不得泄露凭据。';
const permission = '公开访谈原文前须人工确认。';
const task = '不得编造参考文献。';
const marker = '平台强制约束（不得删除或弱化）：';
const groups: ConstraintGroups = {
  platformMandatory: [truth, credential], taskSpecific: [task], additionalPermissions: [permission],
};
const all = [truth, credential, task, permission];
const original = all.map(rule => `- ${rule}`).join('\n');

describe('metadata-based constraint editing', () => {
  it.each(['COMPACT', 'STRUCTURED'] as const)('restores complete rules without a platform heading in %s', format => {
    const edited = `新条件保留。\n- ${truth}`;
    const restored = restoreAppliedConstraints(edited, original, all, groups, format);
    expect(restored).not.toContain(marker);
    expect(restored).toContain('新条件保留。');
    for (const rule of all) expect(restored.split(rule)).toHaveLength(2);
    expect(restored.indexOf(truth)).toBeLessThan(restored.indexOf(credential));
    expect(restoreAppliedConstraints(restored, original, all, groups, format)).toBe(restored);
  });

  it('keeps a complete unchanged body byte-for-byte', () => {
    const content = `\n${original}\n\n`;
    expect(restoreAppliedConstraints(content, original, all, groups, 'COMPACT')).toBe(content);
  });

  it('uses a short independent paragraph for a non-code platform rule', () => {
    const restored = restoreAppliedConstraints('写作要求保留。', truth, [truth], {
      platformMandatory: [truth], taskSpecific: [], additionalPermissions: [],
    }, 'COMPACT');
    expect(restored).toBe(`写作要求保留。\n\n${truth}`);
  });

  it('restores all five code rules in server order after a middle rule is removed', () => {
    const rules = [truth, credential, '保留用户交付格式。', '禁止访问受保护路径。', '高风险操作先人工确认。'];
    const initial = rules.map(rule => `- ${rule}`).join('\n');
    const restored = restoreAppliedConstraints(initial.replace(`- ${credential}\n`, ''), initial, rules,
      { platformMandatory: rules, taskSpecific: [], additionalPermissions: [] }, 'STRUCTURED');
    expect(restored).toBe(initial);
    expect(restored).not.toContain(marker);
  });

  it.each([
    `> ${credential}`,
    `| ${credential} |`,
    `\`\`\`text\n${credential}\n\`\`\``,
    `~~~text\n${credential}\n~~~`,
    `    ${credential}`,
    `只有测试时才执行：\n- ${credential}`,
    `## 调试阶段\n- ${credential}`,
    `并非要求${credential}`,
    `引用“${credential}”不代表当前执行。`,
  ])('does not use a quotation, condition, code or denial as whole-rule coverage: %s', edited => {
    const restored = restoreAppliedConstraints(edited, original, [credential], {
      platformMandatory: [credential], taskSpecific: [], additionalPermissions: [],
    }, 'COMPACT');
    expect(restored).toContain(edited);
    expect(restored.endsWith(`\n\n${credential}`)).toBe(true);
    expect(restored).not.toContain(marker);
  });

  it('retains independent scope, negation, exceptions and sources while restoring canonical rules', () => {
    const edited = `甲系统不适用本条。\n乙系统有审批例外。\n来源：规则文档第3节\n仅调试时：\n- ${credential}`;
    const restored = restoreAppliedConstraints(edited, original, all, groups, 'STRUCTURED');
    expect(restored.startsWith(edited)).toBe(true);
    for (const rule of all) expect(restored).toContain(`- ${rule}`);
    expect(restored).not.toContain(marker);
    expect(restored).toContain('约束条件：');
    expect(restoreAppliedConstraints(restored, original, all, groups, 'STRUCTURED')).toBe(restored);
  });

  it('does not repeat task requirements retained in another responsibility section', () => {
    const restored = restoreAppliedConstraints(`- ${truth}\n- ${credential}\n- ${permission}`, original,
      all, groups, 'COMPACT', [`分析报告。\n${task}`]);
    expect(restored).not.toContain(task);
  });

  it('restores a removed task requirement even when another section only quotes it', () => {
    const restored = restoreAppliedConstraints(`- ${truth}\n- ${credential}\n- ${permission}`, original,
      all, groups, 'COMPACT', [`> ${task}`]);
    expect(restored.endsWith(task)).toBe(true);
  });

  it('keeps platform rules in constraints even if copied to a task section', () => {
    const restored = restoreAppliedConstraints('保留任务说明。', truth, [truth], {
      platformMandatory: [truth], taskSpecific: [], additionalPermissions: [],
    }, 'COMPACT', [truth]);
    expect(restored.endsWith(truth)).toBe(true);
  });

  it('does not let an empty client-visible group remove previously applied requirements', () => {
    const restored = restoreAppliedConstraints('用户修改。', original, all, {
      platformMandatory: [], taskSpecific: [], additionalPermissions: [],
    }, 'STRUCTURED');
    for (const rule of all) expect(restored).toContain(rule);
  });

  it.each(['```text\n未完成代码', '~~~text\n未完成代码', '````text\n```\n外层未闭合'])('rejects restoring rules inside an unclosed fence: %s', edited => {
    expect(() => restoreAppliedConstraints(edited, original, all, groups, 'COMPACT'))
      .toThrow(ConstraintRestorationError);
  });

  it('keeps closed nested code intact and restores rules outside it', () => {
    const edited = '````text\n```\n代码中的内容\n```\n````';
    const restored = restoreAppliedConstraints(edited, original, all, groups, 'COMPACT');
    expect(restored.startsWith(edited)).toBe(true);
    expect(restored).toContain(permission);
    expect(restoreAppliedConstraints(restored, original, all, groups, 'COMPACT')).toBe(restored);
  });

  it('does not reject an unclosed fence when all necessary rules precede it and need no restoration', () => {
    const edited = `${original}\n\n\`\`\`text\n用户后续草稿`;
    expect(restoreAppliedConstraints(edited, original, all, groups, 'COMPACT')).toBe(edited);
  });

  it.each([undefined, null])('preserves old heading compatibility when metadata is %s', metadata => {
    const legacy = `${marker}\n- ${credential}`;
    const restored = restoreAppliedConstraints('旧稿修改。', legacy, [credential], metadata);
    expect(restored).toContain(`${marker}\n- ${credential}`);
    expect(legacy).toBe(`${marker}\n- ${credential}`);
  });
});
