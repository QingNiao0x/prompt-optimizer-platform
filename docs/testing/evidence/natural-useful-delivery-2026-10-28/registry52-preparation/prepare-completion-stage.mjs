import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadCases } from '../registry52-plan-preparation/browser-support.mjs';
import { readCapture, validateIndependentCaptures, build52Completion } from '../registry52-plan-preparation/build-plan-completion.mjs';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const closure = await json(resolve(evidence, 'registry52-plan/closure.json'));
assert.equal(closure.status, 'CLOSED'); assert.equal(closure.actualCalls, 4); assert.ok(closure.allJobsDelivered);
const samples = await loadCases();
const captures = await Promise.all(samples.map(sample => readCapture(resolve(evidence, 'registry52-plan-preparation/plan-captures/registry52-plan-capture', sample.id))));
validateIndependentCaptures(samples, captures);
const template = await json(resolve(evidence, 'browser/captures-r2/C01/input.json'));
const jobs = [], selections = [];
await mkdir(resolve(here, 'operator-selections'), { recursive: true });
await mkdir(resolve(here, 'completion-inputs'), { recursive: true });
for (const [index, sample] of samples.entries()) {
  const result = await json(resolve(evidence, 'registry52-plan/results', sample.futurePlanJobId + '.json'));
  const capture = captures[index], plan = result.body.data;
  assert.equal(plan.questions.length, 1); const question = plan.questions[0]; assert.equal(question.id, 'q1');
  // Both actual questions were read against the preapproved date-boundary fixture before this script was written.
  // These are explicit synthetic operator actions, not recommended defaults or newly invented business choices.
  let answer;
  if (sample.id === 'P02_CONFIRMED') {
    const option = question.options.find(item => item.id === 'allow_today'); assert.ok(option);
    answer = { questionId: question.id, question: question.question, optionIds: [option.id], customAnswer: '', answer: option.answer.trim() };
  } else {
    assert.ok(question.allowCustomAnswer);
    answer = { questionId: question.id, question: question.question, optionIds: [], customAnswer: '今天是否允许尚未决定', answer: '今天是否允许尚未决定' };
  }
  const operator = { kind: 'EXPLICIT_OPERATOR_SELECTION', caseId: sample.id, planId: plan.planId,
    planningContext: plan.planningContext, actorScopeSha256: capture.verdict.actorScopeSha256,
    selectedAt: new Date().toISOString(), decisionQuestionId: question.id, answers: [answer],
    authorization: 'Root approved allow-today and explicitly-still-unknown synthetic states before new Plan calls; no actual end-user decision is claimed.' };
  const base = structuredClone(template); base.rawPrompt = sample.rawPrompt; base.modelId = 'deepseek:deepseek-flash';
  base.context = structuredClone(capture.prepareInput.context); base.conversationHistory = structuredClone(capture.planInput.conversationHistory);
  if (Object.hasOwn(capture.prepareInput, 'permissionPolicy')) base.permissionPolicy = structuredClone(capture.prepareInput.permissionPolicy);
  const job = build52Completion(sample, capture, result, operator, base);
  await save(resolve(here, 'operator-selections', sample.id + '.json'), operator);
  await save(resolve(here, 'completion-inputs', sample.id + '-base.json'), base);
  await save(resolve(here, 'completion-inputs', sample.id + '-job.json'), job);
  jobs.push(job); selections.push({ caseId: sample.id, questionId: question.id, answer: answer.answer, allowCustomAnswer: question.allowCustomAnswer });
}
const planBundle = await json(resolve(here, 'plan-bundle.json'));
const bundle = { syntheticOnly: true, description: '52 confirmed vs explicitly-unknown actual Plan answers, full common P02 inputs retained; service protocol validation, not full UI answer clicks.',
  parameters: { temperature: 0.2, generationMaxTokens: 8192, thinking: 'disabled', stream: false },
  phaseLimits: { GENERATION: 8, PLAN: 0, EXECUTION: 0, REVIEW: 0 }, cases: planBundle.cases, jobs,
  preparationArtifacts: ['registry52-plan/closure.json', 'registry52-preparation/operator-selections/P02_CONFIRMED.json', 'registry52-preparation/operator-selections/P02_UNKNOWN.json'],
  relatedTwoModelStageMaximum: 16, noMetadataRelabeling: true, fullUiAnswerFlowChecked: false };
await save(resolve(here, 'completion-bundle.json'), bundle);
console.log(JSON.stringify({ prepared: true, actualModelCalls: 0, generationMaximum: 8, selections }));
