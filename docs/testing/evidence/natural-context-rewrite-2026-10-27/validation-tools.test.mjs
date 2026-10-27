import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createValidationBundle } from './prepare-validation-bundle.mjs';
import { confirmedAnswer, createConfirmedValidationBundle } from './bind-validation-answer.mjs';

// 这些对象仅测试离线脚本边界，不是服务端响应或模型质量证据。
const input = () => ({ rawPrompt: '调查时间不能超过今天。', modelId: 'deepseek:deepseek-flash',
  context: { customDescription: '', files: [{ path: 'src/views/records/import.js', content: '合成代码', language: 'javascript' }] },
  enhancement: { templateCode: 'AUTO', promptFormat: 'COMPACT', includeConversationHistory: false, includePermissionBoundaries: true },
  conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] }, planConfirmation: null });
const planResult = () => ({ id: 'P02_V45_PLAN', phase: 'PLAN', version: '45', status: 200,
  approvedRawPrompt: '今天是否允许由我确认。', body: { data: {
    provider: { mock: false }, planId: '27000000-0000-4000-8000-000000000042', planningContext: null,
    questions: [{ id: 'actual-question-7', question: '今天是否允许填写？', options: [
      { id: 'exclude', label: '不允许今天', recommended: true },
    ] }],
  } } });

test('正常变体区分实际复用材料与脚本构造DTO，Plan遵守无文件正文协议', () => {
  const captured = input();
  const bundle = createValidationBundle(captured);
  assert.equal(bundle.jobs.length, 5);
  assert.equal(bundle.jobs.filter(({ phase }) => phase === 'GENERATION').length, 3);
  assert.equal(bundle.jobs.filter(({ phase }) => phase === 'PLAN').length, 2);
  assert.deepEqual(bundle.jobs[0].approvedEnhancementInput.context, captured.context);
  assert.notEqual(bundle.jobs[0].approvedEnhancementInput.context, captured.context);
  for (const job of bundle.jobs.slice(1, 3)) assert.deepEqual(job.approvedEnhancementInput.context.files, []);
  for (const job of bundle.jobs.filter(({ phase }) => phase === 'PLAN')) {
    assert.equal(job.approvedEnhancementInput.planningContext, null);
    assert.equal('context' in job.approvedEnhancementInput, false);
  }
  assert.equal(bundle.provenance.includes('不冒称UI评测'), true);
});

test('真实题号和题干保持原样，用户明确回答不被相反推荐覆盖', () => {
  const result = planResult();
  const bundle = createConfirmedValidationBundle(result, 'actual-question-7', input());
  const confirmation = bundle.jobs[0].approvedEnhancementInput.planConfirmation;
  assert.equal(confirmation.planId, result.body.data.planId);
  assert.deepEqual(confirmation.answers, [{ questionId: 'actual-question-7', question: '今天是否允许填写？', answer: confirmedAnswer }]);
  assert.deepEqual(bundle.jobs[0].approvedEnhancementInput.context.files, []);
  assert.equal(bundle.decision.recommendationAccepted, false);
});

test('不存在的题号不能绑定实际回答', () => {
  assert.throws(() => createConfirmedValidationBundle(planResult(), 'invented-question', input()), /实际返回的题号/);
});

test('多余问题不能被脚本静默删掉以伪造Plan通过', () => {
  const result = planResult();
  result.body.data.questions.push({ id: 'extra', question: '请选择实现框架？' });
  assert.throws(() => createConfirmedValidationBundle(result, 'actual-question-7', input()), /不能静默删除多余问题/);
});

test('失败、Mock或无会话响应不能冒充真实Plan', () => {
  const failed = planResult(); failed.status = 422;
  assert.throws(() => createConfirmedValidationBundle(failed, 'actual-question-7', input()), /Plan未成功/);
  const mock = planResult(); mock.body.data.provider.mock = true;
  assert.throws(() => createConfirmedValidationBundle(mock, 'actual-question-7', input()), /Mock不能/);
  const missing = planResult(); missing.body.data.planId = '';
  assert.throws(() => createConfirmedValidationBundle(missing, 'actual-question-7', input()));
});
