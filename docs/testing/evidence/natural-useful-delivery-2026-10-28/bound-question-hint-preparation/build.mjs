import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

// This is a two-artifact constructor/verifier only. It never sends HTTP requests.
const output = dirname(fileURLToPath(import.meta.url));
const repo = resolve(output, '../../../../..');
const evidence = resolve(output, '..');
const field = 'boundQuestionHints';
const verifyOnly = process.argv.slice(2).join(' ') === '--verify';
assert.ok(process.argv.length === 2 || verifyOnly, 'Only --verify is supported');
const sha = value => createHash('sha256').update(value).digest('hex');
const jsonBytes = value => Buffer.from(JSON.stringify(value, null, 2) + '\n');
const repoPath = path => relative(repo, path).replaceAll('\\', '/');
const readJson = async path => JSON.parse(await readFile(path, 'utf8'));
const specs = [
  { state: 'CONFIRMED', trace: '001', traceSha: 'c854f59468e241fe0faa1b0e60f6300d5623b9ae36b116d7f7a541bcf55cb84c', planSha: '45691af2d9f53c948a67f2447c3b23d0bef108b1474f53d3583b3496488efbca' },
  { state: 'UNKNOWN', trace: '003', traceSha: '0c3554a113cd4ed02aa5cd01b531dffa41c8db2d9dea5c25be1cd645df39ed22', planSha: 'cd330346145ee99f4b9166ff0cf3e56a861f6795f7c18b241a3a63f3ceec4322' },
];

const cases = [], jobs = [], provenance = [], proofs = [];
if (!verifyOnly) await mkdir(resolve(output, 'requests'), { recursive: true });
for (const spec of specs) {
  const tracePath = resolve(evidence, 'registry52-plan-completion/traces', spec.trace + '.json');
  const planPath = resolve(evidence, 'registry52-plan/results', `P02_${spec.state}_V52_PLAN.json`);
  const traceBytes = await readFile(tracePath), planBytes = await readFile(planPath);
  assert.equal(sha(traceBytes), spec.traceSha, 'Closed prepare trace drift');
  assert.equal(sha(planBytes), spec.planSha, 'Closed Plan result drift');
  for (const [stage, path, expected] of [['registry52-plan-completion', tracePath, spec.traceSha], ['registry52-plan', planPath, spec.planSha]]) {
    const closure = await readJson(resolve(evidence, stage, 'closure.json'));
    assert.equal(closure.status, 'CLOSED');
    assert.equal(closure.files.find(file => file.path === repoPath(path))?.sha256, expected, 'Source is not in its closed manifest');
  }
  const trace = JSON.parse(traceBytes), plan = JSON.parse(planBytes);
  assert.equal(trace.registry49.callStage, 'REQUIREMENT_PREPARATION');
  assert.equal(plan.status, 200);
  const original = trace.request;
  assert.deepEqual(original.messages.map(message => message.role), ['system', 'user']);
  const user = original.messages[1].content;
  const start = user.indexOf('{');
  assert.ok(start > 0 && user.endsWith('}'));
  const input = JSON.parse(user.slice(start));
  assert.equal(input.strategyVersion, '52');
  assert.equal(Object.hasOwn(input, field), false);
  assert.ok(input.actualAnswers.length > 0);
  const hints = input.actualAnswers.map((answer, index) => {
    const matching = plan.body.data.questions.filter(question => question.id === answer.questionId);
    assert.equal(matching.length, 1, 'Actual answer must match one real Plan question');
    const question = matching[0];
    assert.equal(question.question, answer.question, 'Canonical question changed');
    assert.equal(typeof question.hint, 'string');
    assert.ok(question.hint.trim().length > 0 && question.hint.length <= 1500);
    const sourceId = `actualAnswers.${index}`;
    assert.ok(input.taskSources.some(source => source.id === sourceId && source.kind === 'BOUND_PLAN_ANSWER'));
    return { questionId: answer.questionId, sourceId, hint: question.hint };
  });
  // Append one JSON member without reserializing any existing user-message bytes.
  const suffix = ',' + JSON.stringify(field) + ':' + JSON.stringify(hints) + '}';
  const request = structuredClone(original);
  request.messages[1].content = user.slice(0, -1) + suffix;
  const augmentedInput = JSON.parse(request.messages[1].content.slice(start));
  assert.deepEqual(augmentedInput[field], hints);
  delete augmentedInput[field];
  assert.deepEqual(augmentedInput, input);
  assert.deepEqual(Object.keys(augmentedInput), Object.keys(input));
  const restored = structuredClone(request);
  assert.ok(restored.messages[1].content.endsWith(suffix));
  restored.messages[1].content = restored.messages[1].content.slice(0, -suffix.length) + '}';
  assert.equal(restored.messages[1].content, user);
  assert.deepEqual(restored, original);
  assert.equal(JSON.stringify(restored), JSON.stringify(original));
  assert.equal(request.model, 'deepseek-flash');
  assert.equal(request.temperature, 0.2);
  assert.equal(request.max_tokens, 8192);
  assert.equal(request.stream, false);
  assert.deepEqual(request.thinking, { type: 'disabled' });
  assert.deepEqual(request.response_format, { type: 'json_object' });
  const requestPath = resolve(output, 'requests', `${spec.state}.request.json`);
  const requestBytes = jsonBytes(request);
  if (verifyOnly) assert.deepEqual(await readFile(requestPath), requestBytes, 'Prepared request drift');
  else await writeFile(requestPath, requestBytes, { flag: 'wx' });
  const caseId = `P02_${spec.state}`;
  const jobId = `${caseId}_V52_BOUND_HINT_PREPARE`;
  cases.push({ id: caseId, rawPrompt: input.rawPrompt, executorMaterials: [] });
  jobs.push({ id: jobId, caseId, phase: 'EXECUTION', version: '52', purpose: 'MECHANISM_ONLY_BOUND_QUESTION_HINT_PREPARE', approvedModelRequest: request });
  provenance.push({ jobId, requestPath: repoPath(requestPath), requestSha256: sha(requestBytes), prepareTrace: { path: repoPath(tracePath), sha256: spec.traceSha }, planResult: { path: repoPath(planPath), sha256: spec.planSha }, hintPointers: hints.map((hint, index) => ({ sourceId: hint.sourceId, questionId: hint.questionId, pointer: `/body/data/questions/${plan.body.data.questions.findIndex(question => question.id === hint.questionId)}/hint`, hintUtf8Sha256: sha(hint.hint), actualAnswerPointer: `/request/messages/1/content JSON.actualAnswers/${index}` })) });
  proofs.push({ jobId, addedUserJsonMember: field, sourceHintsExact: true, sourceIdsAlreadyPresent: true, existingUserBytesRestoredExactly: true, existingJsonFieldsAndOrderEqual: true, restoredCapturedRequestWholeObjectEqual: true, restoredCapturedRequestJsonBytesEqual: true, messagesAndSystemUnchanged: true, parametersUnchanged: true, originalUserSha256: sha(user), restoredUserSha256: sha(restored.messages[1].content), originalCapturedRequestCompactJsonSha256: sha(JSON.stringify(original)), restoredCapturedRequestCompactJsonSha256: sha(JSON.stringify(restored)), systemSha256: sha(original.messages[0].content), requestSha256: sha(requestBytes) });
}
const artifacts = {
  'bundle.json': { syntheticOnly: true, cases, jobs, parameters: { executionMaxTokens: 8192 }, phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 2, REVIEW: 0 } },
  'provenance.json': { externalModelCalls: 0, strategyVersion: '52', productCandidate53Created: false, changedInformation: 'Only the actual stored PlanQuestion.hint for each actual bound answer; no scope projection or fabricated analysis', authorityBoundary: 'Hints remain Plan-time model analysis; not new taskSources, facts, selected options, answers or permissions. All original actualAnswers remain byte-identical.', sources: provenance },
  'request-equivalence-proof.json': { externalModelCalls: 0, exactnessBoundary: 'Byte equality is proved for the complete captured user-message string and the captured ModelChatRequest JSON serialized in its recorded property order. Historical outer HTTP whitespace was not retained by traces and is not claimed.', proofs },
};
for (const [name, value] of Object.entries(artifacts)) {
  const bytes = jsonBytes(value), path = resolve(output, name);
  if (verifyOnly) assert.deepEqual(await readFile(path), bytes, `${name} drift`);
  else await writeFile(path, bytes, { flag: 'wx' });
}
console.log(JSON.stringify({ mode: verifyOnly ? 'VERIFIED_OFFLINE' : 'PREPARED_ONLY', externalModelCalls: 0, jobs: jobs.map(job => job.id), artifacts: Object.keys(artifacts) }));
