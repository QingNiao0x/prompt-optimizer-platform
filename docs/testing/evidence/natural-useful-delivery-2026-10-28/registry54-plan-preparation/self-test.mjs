import assert from 'node:assert/strict';
import { loadCases, hash, validateCaptureProtocol } from './browser-support.mjs';
import { build54Completion } from './build-plan-completion.mjs';
const [sample] = await loadCases(), now = Date.parse('2030-01-01T00:10:00Z');
const reference = { contextId: 'LOCAL_SYNTHETIC_CONTEXT', version: 'sha256:local-only' }, actor = hash('LOCAL_ACCOUNT_ONLY');
const files = [{ path: 'LOCAL_ONLY.js', content: 'Offline synthetic fixture' }];
const question = { id: 'LOCAL_Q', question: '今天是否允许？', type: 'SINGLE_CHOICE', allowCustomAnswer: true,
  options: [{ id: 'allow_today', answer: '允许今天', recommended: false }] };
const fixture = () => ({ sample, capture: {
  prepareInput: { rawPrompt: sample.rawPrompt, context: { customDescription: '', files }, permissionPolicy: {} },
  prepareResponse: { status: 200, body: { data: { ...reference, expiresAt: '2030-01-01T00:30:00Z' } } },
  planInput: { rawPrompt: sample.rawPrompt, modelId: 'deepseek:deepseek-flash', contextDescription: '', conversationHistory: [], planningContext: reference },
  verdict: { caseId: sample.id, strategyVersion: '54', realAuthentication: true, actorScopeSha256: actor,
    realDeterministicPreparation: true, planBlockedBeforeModel: true, actualModelCalls: 0, indexedFiles: 79,
    capturedAt: '2030-01-01T00:00:00Z', contextExpiresAt: '2030-01-01T00:30:00Z',
    actualUiModelSelection: { publicFlashOptionClicked: true, selectedModelId: 'deepseek:deepseek-flash' } } },
  result: { id: sample.futurePlanJobId, caseId: sample.id, phase: 'PLAN', version: '54', status: 200, body: { data: {
    planId: 'LOCAL_PLAN', provider: { mock: false, model: 'deepseek:deepseek-flash' }, planningContext: reference,
    expiresAt: '2030-01-01T00:40:00Z', questions: [question] } } },
  operator: { kind: 'EXPLICIT_OPERATOR_SELECTION', caseId: sample.id, planId: 'LOCAL_PLAN', planningContext: reference,
    actorScopeSha256: actor, selectedAt: '2030-01-01T00:05:00Z', decisionQuestionId: question.id,
    answers: [{ questionId: question.id, question: question.question, optionIds: ['allow_today'], customAnswer: '', answer: '允许今天' }] },
  base: { rawPrompt: sample.rawPrompt, modelId: 'deepseek:deepseek-flash', context: { customDescription: '', files: structuredClone(files) },
    conversationHistory: [], permissionPolicy: {}, enhancement: { promptFormat: 'COMPACT' }, planConfirmation: null } });
const run = x => build54Completion(x.sample, x.capture, x.result, x.operator, x.base, now);
const x = fixture(), original = structuredClone(x), job = run(x);
assert.equal(job.version, '54'); assert.equal(job.id, 'P02_CONFIRMED_V54_GEN'); assert.deepEqual(x, original);
let rejected = 0;
for (const mutate of [x => { x.result.version = '52'; }, x => { x.capture.verdict.strategyVersion = '52'; },
  x => { x.operator.planId = 'OLD_PLAN'; }, x => { x.result.body.data.expiresAt = '2020-01-01T00:00:00Z'; },
  x => { x.base.context.files = []; }, x => { x.operator.answers[0].optionIds = ['FAKE']; },
  x => { x.sample = { ...x.sample, id: 'P02_UNKNOWN' }; }]) {
  const copy = structuredClone(fixture()); mutate(copy); assert.throws(() => run(copy)); rejected++;
}
const protocol = { status: 'FROZEN', captureOnly: true, versions: ['54'], maximumNewActualCalls: 0, allowedJobs: [], qualityRetriesAllowed: false,
  modelId: 'deepseek:deepseek-flash', upstreamModel: 'deepseek-flash', endpoint: 'https://api.deepseek.com/chat/completions',
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 0, REVIEW: 0 }, parameters: { generationMaxTokens: 8192, temperature: 0.2, thinking: 'disabled', stream: false } };
validateCaptureProtocol(protocol, []); assert.throws(() => validateCaptureProtocol({ ...protocol, versions: ['52'] }, [])); rejected++;
console.log(JSON.stringify({ syntheticOnly: true, confirmedPositive: 1, rejectedBoundaries: rejected,
  metadataRelabeled: false, unknownPrepared: false, actualHttpRequests: 0, actualModelCalls: 0, browserStarted: false }));
