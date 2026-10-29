import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, copyFile, mkdtemp } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const base = await mkdtemp(resolve(repo, 'tmp/useful-delivery-infrastructure-check-'));
const results = [];
const sample = { id: 'T01', rawPrompt: '请解释输入材料。' };
const valid = { syntheticOnly: true, cases: [sample], phaseLimits: { GENERATION: 3 }, jobs: [
  { id: 'T01_V46_GEN', caseId: 'T01', phase: 'GENERATION', version: '46', approvedEnhancementInput: { rawPrompt: sample.rawPrompt } }
] };
async function fixture(name, bundle) {
  const root = resolve(base, name);
  const target = resolve(root, 'docs/testing/evidence/fixture');
  await mkdir(target, { recursive: true });
  await copyFile(resolve(evidence, 'freeze-live.mjs'), resolve(target, 'freeze-live.mjs'));
  await copyFile(resolve(evidence, 'writer-thinking-profile.mjs'), resolve(target, 'writer-thinking-profile.mjs'));
  await copyFile(resolve(evidence, 'official-model-selection-profile.mjs'), resolve(target, 'official-model-selection-profile.mjs'));
  await writeFile(resolve(root, 'bundle.json'), JSON.stringify(bundle));
  return { root, target };
}
function freeze(value, stage = 'development') {
  return spawnSync(process.execPath, [resolve(value.target, 'freeze-live.mjs'), stage, 'bundle.json'], { cwd: value.root, encoding: 'utf8' });
}
for (const [name, bundle] of [
  ['unknown-version', { ...valid, jobs: [{ ...valid.jobs[0], version: '999' }] }],
  ['duplicate-job', { ...valid, jobs: [valid.jobs[0], valid.jobs[0]] }],
  ['excess-budget', { ...valid, phaseLimits: { GENERATION: 501 } }],
  ['different-input', { ...valid, jobs: [{ ...valid.jobs[0], approvedEnhancementInput: { rawPrompt: 'changed' } }] }],
  ['non-synthetic', { ...valid, syntheticOnly: false }]
]) {
  const value = await fixture(name, bundle);
  assert.notEqual(freeze(value).status, 0, name);
  results.push({ name, passed: true });
}
const good = await fixture('valid', valid);
assert.equal(freeze(good).status, 0);
const task = JSON.parse(await readFile(resolve(good.target, 'task-protocol.json'), 'utf8'));
assert.equal(task.selectedMaximumActualCalls, 500);
assert.equal(task.previousActualCalls, 0);
assert.notEqual(freeze(good).status, 0, 'Frozen stage cannot be overwritten');
results.push({ name: 'independent-500-and-frozen-stage', passed: true });
await writeFile(resolve(good.target, 'closure.json'), '{"status":"CLOSED"}');
assert.notEqual(freeze(good, 'new-stage').status, 0, 'Closed task cannot be reopened');
results.push({ name: 'closed-task', passed: true });
const capture = await fixture('capture-only', { syntheticOnly: true, captureOnly: true, cases: [], jobs: [], phaseLimits: {} });
assert.equal(freeze(capture).status, 0);
const captureProtocol = JSON.parse(await readFile(resolve(capture.target, 'development/protocol.json'), 'utf8'));
assert.equal(captureProtocol.maximumNewActualCalls, 0);
assert.deepEqual(captureProtocol.allowedJobs, []);
results.push({ name: 'capture-only-has-zero-model-jobs-and-zero-budget', passed: true });
const executionPayload = { model: 'deepseek-flash', messages: [{ role: 'user', content: '合成执行材料' }], temperature: 0.2,
  max_tokens: 16384, thinking: { type: 'disabled' }, stream: false };
const executionBundle = { syntheticOnly: true, cases: [sample], phaseLimits: { EXECUTION: 2 },
  parameters: { executionMaxTokens: 16384, generationMaxTokens: 8192 }, jobs: [
    { id: 'T01_ORIGINAL_EXEC', caseId: 'T01', phase: 'EXECUTION', version: 'ORIGINAL', approvedModelRequest: executionPayload },
    { id: 'T01_V47_EXEC', caseId: 'T01', phase: 'EXECUTION', version: '47', approvedModelRequest: executionPayload }
  ] };
const expanded = await fixture('execution-16384', executionBundle);
assert.equal(freeze(expanded).status, 0);
const expandedProtocol = JSON.parse(await readFile(resolve(expanded.target, 'development/protocol.json'), 'utf8'));
assert.equal(expandedProtocol.parameters.executionMaxTokens, 16384);
assert.equal(expandedProtocol.parameters.generationMaxTokens, 8192);
results.push({ name: 'future-stage-16384-same-information-output-capacity', passed: true });
const mixedBundle = structuredClone(executionBundle);
mixedBundle.jobs[1].approvedModelRequest.max_tokens = 8192;
assert.notEqual(freeze(await fixture('mixed-execution-limits', mixedBundle)).status, 0);
results.push({ name: 'mixed-execution-output-capacity-rejected', passed: true });
const mismatched = { ...executionBundle, parameters: { executionMaxTokens: 8192 } };
assert.notEqual(freeze(await fixture('declared-execution-mismatch', mismatched)).status, 0);
results.push({ name: 'declared-execution-output-capacity-mismatch-rejected', passed: true });
for (const limit of [10000, 2600]) {
  const sealedBudget = structuredClone(executionBundle);
  sealedBudget.parameters.executionMaxTokens = limit;
  for (const job of sealedBudget.jobs) job.approvedModelRequest.max_tokens = limit;
  const sealed = await fixture(`sealed-execution-${limit}`, sealedBudget);
  assert.equal(freeze(sealed).status, 0);
  const sealedProtocol = JSON.parse(await readFile(resolve(sealed.target, 'development/protocol.json'), 'utf8'));
  assert.equal(sealedProtocol.parameters.executionMaxTokens, limit);
  results.push({ name: `sealed-single-execution-output-capacity-${limit}`, passed: true });
}
console.log(JSON.stringify({ passed: results.length, failed: 0, modelCalls: 0, fixtureDirectory: base, results }));
