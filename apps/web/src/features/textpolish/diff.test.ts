import { expect, it } from 'vitest';
import { comparePolish } from './diff';
it('行对照可以完整还原两份正文，包含空行和原始空白', () => {
  const before = '标题\n\n原句  \n保留句'; const after = '标题\n\n改写句\n保留句'; const changes = comparePolish(before, after);
  expect(changes.filter(c => c.kind !== 'added').map(c => c.text).join('\n')).toBe(before);
  expect(changes.filter(c => c.kind !== 'removed').map(c => c.text).join('\n')).toBe(after);
});
it('超长换行输入使用有界对照而不分配巨大矩阵', () => { const before = '原文\n'.repeat(1000); const after = '结果\n'.repeat(1000); expect(comparePolish(before, after)).toEqual([{ kind: 'removed', text: before }, { kind: 'added', text: after }]); });
