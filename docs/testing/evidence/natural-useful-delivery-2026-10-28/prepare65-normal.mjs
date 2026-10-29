import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const closure = await json(resolve(evidence, 'registry65-regression/closure.json'));
assert.equal(closure.status, 'CLOSED'); assert.equal(closure.allJobsDelivered, true);
const review = await json(resolve(evidence, 'registry65-preparation/regression-review.json'));
assert.equal(review.bothProductGatesPassed, true);
const previous = await json(resolve(evidence, 'registry61-development/jobs.json'));
const ids = ['V04', 'V05', 'V01', 'V03', 'Q01', 'U02'];
const jobs = ids.map(id => { const job = structuredClone(previous.find(item => item.caseId === id)); job.version = '65'; job.id = `${id}_V65_GEN`; return job; });
const cases = jobs.map(job => ({ id: job.caseId, rawPrompt: job.approvedEnhancementInput.rawPrompt,
  contextDescription: job.approvedEnhancementInput.context?.customDescription ?? '', knownRegression: true }));
await writeFile(resolve(evidence, 'registry65-preparation/normal-bundle.json'), JSON.stringify({
  syntheticOnly: true, description: '65 normal variants after known failure checks: read/wait, explicit two-file reading boundary, sufficient exact output, explicit no materials, query and delegated activity design. Same original61 inputs, no quality retries.',
  cases, jobs, phaseLimits: { GENERATION: 36, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 8192, thinking: 'disabled', stream: false },
  preparationArtifacts: ['registry65-hypothesis.md', 'registry65-preparation/regression-review.json', 'registry65-engineering-freeze.json'],
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ prepared: true, normalJobs: jobs.length, maxCalls: 36, actualCalls: 0, defaultVersion: '31' }));
