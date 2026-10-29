import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const target = resolve(evidence, 'registry66-text-works-preparation');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = value => createHash('sha256').update(value).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
async function closed(stage, name) {
  const path = resolve(evidence, stage, name), bytes = await readFile(path), closure = await json(resolve(evidence, stage, 'closure.json'));
  assert.equal(closure.status, 'CLOSED');
  assert.equal(closure.files.find(file => resolve(repo, file.path) === path)?.sha256, sha(bytes));
  return { value: JSON.parse(bytes), ref: { path: `${stage}/${name}`, sha256: sha(bytes) } };
}
const gate = await json(resolve(evidence, 'registry66-preparation/development-review.json')); assert.equal(gate.contentPromptsEligibleForWorks, true);
const currentJobs = await json(resolve(evidence, 'registry66-development/jobs.json'));
const previousGeneration = await json(resolve(evidence, 'registry65-normal/jobs.json'));
const previousWorks = await json(resolve(evidence, 'registry65-text-works/jobs.json'));
const baselines = await json(resolve(evidence, 'registry58-works/jobs.json'));
const samples = (await json(resolve(evidence, 'registry65-text-works/cases.json'))).cases;
const information = input => ({ rawPrompt: input.rawPrompt, context: input.context, conversationHistory: input.conversationHistory ?? [], permissionPolicy: input.permissionPolicy ?? null, planConfirmation: input.planConfirmation ?? null });
const jobs = [], reused = [], cases = [], provenance = [];
for (const sample of samples) {
  const current = currentJobs.find(job => job.caseId === sample.id), previous = previousGeneration.find(job => job.caseId === sample.id);
  assert.deepEqual(information(current.approvedEnhancementInput), information(previous.approvedEnhancementInput));
  const result = await closed('registry66-development', `results/${current.id}.json`);
  assert.equal(result.value.status, 200); assert.ok(result.value.body.data.processing.completedStages.includes('BODY_GENERATION'));
  const prompt = result.value.body.data.optimizedPrompt;
  const template = previousWorks.find(job => job.caseId === sample.id && job.repeat === 1);
  for (const source of [
    ...baselines.filter(job => job.caseId === sample.id && ['ORIGINAL', '31'].includes(job.version)).map(job => ({ stage: 'registry58-works', job })),
    ...previousWorks.filter(job => job.caseId === sample.id).map(job => ({ stage: 'registry65-text-works', job })),
  ]) {
    const actual = await closed(source.stage, `results/${source.job.id}.json`); assert.equal(actual.value.status, 200);
    assert.equal(actual.value.body.data.finishReason, 'stop');
    const left = structuredClone(source.job.approvedModelRequest), right = structuredClone(template.approvedModelRequest);
    left.messages[1] = right.messages[1]; assert.deepEqual(left, right);
    reused.push({ ...source.job, source: actual.ref });
  }
  cases.push({ ...sample, eligibleArms: ['ORIGINAL', '31', '65', '66'] });
  for (const repeat of [1, 2]) {
    const request = structuredClone(template.approvedModelRequest); request.messages[1].content = prompt;
    jobs.push({ id: `${sample.id}_V66_EXEC_R${repeat}`, caseId: sample.id, version: '66', phase: 'EXECUTION', repeat, approvedModelRequest: request });
  }
  provenance.push({ caseId: sample.id, generation: result.ref, allOriginalInformationEqual: true,
    materialMessageSha256: sha(template.approvedModelRequest.messages[2].content), sameExecutionModelAndParameters: true });
}
assert.equal(reused.length, 12); assert.equal(jobs.length, 4);
const seed = 'registry66-positive-examples-before-new-works';
jobs.sort((a, b) => sha(seed + a.id).localeCompare(sha(seed + b.id)));
const all = [...reused, ...jobs].sort((a, b) => sha(seed + '\0blind\0' + a.id).localeCompare(sha(seed + '\0blind\0' + b.id)));
const mapping = all.map((job, index) => ({ workId: `P${String(index + 1).padStart(2, '0')}`, jobId: job.id, caseId: job.caseId, arm: job.version,
  repeat: job.repeat, source: job.source ?? { path: `registry66-text-works/results/${job.id}.json` } }));
await mkdir(target, { recursive: true });
await save(resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry66-text-works-coordinator.json'), { rootHasSeenOldWorks: true, reviewerVersionBlind: true, mapping });
await writeFile(resolve(target, 'criteria.json'), await readFile(resolve(evidence, 'registry65-text-works-preparation/criteria.json')), { flag: 'wx' });
await save(resolve(target, 'provenance.json'), { provenance, reusedWorks: 12, newWorks: 4, allBaselineAnd65RepeatsIncluded: true, rootHasSeenPriorWorks: true, qualityRetries: 0 });
await save(resolve(target, 'bundle.json'), { syntheticOnly: true, cases, jobs, phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 4, REVIEW: 0 }, parameters: { executionMaxTokens: 8192 },
  preparationArtifacts: ['registry66-text-works-preparation/criteria.json', 'registry66-text-works-preparation/provenance.json', 'registry66-preparation/development-review.json'],
  description: 'Only four new66 works; all RAW/31/65 two-repeat works reused without selection. Common full original information/model/parameters/permissions, no quality retries.' });
console.log(JSON.stringify({ prepared: true, newWorks: 4, reusedWorks: 12, actualCalls: 0 }));
