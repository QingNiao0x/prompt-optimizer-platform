import test from 'node:test';
import assert from 'node:assert/strict';
import { auditExecutionClaims } from './me00a-execution-claims.mjs';

for (const text of [
  '**已实际执行**（在本地 Java 21 环境）：\n- javac：编译通过。',
  '已在本地运行测试，全部成功。',
  'I have actually run the tests.',
]) test(`flags a completed execution claim without an execution tool: ${text.slice(0, 30)}`, () => {
  const result = auditExecutionClaims(text);
  assert.equal(result.state, 'FAIL_UNSUPPORTED_EXECUTION_CLAIM');
  assert.ok(result.claims.length > 0);
  assert.equal(result.laterIndependentExecutionCannotValidateEarlierClaim, true);
});

for (const text of [
  '未实际编译运行，不声称验证通过。', '本环境没有执行工具，无法说明编译通过。',
  '实际运行后才能说明验证通过。', '如果运行成功，再记录编译通过。',
  '例如：编译通过。', '> 编译通过。', '“编译通过”只是示范文字。',
  '```text\n已实际执行\n```\n未运行。', '~~~text\n编译通过\n~~~',
  '    已实际执行', '请运行测试并保留结果。', 'Expected: compiled successfully.',
]) test(`keeps non-claims or protected material: ${text.slice(0, 30)}`, () => {
  assert.equal(auditExecutionClaims(text).state, 'NOT_CLAIMED');
});

test('tools being available is not evidence of execution and later checks never imply prior execution', () => {
  assert.equal(auditExecutionClaims('已实际执行', { executionToolsProvided: true }).state, 'OPEN_NO_BOUND_PRE_RESPONSE_EVIDENCE');
  assert.equal(auditExecutionClaims('已实际执行', { executionToolsProvided: true, verifiedExecutionBeforeResponse: true }).state,
    'SUPPORTED_BY_BOUND_PRE_RESPONSE_EVIDENCE');
  assert.throws(() => auditExecutionClaims('已实际执行', { verifiedExecutionBeforeResponse: true }), TypeError);
});
