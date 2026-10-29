import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, stat, readdir } from 'node:fs/promises';
import { dirname, resolve, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
export { installProjectPicker, inspectIndex, inspectRecall } from '../registry50-normal-preparation/browser-support.mjs';

export const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
export const webRoot = resolve(repo, 'apps/web'), backend = 'http://127.0.0.1:9014', frontend = 'http://127.0.0.1:5184';
export const modelId = 'deepseek:deepseek-v4-pro';
export const json = async file => JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));
export const sha = bytes => createHash('sha256').update(bytes).digest('hex');
export async function absent(path) {
  try { await stat(path); throw Error('Existing artifact/closed stage must not be reused'); } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
}

/** Planned names are inert until infrastructure explicitly implements and freezes them. */
export async function inspectFutureStage(stage, profileName, cap, jobId) {
  assert.match(stage ?? '', /^[a-z0-9-]+$/);
  const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', stage), stageEvidence = resolve(evidence, stage);
  for (const dir of [evidence, dirname(runtime), runtime, stageEvidence]) await absent(resolve(dir, 'closure.json'));
  const protocol = await json(resolve(stageEvidence, 'protocol.json'));
  assert.equal(protocol.status, 'FROZEN'); assert.equal(protocol.modelSelectionProfile?.name, profileName);
  assert.equal(protocol.modelId, modelId); assert.equal(protocol.upstreamModel, 'deepseek-v4-pro');
  assert.equal(protocol.endpoint, 'https://api.deepseek.com/chat/completions');
  assert.equal(protocol.maximumNewActualCalls, cap); assert.equal(protocol.qualityRetriesAllowed, false);
  assert.deepEqual(protocol.versions, ['50']); assert.equal(protocol.parameters.generationMaxTokens, 8192);
  assert.equal(protocol.parameters.temperature, 0.2); assert.equal(protocol.parameters.thinking, 'disabled'); assert.equal(protocol.parameters.stream, false);
  const manifestBytes = await readFile(resolve(stageEvidence, 'source-manifest.json')), manifest = JSON.parse(manifestBytes);
  assert.ok(manifest.files?.length);
  for (const file of manifest.files) {
    assert.ok(isAbsolute(file.path));
    if (file.path.endsWith('.class')) assert.ok(resolve(file.path).startsWith(resolve(runtime, 'classes') + sep));
    assert.equal(sha(await readFile(file.path)), file.sha256, 'Frozen source-pack drift');
  }
  let job;
  if (jobId) {
    assert.ok(protocol.allowedJobs.includes(jobId));
    job = (await json(resolve(stageEvidence, 'jobs.json'))).find(item => item.id === jobId);
    assert.equal(job?.version, '50'); assert.equal(job.approvedEnhancementInput.modelId, modelId);
    await absent(resolve(runtime, 'results', jobId + '.json'));
    for (const file of (await readdir(resolve(runtime, 'traces'))).filter(name => name.endsWith('.json'))) assert.notEqual((await json(resolve(runtime, 'traces', file))).jobId, jobId);
  }
  return { protocol, job, runtime, sourceManifestSha256: sha(manifestBytes), sourcePackFilesVerified: manifest.files.length };
}

/** Click the actual published option; no store assignment or request metadata rewriting. */
export async function selectNativePro(page, context, expect) {
  const result = await context.request.get(backend + '/api/v1/models'); assert.equal(result.status(), 200);
  const matches = (await result.json()).data.filter(item => item.id === modelId);
  assert.equal(matches.length, 1); assert.equal(matches[0].provider, 'deepseek');
  const display = matches[0].displayName.trim();
  const label = display.toLowerCase() === 'deepseek-v4-pro-0813' ? 'DeepSeek-V4-Pro' : display;
  const control = page.getByRole('combobox', { name: '选择模型版本', exact: true });
  await expect(control).toBeEnabled(); await control.click();
  const option = page.getByRole('option', { name: label, exact: true });
  await expect(option).toHaveCount(1); await option.click();
  const selected = await page.evaluate(async () => (await import('/src/stores/optimization.ts')).useOptimizationStore().selectedModelId);
  assert.equal(selected, modelId);
  return { publicCatalogModelFound: true, uiOptionClicked: true, selectedModelId: selected, displayedLabel: label };
}
