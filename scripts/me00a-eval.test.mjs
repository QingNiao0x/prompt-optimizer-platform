import { test } from 'node:test';
import assert from 'node:assert/strict';
import { rawExecutionInput, enhancedExecutionInput, answerLog, batchRequestLimit,
  selectedEvaluationCases, capturedExecutionResponse, completedExecutionWork } from './me00a-eval.mjs';

test('both works receive identical revenue evidence without adding unavailable metrics', () => {
  const sample = { rawPrompt: '分析收入变化。', context: { files: [{ path: 'synthetic.txt', content: '收入100→120；没有销量或市场总量。' }] } };
  const raw = rawExecutionInput(sample);
  const enhanced = enhancedExecutionInput('只分析收入变化，不推断需求。', sample);
  assert.equal(raw.substring(sample.rawPrompt.length), enhanced.substring('只分析收入变化，不推断需求。'.length));
  assert.match(raw, /收入100→120/);
  assert.doesNotMatch(raw, /市场份额增长|销量增长/);
});

test('equal-information raw arm gets actual answers while the copied Plan arm stays untouched', () => {
  const sample = { rawPrompt: '撰写研究设计。', context: { files: [] } };
  const answers = [{ question: '采用哪种评分？', answer: '不知道，请提供有条件的建议。' }];
  const frozen = structuredClone(answers);
  assert.equal(answerLog(answers), '问题：采用哪种评分？\n回答：不知道，请提供有条件的建议。');
  assert.match(rawExecutionInput(sample, answers), /不知道，请提供有条件的建议。/);
  const actualCopiedBody = '请撰写研究设计。';
  assert.equal(enhancedExecutionInput(actualCopiedBody, sample), actualCopiedBody);
  assert.deepEqual(answers, frozen);
});

test('original conditions do not silently gain confirmations or the examples switch', () => {
  const sample = { rawPrompt: '只输出两个键的JSON。', context: { files: [] } };
  assert.equal(rawExecutionInput(sample), sample.rawPrompt);
  assert.equal(enhancedExecutionInput(' {"name":"李明","age":28} ', sample), ' {"name":"李明","age":28} ');
});

test('approved batch caps new attempts even when the overall protocol has more allowance', () => {
  const protocol = { maximumActualModelCalls: 192, activeBatch: {
    version: 'candidate6', mode: 'plan', cases: ['Q05'], model: 'deepseek-flash', callsBefore: 169, maximumNewCalls: 12,
  } };
  assert.equal(batchRequestLimit(protocol, 'candidate6', 'plan', [{ id: 'Q05' }], ['deepseek-flash']), 181);
  for (const [mode, cases, models] of [
    ['direct', [{ id: 'Q05' }], ['deepseek-flash']],
    ['plan', [{ id: 'Q04' }], ['deepseek-flash']],
    ['plan', [{ id: 'Q05' }], ['deepseek-v4-pro']],
  ]) assert.throws(() => batchRequestLimit(protocol, 'candidate6', mode, cases, models), /outside approved batch/);
});

test('a single-round approval rejects a second round before authenticating or reserving a request', () => {
  const protocol = { maximumActualModelCalls: 192, activeBatch: {
    version: 'candidate6', mode: 'plan', cases: ['Q05'], model: 'deepseek-flash',
    callsBefore: 169, maximumNewCalls: 12, secondRoundAuthorized: false,
  } };
  assert.equal(batchRequestLimit(protocol, 'candidate6', 'plan', [{ id: 'Q05' }], ['deepseek-flash'], '1'), 181);
  assert.throws(() => batchRequestLimit(protocol, 'candidate6', 'plan', [{ id: 'Q05' }], ['deepseek-flash'], '2'),
    /outside approved batch/);
});

test('an active batch cannot be bypassed by requesting an older candidate or a new baseline', () => {
  const protocol = { maximumActualModelCalls: 192, activeBatch: {
    version: 'candidate6', mode: 'plan', cases: ['Q05'], model: 'deepseek-flash',
    callsBefore: 169, maximumNewCalls: 12, secondRoundAuthorized: false,
  } };
  for (const version of ['baseline', 'candidate5']) {
    assert.throws(() => batchRequestLimit(protocol, version, 'plan', [{ id: 'Q05' }], ['deepseek-flash'], '1'),
      /outside approved batch/);
  }
  assert.equal(batchRequestLimit({ maximumActualModelCalls: 180 }, 'baseline', 'direct', [], []), 180);
});

test('missing or duplicate case selections cannot report success without executing a case', () => {
  const catalogue = [{ id: 'Q05' }, { id: 'EX01' }];
  assert.deepEqual(selectedEvaluationCases(catalogue, 'Q05'), [{ id: 'Q05' }]);
  assert.deepEqual(selectedEvaluationCases(catalogue, 'all'), catalogue);
  for (const selected of ['', 'UNKNOWN', 'Q05,UNKNOWN', 'Q05,Q05'])
    assert.throws(() => selectedEvaluationCases(catalogue, selected), /missing/);
  assert.throws(() => selectedEvaluationCases([], 'all'), /Invalid frozen/);
  assert.throws(() => selectedEvaluationCases([{ id: 'Q05' }, { id: 'Q05' }], 'all'), /Invalid frozen/);
});

test('a proposed candidate-eight batch cannot spend the old remaining allowance', () => {
  const protocol = { maximumActualModelCalls: 229, activeBatch: {
    version: 'candidate8', mode: 'plan', cases: ['Q05'], model: 'deepseek-flash',
    callsBefore: 185, maximumNewCalls: 14, secondRoundAuthorized: false, authorizationStatus: 'PENDING_USER_APPROVAL',
  } };
  assert.throws(() => batchRequestLimit(protocol, 'candidate8', 'plan', [{ id: 'Q05' }], ['deepseek-flash']), /not been approved/);
  protocol.activeBatch.authorizationStatus = 'APPROVED_BY_USER';
  assert.equal(batchRequestLimit(protocol, 'candidate8', 'plan', [{ id: 'Q05' }], ['deepseek-flash']), 199);
  assert.throws(() => batchRequestLimit(protocol, 'candidate8', 'compliance', [{ id: 'EX01' }], ['deepseek-flash']), /outside approved/);
});

test('failed HTTP and non-JSON model bodies remain available for attribution', async () => {
  const failed = await capturedExecutionResponse(new Response('{"error":{"code":"rate_limit"}}', { status: 429 }));
  assert.equal(failed.httpStatus, 429);
  assert.equal(failed.response.error.code, 'rate_limit');
  assert.equal(failed.responseText, '{"error":{"code":"rate_limit"}}');
  const nonJson = await capturedExecutionResponse(new Response('upstream unavailable', { status: 502 }));
  assert.equal(nonJson.httpStatus, 502);
  assert.equal(nonJson.responseText, 'upstream unavailable');
  assert.equal('response' in nonJson, false);
});

test('HTTP 200 alone cannot make empty or truncated work count as completed', () => {
  const complete = { httpStatus: 200, response: { choices: [{ message: { content: ' 原始作品\n' }, finish_reason: 'stop' }] } };
  assert.deepEqual(completedExecutionWork(complete), { text: ' 原始作品\n', finishReason: 'stop' });
  for (const record of [{ httpStatus: 200, response: { choices: [] } },
    { httpStatus: 200, response: { choices: [{ message: { content: ' ' }, finish_reason: 'stop' }] } },
    { httpStatus: 200, response: { choices: [{ message: { content: '未完成正文' }, finish_reason: 'length' }] } },
    { ...complete, httpStatus: 429 }]) assert.throws(() => completedExecutionWork(record), /Execution/);
});
