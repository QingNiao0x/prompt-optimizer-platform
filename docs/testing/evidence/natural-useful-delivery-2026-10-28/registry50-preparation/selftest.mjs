import assert from 'node:assert/strict';
import { copyFile, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
const base = await mkdtemp(resolve(repo, 'tmp/registry50-future-entry-check-'));
const source = JSON.parse(await readFile(resolve(evidence, 'registry50-development-bundle.json'), 'utf8'));
const tests = [];
const run = (script, args, cwd) => spawnSync(process.execPath, [script, ...args], { cwd, encoding: 'utf8' });
async function fixture(name, bundle) {
  const root = resolve(base, name), target = resolve(root, 'docs/testing/evidence/fixture');
  await mkdir(target, { recursive: true });
  for (const file of ['freeze-live.mjs', 'writer-thinking-profile.mjs', 'official-model-selection-profile.mjs', 'close-stage.mjs']) await copyFile(resolve(evidence, file), resolve(target, file));
  await writeFile(resolve(root, 'bundle.json'), JSON.stringify(bundle));
  return { root, target };
}
function freeze(value) { return run(resolve(value.target, 'freeze-live.mjs'), ['offline-fixture', 'bundle.json'], value.root); }
const valid = await fixture('finite50', source);
assert.equal(freeze(valid).status, 0); tests.push('future50-finite-entry-accepted-only-in-offline-fixture');
const protocol = JSON.parse(await readFile(resolve(valid.target, 'offline-fixture/protocol.json'), 'utf8'));
assert.deepEqual(protocol.generationPayloadVersions['50'], ['50']);
assert.deepEqual(protocol.generationPayloadVersions['49'], ['49']);
assert.equal(protocol.maximumNewActualCalls, 12); tests.push('50-and49-exact-generation-version-mapping-cap12');
assert.notEqual(freeze(valid).status, 0); tests.push('existing-frozen-fixture-not-overwritten');
const unknown = structuredClone(source); unknown.jobs[0].version = '51';
assert.notEqual(freeze(await fixture('unknown51', unknown)).status, 0); tests.push('unknown51-not-allowed');
const past = structuredClone(source); for (const job of past.jobs) { job.version = '49'; job.id = job.id.replace('_V50_', '_V49_'); }
assert.equal(freeze(await fixture('old49', past)).status, 0); tests.push('49-remains-explicitly-accepted');
const over = structuredClone(source); over.phaseLimits.GENERATION = 501;
assert.notEqual(freeze(await fixture('over500', over)).status, 0); tests.push('task500-boundary-not-expanded');
async function eventFixture(name, version) {
  const value = await fixture(name, source); assert.equal(freeze(value).status, 0);
  const runtime = resolve(value.root, 'tmp/natural-useful-delivery-2026-10-28/offline-fixture');
  await mkdir(resolve(runtime, 'traces'), { recursive: true });
  await mkdir(resolve(runtime, 'guard-events'), { recursive: true });
  const event = { jobId: 'Q01_V50_GEN', phase: 'GENERATION', version, code: 'GUARD49_DERIVED_REGISTRY_DRIFT',
    upstreamRequestSent: false, actualModelCalls: 0, classification: 'TOOLING_OR_TRANSPORT_BOUNDARY_NOT_MODEL_SEMANTIC_FAILURE' };
  await writeFile(resolve(runtime, 'guard-events/Q01_V50_GEN-001.json'), JSON.stringify(event));
  return { ...value, runtime, event };
}
const safe = await eventFixture('50-safe-closure', '50');
const closed = run(resolve(safe.target, 'close-stage.mjs'), ['offline-fixture', '0'], safe.root);
assert.equal(closed.status, 0, closed.stderr);
const closure = JSON.parse(await readFile(resolve(safe.target, 'offline-fixture/closure.json'), 'utf8'));
assert.equal(closure.actualCalls, 0); assert.equal(closure.entryLockedByClosure, true);
assert.equal(closure.qualityAdoptionClaimed, false); tests.push('50-zero-upstream-boundary-closes-with-zero-model-count-no-adoption');
assert.equal(await readFile(resolve(safe.runtime, 'guard-events/Q01_V50_GEN-001.json'), 'utf8'),
  await readFile(resolve(safe.target, 'offline-fixture/guard-events/Q01_V50_GEN-001.json'), 'utf8'));
tests.push('50-safe-boundary-original-preserved-byte-for-byte');
assert.notEqual(run(resolve(safe.target, 'close-stage.mjs'), ['offline-fixture', '0'], safe.root).status, 0);
tests.push('50-closed-fixture-not-reopened');
const wrong = await eventFixture('foreign49-event-under50', '49');
assert.notEqual(run(resolve(wrong.target, 'close-stage.mjs'), ['offline-fixture', '0'], wrong.root).status, 0);
tests.push('49-event-cannot-masquerade-as50-frozen-stage');
const output = { status: 'FUTURE_ENTRY_BOUNDARIES_ONLY_NOT_QUALITY', passed: tests.length, failed: 0,
  actualModelCalls: 0, privateTemporaryFixture: base, formalTaskFreezeOrStartPerformed: false, tests };
await writeFile(resolve(here, 'future-entry-selftest.json'), JSON.stringify(output, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify(output));
