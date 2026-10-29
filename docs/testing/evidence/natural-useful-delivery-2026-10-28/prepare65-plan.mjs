import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
assert.equal((await json(resolve(evidence, 'registry65-normal/closure.json'))).status, 'CLOSED');
assert.equal((await json(resolve(evidence, 'registry65-preparation/normal-review.json'))).allEligibleForNextGate, true);
const jobs = await json(resolve(evidence, 'registry65-normal/jobs.json'));
const sufficient = jobs.find(job => job.caseId === 'V01').approvedEnhancementInput.rawPrompt;
const reserved = (await json(resolve(evidence, 'registry65-regression/jobs.json'))).find(job => job.caseId === 'P02_DIRECT').approvedEnhancementInput.rawPrompt;
const cases = [{ id: 'P00', rawPrompt: sufficient }, { id: 'P02_NOCTX_CONFIRMED', rawPrompt: reserved }, { id: 'P02_NOCTX_UNKNOWN', rawPrompt: reserved }];
const planJobs = cases.map(sample => ({ id: `${sample.id}_V65_PLAN`, caseId: sample.id, version: '65', phase: 'PLAN',
  approvedEnhancementInput: { rawPrompt: sample.rawPrompt, modelId: 'deepseek:deepseek-flash', contextDescription: '', conversationHistory: [] } }));
await writeFile(resolve(evidence, 'registry65-preparation/plan-bundle.json'), JSON.stringify({
  syntheticOnly: true, description: 'Fresh65 real service Plan without project attachments: sufficient zero-question, important user-owned boundary then actual confirm/unknown operator choices. No old plan ids, no synthetic server answers, no UI click claim.',
  cases, jobs: planJobs, phaseLimits: { GENERATION: 0, PLAN: 12, EXECUTION: 0, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 8192, thinking: 'disabled', stream: false },
  preparationArtifacts: ['registry65-hypothesis.md', 'registry65-preparation/normal-review.json'],
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ prepared: true, planJobs: 3, maximumPlanCalls: 12, noAttachments: true, actualCalls: 0 }));
