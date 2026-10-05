import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createJobs, safeMaterialPath, sha256, verifyResult, reviewScore, comparePairs } from './prompt-output-evaluation.mjs';

const catalog = { source: '/api/v1/models', models: [{ id: 'deepseek:example' }, { id: 'tokenhub:example' }] };
const options = { models: ['deepseek:example'], repetitions: 1, maxTokens: 4096, maxJobs: 30 };
function sample() {
  return { schemaVersion: 1, dataAuthorization: 'SYNTHETIC', cases: [{ id: 'example', domain: '报告',
    rawPrompt: '为合成季度数据写报告，不编造结果。', files: [{ path: 'materials/brief.md', content: '审批标准与退款标准是两个不同决定。' }],
    direct: { prompt: '交付季度数据报告，保留实际结果和限制。' }, plan: { prompt: '根据用户确认的范围完成报告。', serverAccepted: true },
    hiddenAnswerCard: 'HIDDEN_ORACLE_NEVER_SEND', knownFacts: ['HIDDEN_GRADING_FACT'], submittedAnswers: [] }] };
}

test('完整输入三个组均携带相同资料，隐藏答案卡及评分信息不进入请求', () => {
  const batch = createJobs(sample(), catalog, options);
  assert.deepEqual(new Set(batch.jobs.map(job => job.arm)), new Set(['raw', 'direct', 'plan']));
  assert.equal(new Set(batch.jobs.map(job => job.materialsHash)).size, 1);
  assert.equal(new Set(batch.jobs.map(job => job.systemHash)).size, 1);
  for (const job of batch.jobs) {
    assert.equal(job.userHash, sha256(job.user));
    assert.equal(job.user.includes('HIDDEN_ORACLE'), false);
    assert.equal(job.user.includes('HIDDEN_GRADING'), false);
  }
});

test('Plan 实际回答仅进入同信息轨道；部分确定不能被改成全部确定', () => {
  const input = sample();
  input.cases[0].submittedAnswers = [{ questionId: 'scope', question: '是否区分年度和性别？',
    answer: '按年度和性别，分母暂不确定。', actuallySubmitted: true }];
  input.cases[0].plan.prompt += '按年度和性别，分母暂不确定。';
  const batch = createJobs(input, catalog, options);
  assert.equal(batch.jobs.length, 5);
  for (const job of batch.jobs) {
    assert.equal(job.user.includes('分母暂不确定'), job.informationTrack === 'matched');
    if (job.informationTrack === 'matched') assert.equal(job.user.match(/分母暂不确定/g).length, 1);
  }
  assert.equal(new Set(batch.jobs.filter(job => job.informationTrack === 'matched').map(job => job.answersHash)).size, 1);
});

test('Plan正文漏掉实际回答时执行器不补漏，但保留匹配元数据供评审发现缺陷', () => {
  const input = sample();
  input.cases[0].submittedAnswers = [{ questionId: 'scope', question: '分母如何处理？',
    answer: '保留分母未知，不计算率。', actuallySubmitted: true }];
  const batch = createJobs(input, catalog, options);
  const plan = batch.jobs.find(job => job.arm === 'plan');
  const matched = batch.jobs.find(job => job.arm === 'raw_matched');
  assert.equal(plan.user.includes('保留分母未知'), false);
  assert.equal(matched.user.includes('保留分母未知'), true);
  assert.equal(plan.informationTrack, 'matched');
  assert.equal(plan.answersHash, matched.answersHash);
  assert.equal(batch.cases[0].submittedAnswers[0].answer, '保留分母未知，不计算率。');
});

test('未提交的候选不能当作用户事实；缺少成功 Plan 回执拒绝生成匹配组', () => {
  const input = sample();
  input.cases[0].submittedAnswers = [{ questionId: 'scope', question: '渠道？', answer: '邮件', actuallySubmitted: false }];
  assert.throws(() => createJobs(input, catalog, options), /UNSUBMITTED/);
  input.cases[0].submittedAnswers[0].actuallySubmitted = true;
  input.cases[0].plan.serverAccepted = false;
  assert.throws(() => createJobs(input, catalog, options), /SERVER_ACCEPTED/);
});

test('每次运行受调用预算限制；换执行模型仍保持每题条件一致', () => {
  const batch = createJobs(sample(), catalog, { ...options, models: ['deepseek:example', 'tokenhub:example'], repetitions: 3 });
  assert.equal(batch.jobs.length, 18);
  assert.equal(new Set(batch.jobs.map(job => job.id)).size, 18);
  assert.throws(() => createJobs(sample(), catalog, { ...options, repetitions: 3, maxJobs: 8 }), /CALL_LIMIT/);
  assert.throws(() => createJobs(sample(), catalog, { ...options, models: ['unpublished:kimi'] }), /NOT_PUBLISHED/);
});

test('不同优化器的输入不能混入同一运行后被合并排名', () => {
  const input = sample();
  input.cases.push({ ...input.cases[0], id: 'other', optimizerModelId: 'other:optimizer' });
  assert.throws(() => createJobs(input, catalog, options), /MIXED_OPTIMIZERS/);
  const original = work('raw', 80); original.optimizerModelId = 'one';
  const optimized = work('direct', 90); optimized.optimizerModelId = 'two';
  assert.equal(comparePairs([original, optimized])[0].status, 'PENDING');
});

for (const path of ['../outside.txt', 'E:\\secret.txt', '\\\\server\\secret.txt', '.git/config', '.env', 'nested/.env.local',
  'cert.pem', 'id_rsa', 'keys/private.key', 'src/application-prod.yml']) {
  test('拒绝受保护或逃逸的资料路径：' + path.replace(/.*secret.*/, '绝对路径'), () => assert.throws(() => safeMaterialPath(path)));
}

test('空值、超长原始提示词及敏感凭据在准备阶段拒绝', () => {
  const input = sample();
  for (const raw of ['', 'x'.repeat(8001), 'Bearer abcdefghijklmnopqrstuvwxyz']) {
    input.cases[0].rawPrompt = raw;
    assert.throws(() => createJobs(input, catalog, options));
  }
});

test('请求内容绑定防止混用其他题、模型或资料；HTTP200空正文不算成功', () => {
  const job = createJobs(sample(), catalog, options).jobs[0];
  const result = { id: job.id, caseId: job.caseId, arm: job.arm, requestedPublishedModelId: job.modelId,
    promptHash: job.promptHash, materialsHash: job.materialsHash, executionUserSha256: job.userHash,
    systemSha256: job.systemHash, attempt: 1, automaticRetry: false, status: 'SUCCESS', httpStatus: 200,
    output: '实际报告', outputSha256: sha256('实际报告') };
  assert.doesNotThrow(() => verifyResult(job, result));
  assert.throws(() => verifyResult(job, { ...result, output: '' }), /EMPTY/);
  assert.throws(() => verifyResult(job, { ...result, materialsHash: 'wrong' }), /BINDING/);
  assert.throws(() => verifyResult(job, { ...result, output: '被修改的作品' }), /HASH/);
  assert.throws(() => verifyResult(job, { ...result, attempt: 2 }), /RETRY/);
});

test('严重业务错误不能被满分覆盖，专业未评审保持 PENDING', () => {
  const entry = { status: 'REVIEWED', scores: { accuracy: 4, completeness: 4, ruleFidelity: 4, usability: 4 },
    criticalErrors: ['把退款标准误用为审批标准'], evidence: ['作品第2段与材料第3条冲突'] };
  assert.equal(reviewScore(entry).status, 'CRITICAL_FAIL');
  assert.equal(reviewScore(entry).score, 100);
  assert.deepEqual(reviewScore(undefined), { status: 'PENDING', score: null });
  assert.throws(() => reviewScore({ ...entry, evidence: [] }), /EVIDENCE_REQUIRED/);
});

function work(arm, score, status = 'REVIEWED', track = 'initial', model = 'example') {
  return { caseId: 'same', repetition: 1, arm, modelId: model, informationTrack: track,
    technicalStatus: 'SUCCESS', quality: { status, score } };
}
test('没有同信息原始基线，不把 Plan 多知道答案的优势归功于表达方式', () => {
  assert.equal(comparePairs([work('raw', 80), work('plan', 95, 'REVIEWED', 'matched')])[0].status, 'PENDING');
  assert.equal(comparePairs([work('raw_matched', 80, 'REVIEWED', 'matched'), work('plan', 95, 'REVIEWED', 'matched')])[0].status, 'WIN');
  assert.equal(comparePairs([work('raw', 80, 'REVIEWED', 'initial', 'other'), work('direct', 95)])[0].status, 'PENDING');
});
test('未评审不算通过，双方有严重错误不记为增强胜出，回退仍统计为退化', () => {
  assert.equal(comparePairs([work('raw', null, 'PENDING'), work('direct', 95)])[0].status, 'PENDING');
  assert.equal(comparePairs([work('raw', 60, 'CRITICAL_FAIL'), work('plan', 95, 'CRITICAL_FAIL')])[0].status, 'BOTH_FAIL');
  assert.equal(comparePairs([work('raw', 80), work('direct', 95, 'CRITICAL_FAIL')])[0].status, 'LOSS');
});

test('网络失败不应被计为提示词退化或让另一组自动胜出', () => {
  const failedRaw = { ...work('raw', null), technicalStatus: 'FAILED', quality: { status: 'FAILED', score: null } };
  assert.equal(comparePairs([failedRaw, work('direct', 95)])[0].status, 'PENDING');
  const failedDirect = { ...work('direct', null), technicalStatus: 'FAILED', quality: { status: 'FAILED', score: null } };
  assert.equal(comparePairs([work('raw', 80), failedDirect])[0].status, 'PENDING');
});
