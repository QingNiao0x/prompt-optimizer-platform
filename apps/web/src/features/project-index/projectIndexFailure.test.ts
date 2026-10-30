import { expect, it } from 'vitest';
import { indexFailureMessage } from './projectIndexFailure';

it.each([
  ['QuotaExceededError', '存储空间'], ['NotAllowedError', '权限'],
  ['NotFoundError', '移动'], ['SecurityError', '禁止'], ['AbortError', '未提交'],
])('explains empty %s errors without claiming completed data was lost', (name, expected) => {
  expect(indexFailureMessage(new DOMException('', name))).toContain(expected);
});

it('keeps a useful specific error but supplies a fallback for blank errors', () => {
  expect(indexFailureMessage(new Error('  '))).toContain('意外中断');
  expect(indexFailureMessage(undefined)).toContain('意外中断');
  expect(indexFailureMessage(new Error('请先关闭另一个正在升级索引的页面'))).toContain('关闭另一个');
});
