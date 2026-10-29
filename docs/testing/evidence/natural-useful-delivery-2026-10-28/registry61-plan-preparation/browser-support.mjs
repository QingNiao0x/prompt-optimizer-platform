import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stageOwnsClassPath } from '../registry51-normal-preparation/browser-support.mjs';
export { installProjectPicker, inspectIndex, inspectRecall, selectFlash, json, hash } from '../registry51-normal-preparation/browser-support.mjs';
import { json, hash, absent } from '../registry51-normal-preparation/browser-support.mjs';

export const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
export const webRoot = resolve(repo, 'apps/web'), backend = 'http://127.0.0.1:9014', frontend = 'http://127.0.0.1:5184';
export const branchIds = ['P02_CONFIRMED', 'P02_UNKNOWN'];

/** Only a frozen zero-budget capture stage may prepare real context; no model job is executable here. */
export function validateCaptureProtocol(protocol, jobs) {
  assert.equal(protocol.status, 'FROZEN'); assert.equal(protocol.captureOnly, true);
  assert.deepEqual(protocol.versions, ['61']); assert.equal(protocol.maximumNewActualCalls, 0);
  assert.deepEqual(jobs, []); assert.deepEqual(protocol.allowedJobs, []);
  assert.equal(protocol.qualityRetriesAllowed, false);
  assert.equal(protocol.modelSelectionProfile, undefined); assert.equal(protocol.experimentProfile, undefined);
  assert.equal(protocol.modelId, 'deepseek:deepseek-flash'); assert.equal(protocol.upstreamModel, 'deepseek-flash');
  assert.equal(protocol.endpoint, 'https://api.deepseek.com/chat/completions');
  for (const phase of ['GENERATION', 'PLAN', 'EXECUTION', 'REVIEW']) assert.equal(protocol.phaseLimits[phase], 0);
  assert.equal(protocol.parameters.generationMaxTokens, 8192); assert.equal(protocol.parameters.temperature, 0.2);
  assert.equal(protocol.parameters.thinking, 'disabled'); assert.equal(protocol.parameters.stream, false);
}

/** Read only: reject closure and verify the actual stage source-pack, without creating active-job or traces. */
export async function inspectCaptureStage(stage) {
  assert.match(stage ?? '', /^[a-z0-9-]+$/);
  const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', stage), stageEvidence = resolve(evidence, stage);
  for (const root of [evidence, stageEvidence, dirname(runtime), runtime]) await absent(resolve(root, 'closure.json'));
  const protocol = await json(resolve(stageEvidence, 'protocol.json'));
  validateCaptureProtocol(protocol, await json(resolve(stageEvidence, 'jobs.json')));
  const manifestBytes = await readFile(resolve(stageEvidence, 'source-manifest.json')), manifest = JSON.parse(manifestBytes);
  assert.ok(manifest.files?.length);
  for (const entry of manifest.files) {
    assert.ok(isAbsolute(entry.path)); assert.match(entry.sha256, /^[a-f0-9]{64}$/);
    if (entry.path.endsWith('.class')) assert.ok(stageOwnsClassPath(runtime, entry.path));
    assert.equal(hash(await readFile(entry.path)), entry.sha256);
  }
  return { runtime, stageEvidence, protocol, sourceManifestSha256: hash(manifestBytes), sourcePackFilesVerified: manifest.files.length };
}

/** Both branches keep the original reserved decision intact; only future actual answers may differ. */
export async function loadCases() {
  const original = (await json(resolve(here, '../registry51-normal-preparation/plan-cases.json'))).cases.find(item => item.id === 'P02');
  assert.ok(original?.rawPrompt);
  return branchIds.map(id => ({ id, rawPrompt: original.rawPrompt,
    futurePlanJobId: `${id}_V61_PLAN`, futureGenerationJobId: `${id}_V61_GEN` }));
}
