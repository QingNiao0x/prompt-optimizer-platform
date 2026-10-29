import assert from 'node:assert/strict';
import { loadCases, hash, validateCaptureProtocol } from './browser-support.mjs';
import { build52Completion, validateIndependentCaptures } from './build-plan-completion.mjs';

const cases = await loadCases(), now = Date.parse('2030-01-01T00:10:00Z');
const fixture = sample => {
  const reference = { contextId: 'LOCAL_ONLY_' + sample.id, version: 'sha256:local-only' }, scope = hash('LOCAL_ACCOUNT_ONLY');
  const files = [{ path: 'LOCAL_ONLY.js', content: 'Synthetic offline boundary fixture only' }];
  const question = { id: 'LOCAL_QUESTION', question: '今天是否允许？', type: 'SINGLE_CHOICE', allowCustomAnswer: true,
    options: [{ id: 'LOCAL_FORBID', answer: '不允许今天', recommended: true }, { id: 'LOCAL_ALLOW', answer: '允许今天', recommended: false }] };
  const isUnknown = sample.id === 'P02_UNKNOWN';
  return { sample, capture: { prepareInput: { rawPrompt: sample.rawPrompt, context: { customDescription: '', files }, permissionPolicy: { localOnly: true } },
    prepareResponse: { status: 200, body: { data: { ...reference, expiresAt: '2030-01-01T00:30:00Z' } } },
    planInput: { rawPrompt: sample.rawPrompt, modelId: 'deepseek:deepseek-flash', contextDescription: '', conversationHistory: [], planningContext: reference },
    verdict: { caseId: sample.id, realAuthentication: true, actorScopeSha256: scope, realDeterministicPreparation: true,
      planBlockedBeforeModel: true, actualModelCalls: 0, indexedFiles: 79, capturedAt: '2030-01-01T00:00:00Z', contextExpiresAt: '2030-01-01T00:30:00Z',
      actualUiModelSelection: { publicFlashOptionClicked: true, selectedModelId: 'deepseek:deepseek-flash' } } },
    result: { id: sample.futurePlanJobId, version: '52', phase: 'PLAN', caseId: sample.id, status: 200, body: { data: {
      planId: 'LOCAL_PLAN_' + sample.id, provider: { model: 'deepseek:deepseek-flash', mock: false }, planningContext: reference,
      expiresAt: '2030-01-01T00:40:00Z', questions: [question] } } },
    operator: { kind: 'EXPLICIT_OPERATOR_SELECTION', caseId: sample.id, planId: 'LOCAL_PLAN_' + sample.id, planningContext: reference,
      actorScopeSha256: scope, selectedAt: '2030-01-01T00:05:00Z', decisionQuestionId: question.id,
      answers: [{ questionId: question.id, question: question.question, optionIds: isUnknown ? [] : ['LOCAL_ALLOW'],
        customAnswer: isUnknown ? '今天是否允许尚未决定' : '', answer: isUnknown ? '今天是否允许尚未决定' : '允许今天' }] },
    base: { rawPrompt: sample.rawPrompt, modelId: 'deepseek:deepseek-flash', context: { customDescription: '', files: structuredClone(files) },
      conversationHistory: [], permissionPolicy: { localOnly: true }, enhancement: { promptFormat: 'COMPACT' }, planConfirmation: null } };
};
const run = x => build52Completion(x.sample, x.capture, x.result, x.operator, x.base, now);
for (const sample of cases) { const x = fixture(sample), original = structuredClone(x), job = run(x); assert.equal(job.version, '52'); assert.deepEqual(x, original); }
validateIndependentCaptures(cases, cases.map(sample => fixture(sample).capture), now);
let rejected = 0;
for (const mutate of [
  x => { x.result.version = '51'; }, x => { x.operator.planId = 'OLD_PLAN'; },
  x => { x.capture.prepareResponse.body.data.expiresAt = '2020-01-01T00:00:00Z'; },
  x => { x.result.body.data.expiresAt = '2020-01-01T00:00:00Z'; },
  x => { x.result.body.data.questions[0].allowCustomAnswer = false; },
  x => { x.operator.answers[0].customAnswer = ''; },
  x => { x.operator.answers[0].optionIds = ['FAKE_CHOICE']; },
  x => { x.operator.actorScopeSha256 = hash('OTHER_ACTOR'); },
  x => { x.base.context.files = []; }, x => { x.base.permissionPolicy = {}; },
  x => { x.result.body.data.questions.push({ ...x.result.body.data.questions[0], id: 'SECOND_REQUIRED' }); },
]) { const x = fixture(cases[1]); mutate(x); assert.throws(() => run(x)); rejected++; }
const a = fixture(cases[0]), b = fixture(cases[1]); b.capture.prepareResponse.body.data.contextId = a.capture.prepareResponse.body.data.contextId;
b.capture.planInput.planningContext.contextId = a.capture.planInput.planningContext.contextId;
assert.throws(() => validateIndependentCaptures(cases, [a.capture, b.capture], now)); rejected++;
const protocol = { status: 'FROZEN', captureOnly: true, versions: ['52'], maximumNewActualCalls: 0, allowedJobs: [], qualityRetriesAllowed: false,
  modelId: 'deepseek:deepseek-flash', upstreamModel: 'deepseek-flash', endpoint: 'https://api.deepseek.com/chat/completions',
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 0, REVIEW: 0 }, parameters: { generationMaxTokens: 8192, temperature: 0.2, thinking: 'disabled', stream: false } };
validateCaptureProtocol(protocol, []);
assert.throws(() => validateCaptureProtocol({ ...protocol, maximumNewActualCalls: 1 }, [])); rejected++;
assert.throws(() => validateCaptureProtocol(protocol, [{ id: 'FAKE_MODEL_JOB' }])); rejected++;
console.log(JSON.stringify({ syntheticOnly: true, positiveAnswerBranches: 2, independentContextCheck: true, rejectedBoundaries: rejected,
  metadataRelabeled: false, actualHttpRequests: 0, actualModelCalls: 0, browserStarted: false, frozen: false }));
