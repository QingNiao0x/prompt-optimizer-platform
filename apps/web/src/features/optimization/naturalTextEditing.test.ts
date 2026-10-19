import { describe, expect, it } from 'vitest';
import { restoreNaturalBoundaries } from './naturalTextEditing';

describe('natural text editing', () => {
  const groups = { platformMandatory: ['事实不得捏造。'], taskSpecific: ['旧任务的其他指导。'], additionalPermissions: ['删除前确认。'] };
  it('preserves free text, independent conditions and code formatting while restoring only explicit permissions', () => {
    const text = '请写报告。\n仅甲院采用90分钟，乙院仍未确认。\n~~~json\n{"title":"原格式"}\n~~~';
    const result = restoreNaturalBoundaries(text, groups);
    expect(result).toContain(text);
    expect(result).toBe(`${text}\n\n删除前确认。`);
    expect(result).not.toContain('事实不得捏造。');
    expect(result).not.toContain('旧任务的其他指导。');
    expect(result).not.toContain('## 背景');
  });
  it('does not duplicate rules already present or invent ownership for old history', () => {
    expect(restoreNaturalBoundaries('事实不得捏造。 删除前确认。', groups)).toBe('事实不得捏造。 删除前确认。');
    expect(restoreNaturalBoundaries('旧记录正文。')).toBe('旧记录正文。');
  });
  it('allows removing all five old fixed clauses while keeping explicit permissions', () => {
    const platformMandatory = [
      '明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。',
      '不得在代码、日志或响应中泄露密码、Token、API Key 或私钥。',
      '输出应直接回应用户目标，遵守已明确的交付范围和格式；仅在任务需要且未限制额外说明时，说明关键依据、适用范围和限制条件。',
      '禁止读取或输出受保护路径：.env、**/*.pem、**/*.key、生产环境配置。',
      '以下操作必须先获得人工确认：删除或覆盖文件、数据库结构迁移、升级核心依赖、生产环境部署。',
    ];
    const additionalPermissions = ['禁止读取或输出受保护路径：private/**。'];
    const fixedGroups = { platformMandatory, taskSpecific: [], additionalPermissions };
    const text = '请写一封邮件，保留已确定的收件对象及截止时间。';
    const result = restoreNaturalBoundaries(text, fixedGroups);
    expect(result).toBe(`${text}\n\n${additionalPermissions.join(' ')}`);
    expect(restoreNaturalBoundaries(result, fixedGroups)).toBe(result);
  });
  it('leaves a new natural result unchanged when no additional permissions are present', () => {
    const text = '请完成原定任务，只输出明确要求的交付内容。';
    const emptyGroups = { platformMandatory: [], taskSpecific: [], additionalPermissions: [] };
    expect(restoreNaturalBoundaries(text, emptyGroups)).toBe(text);
    expect(restoreNaturalBoundaries(text, { ...emptyGroups, platformMandatory: ['旧固定条款。'] })).toBe(text);
  });
});
