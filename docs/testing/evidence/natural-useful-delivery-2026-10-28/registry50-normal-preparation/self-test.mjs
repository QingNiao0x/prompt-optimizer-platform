import assert from 'node:assert/strict';
import { buildPlanCompletion } from './build-plan-completion.mjs';
import { loadSyntheticProject, cases } from '../fixtures/project.mjs';
import { inspectRecall } from './browser-support.mjs';

// Synthetic local controls only. These objects are not real context/Plan
// artifacts, are never written as live results and must never enter a bundle.
const fixture = await loadSyntheticProject();
assert.equal(fixture.files.length, 79);
assert.ok(fixture.files.some(file => file.path === 'src/views/records-old/create.js'));
assert.equal(fixture.files.filter(file => file.path.startsWith('src/views/records-archive')).length, 72);
const now = Date.parse('2030-01-01T00:10:00Z');
const sourceFiles = fixture.files.filter(file => fixture.targetPaths.includes(file.path)).map(file => ({ ...file, path: file.path + '#chunk-1' }));
const reference = { contextId: 'LOCAL_SELFTEST_CONTEXT_NOT_REAL', version: 'sha256:local-control-only' };
const rawPrompt = cases[0].rawPrompt;
const question = { id: 'different-id-each-real-plan', question: '今天是否允许？', type: 'SINGLE_CHOICE', allowCustomAnswer: true,
  options: [{ id: 'forbid', answer: '不允许今天', recommended: true }, { id: 'allow_today', answer: '允许今天', recommended: false }] };
const second = { ...question, id: 'independent-second-question', question: '请再次确认另一独立范围的日期。' };
const input = {
  prepareInput: { rawPrompt, context: { customDescription: '', files: sourceFiles } },
  prepareResponse: { status: 200, body: { data: { ...reference, expiresAt: '2030-01-01T00:30:00Z' } } },
  planInput: { rawPrompt, contextDescription: '', conversationHistory: [], modelId: 'deepseek:deepseek-flash', planningContext: reference },
  captureVerdict: { stage: 'SYNTHETIC_SELFTEST_ONLY', realAuthentication: true, realDeterministicPreparation: true,
    planBlockedBeforeModel: true, actualModelCalls: 0, indexedFiles: 79, capturedAt: '2030-01-01T00:00:00Z', contextExpiresAt: '2030-01-01T00:30:00Z' },
  planResult: { id: 'SELFTEST_NOT_LIVE', phase: 'PLAN', version: '50', status: 200, body: { data: {
    planId: 'LOCAL_SELFTEST_PLAN_NOT_REAL', provider: { mock: false }, planningContext: reference,
    expiresAt: '2030-01-01T00:40:00Z', questions: [question, second] } } },
  selection: { kind: 'EXPLICIT_OPERATOR_SELECTION', planId: 'LOCAL_SELFTEST_PLAN_NOT_REAL', selectedAt: '2030-01-01T00:05:00Z',
    answers: [question, second].map(q => ({ questionId: q.id, question: q.question, optionIds: ['allow_today'], customAnswer: '', answer: '允许今天' })) },
  baseInput: { rawPrompt, modelId: 'deepseek:deepseek-flash', context: { customDescription: '', files: structuredClone(sourceFiles) },
    enhancement: { promptFormat: 'COMPACT' }, conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] }, planConfirmation: null },
};
const original = structuredClone(input);
const passed = [];
const valid = buildPlanCompletion(input, now);
assert.deepEqual(input, original);
assert.equal(valid.input.planConfirmation.answers.length, 2);
assert.deepEqual(valid.input.planConfirmation.answers.map(answer => answer.answer), ['允许今天', '允许今天']);
assert.notEqual(valid.input.planConfirmation.answers[0].questionId, valid.input.planConfirmation.answers[1].questionId);
assert.equal(valid.provenance.actualUiFinalGenerationCaptured, false);
assert.ok(!Object.hasOwn(valid.input, 'actualAnswers'));
passed.push('nonrecommended-real-option-meaning-and-independent-same-text-answers-preserved', 'no-input-mutation', 'service-boundary-explicit');
for (const [label, mutate] of [
  ['expired-context', x => { x.prepareResponse.body.data.expiresAt = '2029-12-31T00:00:00Z'; }],
  ['expired-plan', x => { x.planResult.body.data.expiresAt = '2029-12-31T00:00:00Z'; }],
  ['previous-plan-id', x => { x.selection.planId = 'OLD_PLAN'; }],
  ['different-context', x => { x.planResult.body.data.planningContext = { contextId: 'OTHER', version: 'OTHER' }; }],
  ['forged-question-id', x => { x.selection.answers[0].questionId = 'UNKNOWN'; }],
  ['changed-question-text', x => { x.selection.answers[0].question = '另一个问题'; }],
  ['unknown-option', x => { x.selection.answers[0].optionIds = ['OTHER']; }],
  ['claimed-answer-not-selected-meaning', x => { x.selection.answers[0].answer = '不允许今天'; }],
  ['one-independent-answer-missing', x => { x.selection.answers.pop(); }],
  ['previous-generation-confirmation', x => { x.baseInput.planConfirmation = { planId: 'OLD' }; }],
  ['invented-actualAnswers', x => { x.baseInput.actualAnswers = []; }],
  ['prepared-source-dropped', x => { x.baseInput.context.files.pop(); }],
  ['mock-plan', x => { x.planResult.body.data.provider.mock = true; }],
  ['context-success-masquerading-as-plan', x => { x.planResult.phase = 'CONTEXT'; }],
  ['unrecorded-selection', x => { x.selection.kind = 'MODEL_RECOMMENDATION'; }],
]) {
  const example = structuredClone(input); mutate(example);
  assert.throws(() => buildPlanCompletion(example, now), undefined, label); passed.push(label);
}
inspectRecall(input.baseInput, fixture);
assert.throws(() => inspectRecall({ context: { files: [{ ...sourceFiles[0], path: 'src/views/records-old/create.js#chunk-1' }] } }, fixture));
passed.push('full79-fixture-with-old-and-noise-preserved', 'wrong-target-recall-rejected');
console.log(JSON.stringify({ kind: 'OFFLINE_SYNTHETIC_ADAPTER_SELFTEST', passed: passed.length, checks: passed,
  modelCalls: 0, browserStarted: false, productCompiled: false, liveArtifactsCreated: false }, null, 2));
