import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse(await readFile(resolve(evidence, path), 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const here = resolve(evidence, 'registry56-regression-preparation'); await mkdir(here, { recursive: true });
const prior = await json('registry55-regression-preparation/bundle.json');
const order = ['U02', 'U03', 'C01', 'C02', 'V01', 'V02', 'V03', 'Q01'];
const jobs = order.map(id => { const job = structuredClone(prior.jobs.find(value => value.caseId === id)); assert.ok(job);
  job.id = job.id.replace('_V55_', '_V56_'); job.version = '56'; job.purpose = 'GOAL_ROLE_PROTOCOL_REGRESSION'; return job; });
await writeFile(resolve(here, 'bundle.json'), JSON.stringify({ ...prior, jobs }, null, 2) + '\n', { flag: 'wx' });
const sources = [];
for (const path of [
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptSinglePassInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptStageInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleProviderConfiguration.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptRequirementsOnlyPreparation.java',
  'services/api/src/test/java/com/promptoptimizer/provider/infrastructure/openai/NaturalReasoningProviderTest.java',
  'apps/web/src/services/promptOptimizerApi.ts', 'apps/web/src/services/promptOptimizerApi.test.ts',
]) sources.push({ path, sha256: hash(await readFile(resolve(repo, path))) });
const junit = [];
const archive = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry56-engineering'); await mkdir(archive, { recursive: true });
for (const name of ['NaturalReasoningProviderTest', 'NaturalSinglePassProviderTest']) {
  const path = `services/api/target/surefire-reports/TEST-com.promptoptimizer.provider.infrastructure.openai.${name}.xml`;
  const bytes = await readFile(resolve(repo, path)), suite = bytes.toString('utf8').match(/<testsuite\b[^>]*>/)?.[0]; assert.ok(suite);
  const result = Object.fromEntries(['tests', 'failures', 'errors', 'skipped'].map(key => [key, Number(suite.match(new RegExp(`${key}="(\\d+)"`))?.[1])]));
  assert.equal(result.failures + result.errors + result.skipped, 0); await copyFile(resolve(repo, path), resolve(archive, name + '.xml'));
  junit.push({ path, sha256: hash(bytes), ...result });
}
assert.equal(junit.reduce((sum, value) => sum + value.tests, 0), 32);
await writeFile(resolve(evidence, 'registry56-engineering-verification.json'), JSON.stringify({ version: '56', frozenAt: new Date().toISOString(),
  actualModelCalls: 0, sources, junit, frontend: { affectedTestsPassed: 11, typecheckPassed: true, globalHttpTimeoutUnchanged: true },
  hypothesis: 'Clarify current user task GOAL versus context business objectives without relaxing original authority/source checks.',
  changedFrom55: ['Internal dimensions.GOAL explanation only', 'Natural generation and historical regeneration browser wait budget'],
  unchanged: ['Business writing guidance', 'Thinking/high profile', 'Plan', 'All source/answer/body/permission checks', 'Old strategies and source-role guard'],
  original55FailureRetained: true, historicalContentNotRelabeled: true, defaultVersion: '31', qualityAdoptionClaimed: false,
}, null, 2) + '\n', { flag: 'wx' });
await writeFile(resolve(here, 'protocol.md'), '# 56 protocol-role regression\n\nStart with the actual U02 rejection, then U03 and normal variants; run Q01 last. Maximum16 calls, including the existing single protocol repair. No quality retries. The old55 bodies and rejection status remain unchanged. Stop on the same severe role-protocol failure or a new semantic/authority violation. First prove current task roles and retained business purposes, then open previously unseen cases and actual works. The current role guard is not removed or relaxed.\n', { flag: 'wx' });
console.log(JSON.stringify({ frozenEngineering: true, tests: 32, frontendTests: 11, jobs: jobs.length, actualCalls: 0 }));
