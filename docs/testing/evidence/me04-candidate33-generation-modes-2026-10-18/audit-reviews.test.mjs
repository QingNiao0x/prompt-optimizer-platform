/** Synthetic audit-tool checks; these do not execute a model, a generated work or the saved-review audit. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { auditReviewText, comparisonGate, enhancedVerdict } from './audit-reviews.mjs';

const hash = text => createHash('sha256').update(text).digest('hex');
const task = '写一封邮件，请对方回复是否有异议。';
const works = {
  A: { text: '您好，请回复是否有异议，谢谢。', input: task, inputSha256: hash(task) },
  B: { text: '请核对附件报价，并回复是否有异议。', input: task, inputSha256: hash(task) },
};
const bindings = { works, pairs: [{ left: 'A', right: 'B' }], gates: { A: 'OPEN', B: 'OPEN' } };
const review = (issue, changes = {}) => JSON.stringify({ reviewerType: 'AI_ASSISTED',
  scores: Object.fromEntries(['A', 'B'].map(label => [label, { accuracy: 5, completeness: 5, clarity: 5, executability: 5 }])),
  issues: issue ? [issue] : [], pairs: [{ left: 'A', right: 'B', verdict: 'TIE', relativeVerdict: 'TIE', reason: '无实质差异。', ...changes }],
  promptAmbiguities: [], uncertainties: [] });

test('A shared original-task quote is legitimate without supplementing an unseen enhanced execution prompt', () => {
  const result = auditReviewText(review({ work: 'B', origin: 'INPUT', severity: 'MINOR', evidence: task, reason: '共享原题引用。' }), bindings);
  assert.equal(result.deterministicChecksPassed, true);
  assert.equal(result.evidenceChecks[0].exactQuoteInAssociatedInput, true);
});

test('A quote from another work or a rewritten paraphrase remains rejected', () => {
  for (const evidence of [works.B.text, '您好，请及时回复，谢谢。']) {
    const result = auditReviewText(review({ work: 'A', origin: 'WORK', severity: 'MAJOR', evidence, reason: '需要核对来源。' }), bindings);
    assert.equal(result.deterministicChecksPassed, false);
  }
});

test('Contradictory relative direction is flagged while the original verdict stays unchanged', () => {
  const result = auditReviewText(review(null, { verdict: 'LEFT_WIN', relativeVerdict: 'LOSS' }), bindings);
  assert.equal(result.strictJsonAndSchemaValid, true);
  assert.equal(result.deterministicChecksPassed, false);
  assert.ok(result.errors.some(error => error.code === 'RELATIVE_VERDICT_DIRECTION_MISMATCH'));
  assert.equal(result.originalReview.pairs[0].verdict, 'LEFT_WIN');
});

test('Missing mandatory relative direction is not silently supplied', () => {
  const value = JSON.parse(review());
  delete value.pairs[0].relativeVerdict;
  assert.equal(auditReviewText(JSON.stringify(value), bindings).deterministicChecksPassed, false);
});

test('Unblinding handles RAW on either side and preserves tie or both-fail', () => {
  assert.equal(enhancedVerdict({ left: 'RAW', right: 'ENH', verdict: 'RIGHT_WIN' }, 'RAW', 'ENH'), 'WIN');
  assert.equal(enhancedVerdict({ left: 'ENH', right: 'RAW', verdict: 'RIGHT_WIN' }, 'RAW', 'ENH'), 'LOSS');
  for (const verdict of ['TIE', 'BOTH_FAIL']) assert.equal(enhancedVerdict({ left: 'ENH', right: 'RAW', verdict }, 'RAW', 'ENH'), verdict);
});

test('High review scores never clear a known independent hard failure', () => {
  assert.equal(auditReviewText(review(), bindings).originalReview.scores.B.accuracy, 5);
  const gate = comparisonGate({ baselineHard: 'PASS', enhancedHard: 'FAIL', baselineSevere: false, enhancedSevere: false, auditsValid: true });
  assert.equal(gate.acceptanceGate, 'FAIL');
  assert.equal(gate.acceptanceBlocked, true);
});

test('Reported severe issues block acceptance pending evidence adjudication', () => {
  const gate = comparisonGate({ baselineHard: 'PASS', enhancedHard: 'PASS', baselineSevere: false, enhancedSevere: true, auditsValid: true });
  assert.equal(gate.acceptanceBlocked, true);
  assert.equal(gate.acceptanceGate, 'OPEN');
  assert.equal(gate.severeIssueState, 'REVIEW_REPORTED_REQUIRES_EVIDENCE_ADJUDICATION');
});

test('Two agreeable valid reviews cannot close an open semantic gate', () => {
  const gate = comparisonGate({ baselineHard: 'OPEN', enhancedHard: 'OPEN', baselineSevere: false, enhancedSevere: false, auditsValid: true });
  assert.equal(gate.acceptanceGate, 'OPEN');
  assert.equal(gate.acceptanceBlocked, true);
});
