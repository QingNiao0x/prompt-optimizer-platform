import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadCases } from '../registry52-plan-preparation/browser-support.mjs';
import { readCapture, validateIndependentCaptures } from '../registry52-plan-preparation/build-plan-completion.mjs';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
const samples = await loadCases();
const root = resolve(evidence, 'registry52-plan-preparation/plan-captures/registry52-plan-capture');
const captures = await Promise.all(samples.map(sample => readCapture(resolve(root, sample.id))));
validateIndependentCaptures(samples, captures);
const closure = JSON.parse(await readFile(resolve(evidence, 'registry52-plan-capture/closure.json'), 'utf8'));
assert.equal(closure.status, 'CLOSED'); assert.equal(closure.actualCalls, 0);
const cases = samples.map((sample, index) => ({ id: sample.id, rawPrompt: sample.rawPrompt,
  contextDescription: captures[index].planInput.contextDescription,
  sourceCaptureDirectory: relative(repo, resolve(root, sample.id)).replaceAll('\\', '/'),
  originalPreparationRequest: captures[index].prepareInput,
  actualContextReference: captures[index].planInput.planningContext,
  actualContextExpiresAt: captures[index].prepareResponse.body.data.expiresAt,
  expectedPath: sample.id === 'P02_CONFIRMED' ? 'IMPORTANT_UNKNOWN_THEN_EXPLICIT_ALLOW_TODAY' : 'IMPORTANT_UNKNOWN_THEN_EXPLICIT_STILL_UNKNOWN',
  origin: 'Fresh actual full79 UI preparation and blocked-before-model Plan DTO; API Plan stage follows.' }));
const bundle = { syntheticOnly: true, description: 'Candidate52 two real independent P02 Plans; no answer is predetermined in the model input.',
  parameters: { temperature: 0.2, generationMaxTokens: 8192, thinking: 'disabled', stream: false },
  phaseLimits: { GENERATION: 0, PLAN: 8, EXECUTION: 0, REVIEW: 0 }, cases,
  jobs: samples.map((sample, index) => ({ id: sample.futurePlanJobId, caseId: sample.id, phase: 'PLAN', version: '52',
    approvedEnhancementInput: captures[index].planInput })),
  preparationArtifacts: ['registry52-preparation/adapter-verification.json', 'registry52-plan-capture/closure.json'],
  relatedTwoModelStageMaximum: 16 };
await writeFile(resolve(here, 'plan-bundle.json'), JSON.stringify(bundle, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ prepared: true, cases: cases.map(item => ({ id: item.id, expiresAt: item.actualContextExpiresAt })), actualModelCalls: 0, planMaximum: 8 }));
