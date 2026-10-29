import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { buildCompletion } from './build-plan-completion.mjs';
import { here, modelId, json } from './support.mjs';

const proposed = await json(resolve(here, 'generation-bundle.json'));
const old = await json(resolve(here, '../registry50-normal-preparation/generation-bundle.json'));
assert.equal(proposed.relatedNormalFlowBudget.maximumActualAcrossNormalAndPlanStages, 36);
assert.equal(proposed.relatedNormalFlowBudget.unallocated, 0);
assert.equal(proposed.finiteProfileRegistered, false); assert.equal(proposed.phaseLimits.GENERATION, 20);
for (const job of proposed.jobs) {
  const baseline = old.jobs.find(item => item.caseId === job.caseId).approvedEnhancementInput;
  const copy = structuredClone(job.approvedEnhancementInput); assert.equal(copy.modelId, modelId); copy.modelId = baseline.modelId;
  assert.ok(isDeepStrictEqual(copy, baseline));
}
const cases = (await json(resolve(here, 'plan-cases.json'))).cases, now = Date.parse('2030-01-01T00:10:00Z');
const fixture = sample => {
  const ref = { contextId: 'LOCAL_TEST_NOT_LIVE', version: 'LOCAL_TEST_VERSION' };
  const q = { id: 'ACTUAL_ID_WILL_DIFFER', question: '本地离线选择控制', type: 'SINGLE_CHOICE', allowCustomAnswer: false,
    options: [{ id: 'do_not_use', answer: '不选这个', recommended: true }, { id: 'actual_nonrecommended', answer: '选定含义', recommended: false }] };
  const questionList = sample.id === 'P01' ? [] : [q];
  const files = [{ path: 'local-test-only.js', content: 'LOCAL SELFTEST ONLY', language: 'javascript' }];
  return { sample, capture: { prepareInput: { rawPrompt: sample.rawPrompt, context: { files } },
    prepareResponse: { status: 200, body: { data: { ...ref, expiresAt: '2030-01-01T00:30:00Z' } } },
    planInput: { rawPrompt: sample.rawPrompt, modelId, contextDescription: '', conversationHistory: [], planningContext: ref },
    verdict: { caseId: sample.id, realAuthentication: true, realDeterministicPreparation: true, planBlockedBeforeModel: true,
      actualModelCalls: 0, indexedFiles: 79, capturedAt: '2030-01-01T00:00:00Z', contextExpiresAt: '2030-01-01T00:30:00Z',
      actualUiModelSelection: { uiOptionClicked: true, selectedModelId: modelId } } },
    result: { id: sample.futurePlanJobId, phase: 'PLAN', version: '50', caseId: sample.id, status: 200, body: { data: {
      planId: 'LOCAL_PLAN_NOT_REAL', planningContext: ref, expiresAt: '2030-01-01T00:40:00Z', provider: { mock: false, model: modelId }, questions: questionList } } },
    operator: { kind: 'EXPLICIT_OPERATOR_SELECTION', planId: 'LOCAL_PLAN_NOT_REAL', selectedAt: '2030-01-01T00:05:00Z',
      answers: questionList.map(question => ({ questionId: question.id, question: question.question, optionIds: ['actual_nonrecommended'], customAnswer: '', answer: '选定含义' })) },
    base: { rawPrompt: sample.rawPrompt, modelId, context: { customDescription: '', files: structuredClone(files) },
      enhancement: { promptFormat: 'COMPACT' }, conversationHistory: [], planConfirmation: null } };
};
const run = x => buildCompletion(x.sample, x.capture, x.result, x.operator, x.base, now);
const p01 = fixture(cases[0]), p02 = fixture(cases[1]);
assert.equal(run(p01).approvedEnhancementInput.planConfirmation.answers.length, 0);
assert.equal(run(p02).approvedEnhancementInput.planConfirmation.answers[0].answer, '选定含义');
let negativeControls = 0;
for (const mutate of [
  x => { x.operator.planId = 'OLD'; },
  x => { x.capture.prepareResponse.body.data.expiresAt = '2020-01-01T00:00:00Z'; },
  x => { x.result.body.data.questions = []; },
  x => { x.operator.answers[0].optionIds = ['FABRICATED']; },
  x => { x.base.context.files = []; },
  x => { x.capture.verdict.actualUiModelSelection.selectedModelId = 'deepseek:deepseek-flash'; },
]) { const x = structuredClone(p02); mutate(x); assert.throws(() => run(x)); negativeControls++; }
const falseZero = structuredClone(p01); falseZero.result.body.data.questions = p02.result.body.data.questions;
assert.throws(() => run(falseZero)); negativeControls++;
console.log(JSON.stringify({ kind: 'OFFLINE_NATIVE_PRO50_NORMAL_PREPARATION', existingDtoEquivalenceChecks: 5,
  actualPlanPathSyntheticPositiveControls: 2, syntheticNegativeControls: negativeControls,
  maximumActualCallsPlanned: 36, normalEstimatedActual: 18, finiteProfilesRegistered: false,
  actualModelCalls: 0, actualHttpRequests: 0, browserStarted: false, stageFrozen: false }));
