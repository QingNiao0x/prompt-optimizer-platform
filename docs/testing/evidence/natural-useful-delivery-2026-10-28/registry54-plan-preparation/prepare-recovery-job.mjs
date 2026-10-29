import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { here, repo, json, loadCases } from './browser-support.mjs';
import { readCapture, build54Completion } from './build-plan-completion.mjs';

// Tool-interruption recovery only: new context and real Plan, with unchanged product and task.
const [mode, captureStage, resultStage, outputName] = process.argv.slice(2);
for (const value of [captureStage, resultStage, outputName]) assert.match(value ?? '', /^[a-z0-9-]+$/);
assert.ok(['PLAN', 'GENERATION'].includes(mode));
const capture = await readCapture(resolve(here, 'plan-captures', captureStage, 'P02_CONFIRMED'));
const sample = { ...(await loadCases())[0], futurePlanJobId: 'P02_CONFIRMED_V54_PLAN_RECOVERY',
  futureGenerationJobId: 'P02_CONFIRMED_V54_GEN_RECOVERY' };
const bundle = await json(resolve(here, 'confirmed-plan-bundle.json'));
delete bundle.prerequisiteStages;
if (mode === 'PLAN') {
  assert.ok(Date.parse(capture.verdict.contextExpiresAt) > Date.now());
  bundle.jobs = [{ ...bundle.jobs[0], id: sample.futurePlanJobId, approvedEnhancementInput: capture.planInput }];
} else {
  const result = await json(resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', resultStage,
    'results', sample.futurePlanJobId + '.json'));
  const plan = result.body?.data;
  assert.equal(result.status, 200); assert.equal(plan.questions.length, 1);
  const question = plan.questions[0], option = question.options.find(value => value.id === 'allow_today');
  assert.ok(option?.answer); assert.equal(question.type, 'SINGLE_CHOICE');
  // The frozen CONFIRMED branch intentionally selects the actual allow_today option, never fabricated text.
  const operator = { kind: 'EXPLICIT_OPERATOR_SELECTION', caseId: sample.id, planId: plan.planId,
    planningContext: plan.planningContext, actorScopeSha256: capture.verdict.actorScopeSha256,
    selectedAt: new Date().toISOString(), decisionQuestionId: question.id,
    answers: [{ questionId: question.id, question: question.question, optionIds: [option.id],
      customAnswer: '', answer: option.answer.trim() }] };
  const base = structuredClone((await json(resolve(here, '../registry52-plan-completion/jobs.json')))[0].approvedEnhancementInput);
  delete base.planConfirmation;
  base.context.customDescription = capture.planInput.contextDescription;
  base.context.files = capture.prepareInput.context.files;
  base.conversationHistory = capture.planInput.conversationHistory;
  if (capture.prepareInput.permissionPolicy) base.permissionPolicy = capture.prepareInput.permissionPolicy;
  bundle.jobs = [build54Completion(sample, capture, result, operator, base)];
  bundle.phaseLimits = { PLAN: 0, GENERATION: 2, EXECUTION: 0, REVIEW: 0 };
  await writeFile(resolve(here, outputName + '-selection.json'), JSON.stringify(operator, null, 2) + '\n', { flag: 'wx' });
}
await writeFile(resolve(here, outputName + '.json'), JSON.stringify(bundle, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ bundle: outputName, job: bundle.jobs[0].id, calls: 0, expiresAt: capture.verdict.contextExpiresAt }));
