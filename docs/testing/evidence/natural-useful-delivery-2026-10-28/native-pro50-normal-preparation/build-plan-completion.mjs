import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { here, modelId, json, sha } from './support.mjs';

/** Finite Pro service DTO constructor, not a claim of full UI Plan-answer coverage. */
export function buildCompletion(sample, capture, planResult, operator, base, now = Date.now()) {
  const { prepareInput, prepareResponse, planInput, verdict } = capture;
  assert.ok(['P01', 'P02'].includes(sample.id));
  assert.equal(verdict.caseId, sample.id); assert.equal(verdict.realAuthentication, true);
  assert.equal(verdict.realDeterministicPreparation, true); assert.equal(verdict.planBlockedBeforeModel, true);
  assert.equal(verdict.actualModelCalls, 0); assert.equal(verdict.indexedFiles, 79);
  assert.equal(verdict.actualUiModelSelection.uiOptionClicked, true); assert.equal(verdict.actualUiModelSelection.selectedModelId, modelId);
  assert.equal(prepareResponse.status, 200); const prepared = prepareResponse.body.data;
  assert.ok(Date.parse(prepared.expiresAt) > now); assert.equal(verdict.contextExpiresAt, prepared.expiresAt);
  const reference = { contextId: prepared.contextId, version: prepared.version };
  assert.ok(reference.contextId && reference.version); assert.deepEqual(planInput.planningContext, reference);
  assert.equal(prepareInput.rawPrompt, sample.rawPrompt); assert.equal(planInput.rawPrompt, sample.rawPrompt); assert.equal(planInput.modelId, modelId);
  assert.equal(planResult.phase, 'PLAN'); assert.equal(planResult.version, '50'); assert.equal(planResult.caseId, sample.id);
  assert.equal(planResult.id, sample.futurePlanJobId); assert.equal(planResult.status, 200);
  const plan = planResult.body.data;
  assert.equal(plan.provider.mock, false); assert.equal(plan.provider.model, modelId);
  assert.ok(plan.planId && Date.parse(plan.expiresAt) > now); assert.deepEqual(plan.planningContext, reference);
  assert.ok(Array.isArray(plan.questions));
  if (sample.id === 'P01') assert.equal(plan.questions.length, 0, 'P01 must actually return zero questions; do not relabel another path');
  else assert.ok(plan.questions.length > 0, 'P02 must actually ask the important reserved unknown');
  assert.equal(operator.kind, 'EXPLICIT_OPERATOR_SELECTION'); assert.equal(operator.planId, plan.planId);
  assert.ok(Date.parse(operator.selectedAt) >= Date.parse(verdict.capturedAt) && Date.parse(operator.selectedAt) <= now);
  assert.equal(operator.answers.length, plan.questions.length); const seen = new Set();
  const answers = plan.questions.map(question => {
    assert.ok(question.id && !seen.has(question.id)); seen.add(question.id);
    const matches = operator.answers.filter(answer => answer.questionId === question.id); assert.equal(matches.length, 1);
    const chosen = matches[0]; assert.equal(chosen.question, question.question);
    assert.ok(Array.isArray(chosen.optionIds) && new Set(chosen.optionIds).size === chosen.optionIds.length);
    assert.equal(typeof chosen.customAnswer, 'string'); const custom = chosen.customAnswer.trim();
    const options = question.options; assert.ok(Array.isArray(options));
    for (const id of chosen.optionIds) assert.ok(options.some(option => option.id === id));
    assert.ok(!(custom && chosen.optionIds.length)); assert.ok(!custom || question.allowCustomAnswer);
    if (question.type === 'SINGLE_CHOICE') assert.ok(chosen.optionIds.length <= 1);
    else if (question.type === 'FREE_TEXT') assert.equal(chosen.optionIds.length, 0);
    else assert.equal(question.type, 'MULTIPLE_CHOICE');
    const parts = options.filter(option => chosen.optionIds.includes(option.id)).map(option => option.answer.trim());
    if (custom) parts.push(custom); const answer = parts.join('；');
    assert.ok(answer.length > 0 && answer.length <= 1500); assert.equal(chosen.answer, answer);
    return { questionId: question.id, question: question.question, answer };
  });
  assert.equal(base.rawPrompt, sample.rawPrompt); assert.equal(base.modelId, modelId); assert.ok(base.planConfirmation == null);
  assert.ok(Object.keys(base).every(key => ['rawPrompt', 'modelId', 'context', 'enhancement', 'conversationHistory', 'permissionPolicy', 'planConfirmation'].includes(key)));
  assert.ok(!Object.hasOwn(base, 'actualAnswers')); assert.equal(base.enhancement.promptFormat, 'COMPACT');
  assert.equal(base.context.customDescription, planInput.contextDescription); assert.deepEqual(base.conversationHistory, planInput.conversationHistory);
  if (Object.hasOwn(prepareInput, 'permissionPolicy')) assert.deepEqual(base.permissionPolicy, prepareInput.permissionPolicy);
  for (const source of prepareInput.context.files) assert.ok(base.context.files.some(file => isDeepStrictEqual(file, source)), 'Prepared material cannot be dropped');
  const input = structuredClone(base); input.planConfirmation = { planId: plan.planId, planningContext: reference, answers };
  return { id: sample.futureGenerationJobId, caseId: sample.id, phase: 'GENERATION', version: '50',
    approvedEnhancementInput: input, purpose: 'NATIVE_PRO50_ACTUAL_PLAN_COMPLETION' };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.includes('--help')) {
    console.log('node build-plan-completion.mjs P01|P02 <fresh-capture-directory> <actual-plan-result.json> <explicit-operator-selection.json> <same-input-base.json> <new-output-job.json>. Offline only; no freeze/call.'); process.exit(0);
  }
  const [id, captureDir, planFile, operatorFile, baseFile, outputFile] = process.argv.slice(2);
  assert.ok(['P01', 'P02'].includes(id) && captureDir && planFile && operatorFile && baseFile && outputFile);
  assert.ok(resolve(captureDir).startsWith(resolve(here, 'plan-captures') + sep)); assert.ok(resolve(outputFile).startsWith(here + sep));
  const capture = {};
  for (const [name, file] of Object.entries({ prepareInput: 'prepare-input.json', prepareResponse: 'prepare-response.json', planInput: 'plan-input.json', verdict: 'verdict.json' })) capture[name] = await json(resolve(captureDir, file));
  const sample = (await json(resolve(here, 'plan-cases.json'))).cases.find(item => item.id === id);
  const job = buildCompletion(sample, capture, await json(resolve(planFile)), await json(resolve(operatorFile)), await json(resolve(baseFile)));
  await mkdir(dirname(resolve(outputFile)), { recursive: true });
  await writeFile(resolve(outputFile), JSON.stringify(job, null, 2) + '\n', { flag: 'wx' });
  await writeFile(resolve(outputFile) + '.provenance.json', JSON.stringify({ actualModelCalls: 0, serviceProtocolOnly: true,
    fullUiPlanAnswerFlowChecked: false, finalOwnershipValidationPending: true, stageFreezeRequired: true,
    sourceHashes: { actualPlan: sha(await readFile(resolve(planFile))), explicitSelection: sha(await readFile(resolve(operatorFile))), base: sha(await readFile(resolve(baseFile))) } }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ preparedJob: job.id, actualModelCalls: 0, fullUiPlanAnswerFlowChecked: false }));
}
