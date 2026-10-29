import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
assert.equal((await json(resolve(evidence, 'registry65-text-review/closure.json'))).actualCalls, 2);
const ordinary = await json(resolve(evidence, 'registry65-normal/jobs.json'));
const reserved = (await json(resolve(evidence, 'registry65-regression/jobs.json'))).find(job => job.caseId === 'P02_DIRECT');
const selected = [reserved, ...['V05', 'V01', 'V03', 'Q01', 'U02'].map(id => ordinary.find(job => job.caseId === id))];
const jobs = selected.map(original => { const job = structuredClone(original); job.id = `${job.caseId}_V66_GEN`; job.version = '66'; return job; });
const cases = jobs.map(job => ({ id: job.caseId, rawPrompt: job.approvedEnhancementInput.rawPrompt,
  contextDescription: job.approvedEnhancementInput.context?.customDescription ?? '', knownDevelopmentCase: true }));
await mkdir(resolve(evidence, 'registry66-preparation'), { recursive: true });
await save(resolve(evidence, 'registry66-preparation/development-bundle.json'), {
  syntheticOnly: true, description: '66 only internal writing examples; all65 original inputs/requirements/Plan/protocol unchanged. Reserved decision and hard normal boundaries first, then query/activity. No quality retries or topic-specific rules.',
  cases, jobs, phaseLimits: { GENERATION: 36, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 8192, thinking: 'disabled', stream: false },
  preparationArtifacts: ['registry66-hypothesis.md', 'registry65-decision.md'],
});
const paths = (await json(resolve(evidence, 'registry65-engineering-freeze.json'))).files.map(file => file.path);
const files = []; for (const path of paths) files.push({ path, sha256: sha(await readFile(resolve(repo, path))) });
const suites = [];
for (const name of ['NaturalBodyFirstProviderTest', 'NaturalReasoningProviderTest', 'NaturalEffectiveRegistryProviderTest', 'NaturalPromptMetadataRepairTest', 'NaturalSinglePassProviderTest']) {
  const xml = await readFile(resolve(repo, `services/api/target/surefire-reports/TEST-com.promptoptimizer.provider.infrastructure.openai.${name}.xml`), 'utf8');
  const head = xml.match(/<testsuite\b[^>]*>/)[0]; const attr = key => Number(head.match(new RegExp(` ${key}="(\\d+)"`))[1]);
  suites.push({ name, tests: attr('tests'), failures: attr('failures'), errors: attr('errors'), skipped: attr('skipped') });
}
assert.ok(suites.every(suite => suite.failures === 0 && suite.errors === 0 && suite.skipped === 0));
await save(resolve(evidence, 'registry66-engineering-freeze.json'), { candidate: '66', frozenAt: new Date().toISOString(), defaultVersion: '31', actualCallsToDate: 428,
  files, suites, passedTests: suites.reduce((sum, suite) => sum + suite.tests, 0), offlineGuardChecks: 18,
  change: 'Only two internal positive writing examples in body system; same65 effective preparation, Plan, immutable first body and protections.',
  unused52HoldoutNotOpened: true, actualWorksNotYetCompared: true, qualityAdopted: false });
console.log(JSON.stringify({ prepared: true, jobs: jobs.length, maximumCalls: 36, tests: suites.reduce((sum, suite) => sum + suite.tests, 0), actualCalls: 0 }));
