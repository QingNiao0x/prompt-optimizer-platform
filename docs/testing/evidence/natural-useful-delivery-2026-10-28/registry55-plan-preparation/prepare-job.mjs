import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { here, repo, json, loadCases } from './browser-support.mjs';
import { readCapture, validateCapture55, build55Completion } from './build-plan-completion.mjs';
const [mode, caseId, captureStage, planStage, outputName] = process.argv.slice(2);
assert.ok(['PLAN', 'GENERATION'].includes(mode));
for (const value of [captureStage, planStage, outputName]) assert.match(value ?? '', /^[a-z0-9-]+$/);
const sample = (await loadCases()).find(item => item.id === caseId); assert.ok(sample);
const capture = await readCapture(resolve(here, 'plan-captures', captureStage, caseId));
validateCapture55(sample, capture);
let job;
const save = (name, value) => writeFile(resolve(here, name), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
if (mode === 'PLAN') {
  job = { id: sample.futurePlanJobId, caseId, version: '55', phase: 'PLAN', approvedEnhancementInput: capture.planInput };
} else {
  const result = await json(resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', planStage, 'results', sample.futurePlanJobId + '.json'));
  const plan = result.body?.data; assert.equal(result.status, 200); assert.equal(plan.questions.length, 1);
  const question = plan.questions[0]; assert.equal(question.type, 'SINGLE_CHOICE');
  const option = question.options.find(item => item.id === 'allow_today');
  if (caseId === 'P02_CONFIRMED') assert.ok(option?.answer);
  const customAnswer = caseId === 'P02_UNKNOWN' ? '今天是否允许尚未决定' : '';
  if (customAnswer) assert.equal(question.allowCustomAnswer, true);
  const operator = { kind: 'EXPLICIT_OPERATOR_SELECTION', caseId, planId: plan.planId,
    planningContext: plan.planningContext, actorScopeSha256: capture.verdict.actorScopeSha256,
    selectedAt: new Date().toISOString(), decisionQuestionId: question.id,
    answers: [{ questionId: question.id, question: question.question, optionIds: customAnswer ? [] : [option.id],
      customAnswer, answer: customAnswer || option.answer.trim() }] };
  const base = structuredClone((await json(resolve(here, '../registry52-plan-completion/jobs.json')))[0].approvedEnhancementInput);
  delete base.planConfirmation; base.context.customDescription = capture.planInput.contextDescription;
  base.context.files = capture.prepareInput.context.files; base.conversationHistory = capture.planInput.conversationHistory;
  if (capture.prepareInput.permissionPolicy) base.permissionPolicy = capture.prepareInput.permissionPolicy;
  job = build55Completion(sample, capture, result, operator, base);
  await save(outputName + '-selection.json', operator);
}
await save(outputName + '.json', { syntheticOnly: true, cases: [sample], jobs: [job],
  phaseLimits: { PLAN: mode === 'PLAN' ? 4 : 0, GENERATION: mode === 'GENERATION' ? 2 : 0, EXECUTION: 0, REVIEW: 0 } });
console.log(JSON.stringify({ job: job.id, actualModelCalls: 0, contextExpiresAt: capture.verdict.contextExpiresAt }));
