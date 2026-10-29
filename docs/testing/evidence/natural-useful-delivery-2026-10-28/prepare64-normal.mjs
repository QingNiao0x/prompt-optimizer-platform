import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const closure = await json(resolve(evidence, 'registry64-regression/closure.json'));
assert.equal(closure.status, 'CLOSED'); assert.equal(closure.actualCalls, 5);
const result = await json(resolve(evidence, 'registry64-regression/results/H54_02_V64_GEN.json'));
assert.equal(result.status, 200);
assert.deepEqual(result.body.data.processing.completedStages, ['REQUIREMENT_PREPARATION', 'BODY_GENERATION', 'RECORD_LINKING']);
await save(resolve(evidence, 'registry64-preparation/regression-review.json'), {
  candidate: '64', reviewedAt: new Date().toISOString(), knownCase: true, notUnseen: true, actualCalls: 5,
  eligibleForWorks: true, originalInputsUnchanged: true, sourceStates: 'Known differences no longer asserted unrecorded; sampling/non-extrapolation conditions explicitly retained; 5 shifts and resource/authorization limits maintained.',
  requirementQuotesAreNotSemanticCertification: true, internalAiReviewOnly: true, adopted: false,
  nativeRepairs: ['Preparation protocol', 'Four material-only GOAL dimensions; fixed body unchanged'],
});
const previous = await json(resolve(evidence, 'registry61-development/jobs.json'));
const ids = ['V04', 'V01', 'V03', 'Q01'];
const jobs = ids.map(id => { const job = structuredClone(previous.find(item => item.caseId === id)); job.version = '64'; job.id = `${id}_V64_GEN`; return job; });
const reservation = structuredClone((await json(resolve(evidence, 'registry63-raw-reservation/jobs.json')))[0]);
reservation.id = 'P02_DIRECT_V64_GEN'; reservation.version = '64'; jobs.splice(3, 0, reservation);
const cases = jobs.map(job => ({ id: job.caseId, rawPrompt: job.approvedEnhancementInput.rawPrompt,
  contextDescription: job.approvedEnhancementInput.context?.customDescription ?? '', knownRegression: true }));
await save(resolve(evidence, 'registry64-preparation/normal-bundle.json'), {
  syntheticOnly: true, description: '64 known normal variants before fresh holdout: read/wait, sufficient exact output, explicit no materials, reserved decision without Plan, query without attachments. Native first jobs only.',
  cases, jobs, phaseLimits: { GENERATION: 30, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 8192, thinking: 'disabled', stream: false },
  preparationArtifacts: ['registry64-hypothesis.md', 'registry64-preparation/regression-review.json'],
});
const paths = [
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptBodyFirstInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalTaskReview.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/NaturalPromptMetadataRepair.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/NaturalPromptSourceReferences.java',
];
const files = []; for (const path of paths) files.push({ path, sha256: sha(await readFile(resolve(repo, path))) });
const suites = [];
for (const name of ['NaturalBodyFirstProviderTest', 'NaturalReasoningProviderTest', 'NaturalEffectiveRegistryProviderTest', 'NaturalPromptMetadataRepairTest', 'NaturalSinglePassProviderTest']) {
  const xml = await readFile(resolve(repo, `services/api/target/surefire-reports/TEST-com.promptoptimizer.provider.infrastructure.openai.${name}.xml`), 'utf8');
  const head = xml.match(/<testsuite\b[^>]*>/)[0];
  const attr = key => Number(head.match(new RegExp(` ${key}="(\\d+)"`))[1]);
  suites.push({ name, tests: attr('tests'), failures: attr('failures'), errors: attr('errors'), skipped: attr('skipped') });
}
assert.ok(suites.every(suite => suite.failures === 0 && suite.errors === 0 && suite.skipped === 0));
await save(resolve(evidence, 'registry64-engineering-freeze.json'), { candidate: '64', frozenAt: new Date().toISOString(), defaultVersion: '31', actualCallsToDate: 367,
  files, suites, passedTests: suites.reduce((sum, suite) => sum + suite.tests, 0), offlineGuardChecks: 18,
  change: 'Existing requirement preparation before every64 writer; old63 and actual-answer protocol unchanged.',
  freshHoldoutNotOpened: true, actualWorksNotYetCompared: true, qualityAdopted: false });
console.log(JSON.stringify({ prepared: true, normalJobs: jobs.length, maxCalls: 30, passedTests: suites.reduce((sum, suite) => sum + suite.tests, 0), defaultVersion: '31' }));
