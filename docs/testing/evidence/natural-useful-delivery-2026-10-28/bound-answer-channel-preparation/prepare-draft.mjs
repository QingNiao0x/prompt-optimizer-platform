import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Create reviewable requests only. No freeze, HTTP, service, model or product change is performed here.
const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const sourceStage = 'registry52-plan-completion', sourceRoot = resolve(evidence, sourceStage);
const closure = await json(resolve(sourceRoot, 'closure.json')); assert.equal(closure.status, 'CLOSED'); assert.ok(closure.entryLockedByClosure);
const jobs = [], cases = [], provenance = [];
for (const [id, number] of [['P02_CONFIRMED', '001'], ['P02_UNKNOWN', '003']]) {
  const relativeTrace = sourceStage + '/traces/' + number + '.json';
  const bytes = await readFile(resolve(evidence, relativeTrace));
  const frozen = closure.files.find(item => item.path.replaceAll('\\', '/').endsWith('/' + relativeTrace));
  assert.equal(hash(bytes), frozen?.sha256);
  const trace = JSON.parse(bytes), original = trace.request;
  assert.equal(trace.registry49.callStage, 'REQUIREMENT_PREPARATION'); assert.equal(original.messages.length, 2);
  const user = original.messages[1].content, input = JSON.parse(user.slice(user.indexOf('{')));
  assert.equal(input.strategyVersion, '52'); assert.ok(input.actualAnswers.length > 0);
  assert.equal(original.model, 'deepseek-flash'); assert.equal(original.temperature, 0.2); assert.equal(original.max_tokens, 8192);
  assert.deepEqual(original.thinking, { type: 'disabled' }); assert.deepEqual(original.response_format, { type: 'json_object' }); assert.equal(original.stream, false);
  const data = { boundPlanAnswers: input.actualAnswers.map((answer, index) => {
    const source = input.taskSources.find(item => item.id === 'actualAnswers.' + index);
    assert.equal(source?.kind, 'BOUND_PLAN_ANSWER');
    assert.deepEqual(Object.keys(answer).sort(), ['questionId', 'question', 'answer'].sort());
    return { sourceId: source.id, questionId: answer.questionId, question: answer.question, answer: answer.answer };
  }) };
  const approved = structuredClone(original); approved.messages.push({ role: 'user', content: JSON.stringify(data) });
  const restored = structuredClone(approved); const appended = restored.messages.pop(); assert.deepEqual(restored, original);
  assert.equal(appended.role, 'user'); assert.deepEqual(JSON.parse(appended.content), data);
  cases.push({ id, rawPrompt: input.rawPrompt, executorMaterials: [],
    origin: 'Mechanism-only immutable52 original request replay; old Plan TTL/ownership is not renewed or claimed.' });
  jobs.push({ id: id + '_V52_QA_CHANNEL_PREPARE_EXEC', caseId: id, phase: 'EXECUTION', version: '52',
    purpose: 'MECHANISM_ONLY_LAST_USER_BOUND_ANSWER_DATA_CHANNEL_PREPARATION_NOT_DOWNSTREAM_WORK', approvedModelRequest: approved });
  provenance.push({ caseId: id, sourceTrace: relativeTrace, sourceTraceSha256: hash(bytes),
    sourceRequestCanonicalObjectSha256: hash(JSON.stringify(original)), approvedRequestCanonicalObjectSha256: hash(JSON.stringify(approved)),
    appendedMessage: data, appendedCharacters: appended.content.length,
    sourceSystemSha256: hash(original.messages[0].content), firstTwoMessagesVerbatimUnchanged: true,
    allOtherPayloadFieldsUnchanged: true, originalCompleteInputUnchanged: true, newFactsOrInstructionsAdded: false });
}
const bundle = { syntheticOnly: true,
  description: 'Review draft only: two known52 PREPARE requests, solely append last user JSON boundPlanAnswers with original sourceId/questionId/question/answer values. No product/quality adoption claim.',
  parameters: { executionMaxTokens: 8192, temperature: 0.2 },
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 2, REVIEW: 0 }, cases, jobs,
  mechanismExperiment: 'BOUND_ANSWER_LAST_USER_DATA_CHANNEL_PREPARATION', sourceStage,
  laterWriterStage: 'Not constructed or approved here; at most2 separate EXEC after both preparation results pass strict product resolve and independent semantic review.' };
await save(resolve(here, 'prepare-bundle.draft.json'), bundle);
await save(resolve(here, 'prepare-provenance.json'), { actualModelCalls: 0, sourceClosed: true, provenance });
const report = { status: 'ZERO_CALL_REVIEW_DRAFT_NOT_FROZEN_OR_LIVE', generatedAt: new Date().toISOString(), taskActual: 177,
  jobs: jobs.length, maximumPrepareActualCalls: 2, proposedLaterWriterMaximum: 2,
  bundleSha256: hash(await readFile(resolve(here, 'prepare-bundle.draft.json'))),
  provenanceSha256: hash(await readFile(resolve(here, 'prepare-provenance.json'))),
  checks: ['Source trace hashes equal immutable closure', 'Actual52 PREPARE requests only', 'First2 message strings unchanged',
    'Pop appended message restores entire original object', 'SourceId and completeQA values come from actual bound sources',
    'Original model/system/8192/.2/JSON/disabled/streamfalse unchanged', 'No confirmed label applied to unknown', 'No oracle registry or future writer constructed'],
  noProductOrHarnessChange: true, noFormalFreezeOrService: true };
await save(resolve(here, 'draft-verification.json'), report);
console.log(JSON.stringify({ actualModelCalls: 0, bundleSha256: report.bundleSha256, provenanceSha256: report.provenanceSha256,
  maximumPrepareActualCalls: 2, laterWriterNotConstructed: true, appendedCharacters: provenance.map(item => ({ caseId: item.caseId, characters: item.appendedCharacters })) }));
