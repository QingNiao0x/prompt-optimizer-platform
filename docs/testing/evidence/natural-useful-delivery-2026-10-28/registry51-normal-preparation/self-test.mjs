import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { here, json } from './browser-support.mjs';
import { build51Completion } from './build-plan-completion.mjs';
const draft = await json(resolve(here, 'generation-bundle.json'));
const original = await json(resolve(here, '../registry50-normal-preparation/generation-bundle.json'));
assert.equal(draft.relatedNormalFlowBudget.maximumActualAcrossNormalAndPlanStages, 36);
assert.equal(draft.relatedNormalFlowBudget.unallocated, 0); assert.equal(draft.jobs.length, 5);
for (const job of draft.jobs) {
  assert.equal(job.version, '51');
  assert.ok(isDeepStrictEqual(job.approvedEnhancementInput, original.jobs.find(item => item.caseId === job.caseId).approvedEnhancementInput));
}
const plans = (await json(resolve(here, 'plan-cases.json'))).cases, now = Date.parse('2030-01-01T00:10:00Z');
const fixture = sample => {
  const reference = { contextId: 'LOCAL_SELFTEST_NOT_REAL', version: 'LOCAL_SELFTEST' };
  const files = [{ path: 'LOCAL_SELFTEST.js', content: 'Synthetic local fixture only' }];
  const questions = sample.id === 'P01' ? [] : [{ id: 'LOCAL_QUESTION_NOT_REAL', question: '本地控制问题', type: 'SINGLE_CHOICE', allowCustomAnswer: false,
    options: [{ id: 'LOCAL_CHOICE', answer: '本地明确选择', recommended: false }] }];
  return { sample, capture: { prepareInput: { rawPrompt: sample.rawPrompt, context: { files } },
    prepareResponse: { status: 200, body: { data: { ...reference, expiresAt: '2030-01-01T00:30:00Z' } } },
    planInput: { rawPrompt: sample.rawPrompt, modelId: 'deepseek:deepseek-flash', contextDescription: '', conversationHistory: [], planningContext: reference },
    verdict: { caseId: sample.id, realAuthentication: true, realDeterministicPreparation: true, planBlockedBeforeModel: true,
      actualModelCalls: 0, indexedFiles: 79, contextExpiresAt: '2030-01-01T00:30:00Z', capturedAt: '2030-01-01T00:00:00Z',
      actualUiModelSelection: { selectedModelId: 'deepseek:deepseek-flash', publicFlashOptionClicked: true } } },
    result: { id: sample.futurePlanJobId, version: '51', phase: 'PLAN', caseId: sample.id, status: 200, body: { data: {
      planId: 'LOCAL_PLAN_NOT_REAL', provider: { mock: false, model: 'deepseek:deepseek-flash' }, planningContext: reference,
      expiresAt: '2030-01-01T00:40:00Z', questions } } },
    operator: { kind: 'EXPLICIT_OPERATOR_SELECTION', planId: 'LOCAL_PLAN_NOT_REAL', selectedAt: '2030-01-01T00:05:00Z',
      answers: questions.map(q => ({ questionId: q.id, question: q.question, optionIds: ['LOCAL_CHOICE'], customAnswer: '', answer: '本地明确选择' })) },
    base: { rawPrompt: sample.rawPrompt, modelId: 'deepseek:deepseek-flash', context: { customDescription: '', files: structuredClone(files) },
      conversationHistory: [], enhancement: { promptFormat: 'COMPACT' }, planConfirmation: null } };
};
const run = x => build51Completion(x.sample, x.capture, x.result, x.operator, x.base, now);
for (const sample of plans) {
  const x = fixture(sample), unmodified = structuredClone(x), job = run(x);
  assert.equal(job.version, '51'); assert.equal(job.approvedEnhancementInput.planConfirmation.answers.length, sample.id === 'P01' ? 0 : 1);
  assert.deepEqual(x, unmodified);
}
let rejected = 0;
for (const mutate of [
  x => { x.result.version = '50'; },
  x => { x.operator.planId = 'OLD_PLAN'; },
  x => { x.result.body.data.expiresAt = '2020-01-01T00:00:00Z'; },
  x => { x.operator.answers[0].optionIds = ['FAKE_OPTION']; },
  x => { x.base.context.files = []; },
  x => { x.result.body.data.questions = []; },
]) { const x = fixture(plans[1]); mutate(x); assert.throws(() => run(x)); rejected++; }
console.log(JSON.stringify({ kind: 'OFFLINE_FLASH51_NORMAL_PREPARATION', unchangedFlashDtoChecks: 5,
  syntheticPlanPathChecks: 2, syntheticRejections: rejected, actualResponsesRelabeled: false,
  actualModelCalls: 0, actualHttpRequests: 0, browserStarted: false, stageFrozen: false }));
