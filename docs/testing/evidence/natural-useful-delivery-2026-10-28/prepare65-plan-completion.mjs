import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
assert.equal((await json(resolve(evidence, 'registry65-plan/closure.json'))).actualCalls, 6);
const planned = await json(resolve(evidence, 'registry65-plan/jobs.json'));
const template = (await json(resolve(evidence, 'registry65-normal/jobs.json'))).find(job => job.caseId === 'V01').approvedEnhancementInput;
await mkdir(resolve(evidence, 'registry65-preparation/plan-operator-selections'), { recursive: true });
const cases = [], jobs = [];
for (const job of planned) {
  const result = await json(resolve(evidence, 'registry65-plan/results', job.id + '.json'));
  assert.equal(result.status, 200); const plan = result.body.data;
  assert.ok(plan.planId && Date.parse(plan.expiresAt) > Date.now()); assert.equal(plan.planningContext, null);
  const zero = job.caseId === 'P00'; const answers = [];
  if (zero) assert.deepEqual(plan.questions, []);
  else {
    assert.equal(plan.questions.length, 1); const question = plan.questions[0];
    assert.equal(question.question, '调查时间是否允许填写今天（当日）？');
    assert.equal(question.type, 'SINGLE_CHOICE'); assert.equal(question.allowCustomAnswer, true);
    assert.ok(question.options.every(option => !option.recommended));
    // Root read both actual questions/options before making these explicit synthetic choices.
    // Product code contains no such topic branch; no default, prior plan or invented question is substituted.
    const confirmed = job.caseId === 'P02_NOCTX_CONFIRMED';
    const option = confirmed ? question.options.find(option => option.id === 'allow_today') : null;
    if (confirmed) { assert.equal(option?.label, '允许填写今天'); assert.equal(option.answer, '允许填写今天：校验条件为调查日期不晚于今日。'); }
    const answer = { questionId: question.id, question: question.question,
      answer: confirmed ? option.answer : '今天是否允许尚未决定' };
    answers.push(answer);
    await save(resolve(evidence, 'registry65-preparation/plan-operator-selections', job.caseId + '.json'), {
      kind: 'EXPLICIT_SYNTHETIC_OPERATOR_CHOICE', selectedAt: new Date().toISOString(), planId: plan.planId,
      actualQuestion: question, selectedOptionIds: confirmed ? [option.id] : [], customAnswer: confirmed ? '' : answer.answer,
      answers, realEndUserDecisionClaimed: false, actualUiAnswerClickClaimed: false,
    });
  }
  const input = structuredClone(template); input.rawPrompt = job.approvedEnhancementInput.rawPrompt;
  input.context = { customDescription: '', files: [] }; input.conversationHistory = [];
  input.planConfirmation = { planId: plan.planId, planningContext: null, answers };
  cases.push({ id: job.caseId, rawPrompt: input.rawPrompt, contextDescription: '', actualNoAttachmentPlan: true });
  jobs.push({ id: `${job.caseId}_V65_GEN`, caseId: job.caseId, phase: 'GENERATION', version: '65', approvedEnhancementInput: input });
}
await save(resolve(evidence, 'registry65-preparation/plan-completion-bundle.json'), {
  syntheticOnly: true, description: 'Fresh actual65 no-attachment Plan ids and explicit operator-selected confirm/unknown states; server owns actor/question/source binding. P00 has no question or fabricated answer. API flow, not UI click claim.',
  cases, jobs, phaseLimits: { GENERATION: 18, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 8192, thinking: 'disabled', stream: false },
  preparationArtifacts: ['registry65-plan/closure.json', 'registry65-preparation/normal-review.json', 'registry65-engineering-freeze.json'],
});
console.log(JSON.stringify({ prepared: true, jobs: jobs.length, maximumGenerationCalls: 18, actualCalls: 0, freshPlanIds: true }));
