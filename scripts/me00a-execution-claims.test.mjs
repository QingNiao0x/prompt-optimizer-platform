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

const completedTable = '| 场景 | 用例 | 状态 |\n|---|---|---|\n| 正常页面 | test_normal | ✅ 已通过（本地 mock 运行） |\n| 超时 | test_timeout | 已通过 |';
test('actual test status cells are execution claims even when prose and table quotes are absent', () => {
  const result = auditExecutionClaims(completedTable);
  assert.equal(result.state, 'FAIL_UNSUPPORTED_EXECUTION_CLAIM');
  assert.equal(result.claims.length, 2);
  assert.equal(result.claims[0].line, 3);
});
test('a quoted status table remains quoted evidence', () => {
  assert.equal(auditExecutionClaims(completedTable.split('\n').map(line => `> ${line}`).join('\n')).state, 'NOT_CLAIMED');
});
test('expected-result tables do not claim completed execution', () => {
  assert.equal(auditExecutionClaims('| 测试 | 预期结果 |\n|---|---|\n| 正常 | 已通过后再继续 |').state, 'NOT_CLAIMED');
});
test('fenced status tables remain literal material', () => {
  assert.equal(auditExecutionClaims(`\`\`\`text\n${completedTable}\n\`\`\``).state, 'NOT_CLAIMED');
});
test('negative status cells do not assert a pass', () => {
  assert.equal(auditExecutionClaims('| 测试 | 状态 |\n|---|---|\n| 正常 | 尚未执行，不能标记已通过 |').state, 'NOT_CLAIMED');
});
test('status claims require the same pre-response evidence as prose claims', () => {
  assert.equal(auditExecutionClaims(completedTable, { executionToolsProvided: true }).state, 'OPEN_NO_BOUND_PRE_RESPONSE_EVIDENCE');
  assert.equal(auditExecutionClaims(completedTable, { executionToolsProvided: true, verifiedExecutionBeforeResponse: true }).state,
    'SUPPORTED_BY_BOUND_PRE_RESPONSE_EVIDENCE');
});
test('a descriptive table without actual execution status does not create an execution claim', () => {
  assert.equal(auditExecutionClaims('| 测试 | 文档说明 |\n|---|---|\n| 正常 | 已通过只是文档中的词语 |').state, 'NOT_CLAIMED');
});
