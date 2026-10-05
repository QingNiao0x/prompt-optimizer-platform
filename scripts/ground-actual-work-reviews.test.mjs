import { test } from 'node:test';
import assert from 'node:assert/strict';
import { groundReviews } from './ground-actual-work-reviews.mjs';

const mapping = [{ jobId: 'news-plan', blindId: 'B001', outputSha256: 'bound' }];
const sample = () => ({ entries: [{ blindId: 'B001', outputSha256: 'bound', status: 'REVIEWED',
  scores: { accuracy: 4, completeness: 4, ruleFidelity: 4, usability: 4 }, evidence: ['真实作品的原文'], criticalErrors: [] }] });

test('实际正文不满足硬要求时满分不能变成通过，原评分和引文不被覆盖', () => {
  const original = sample();
  const result = groundReviews(original, mapping, [{ jobId: 'news-plan', outputSha256: 'bound',
    status: 'FACT_OR_LENGTH_FAILED', bodyHanCharacters: 599, bodyWithinRange: false, bodyHasMvp: true }]);
  assert.equal(result.entries[0].criticalErrors.length, 1);
  assert.deepEqual(result.entries[0].scores, original.entries[0].scores);
  assert.equal(original.entries[0].criticalErrors.length, 0);
});

test('编译通过不能抹掉其他语义错误，也不能将未评审升级为通过', () => {
  const original = sample(); original.entries[0].criticalErrors = ['其他真实语义错误'];
  assert.deepEqual(groundReviews(original, mapping, [{ jobId: 'news-plan', status: 'PASS' }]).entries, original.entries);
  original.entries[0].status = 'PENDING';
  assert.equal(groundReviews(original, mapping, [{ jobId: 'news-plan', outputSha256: 'bound', status: 'COMPILE_FAILED' }]).entries[0].status, 'PENDING');
});

test('另一作品或另一内容哈希的断言不能套用到当前作品', () => {
  assert.throws(() => groundReviews(sample(), mapping, [{ jobId: 'other', outputSha256: 'bound', status: 'COMPILE_FAILED' }]), /BINDING_MISMATCH/);
  assert.throws(() => groundReviews(sample(), mapping, [{ jobId: 'news-plan', outputSha256: 'changed', status: 'COMPILE_FAILED' }]), /BINDING_MISMATCH/);
});

test('资料格的已证明误绑进入验收失败，有限未检出不能升级为专业通过', () => {
  const original = sample();
  const failed = groundReviews(original, mapping, [{jobId: 'news-plan', outputSha256: 'bound',
    status: 'SOURCE_OBJECT_FAILED', violations: [{quote: '| 维修 | 日常维护 | 全部设施故障费用 |'}]}]);
  assert.equal(failed.entries[0].criticalErrors.length, 1);
  assert.match(failed.entries[0].criticalErrors[0], /资料归属/);
  assert.deepEqual(groundReviews(original, mapping, [{jobId: 'news-plan', outputSha256: 'bound',
    status: 'NO_DEFINITE_TABLE_BINDING_FOUND'}]).entries, original.entries);
});
