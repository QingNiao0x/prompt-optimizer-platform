import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
assert.equal((await json(resolve(evidence, 'registry64-normal/closure.json'))).actualCalls, 12);
const first = structuredClone((await json(resolve(evidence, 'registry63-raw-reservation/jobs.json')))[0]);
const second = structuredClone((await json(resolve(evidence, 'registry64-regression/jobs.json')))[0]);
const jobs = [first, second].map(job => ({ ...job, id: `${job.caseId}_V65_GEN`, version: '65' }));
const cases = jobs.map(job => ({ id: job.caseId, rawPrompt: job.approvedEnhancementInput.rawPrompt,
  contextDescription: job.approvedEnhancementInput.context.customDescription ?? '', knownRegression: true }));
await mkdir(resolve(evidence, 'registry65-preparation'), { recursive: true });
await save(resolve(evidence, 'registry65-preparation/regression-bundle.json'), {
  syntheticOnly: true, description: '65 only changes preparation responsibility for current state, old input bytes intact. Known64 raw-reservation failure first, then warehouse; neither is unseen.',
  cases, jobs, phaseLimits: { GENERATION: 12, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 8192, thinking: 'disabled', stream: false },
  preparationArtifacts: ['registry65-hypothesis.md', 'registry64-decision.md', 'registry63-decision.md'],
});
const paths = (await json(resolve(evidence, 'registry64-engineering-freeze.json'))).files.map(file => file.path);
const files = []; for (const path of paths) files.push({ path, sha256: sha(await readFile(resolve(repo, path))) });
const suites = [];
for (const name of ['NaturalBodyFirstProviderTest', 'NaturalReasoningProviderTest', 'NaturalEffectiveRegistryProviderTest', 'NaturalPromptMetadataRepairTest', 'NaturalSinglePassProviderTest']) {
  const xml = await readFile(resolve(repo, `services/api/target/surefire-reports/TEST-com.promptoptimizer.provider.infrastructure.openai.${name}.xml`), 'utf8');
  const head = xml.match(/<testsuite\b[^>]*>/)[0]; const attr = key => Number(head.match(new RegExp(` ${key}="(\\d+)"`))[1]);
  suites.push({ name, tests: attr('tests'), failures: attr('failures'), errors: attr('errors'), skipped: attr('skipped') });
}
assert.ok(suites.every(suite => suite.failures === 0 && suite.errors === 0 && suite.skipped === 0));
await save(resolve(evidence, 'registry65-engineering-freeze.json'), { candidate: '65', frozenAt: new Date().toISOString(), defaultVersion: '31', actualCallsToDate: 379,
  files, suites, passedTests: suites.reduce((sum, suite) => sum + suite.tests, 0), offlineGuardChecks: 18,
  change: 'Current effective state of original expressions as well as actual answers in existing preparation; no new stages/fields, body/Plan unchanged.',
  freshHoldoutNotOpened: true, actualWorksNotYetCompared: true, qualityAdopted: false });
console.log(JSON.stringify({ prepared: true, knownRegressions: jobs.length, maxCalls: 12, tests: suites.reduce((sum, suite) => sum + suite.tests, 0) }));
