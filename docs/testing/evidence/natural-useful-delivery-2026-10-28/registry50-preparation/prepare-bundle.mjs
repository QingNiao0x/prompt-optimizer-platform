import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// This constructor writes only a future bundle, never a protocol, account or model request.
const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..');
const bytes = await readFile(resolve(evidence, 'registry49-development-bundle.json'));
const previous = JSON.parse(bytes);
const sha = value => createHash('sha256').update(value).digest('hex');
assert.deepEqual(previous.jobs.map(job => job.caseId), ['Q01', 'U02', 'U03']);
assert.deepEqual(previous.phaseLimits, { GENERATION: 12, PLAN: 0, EXECUTION: 0, REVIEW: 0 });
const bundle = structuredClone(previous);
bundle.description = 'Candidate50 context registration/consumption duty development through the same two-call product path. Exact complete public DTOs from the closed49 development baseline; no synthetic live registry, no new material, no independent holdout or works/adoption claim. Prepared only; source engineering and root approval must precede freeze/start.';
for (const job of bundle.jobs) {
  assert.equal(job.version, '49');
  job.id = job.id.replace('_V49_', '_V50_');
  job.version = '50';
  job.purpose = 'CONTEXT_REGISTRATION_AND_CONSUMPTION_TWO_STAGE_MECHANISM';
}
bundle.preFreezeRequirements = [
  'Root verifies candidate50 source engineering and frozen47/49 instruction constants; no product compile/start by this preparation tool.',
  'Root authorizes actual calls after zero-call guard and real product50 branch capture/normalization checks.',
  'Actual scope/requirements must be freshly generated and product-resolved for each job; no synthetic preparation fixture enters the live stage.',
  'Retain exact same full public DTOs,8192/.2/JSON/thinking-disabled parameters and original two-call path; all failures/repairs count within12.',
  'New registry49 sealed holdout stays sealed and cannot be relabeled as candidate50 unseen evidence.',
];
bundle.preparationSource = { path: 'registry49-development-bundle.json', sha256: sha(bytes),
  sourceStatus: 'CLOSED_DEVELOPMENT_REGRESSION_NOT_INDEPENDENT_HOLDOUT',
  publicInputComparison: 'JSON.stringify UTF-8 byte equality including object property order and every original material string' };
const review = [];
for (let index = 0; index < bundle.jobs.length; index++) {
  const prior = previous.jobs[index], next = bundle.jobs[index];
  assert.equal(JSON.stringify(next.approvedEnhancementInput), JSON.stringify(prior.approvedEnhancementInput));
  assert.deepEqual(next.approvedEnhancementInput, prior.approvedEnhancementInput);
  review.push({ caseId: next.caseId, sourceJobId: prior.id, candidateJobId: next.id,
    inputSha256: sha(JSON.stringify(next.approvedEnhancementInput)),
    exactPublicDtoUtf8Bytes: true, contextSnippetCount: next.approvedEnhancementInput.context.files.length });
}
const output = resolve(evidence, 'registry50-development-bundle.json');
const outputBytes = Buffer.from(JSON.stringify(bundle, null, 2) + '\n');
await writeFile(output, outputBytes, { flag: 'wx' });
await writeFile(resolve(here, 'input-equivalence.json'), JSON.stringify({ preparedAt: new Date().toISOString(),
  status: 'PREPARED_NOT_FROZEN', actualModelCalls: 0, stageMaximumActualCalls: 12, normalActualCalls: 6,
  sourceBundleSha256: sha(bytes), candidateBundleSha256: sha(outputBytes), comparisons: review,
  noNewSourceOrPermissionOrActualAnswer: true, livePreparationFixtureUsed: false }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ event: 'CANDIDATE50_BUNDLE_PREPARED_ONLY', sha256: sha(outputBytes),
  jobs: bundle.jobs.length, maximumActualCalls: 12, modelCalls: 0, frozen: false }));
