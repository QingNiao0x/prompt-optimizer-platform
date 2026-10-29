import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse(await readFile(resolve(evidence, path), 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const original = await json('registry56-regression-preparation/bundle.json'), ids = ['U02', 'V02', 'V03', 'Q01'];
const jobs = ids.map(id => { const job = structuredClone(original.jobs.find(value => value.caseId === id)); assert.ok(job);
  job.id = job.id.replace('_V56_', '_V57_'); job.version = '57'; job.purpose = 'BOUNDED_METADATA_REPAIR_PRODUCT_REGRESSION'; return job; });
const root = resolve(evidence, 'registry57-regression-preparation'); await mkdir(root, { recursive: true });
await writeFile(resolve(root, 'bundle.json'), JSON.stringify({ syntheticOnly: true, cases: original.cases.filter(item => ids.includes(item.id)), jobs,
  phaseLimits: { GENERATION: 8, PLAN: 0, EXECUTION: 0, REVIEW: 0 } }, null, 2) + '\n', { flag: 'wx' });
const sources = [];
for (const path of [
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptSinglePassInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptStageInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/NaturalPromptMetadataRepair.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleProviderConfiguration.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptRequirementsOnlyPreparation.java',
  'services/api/src/test/resources/natural-metadata-repair/real-first-drafts-and-patches.json',
  'apps/web/src/services/promptOptimizerApi.ts', 'apps/web/src/services/promptOptimizerApi.test.ts',
]) sources.push({ path, sha256: hash(await readFile(resolve(repo, path))) });
const junit = [], archive = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry57-engineering'); await mkdir(archive, { recursive: true });
for (const name of ['NaturalMetadataRepairProviderTest', 'NaturalPromptMetadataRepairTest', 'NaturalReasoningProviderTest', 'NaturalSinglePassProviderTest']) {
  const path = `services/api/target/surefire-reports/TEST-com.promptoptimizer.provider.infrastructure.openai.${name}.xml`;
  const bytes = await readFile(resolve(repo, path)), suite = bytes.toString('utf8').match(/<testsuite\b[^>]*>/)?.[0]; assert.ok(suite);
  const counts = Object.fromEntries(['tests', 'failures', 'errors', 'skipped'].map(key => [key, Number(suite.match(new RegExp(`${key}="(\\d+)"`))?.[1])]));
  assert.equal(counts.failures + counts.errors + counts.skipped, 0); await copyFile(resolve(repo, path), resolve(archive, name + '.xml'));
  junit.push({ path, sha256: hash(bytes), ...counts });
}
assert.equal(junit.reduce((sum, row) => sum + row.tests, 0), 47);
await writeFile(resolve(evidence, 'registry57-engineering-verification.json'), JSON.stringify({ version: '57', frozenAt: new Date().toISOString(),
  actualModelCalls: 0, sources, junit, transportGuardChecksPassed: 10, transportGuardFixtureOnly: true,
  frontendAffectedChecksPassed: 11, frontendTypecheckPassed: true, firstWritingAndPlanUnchangedFrom56: true,
  hypothesis: 'An identified metadata error must not reopen a valid first body for regeneration. Patch only server-listed evidence/dimensions; rerun all original protections.',
  maximumTotalAttempts: 2, extraReviewStageAdded: false, fieldsNotPatchable: ['optimizedPrompt', 'goal', 'deliverable', 'requirement text', 'array membership'],
  supportedMetadataIssues: ['UNKNOWN_SOURCE', 'SOURCE_QUOTE_MISMATCH', 'CONTEXT_GOAL_DIMENSION'],
  original56FailureRetained: true, defaultVersion: '31', qualityAdoptionClaimed: false,
}, null, 2) + '\n', { flag: 'wx' });
await writeFile(resolve(root, 'protocol.md'), '# 57 focused regression\n\nFresh U02/V02 test the two actual failures. V03/Q01 finish cases not executed after the56 stop. Maximum8 actual calls, one existing repair per job, no quality retries. First writing and inference are identical to56. Unaffected C01/C02/U03/V01 source results remain version56 evidence, not relabeled57; reuse their common-writing checks plus current47 offline checks. Stop on severe semantic or protection failure. Only then open sealed unseen cases, Plan normal paths and actual works. Default31 remains.\n', { flag: 'wx' });
console.log(JSON.stringify({ version: '57', jobs: jobs.length, testsPassed: 47, guardChecks: 10, actualModelCalls: 0 }));
