import assert from 'node:assert/strict';
import { readFile, readdir, stat } from 'node:fs/promises';
import { dirname, isAbsolute, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
export { hash, json, absent, installProjectPicker, inspectIndex, inspectRecall } from '../registry50-normal-preparation/browser-support.mjs';
import { hash, json, absent } from '../registry50-normal-preparation/browser-support.mjs';
export const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
export const webRoot = resolve(repo, 'apps/web'), backend = 'http://127.0.0.1:9014', frontend = 'http://127.0.0.1:5184';

/** Match start-live's product snapshot and temporary runner roots; another stage or current target is never allowed. */
export function stageOwnsClassPath(runtime, file) {
  const actual = resolve(file);
  return ['runtime/classes', 'runner'].some(root => actual.startsWith(resolve(runtime, root) + sep));
}

/** Candidate51 uses the existing Flash harness, not a model/experiment profile. */
export async function inspectStage(stage, id) {
  assert.match(stage ?? '', /^[a-z0-9-]+$/);
  const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', stage), stageEvidence = resolve(evidence, stage);
  for (const root of [evidence, stageEvidence, dirname(runtime), runtime]) await absent(resolve(root, 'closure.json'));
  const protocol = await json(resolve(stageEvidence, 'protocol.json'));
  assert.equal(protocol.status, 'FROZEN'); assert.equal(protocol.modelSelectionProfile, undefined); assert.equal(protocol.experimentProfile, undefined);
  assert.equal(protocol.modelId, 'deepseek:deepseek-flash'); assert.equal(protocol.upstreamModel, 'deepseek-flash');
  assert.equal(protocol.endpoint, 'https://api.deepseek.com/chat/completions'); assert.deepEqual(protocol.versions, ['51']);
  assert.equal(protocol.parameters.generationMaxTokens, 8192); assert.equal(protocol.parameters.temperature, 0.2);
  assert.equal(protocol.parameters.thinking, 'disabled'); assert.equal(protocol.parameters.stream, false);
  assert.equal(protocol.qualityRetriesAllowed, false);
  const jobs = await json(resolve(stageEvidence, 'jobs.json'));
  const allowed = jobs.length === 5 ? ['C01', 'C02', 'V01', 'V02', 'V03'].map(caseId => caseId + '_V51_GEN')
    : jobs.every(job => job.phase === 'PLAN') ? ['P01_V51_PLAN', 'P02_V51_PLAN'] : ['P01_V51_CONFIRMED_GEN', 'P02_V51_CONFIRMED_GEN'];
  assert.deepEqual(jobs.map(job => job.id).sort(), [...allowed].sort());
  const cap = jobs.length === 5 ? 20 : 8, phase = jobs.length === 5 || !jobs.every(job => job.phase === 'PLAN') ? 'GENERATION' : 'PLAN';
  assert.equal(protocol.maximumNewActualCalls, cap);
  for (const name of ['GENERATION', 'PLAN', 'EXECUTION', 'REVIEW']) assert.equal(protocol.phaseLimits[name], name === phase ? cap : 0);
  for (const job of jobs) assert.ok(job.phase === phase && job.version === '51' && job.approvedEnhancementInput.modelId === 'deepseek:deepseek-flash');
  const manifestBytes = await readFile(resolve(stageEvidence, 'source-manifest.json')), manifest = JSON.parse(manifestBytes);
  assert.ok(manifest.files?.length);
  for (const file of manifest.files) {
    assert.ok(isAbsolute(file.path));
    if (file.path.endsWith('.class')) assert.ok(stageOwnsClassPath(runtime, file.path));
    assert.equal(hash(await readFile(file.path)), file.sha256);
  }
  let job;
  if (id) {
    assert.ok(protocol.allowedJobs.includes(id)); job = jobs.find(item => item.id === id); assert.ok(job);
    await absent(resolve(runtime, 'results', id + '.json'));
    for (const file of (await readdir(resolve(runtime, 'traces'))).filter(name => name.endsWith('.json'))) assert.notEqual((await json(resolve(runtime, 'traces', file))).jobId, id);
  }
  return { runtime, stageEvidence, protocol, job, sourceManifestSha256: hash(manifestBytes), sourcePackFilesVerified: manifest.files.length };
}

/** Explicitly click the original public Flash option; no store mutation. */
export async function selectFlash(page, context, expect) {
  const response = await context.request.get(backend + '/api/v1/models'); assert.equal(response.status(), 200);
  const candidates = (await response.json()).data.filter(item => item.id === 'deepseek:deepseek-flash');
  assert.equal(candidates.length, 1); assert.equal(candidates[0].provider, 'deepseek');
  const control = page.getByRole('combobox', { name: '选择模型版本', exact: true });
  await expect(control).toBeEnabled();
  // Element Plus overlays the readonly input with its selected-item span.
  // Click the actual user-facing container rather than forcing through that span.
  await page.locator('.model-control .el-select').click();
  const option = page.getByRole('option', { name: candidates[0].displayName.trim(), exact: true });
  await expect(option).toHaveCount(1); await option.click();
  const selected = await page.evaluate(async () => (await import('/src/stores/optimization.ts')).useOptimizationStore().selectedModelId);
  assert.equal(selected, 'deepseek:deepseek-flash');
  return { publicFlashOptionClicked: true, selectedModelId: selected };
}
