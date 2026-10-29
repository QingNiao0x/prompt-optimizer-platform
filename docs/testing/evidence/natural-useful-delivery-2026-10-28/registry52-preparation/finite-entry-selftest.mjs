import assert from 'node:assert/strict';
import { copyFile, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

// Only private temporary protocol fixtures are frozen; no formal stage, service, HTTP or model is started.
const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
const source = JSON.parse(await readFile(resolve(evidence, 'registry51-normal-preparation/completion-stage-bundle.json'), 'utf8'));
for (const job of source.jobs) { job.version = '52'; job.id = job.id.replace('_V51_', '_V52_'); }
const base = await mkdtemp(resolve(repo, 'tmp/registry52-finite-entry-check-'));
async function fixture(name, bundle) {
  const root = resolve(base, name), target = resolve(root, 'docs/testing/evidence/fixture'); await mkdir(target, { recursive: true });
  for (const file of ['freeze-live.mjs', 'writer-thinking-profile.mjs', 'official-model-selection-profile.mjs']) await copyFile(resolve(evidence, file), resolve(target, file));
  await writeFile(resolve(root, 'bundle.json'), JSON.stringify(bundle));
  return { root, target };
}
function freeze(value) { return spawnSync(process.execPath, [resolve(value.target, 'freeze-live.mjs'), 'offline-fixture', 'bundle.json'], { cwd: value.root, encoding: 'utf8' }); }
const good = await fixture('valid52', source); const result = freeze(good); assert.equal(result.status, 0, result.stderr);
const protocol = JSON.parse(await readFile(resolve(good.target, 'offline-fixture/protocol.json'), 'utf8'));
assert.deepEqual(protocol.generationPayloadVersions['52'], ['52']); assert.equal(protocol.maximumNewActualCalls, 8);
assert.equal(protocol.modelId, 'deepseek:deepseek-flash'); assert.equal(protocol.modelSelectionProfile, undefined);
assert.equal(protocol.parameters.generationMaxTokens, 8192); assert.equal(protocol.parameters.thinking, 'disabled');
const unknown = structuredClone(source); unknown.jobs[0].version = '53'; assert.notEqual(freeze(await fixture('unknown53', unknown)).status, 0);
const pro = structuredClone(source); pro.jobs[0].approvedEnhancementInput.modelId = 'deepseek:deepseek-v4-pro';
assert.notEqual(freeze(await fixture('unauthorized-pro52', pro)).status, 0);
const capture = structuredClone(source); capture.captureOnly = true; capture.captureVersions = ['52']; capture.jobs = [];
capture.phaseLimits = { GENERATION: 0, PLAN: 0, EXECUTION: 0, REVIEW: 0 };
const zero = await fixture('capture52', capture); const captureResult = freeze(zero); assert.equal(captureResult.status, 0, captureResult.stderr);
const captureProtocol = JSON.parse(await readFile(resolve(zero.target, 'offline-fixture/protocol.json'), 'utf8'));
assert.equal(captureProtocol.maximumNewActualCalls, 0); assert.deepEqual(captureProtocol.versions, ['52']); assert.deepEqual(captureProtocol.allowedJobs, []);
const report = { status: 'FUTURE52_FINITE_ENTRY_ONLY_NOT_FORMAL_FREEZE', passed: 4, failed: 0, actualModelCalls: 0,
  privateTemporaryFixture: base, expiredClosedInputsUsedOnlyForPrivateSchemaFixture: true,
  checks: ['52exactVersion8capFlashAccepted', 'unknown53Rejected', 'Pro52WithoutNewProfileRejected', '52CaptureOnlyZeroJobsZeroAllowance'], noOldFullMatrixRepeated: true };
await writeFile(resolve(here, 'finite-entry-offline-validation-v2.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify(report));
