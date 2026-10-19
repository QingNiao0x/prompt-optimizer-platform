import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { assessTextArtifact, summarizeEvidence } from './evidence-assessment.mjs';

const review = JSON.parse(await readFile(new URL('./assessment-review.json', import.meta.url), 'utf8'));
const traces = (await readFile(new URL('./candidate2-transports.jsonl', import.meta.url), 'utf8'))
  .trim().split(/\r?\n/).map(row => JSON.parse(row));

test('真实回复的成品正文与全部回复分别计数，不把说明长度归到正文', () => {
  const capture = traces.find(row => row.jobId === review.copySelection.jobId);
  const text = capture.response.choices[0].message.content;
  const original = JSON.stringify(capture);
  const result = assessTextArtifact(text, review.copySelection.bodyQuote, review.copySelection.requirement);
  assert.equal(result.selectedBody.nonWhitespace, 96);
  assert.equal(result.selectedBody.han, 78);
  assert.equal(result.completeResponse.nonWhitespace, 216);
  assert.equal(result.completeResponse.han, 174);
  assert.equal(result.lengthAssessment.bodyWithinRange.nonWhitespace, true);
  assert.equal(result.lengthAssessment.bodyWithinRange.han, false);
  assert.equal(result.lengthAssessment.status, 'COUNTING_CONVENTION_NOT_FROZEN');
  assert.equal(JSON.stringify(capture), original);
});

test('正文提取不接受不存在或不唯一的引文', () => {
  const limit = { min: 1, max: 5, metric: 'nonWhitespace' };
  assert.throws(() => assessTextArtifact('正文', '缺失', limit));
  assert.throws(() => assessTextArtifact('正文 / 正文', '正文', limit));
  assert.equal(assessTextArtifact('内容🙂', '内容🙂', limit).selectedBody.nonWhitespace, 3);
});

test('明确冻结的篇幅口径才形成该检查的通过或失败', () => {
  assert.equal(assessTextArtifact('一二三', '一二三', { min: 3, max: 3, metric: 'han' })
    .lengthAssessment.status, 'PASSED');
  assert.equal(assessTextArtifact('一二三', '一二三', { min: 1, max: 2, metric: 'han' })
    .lengthAssessment.status, 'FAILED');
  assert.throws(() => assessTextArtifact('内容', '内容', { min: 1, max: 2, metric: 'unknown' }));
});

test('空集合、未评审和失败分开汇总，全部通过也只限于所列范围', () => {
  assert.equal(summarizeEvidence([]).status, 'UNVERIFIED');
  assert.equal(summarizeEvidence([{ id: 'one', status: 'UNVERIFIED' }]).status, 'UNVERIFIED');
  const failure = summarizeEvidence([{ id: 'one', status: 'FAILED' }, { id: 'two', status: 'PASSED' }]);
  assert.equal(failure.status, 'FAILURES_RECORDED');
  assert.equal(failure.failed, 1);
  assert.equal(failure.passed, 1);
  assert.equal(summarizeEvidence([{ id: 'one', status: 'PASSED' }]).status, 'VERIFIED_WITHIN_RECORDED_SCOPE');
  assert.throws(() => summarizeEvidence([{ id: 'one', status: 'unknown' }]));
  assert.throws(() => summarizeEvidence([{ id: 'one', status: 'PASSED' }, { id: 'one', status: 'PASSED' }]));
});
