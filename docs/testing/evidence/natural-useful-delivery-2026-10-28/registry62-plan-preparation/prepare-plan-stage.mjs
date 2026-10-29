import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { here, loadCases, json } from './browser-support.mjs';
import { readCapture, validateIndependentCaptures } from './build-plan-completion.mjs';

// Freeze fresh UI inputs, not a relabeled old context or simulated Plan session.
const samples = await loadCases();
const captures = await Promise.all(samples.map(sample => readCapture(resolve(here, 'plan-captures/registry62-plan-context-capture', sample.id))));
validateIndependentCaptures(samples, captures);
const normal = await json(resolve(here, '../registry61-development/jobs.json'));
const rawPrompt = normal.find(job => job.caseId === 'V01').approvedEnhancementInput.rawPrompt;
const cases = [...samples, { id: 'P00', rawPrompt, purpose: 'FULLY_SPECIFIED_ZERO_QUESTION_PLAN' }];
const jobs = samples.map((sample, i) => ({ id: sample.futurePlanJobId, caseId: sample.id, version: '62', phase: 'PLAN', approvedEnhancementInput: captures[i].planInput }));
jobs.unshift({ id: 'P00_V62_PLAN', caseId: 'P00', version: '62', phase: 'PLAN', approvedEnhancementInput: { rawPrompt, modelId: 'deepseek:deepseek-flash', contextDescription: '', conversationHistory: [] } });
assert.equal(jobs.length, 3);
await writeFile(resolve(here, 'plan-bundle.json'), JSON.stringify({ syntheticOnly: true, cases, jobs, phaseLimits: { PLAN: 12, GENERATION: 0, EXECUTION: 0, REVIEW: 0 }, qualityRetriesAllowed: false,
  purpose: 'Fresh62 Plan no-question, actual allow-today selection, and unknown retained. All context and ownership checks remain server-side.' }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ prepared: true, jobs: jobs.length, actualCalls: 0 }));
