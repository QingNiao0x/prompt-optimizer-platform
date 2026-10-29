import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const target = resolve(evidence, 'registry65-text-works-preparation');
const sha = value => createHash('sha256').update(value).digest('hex');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
async function closed(stage, name) {
  const path = resolve(evidence, stage, name), bytes = await readFile(path), closure = await json(resolve(evidence, stage, 'closure.json'));
  assert.equal(closure.status, 'CLOSED');
  assert.equal(closure.files.find(file => resolve(repo, file.path) === path)?.sha256, sha(bytes));
  return { value: JSON.parse(bytes), ref: { path: `${stage}/${name}`, sha256: sha(bytes) } };
}
const information = input => ({ rawPrompt: input.rawPrompt, context: input.context, conversationHistory: input.conversationHistory ?? [], permissionPolicy: input.permissionPolicy ?? null, planConfirmation: input.planConfirmation ?? null });
assert.equal((await json(resolve(evidence, 'registry65-preparation/normal-review.json'))).allEligibleForNextGate, true);
assert.equal((await json(resolve(evidence, 'registry65-preparation/plan-review.json'))).allThreePathsPassed, true);
assert.equal((await json(resolve(evidence, 'engineering65-run/live/engineering65/E01_V65_GEN/verdict.json'))).copyEqualsResponseAfterClipboardNewlineNormalization, true);
const oldJobs = await json(resolve(evidence, 'registry58-works/jobs.json'));
const oldCases = (await json(resolve(evidence, 'registry58-works/cases.json'))).cases;
const oldGeneration = await json(resolve(evidence, 'registry58-development/jobs.json'));
const generation = await json(resolve(evidence, 'registry65-normal/jobs.json'));
const jobs = [], cases = [], reused = [], provenance = [];
for (const id of ['Q01', 'U02']) {
  const current = generation.find(job => job.caseId === id), previous = oldGeneration.find(job => job.caseId === id);
  assert.deepEqual(information(current.approvedEnhancementInput), information(previous.approvedEnhancementInput));
  const result = await closed('registry65-normal', `results/${current.id}.json`);
  assert.equal(result.value.status, 200); assert.ok(result.value.body.data.processing.completedStages.includes('BODY_GENERATION'));
  const prompt = result.value.body.data.optimizedPrompt;
  const template = oldJobs.find(job => job.caseId === id && job.version === '58' && job.repeat === 1);
  assert.equal(template.approvedModelRequest.max_tokens, 8192);
  const sample = structuredClone(oldCases.find(sample => sample.id === id));
  sample.eligibleArms = ['ORIGINAL', '31', '65']; cases.push(sample);
  const baseline = oldJobs.filter(job => job.caseId === id && ['ORIGINAL', '31'].includes(job.version));
  assert.equal(baseline.length, 4, 'Use all two pre-registered repetitions of both eligible baselines');
  for (const job of baseline) {
    const actual = await closed('registry58-works', `results/${job.id}.json`);
    assert.equal(actual.value.status, 200); assert.equal(actual.value.body.data.finishReason, 'stop');
    assert.deepEqual(job.approvedModelRequest.messages[0], template.approvedModelRequest.messages[0]);
    assert.deepEqual(job.approvedModelRequest.messages[2], template.approvedModelRequest.messages[2]);
    const left = structuredClone(job.approvedModelRequest), right = structuredClone(template.approvedModelRequest);
    left.messages[1] = right.messages[1]; assert.deepEqual(left, right);
    reused.push({ ...job, result: actual.ref, reused: true });
  }
  for (const repeat of [1, 2]) {
    const request = structuredClone(template.approvedModelRequest); request.messages[1].content = prompt;
    jobs.push({ id: `${id}_V65_EXEC_R${repeat}`, caseId: id, version: '65', phase: 'EXECUTION', repeat,
      promptSha256: sha(prompt), approvedModelRequest: request });
  }
  provenance.push({ caseId: id, generation: result.ref, originalInformationEqual: true,
    commonSystemSha256: sha(template.approvedModelRequest.messages[0].content),
    completeSharedMaterialMessageSha256: sha(template.approvedModelRequest.messages[2].content) });
}
const seed = 'registry65-text-works-before-any-new-work';
jobs.sort((a, b) => sha(seed + '\0' + a.id).localeCompare(sha(seed + '\0' + b.id)));
const all = [...reused, ...jobs].sort((a, b) => sha(seed + '\0blind\0' + a.id).localeCompare(sha(seed + '\0blind\0' + b.id)));
const mapping = all.map((job, index) => ({ workId: `N${String(index + 1).padStart(2, '0')}`, jobId: job.id,
  caseId: job.caseId, arm: job.version, repeat: job.repeat, source: job.result ?? { path: `registry65-text-works/results/${job.id}.json` } }));
await mkdir(target, { recursive: true });
await save(resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry65-text-works-coordinator.json'), { rootHasSeenOldBaselines: true, reviewerVersionBlind: true, mapping });
await writeFile(resolve(target, 'criteria.json'), await readFile(resolve(evidence, 'registry63-works-preparation/criteria.json')), { flag: 'wx' });
await save(resolve(target, 'provenance.json'), { provenance, reusedWorks: 8, newWorks: 4, allBaselineRepeatsIncluded: true,
  rootPriorBaselineKnowledge: true, qualityRetries: 0, onlyPromptDiffersWithinEachCase: true });
await save(resolve(target, 'bundle.json'), { syntheticOnly: true, cases, jobs,
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: jobs.length, REVIEW: 0 }, parameters: { executionMaxTokens: 8192 },
  preparationArtifacts: ['registry65-text-works-preparation/criteria.json', 'registry65-text-works-preparation/provenance.json'],
  description: 'Two first65 work observations per query/activity case; all eight exact58 RAW/31 work observations reused, complete identical information/system/model/parameters/permissions. All failures retained, no quality retries.' });
console.log(JSON.stringify({ prepared: true, newWorks: jobs.length, reusedWorks: reused.length, actualCalls: 0, defaultVersion: '31' }));
