import { test } from 'node:test';
import assert from 'node:assert/strict';
import { rawExecutionInput, enhancedExecutionInput, answerLog, batchRequestLimit } from './me00a-eval.mjs';

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
