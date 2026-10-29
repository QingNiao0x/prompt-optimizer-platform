import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { here, json, hash, loadCases, branchIds } from './browser-support.mjs';

/** Check a fresh real UI context without inventing a server identity or extending either expiration. */
export function validateCapture61(sample, capture, now = Date.now()) {
  const { prepareInput, prepareResponse, planInput, verdict } = capture;
  assert.equal(verdict.strategyVersion, '61'); assert.ok(branchIds.includes(sample.id)); assert.equal(verdict.caseId, sample.id);
  assert.ok(verdict.realAuthentication && verdict.realDeterministicPreparation && verdict.planBlockedBeforeModel);
  assert.equal(verdict.actualModelCalls, 0); assert.equal(verdict.indexedFiles, 79);
  assert.match(verdict.actorScopeSha256, /^[a-f0-9]{64}$/);
  assert.ok(verdict.actualUiModelSelection.publicFlashOptionClicked);
  assert.equal(verdict.actualUiModelSelection.selectedModelId, 'deepseek:deepseek-flash');
  assert.equal(prepareResponse.status, 200); const prepared = prepareResponse.body.data;
  assert.ok(prepared.contextId && prepared.version && Date.parse(prepared.expiresAt) > now);
  assert.ok(Date.parse(verdict.capturedAt) <= now); assert.equal(verdict.contextExpiresAt, prepared.expiresAt);
  const reference = { contextId: prepared.contextId, version: prepared.version };
  assert.deepEqual(planInput.planningContext, reference);
  assert.equal(prepareInput.rawPrompt, sample.rawPrompt); assert.equal(planInput.rawPrompt, sample.rawPrompt);
  assert.equal(planInput.modelId, 'deepseek:deepseek-flash');
  assert.equal(planInput.contextDescription, prepareInput.context.customDescription);
  assert.ok(prepareInput.context.files.length > 0 && Array.isArray(planInput.conversationHistory));
  return reference;
}

/** Two independent preparations must represent the same original request and fixed actor, never a reused context. */
export function validateIndependentCaptures(samples, captures, now = Date.now()) {
  assert.deepEqual(samples.map(item => item.id).sort(), [...branchIds].sort());
  const refs = samples.map((sample, index) => validateCapture61(sample, captures[index], now));
  assert.notEqual(refs[0].contextId, refs[1].contextId);
  assert.equal(samples[0].rawPrompt, samples[1].rawPrompt);
  assert.equal(captures[0].verdict.actorScopeSha256, captures[1].verdict.actorScopeSha256);
  assert.deepEqual(captures[0].prepareInput, captures[1].prepareInput);
}

/** Use actual Plan questions and an explicit operator record; the UNKNOWN branch cannot fabricate a choice. */
export function build61Completion(sample, capture, result, operator, base, now = Date.now()) {
  const reference = validateCapture61(sample, capture, now), { prepareInput, planInput, verdict } = capture;
  assert.equal(result.version, '61'); assert.equal(result.phase, 'PLAN'); assert.equal(result.caseId, sample.id);
  assert.equal(result.id, sample.futurePlanJobId); assert.equal(result.status, 200);
  const plan = result.body.data;
  if (Object.hasOwn(plan, 'strategyVersion')) assert.equal(plan.strategyVersion, '61');
  assert.equal(plan.provider.mock, false); assert.equal(plan.provider.model, 'deepseek:deepseek-flash');
  assert.ok(plan.planId && Date.parse(plan.expiresAt) > now); assert.deepEqual(plan.planningContext, reference);
  assert.ok(Array.isArray(plan.questions) && plan.questions.length > 0);
  assert.equal(operator.kind, 'EXPLICIT_OPERATOR_SELECTION'); assert.equal(operator.caseId, sample.id);
  assert.equal(operator.planId, plan.planId); assert.deepEqual(operator.planningContext, reference);
  assert.equal(operator.actorScopeSha256, verdict.actorScopeSha256);
  assert.ok(Date.parse(operator.selectedAt) >= Date.parse(verdict.capturedAt) && Date.parse(operator.selectedAt) <= now);
  assert.equal(operator.answers.length, plan.questions.length); const seen = new Set();
  const answers = plan.questions.map(question => {
    assert.ok(question.id && !seen.has(question.id)); seen.add(question.id);
    assert.ok(Array.isArray(question.options));
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
    if (custom) parts.push(custom); const text = parts.join('；');
    assert.equal(answer.answer, text); assert.ok(text.length > 0 && text.length <= 1500);
    return { questionId: question.id, question: question.question, answer: text };
  });
  assert.ok(seen.has(operator.decisionQuestionId));
  const decision = operator.answers.find(answer => answer.questionId === operator.decisionQuestionId);
  if (sample.id === 'P02_UNKNOWN') {
    assert.equal(decision.customAnswer, '今天是否允许尚未决定'); assert.deepEqual(decision.optionIds, []);
  } else {
    assert.notEqual(decision.answer, '今天是否允许尚未决定');
  }
  assert.equal(base.rawPrompt, sample.rawPrompt); assert.equal(base.modelId, 'deepseek:deepseek-flash');
  assert.ok(base.planConfirmation == null && !Object.hasOwn(base, 'actualAnswers'));
  assert.equal(base.enhancement.promptFormat, 'COMPACT'); assert.equal(base.context.customDescription, planInput.contextDescription);
  assert.deepEqual(base.context.files, prepareInput.context.files); assert.deepEqual(base.conversationHistory, planInput.conversationHistory);
  if (Object.hasOwn(prepareInput, 'permissionPolicy')) assert.deepEqual(base.permissionPolicy, prepareInput.permissionPolicy);
  const input = structuredClone(base); input.planConfirmation = { planId: plan.planId, planningContext: reference, answers };
  return { id: sample.futureGenerationJobId, caseId: sample.id, phase: 'GENERATION', version: '61',
    approvedEnhancementInput: input, purpose: 'FLASH61_REAL_PLAN_' + sample.id.slice(4) };
}

export async function readCapture(dir) {
  const capture = {};
  for (const [key, name] of Object.entries({ prepareInput: 'prepare-input.json', prepareResponse: 'prepare-response.json', planInput: 'plan-input.json', verdict: 'verdict.json' })) capture[key] = await json(resolve(dir, name));
  return capture;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.includes('--help')) { console.log('node build-plan-completion.mjs P02_CONFIRMED|P02_UNKNOWN <fresh-capture-dir> <actual61-Plan-result> <explicit-selection> <same-full-input-base> <new-output-job>. Offline only; no relabeling, no synthetic answer.'); process.exit(0); }
  const [id, dir, resultFile, selectionFile, baseFile, outputFile] = process.argv.slice(2);
  assert.ok(branchIds.includes(id) && dir && resultFile && selectionFile && baseFile && outputFile);
  assert.ok(resolve(dir).startsWith(resolve(here, 'plan-captures') + sep) && resolve(outputFile).startsWith(here + sep));
  const sample = (await loadCases()).find(item => item.id === id), capture = await readCapture(dir);
  const job = build61Completion(sample, capture, await json(resolve(resultFile)), await json(resolve(selectionFile)), await json(resolve(baseFile)));
  await mkdir(dirname(resolve(outputFile)), { recursive: true });
  await writeFile(resolve(outputFile), JSON.stringify(job, null, 2) + '\n', { flag: 'wx' });
  await writeFile(resolve(outputFile) + '.provenance.json', JSON.stringify({ originalResultVersion: '61', metadataRelabeled: false,
    captureSha256: hash(JSON.stringify(capture)), actualModelCalls: 0, serviceProtocolOnly: true, fullUiAnswerFlowChecked: false,
    actualOwnershipValidationPending: true, rootFreezeRequired: true }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ preparedJob: job.id, actualModelCalls: 0, metadataRelabeled: false }));
}
