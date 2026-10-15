import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { assessmentCoverageRecord, freezeCrossModelInputs, crossModelExecutionPlan,
  validateCrossModelBatch, textSha256, CROSS_MODEL_VENDORS } from './me00a-cross-model.mjs';

const sample = { id: 'Q05', rawPrompt: '写研究方法。', context: { files: [] } };
const answers = [{ question: '是否已确认？', answer: '允许拟议设计，参数未确认。' }];
const frozen = freezeCrossModelInputs(sample, answers, { direct: '直接复制正文。', plan: 'Plan复制正文。' });
const profiles = CROSS_MODEL_VENDORS.map(vendor => ({ vendor, modelId: `${vendor}-fixture`,
  surface: 'API', parameters: { max_tokens: 8192 }, resolutionStatus: 'RESOLVED' }));
const plan = crossModelExecutionPlan(frozen, profiles);
const actual = { total: 229, baseline: 70, candidateValidation: 159 };
const approved = { maximumActualModelCalls: 259, phaseLimits: { baseline: 80, candidateValidation: 179 },
  activeBatch: { authorizationStatus: 'APPROVED_BY_USER', status: 'OPEN', version: 'candidate13',
    callsBefore: 229, candidateCallsBefore: 159, maximumNewCalls: 20, secondRoundAuthorized: false,
    executionProfiles: profiles, reviewProfiles: [profiles[0], profiles[1]],
    purposeMaximums: { PLATFORM_GENERATION: 6, EXECUTION: 12, REVIEW: 2 },
    inputHashes: Object.fromEntries(frozen.arms.map(arm => [arm.arm, arm.sha256])) } };

test('Same-information raw input uses actual answers and neither enhanced copy is repaired', () => {
  assert.ok(frozen.arms[0].input.includes(answers[0].answer));
  assert.equal(frozen.arms[1].input, '直接复制正文。');
  assert.equal(frozen.arms[2].input, 'Plan复制正文。');
  for (const arm of frozen.arms) assert.equal(arm.sha256, textSha256(arm.input));
});

test('All models receive the identical arm bodies with fixed settings within each model', () => {
  assert.equal(plan.length, 12);
  for (const arm of frozen.arms) assert.equal(new Set(plan.filter(item => item.arm === arm.arm).map(item => item.inputSha256)).size, 1);
  assert.throws(() => crossModelExecutionPlan(frozen, profiles.map((profile, i) => i === 2 ? { ...profile, resolutionStatus: 'PENDING' } : profile)));
});

test('Original closed candidate12 batch rejects the new plan before any network action', async () => {
  const closed = JSON.parse(await readFile(new URL('../docs/testing/evidence/me00a-candidate12-2026-10-14/protocol-final.json', import.meta.url), 'utf8'));
  assert.throws(() => validateCrossModelBatch(closed, actual, plan), /open authorized/);
});

test('Candidate phase balance cannot borrow baseline or unallocated total allowance', () => {
  const insufficient = structuredClone(approved);
  insufficient.phaseLimits.candidateValidation = 163;
  assert.throws(() => validateCrossModelBatch(insufficient, actual, plan), /phase allowance/);
  assert.equal(validateCrossModelBatch(approved, actual, plan).maximumNewCalls, 20);
});

test('Changed copies, model parameters, ledger or duplicate arms invalidate the freeze', () => {
  for (const mutate of [
    records => { records[0].input += '补评分尺度'; },
    records => { records[0].parameters.max_tokens = 9999; },
    records => { records[0] = records[1]; },
    records => { records[0].modelId = 'another-model'; },
  ]) { const records = structuredClone(plan); mutate(records); assert.throws(() => validateCrossModelBatch(approved, actual, records)); }
  assert.throws(() => validateCrossModelBatch(approved, { ...actual, total: 230, candidateValidation: 160 }, plan), /Ledger changed/);
});

test('Two reviewers must have different vendors and the purpose split must remain frozen', () => {
  const same = structuredClone(approved); same.activeBatch.reviewProfiles[1] = same.activeBatch.reviewProfiles[0];
  assert.throws(() => validateCrossModelBatch(same, actual, plan), /independent/);
  const widened = structuredClone(approved); widened.activeBatch.purposeMaximums.EXECUTION = 13;
  assert.throws(() => validateCrossModelBatch(widened, actual, plan), /allocation/);
  const reordered = structuredClone(approved);
  reordered.activeBatch.purposeMaximums = { REVIEW: 2, EXECUTION: 12, PLATFORM_GENERATION: 6 };
  assert.equal(validateCrossModelBatch(reordered, actual, plan).works, 12);
});

const model = { assessment: { sufficiency: 'NEEDS_COMPLETION', gaps: [
  { kind: 'EXECUTION_STEP', missing: '评分设计不足', completion: '在方法中提出评分尺度建议', basis: '委托设计' } ] },
  sections: [{ type: 'TASK', content: '说明评价与分歧处理。' },
    { type: 'ACCEPTANCE', content: '评分尺度已说明。' }] };

test('Assessment promises and acceptance statements do not prove execution coverage', () => {
  const audit = assessmentCoverageRecord(model, '说明评价与分歧处理。\n评分尺度已说明。');
  assert.equal(audit.gaps[0].defaultStatus, 'UNVERIFIED');
  assert.equal(audit.automaticSemanticVerdict, false);
  assert.equal(audit.runtimeValidation, false);
  assert.throws(() => assessmentCoverageRecord(model, '评分尺度已说明。', [{ gapIndex: 0, component: '尺度',
    status: 'COVERED', reason: '测试夹具', evidence: [{ sectionType: 'ACCEPTANCE', quote: '评分尺度已说明。' }] }]), /responsibility/);
});

test('Positive observations require a true quote in both the responsibility section and copy', () => {
  const complete = structuredClone(model); complete.sections[0].content += '提出适用的评分尺度建议。';
  const observations = [{ gapIndex: 0, component: '尺度', status: 'COVERED', reason: '正文明确委托尺度设计',
    evidence: [{ sectionType: 'TASK', quote: '提出适用的评分尺度建议。' }] }];
  assert.equal(assessmentCoverageRecord(complete, complete.sections[0].content, observations).gaps[0].observations[0].status, 'COVERED');
  assert.throws(() => assessmentCoverageRecord(complete, '平台遗漏了尺度。', observations), /actual copy/);
  assert.throws(() => assessmentCoverageRecord(complete, complete.sections[0].content, [{ ...observations[0], evidence: [] }]), /literal evidence/);
});

test('Downstream findings bind the exact executed copy and keep separate observed failures', () => {
  const copy = model.sections[0].content;
  const work = { id: 'saved-work', inputSha256: textSha256(copy), text: '分歧超过预设阈值时讨论。',
    findings: [{ component: '尺度', status: 'MISSING', reason: '离线检查未展开尺度', quotes: ['预设阈值'] }] };
  const audit = assessmentCoverageRecord(model, copy, [], [work]);
  assert.equal(audit.downstream[0].findings[0].status, 'MISSING');
  assert.throws(() => assessmentCoverageRecord(model, copy, [], [{ ...work, inputSha256: 'wrong-input' }]), /actual copied input/);
  assert.throws(() => assessmentCoverageRecord(model, copy, [], [{ ...work, findings: [{ ...work.findings[0], quotes: ['不存在的原句'] }] }]), /true quotes/);
});

test('True candidate12 scale omission stays an explicit offline observation', async () => {
  const root = new URL('../docs/testing/evidence/me00a-candidate12-2026-10-14/', import.meta.url);
  const response = JSON.parse(await readFile(new URL('models/plan-response.json', root), 'utf8'));
  const copy = await readFile(new URL('live/plan/copied.md', root), 'utf8');
  const gapIndex = response.assessment.gaps.findIndex(gap => gap.completion.includes('评分尺度建议'));
  const audit = assessmentCoverageRecord(response, copy, [{ gapIndex, component: '评分尺度建议', status: 'MISSING',
    reason: '逐段核对责任段和复制正文：仅总体评价要求，未落实该声明的具体评分尺度交付要求。' }]);
  assert.equal(audit.gaps[gapIndex].observations[0].status, 'MISSING');
  assert.equal(audit.newModelCalls, 0);
});
