import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { here, json, hash } from './browser-support.mjs';

function future(value, now, label) {
  assert.equal(typeof value, 'string', `${label} is required`);
  assert.ok(Number.isFinite(Date.parse(value)) && Date.parse(value) > now, `${label} expired; capture a new real context/Plan rather than rebinding`);
}

/**
 * Constructs the service-protocol DTO from explicitly supplied real artifacts.
 * It does not claim the operator clicked the full UI, authenticate a user itself,
 * register a Plan, invent actualAnswers, or change any context/Plan ownership.
 */
export function buildPlanCompletion({ prepareInput, prepareResponse, planInput, captureVerdict,
  planResult, selection, baseInput }, now = Date.now()) {
  assert.equal(captureVerdict.realAuthentication, true);
  assert.equal(captureVerdict.realDeterministicPreparation, true);
  assert.equal(captureVerdict.planBlockedBeforeModel, true);
  assert.equal(captureVerdict.actualModelCalls, 0);
  assert.equal(captureVerdict.indexedFiles, 79);
  assert.ok(Number.isFinite(Date.parse(captureVerdict.capturedAt)));
  assert.equal(prepareResponse.status, 200);
  const prepared = prepareResponse.body.data;
  assert.ok(prepared.contextId && prepared.version);
  future(prepared.expiresAt, now, 'Actual preparation');
  assert.equal(captureVerdict.contextExpiresAt, prepared.expiresAt);
  const reference = { contextId: prepared.contextId, version: prepared.version };
  assert.ok(isDeepStrictEqual(planInput.planningContext, reference));
  assert.equal(prepareInput.rawPrompt, planInput.rawPrompt);
  assert.equal(planInput.modelId, 'deepseek:deepseek-flash');
  assert.equal(planResult.status, 200);
  assert.equal(planResult.phase, 'PLAN'); assert.equal(planResult.version, '50');
  const plan = planResult.body.data;
  assert.ok(plan.planId); assert.equal(plan.provider.mock, false);
  assert.ok(isDeepStrictEqual(plan.planningContext, reference), 'Actual Plan belongs to a different context; never silently rebind');
  future(plan.expiresAt, now, 'Actual Plan');
  assert.ok(Array.isArray(plan.questions));
  assert.equal(selection.kind, 'EXPLICIT_OPERATOR_SELECTION');
  assert.equal(selection.planId, plan.planId);
  const selectedAt = Date.parse(selection.selectedAt);
  assert.ok(Number.isFinite(selectedAt) && selectedAt >= Date.parse(captureVerdict.capturedAt) && selectedAt <= now,
    'Operator selection must belong to this current real capture and cannot be future dated');
  assert.ok(Array.isArray(selection.answers));
  assert.equal(selection.answers.length, plan.questions.length, 'Every actual question must have its own explicit selection');
  const questionIds = new Set(), answerIds = new Set();
  for (const answer of selection.answers) { assert.ok(!answerIds.has(answer.questionId)); answerIds.add(answer.questionId); }
  const answers = plan.questions.map(question => {
    assert.ok(question.id && !questionIds.has(question.id)); questionIds.add(question.id);
    const chosen = selection.answers.find(answer => answer.questionId === question.id);
    assert.ok(chosen, 'Selection references a different actual question');
    assert.equal(chosen.question, question.question, 'Question text must come from the real registered Plan');
    assert.ok(Array.isArray(chosen.optionIds));
    assert.equal(new Set(chosen.optionIds).size, chosen.optionIds.length);
    assert.ok(Array.isArray(question.options));
    const optionIds = new Set(question.options.map(option => option.id));
    for (const id of chosen.optionIds) assert.ok(optionIds.has(id), 'Selected option does not exist in this actual Plan');
    assert.equal(typeof chosen.customAnswer, 'string');
    const custom = chosen.customAnswer.trim();
    assert.ok(!custom || question.allowCustomAnswer, 'Actual question does not permit custom answers');
    assert.ok(!(custom && chosen.optionIds.length), 'UI custom answer and option selection are mutually exclusive');
    if (question.type === 'SINGLE_CHOICE') assert.ok(chosen.optionIds.length <= 1);
    else if (question.type === 'FREE_TEXT') assert.equal(chosen.optionIds.length, 0);
    else assert.equal(question.type, 'MULTIPLE_CHOICE');
    // Match PlanQuestionDialog.toAnswer: use actual Plan option order, not a fixed
    // ID, label, recommendation or the order supplied in the selection file.
    const parts = question.options.filter(option => chosen.optionIds.includes(option.id)).map(option => option.answer.trim());
    if (custom) parts.push(custom);
    const answer = parts.join('；');
    assert.ok(answer.length > 0 && answer.length <= 1500);
    assert.equal(chosen.answer, answer, 'Recorded operator answer differs from actual selected option meaning');
    return { questionId: question.id, question: question.question, answer };
  });
  const allowed = ['rawPrompt', 'modelId', 'context', 'enhancement', 'conversationHistory', 'permissionPolicy', 'planConfirmation'];
  assert.ok(Object.keys(baseInput).every(key => allowed.includes(key)), 'Unexpected generation DTO field');
  assert.ok(baseInput.planConfirmation == null, 'Base input must not carry any previous Plan');
  assert.equal(baseInput.rawPrompt, planInput.rawPrompt);
  assert.equal(baseInput.modelId, planInput.modelId);
  assert.equal(baseInput.context.customDescription, planInput.contextDescription);
  assert.ok(isDeepStrictEqual(baseInput.conversationHistory, planInput.conversationHistory));
  assert.equal(baseInput.enhancement.promptFormat, 'COMPACT');
  // Final retrieval can add relevant snippets. It cannot silently drop any of
  // the actual preparation materials when building this service-only request.
  for (const file of prepareInput.context.files) {
    assert.ok(baseInput.context.files.some(candidate => isDeepStrictEqual(candidate, file)), 'A prepared source was dropped or changed');
  }
  const input = structuredClone(baseInput);
  input.planConfirmation = { planId: plan.planId, planningContext: reference, answers };
  assert.ok(!Object.hasOwn(input, 'actualAnswers'));
  return { input, provenance: {
    version: '50', phase: 'GENERATION', construction: 'SERVICE_PROTOCOL_FROM_REAL_CONTEXT_PLAN_AND_EXPLICIT_OPERATOR_SELECTION',
    captureStage: captureVerdict.stage, planJobId: planResult.id, planId: plan.planId,
    contextExpiresAt: prepared.expiresAt, planExpiresAt: plan.expiresAt,
    answersFromActualPlanAndExplicitSelection: true, recommendationAutoSelected: false,
    actualUiFinalGenerationCaptured: false, realFinalServerOwnershipValidationPerformed: false,
    actualModelCalls: 0, outputRequiresFreshFreezeAndLiveValidation: true,
  } };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.includes('--help')) {
    console.log('node build-plan-completion.mjs --capture-dir <new-registry50-capture> --plan-result <this-real-PLAN-result> --selection <explicit-operator-selection.json> --base-input <same-prompt-generation-DTO> --out <new-preparation-input.json>. Offline only; does not freeze/start/call.');
    process.exit(0);
  }
  const args = {};
  for (let index = 2; index < process.argv.length; index += 2) {
    assert.ok(['--capture-dir', '--plan-result', '--selection', '--base-input', '--out'].includes(process.argv[index]));
    assert.ok(process.argv[index + 1]); assert.ok(!Object.hasOwn(args, process.argv[index]));
    args[process.argv[index]] = resolve(process.argv[index + 1]);
  }
  for (const name of ['--capture-dir', '--plan-result', '--selection', '--base-input', '--out']) assert.ok(args[name]);
  const captureDirectory = args['--capture-dir'];
  assert.ok(captureDirectory.startsWith(resolve(here, 'plan-captures') + sep), 'Use a fresh capture from this preparation tool, not an old-stage Plan reference');
  assert.ok(args['--out'].startsWith(here + sep), 'Preparation output must stay in this new directory');
  const files = { prepareInput: resolve(captureDirectory, 'prepare-input.json'), prepareResponse: resolve(captureDirectory, 'prepare-response.json'),
    planInput: resolve(captureDirectory, 'plan-input.json'), captureVerdict: resolve(captureDirectory, 'verdict.json'),
    planResult: args['--plan-result'], selection: args['--selection'], baseInput: args['--base-input'] };
  const payload = {}, artifactHashes = {};
  for (const [name, file] of Object.entries(files)) {
    const bytes = await readFile(file); payload[name] = JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
    artifactHashes[name] = hash(bytes);
  }
  const result = buildPlanCompletion(payload);
  await mkdir(dirname(args['--out']), { recursive: true });
  await writeFile(args['--out'], JSON.stringify(result.input, null, 2) + '\n', { flag: 'wx' });
  await writeFile(args['--out'].replace(/\.json$/, '') + '.provenance.json', JSON.stringify({ ...result.provenance,
    artifactHashes, generationDtoSha256: hash(JSON.stringify(result.input)) }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ event: 'REGISTRY50_PLAN_COMPLETION_PREPARED', modelCalls: 0,
    finalUiValidated: false, freshFreezeRequired: true }));
}
