import { describe, expect, it } from 'vitest';
import { comparePromptText } from './promptDiff';

describe('saved prompt comparison', () => {
  it.each([
    ['', '新增正文'],
    ['旧正文', ''],
    ['相同正文', '相同正文'],
    ['保留日期2026-11-10\n旧语气\n不承诺赔偿', '保留日期2026-11-10\n新语气\n不承诺赔偿'],
    ['重复\n中间要求\n重复\n', '重复\n新增要求\n中间要求\n重复\n'],
    ['\r\n  空格与🌞\r\n末尾', '\r\n  空格与🌟\r\n末尾\n'],
    ['<script>旧文本</script>', '<script>新文本</script>'],
  ])('preserves both full texts exactly', (before, after) => {
    const changes = comparePromptText(before, after);
    expect(changes.filter(change => change.kind !== 'added').map(change => change.text).join('')).toBe(before);
    expect(changes.filter(change => change.kind !== 'removed').map(change => change.text).join('')).toBe(after);
  });

  it('keeps unchanged intermediate requirements visible', () => {
    const changes = comparePromptText('旧开头\n保留只读限制\n旧结尾', '新开头\n保留只读限制\n新结尾');
    expect(changes).toContainEqual({ kind: 'unchanged', text: '保留只读限制\n' });
    expect(changes.some(change => change.kind === 'added')).toBe(true);
    expect(changes.some(change => change.kind === 'removed')).toBe(true);
  });

  it('uses a bounded comparison for large historical texts without splitting surrogate pairs or dropping tails', () => {
    const before = '大量旧行\n'.repeat(520) + '🌞旧内容🌞尾部';
    const after = '大量旧行\n'.repeat(520) + '🌟新内容🌞尾部';
    const changes = comparePromptText(before, after);
    expect(changes.filter(change => change.kind !== 'added').map(change => change.text).join('')).toBe(before);
    expect(changes.filter(change => change.kind !== 'removed').map(change => change.text).join('')).toBe(after);
    for (const change of changes) expect(change.text).not.toMatch(/[\uD800-\uDFFF]/u);
    expect(changes.at(-1)?.text).toBe('内容🌞尾部');
  });
});
