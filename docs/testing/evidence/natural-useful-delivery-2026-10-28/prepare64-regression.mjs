import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const previous = await json(resolve(evidence, 'registry63-holdout-gen/closure.json'));
assert.equal(previous.status, 'CLOSED'); assert.equal(previous.actualCalls, 9);
const frozen = await json(resolve(evidence, 'registry63-holdout-preparation/generation-bundle.json'));
const sample = frozen.cases.find(item => item.id === 'H54_02');
const job = structuredClone(frozen.jobs.find(item => item.caseId === sample.id && item.version === '63'));
job.id = 'H54_02_V64_GEN'; job.version = '64';
await mkdir(resolve(evidence, 'registry64-preparation'), { recursive: true });
await writeFile(resolve(evidence, 'registry64-preparation/regression-bundle.json'), JSON.stringify({
  syntheticOnly: true, description: 'Known63 failure replay for64; original same inputs, source states and output limits. No longer an unseen case; one native first generation, no quality retry.',
  cases: [sample], jobs: [job], phaseLimits: { GENERATION: 6, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 8192, thinking: 'disabled', stream: false },
  preparationArtifacts: ['registry64-hypothesis.md', 'registry63-decision.md', 'sealed-registry54-holdout2/review-criteria.json'],
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ prepared: true, actualCalls: 0, generationMaximum: 6, originalInputsUnchanged: true, unseen: false }));
