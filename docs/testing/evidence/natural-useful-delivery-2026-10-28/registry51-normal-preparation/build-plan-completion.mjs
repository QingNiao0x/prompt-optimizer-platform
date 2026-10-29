import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { here, json } from './browser-support.mjs';

/** Explicit future51 metadata contract; original response/Plan IDs are never relabeled. */
export function build51Completion(sample, capture, result, operator, base, now = Date.now()) {
  const { prepareInput, prepareResponse, planInput, verdict } = capture;
  assert.ok(['P01', 'P02'].includes(sample.id)); assert.equal(verdict.caseId, sample.id);
  assert.ok(verdict.realAuthentication && verdict.realDeterministicPreparation && verdict.planBlockedBeforeModel);
  assert.equal(verdict.actualModelCalls, 0); assert.equal(verdict.indexedFiles, 79);
  assert.equal(verdict.actualUiModelSelection.selectedModelId, 'deepseek:deepseek-flash');
  assert.equal(verdict.actualUiModelSelection.publicFlashOptionClicked, true);
  assert.equal(prepareResponse.status, 200); const prepared = prepareResponse.body.data;
  assert.ok(prepared.contextId && prepared.version && Date.parse(prepared.expiresAt) > now);
  assert.equal(verdict.contextExpiresAt, prepared.expiresAt);
  const reference = { contextId: prepared.contextId, version: prepared.version };
  assert.deepEqual(planInput.planningContext, reference);
  assert.equal(prepareInput.rawPrompt, sample.rawPrompt); assert.equal(planInput.rawPrompt, sample.rawPrompt);
  assert.equal(planInput.modelId, 'deepseek:deepseek-flash');
  assert.equal(result.version, '51'); assert.equal(result.phase, 'PLAN'); assert.equal(result.caseId, sample.id);
  assert.equal(result.id, sample.futurePlanJobId); assert.equal(result.status, 200);
  const plan = result.body.data;
  assert.equal(plan.provider.mock, false); assert.equal(plan.provider.model, 'deepseek:deepseek-flash');
  assert.ok(plan.planId && Date.parse(plan.expiresAt) > now); assert.deepEqual(plan.planningContext, reference);
  assert.ok(Array.isArray(plan.questions));
  assert.ok(sample.id === 'P01' ? plan.questions.length === 0 : plan.questions.length > 0);
  assert.equal(operator.kind, 'EXPLICIT_OPERATOR_SELECTION'); assert.equal(operator.planId, plan.planId);
  assert.ok(Date.parse(operator.selectedAt) >= Date.parse(verdict.capturedAt) && Date.parse(operator.selectedAt) <= now);
  assert.equal(operator.answers.length, plan.questions.length); const seen = new Set();
  const answers = plan.questions.map(question => {
    assert.ok(question.id && !seen.has(question.id)); seen.add(question.id);
    const matches = operator.answers.filter(answer => answer.questionId === question.id); assert.equal(matches.length, 1);
    const answer = matches[0]; assert.equal(answer.question, question.question);
    assert.ok(Array.isArray(answer.optionIds) && new Set(answer.optionIds).size === answer.optionIds.length);
    for (const id of answer.optionIds) assert.ok(question.options.some(option => option.id === id));
    assert.equal(typeof answer.customAnswer, 'string'); const custom = answer.customAnswer.trim();
    assert.ok(!(custom && answer.optionIds.length)); assert.ok(!custom || question.allowCustomAnswer);
    if (question.type === 'SINGLE_CHOICE') assert.ok(answer.optionIds.length <= 1);
    else if (question.type === 'FREE_TEXT') assert.equal(answer.optionIds.length, 0);
    else assert.equal(question.type, 'MULTIPLE_CHOICE');
    const parts = question.options.filter(option => answer.optionIds.includes(option.id)).map(option => option.answer.trim());
    if (custom) parts.push(custom); const text = parts.join('；'); assert.equal(answer.answer, text); assert.ok(text.length > 0 && text.length <= 1500);
    return { questionId: question.id, question: question.question, answer: text };
  });
  assert.equal(base.rawPrompt, sample.rawPrompt); assert.equal(base.modelId, 'deepseek:deepseek-flash');
  assert.ok(base.planConfirmation == null && !Object.hasOwn(base, 'actualAnswers'));
  assert.equal(base.enhancement.promptFormat, 'COMPACT'); assert.equal(base.context.customDescription, planInput.contextDescription);
  assert.deepEqual(base.conversationHistory, planInput.conversationHistory);
  if (Object.hasOwn(prepareInput, 'permissionPolicy')) assert.deepEqual(base.permissionPolicy, prepareInput.permissionPolicy);
  for (const file of prepareInput.context.files) assert.ok(base.context.files.some(actual => isDeepStrictEqual(actual, file)));
  const input = structuredClone(base); input.planConfirmation = { planId: plan.planId, planningContext: reference, answers };
  return { id: sample.futureGenerationJobId, caseId: sample.id, phase: 'GENERATION', version: '51',
    approvedEnhancementInput: input, purpose: 'FLASH51_ACTUAL_PLAN_COMPLETION' };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.includes('--help')) { console.log('node build-plan-completion.mjs P01|P02 <fresh-capture-dir> <actual-51-Plan-result> <explicit-selection> <same-Flash-input-base> <new-output-job>. Offline only.'); process.exit(0); }
  const [id, dir, resultFile, selectionFile, baseFile, outputFile] = process.argv.slice(2);
  assert.ok(['P01', 'P02'].includes(id) && dir && resultFile && selectionFile && baseFile && outputFile);
  assert.ok(resolve(dir).startsWith(resolve(here, 'plan-captures') + sep) && resolve(outputFile).startsWith(here + sep));
  const capture = {};
  for (const [key, name] of Object.entries({ prepareInput: 'prepare-input.json', prepareResponse: 'prepare-response.json', planInput: 'plan-input.json', verdict: 'verdict.json' })) capture[key] = await json(resolve(dir, name));
  const sample = (await json(resolve(here, 'plan-cases.json'))).cases.find(item => item.id === id);
  const job = build51Completion(sample, capture, await json(resolve(resultFile)), await json(resolve(selectionFile)), await json(resolve(baseFile)));
  await mkdir(dirname(resolve(outputFile)), { recursive: true });
  await writeFile(resolve(outputFile), JSON.stringify(job, null, 2) + '\n', { flag: 'wx' });
  await writeFile(resolve(outputFile) + '.provenance.json', JSON.stringify({ originalResultVersion: '51', metadataRelabeled: false,
    actualModelCalls: 0, serviceProtocolOnly: true, fullUiAnswerFlowChecked: false, actualOwnershipValidationPending: true, rootFreezeRequired: true }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ preparedJob: job.id, actualModelCalls: 0, metadataRelabeled: false }));
}
