import { expect, it } from 'vitest';
import { comparePolish } from './diff';

it.each([
  ['标题\n\n原句  \n保留句', '标题\n\n改写句\n保留句'],
  ['', '新增'], ['删除', ''], ['原样😀\r\n', '原样😀\r\n'],
  ['雨停了。 我站着！\n🙂', '雨停了。 我坐着！\n🙃'],
  ['原文\n'.repeat(1000), '结果\n'.repeat(1000)],
  ['甲'.repeat(12000), '乙'.repeat(24000)],
])('差异能无损还原原文和结果 %#', (before, after) => {
  const changes = comparePolish(before, after);
  expect(changes.filter(c => c.kind !== 'added').map(c => c.text).join('')).toBe(before);
  expect(changes.filter(c => c.kind !== 'removed').map(c => c.text).join('')).toBe(after);
});

it('长段落只高亮改动的词，保留其余正文', () => {
  const prefix = '这是一段保留的正文。'.repeat(100);
  const changes = comparePolish(prefix + '持续优化流程。', prefix + '持续改进流程。');
  expect(changes.filter(c => c.kind === 'removed').map(c => c.text).join('')).toBe('优化');
  expect(changes.filter(c => c.kind === 'added').map(c => c.text).join('')).toBe('改进');
});
