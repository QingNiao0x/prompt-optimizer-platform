import { describe, expect, it } from 'vitest';
import { restoreNaturalBoundaries } from './naturalTextEditing';

describe('natural text editing', () => {
  const groups = { platformMandatory: ['事实不得捏造。'], taskSpecific: ['旧任务的其他指导。'], additionalPermissions: ['删除前确认。'] };
  it('preserves free text, independent conditions and code formatting while restoring only authoritative boundaries', () => {
    const text = '请写报告。\n仅甲院采用90分钟，乙院仍未确认。\n~~~json\n{"title":"原格式"}\n~~~';
    const result = restoreNaturalBoundaries(text, groups);
    expect(result).toContain(text);
    expect(result).toContain('事实不得捏造。 删除前确认。');
    expect(result).not.toContain('旧任务的其他指导。');
    expect(result).not.toContain('## 背景');
  });
  it('does not duplicate rules already present or invent ownership for old history', () => {
    expect(restoreNaturalBoundaries('事实不得捏造。 删除前确认。', groups)).toBe('事实不得捏造。 删除前确认。');
    expect(restoreNaturalBoundaries('旧记录正文。')).toBe('旧记录正文。');
  });
});
