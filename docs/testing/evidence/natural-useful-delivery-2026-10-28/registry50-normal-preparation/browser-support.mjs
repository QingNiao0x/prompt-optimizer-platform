import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, stat, readdir } from 'node:fs/promises';
import { dirname, isAbsolute, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadSyntheticProject } from '../fixtures/project.mjs';

export const here = dirname(fileURLToPath(import.meta.url));
export const evidence = resolve(here, '..');
export const repo = resolve(evidence, '../../../..');
export const webRoot = resolve(repo, 'apps/web');
export const backend = 'http://127.0.0.1:9014';
export const frontend = 'http://127.0.0.1:5184';
export const hash = value => createHash('sha256').update(value).digest('hex');
export const json = async file => JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));
export async function absent(file, message) {
  try { await stat(file); throw Error(message); } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
}

/** Read-only preflight. It neither starts a service nor creates stage state. */
export async function inspectStage(stage, id) {
  assert.match(stage ?? '', /^[a-z0-9-]+$/);
  const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', stage);
  const stageEvidence = resolve(evidence, stage);
  for (const directory of [evidence, stageEvidence, dirname(runtime), runtime]) {
    await absent(resolve(directory, 'closure.json'), 'Closed task/stage cannot be used');
  }
  const protocol = await json(resolve(stageEvidence, 'protocol.json'));
  assert.equal(protocol.status, 'FROZEN');
  assert.equal(protocol.qualityRetriesAllowed, false);
  assert.ok(protocol.versions.includes('50'));
  assert.equal(protocol.modelId, 'deepseek:deepseek-flash');
  assert.equal(protocol.parameters.temperature, 0.2);
  assert.equal(protocol.parameters.generationMaxTokens, 8192);
  assert.equal(protocol.parameters.thinking, 'disabled');
  assert.equal(protocol.parameters.stream, false);
  const manifestBytes = await readFile(resolve(stageEvidence, 'source-manifest.json'));
  const manifest = JSON.parse(manifestBytes);
  assert.ok(Array.isArray(manifest.files) && manifest.files.length > 0);
  const classes = resolve(runtime, 'classes');
  for (const entry of manifest.files) {
    assert.ok(isAbsolute(entry.path)); assert.match(entry.sha256, /^[a-f0-9]{64}$/);
    if (entry.path.endsWith('.class')) assert.ok(resolve(entry.path).startsWith(classes + sep), 'Frozen classes must belong to this stage');
    assert.equal(hash(await readFile(entry.path)), entry.sha256, 'Frozen runtime source-pack drifted');
  }
  let job;
  if (id) {
    assert.match(id, /^[A-Za-z0-9_]+$/);
    assert.ok(protocol.allowedJobs.includes(id));
    job = (await json(resolve(stageEvidence, 'jobs.json'))).find(item => item.id === id);
    assert.equal(job?.version, '50');
    await absent(resolve(runtime, 'results', `${id}.json`), 'Job already has a result; no repeat allowed');
    for (const file of (await readdir(resolve(runtime, 'traces'))).filter(name => name.endsWith('.json'))) {
      assert.notEqual((await json(resolve(runtime, 'traces', file))).jobId, id, 'Job was already attempted');
    }
  }
  return { runtime, stageEvidence, protocol, job, sourceManifestSha256: hash(manifestBytes), sourcePackFilesVerified: manifest.files.length };
}

/** Uses a genuine browser FileSystemDirectoryHandle; only the picker result is supplied by the fixture. */
export async function installProjectPicker(page, planEnabled) {
  const fixture = await loadSyntheticProject(); assert.equal(fixture.files.length, 79);
  await page.addInitScript(async ({ fixture, planEnabled }) => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: planEnabled, introSeen: true }));
    const storage = await navigator.storage.getDirectory();
    const directory = await storage.getDirectoryHandle(fixture.rootName, { create: true });
    for (const file of fixture.files) {
      let parent = directory; const parts = file.path.split('/');
      for (const part of parts.slice(0, -1)) parent = await parent.getDirectoryHandle(part, { create: true });
      const handle = await parent.getFileHandle(parts.at(-1), { create: true });
      const writer = await handle.createWritable(); await writer.write(file.content); await writer.close();
    }
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => directory });
    window.__registry50FixtureReady = true;
  }, { fixture, planEnabled });
  return fixture;
}

export async function inspectIndex(page, fixture) {
  const paths = await page.evaluate(async () => {
    const opening = indexedDB.open('prompt-optimizer-project-index');
    const db = await new Promise((resolve, reject) => { opening.onsuccess = () => resolve(opening.result); opening.onerror = () => reject(opening.error); });
    const request = db.transaction('files', 'readonly').objectStore('files').getAll();
    const files = await new Promise((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
    db.close(); return files.map(file => file.path).sort();
  });
  assert.deepEqual(paths, fixture.files.map(file => file.path).sort());
  return paths;
}

export function inspectRecall(input, fixture) {
  const paths = input.context.files.map(file => file.path.split('#chunk-')[0]);
  for (const required of fixture.targetPaths) assert.ok(paths.includes(required), 'A required target module entry was not recalled');
  assert.ok(!paths.some(path => path.startsWith('src/views/records-old/') || path.startsWith('src/views/records-archive')));
  for (const snippet of input.context.files) {
    const source = fixture.files.find(file => file.path === snippet.path.split('#chunk-')[0]);
    assert.ok(source && source.content.includes(snippet.content), 'Recalled code must come from the actual indexed fixture');
  }
  return paths;
}
