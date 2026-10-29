import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const here = resolve(evidence, 'registry55-plan-preparation'); await mkdir(here, { recursive: true });
const read = path => readFile(resolve(evidence, path), 'utf8');
const write = (name, content) => writeFile(resolve(here, name), content, { flag: 'wx' });
const save = (name, value) => write(name, JSON.stringify(value, null, 2) + '\n');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
let support = await read('registry54-plan-preparation/browser-support.mjs');
support = support.replace("export const branchIds = ['P02_CONFIRMED'];", "export const branchIds = ['P02_CONFIRMED', 'P02_UNKNOWN'];")
  .replaceAll('54', '55');
await write('browser-support.mjs', support);
await write('live-browser.mjs', (await read('registry54-plan-preparation/live-browser.mjs')).replaceAll('54', '55'));
let completion = (await read('registry52-plan-preparation/build-plan-completion.mjs')).replaceAll('52', '55');
completion = completion.replace('assert.ok(branchIds.includes(sample.id));', "assert.equal(verdict.strategyVersion, '55'); assert.ok(branchIds.includes(sample.id));");
await write('build-plan-completion.mjs', completion);
const original = JSON.parse(await read('registry51-normal-preparation/plan-cases.json')).cases.find(item => item.id === 'P02');
assert.ok(original?.rawPrompt);
const cases = ['P02_CONFIRMED', 'P02_UNKNOWN'].map(id => ({ id, rawPrompt: original.rawPrompt,
  futurePlanJobId: `${id}_V55_PLAN`, futureGenerationJobId: `${id}_V55_GEN`, contextDescription: '',
  operatorBranch: id === 'P02_CONFIRMED' ? 'Select the actual allow_today option' : 'Custom answer: 今天是否允许尚未决定' }));
await save('cases.json', { cases });
await save('capture-bundle.json', { syntheticOnly: true, captureOnly: true, captureVersions: ['55'], cases, jobs: [],
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  purpose: 'Fresh55 actual UI context capture only; no models. CONFIRMED semantic pass is required before UNKNOWN model jobs.' });
const files = [
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptSinglePassInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptStageInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleProviderConfiguration.java',
  'services/api/src/test/java/com/promptoptimizer/provider/infrastructure/openai/NaturalReasoningProviderTest.java',
];
const sourceHashes = [];
for (const path of files) sourceHashes.push({ path, sha256: hash(await readFile(resolve(repo, path))) });
const reports = [];
const archive = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry55-engineering');
for (const name of ['NaturalReasoningProviderTest', 'NaturalSinglePassProviderTest']) {
  const path = `services/api/target/surefire-reports/TEST-com.promptoptimizer.provider.infrastructure.openai.${name}.xml`;
  const bytes = await readFile(resolve(repo, path)), text = bytes.toString('utf8');
  const suite = text.match(/<testsuite\b[^>]*>/)?.[0]; assert.ok(suite);
  const numbers = Object.fromEntries(['tests', 'failures', 'errors', 'skipped'].map(key => [key, Number(suite.match(new RegExp(`${key}="(\\d+)"`))?.[1])]));
  assert.equal(numbers.failures + numbers.errors + numbers.skipped, 0);
  await copyFile(resolve(repo, path), resolve(archive, name + '-passed.xml'));
  reports.push({ path, sha256: hash(bytes), ...numbers });
}
assert.equal(reports.reduce((sum, item) => sum + item.tests, 0), 31);
await writeFile(resolve(evidence, 'registry55-engineering-verification.json'), JSON.stringify({
  version: '55', frozenAt: new Date().toISOString(), actualModelCalls: 0, sources: sourceHashes, tests: reports,
  writingAndPlanGuidanceUnchangedFrom54: true, oldRequestFixtureCount: 35, oldGuideFixtureCount: 25,
  inference: { adapter: 'Official DeepSeek Flash/Pro', body: 'thinking enabled/high, max_tokens32768 within configured capacity',
    transport: 'Dedicated 180-second client preserving shared interceptors; wrapper avoids changing RestClient injection',
    planAndOtherRoutes: 'Original parameters and transport' },
  priorToolFailures: ['Windows selector loopback failed until using the existing IPv4/tmp JVM flags', 'New Plan test initially used an incorrect overload; fixed in tests'],
  qualityAdoptionClaimed: false, defaultVersion: '31', service9000Updated: false,
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ prepared: true, version: '55', testsPassed: 31, actualModelCalls: 0 }));
