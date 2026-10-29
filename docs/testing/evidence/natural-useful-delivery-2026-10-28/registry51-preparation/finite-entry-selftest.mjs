import assert from 'node:assert/strict';
import { copyFile, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
const source = JSON.parse(await readFile(resolve(here, 'development-bundle.json'), 'utf8'));
const base = await mkdtemp(resolve(repo, 'tmp/registry51-finite-entry-check-'));
async function fixture(name, bundle) {
  const root = resolve(base, name), target = resolve(root, 'docs/testing/evidence/fixture'); await mkdir(target, { recursive: true });
  for (const file of ['freeze-live.mjs', 'writer-thinking-profile.mjs', 'official-model-selection-profile.mjs']) await copyFile(resolve(evidence, file), resolve(target, file));
  await writeFile(resolve(root, 'bundle.json'), JSON.stringify(bundle));
  return { root, target };
}
function freeze(value) { return spawnSync(process.execPath, [resolve(value.target, 'freeze-live.mjs'), 'offline-fixture', 'bundle.json'], { cwd: value.root, encoding: 'utf8' }); }
const good = await fixture('valid51', source); const result = freeze(good); assert.equal(result.status, 0, result.stderr);
const protocol = JSON.parse(await readFile(resolve(good.target, 'offline-fixture/protocol.json'), 'utf8'));
assert.deepEqual(protocol.generationPayloadVersions['51'], ['51']); assert.equal(protocol.maximumNewActualCalls, 12);
assert.equal(protocol.modelId, 'deepseek:deepseek-flash'); assert.equal(protocol.modelSelectionProfile, undefined);
const unknown = structuredClone(source); unknown.jobs[0].version = '52'; assert.notEqual(freeze(await fixture('unknown52', unknown)).status, 0);
const pro = structuredClone(source); pro.jobs[0].approvedEnhancementInput.modelId = 'deepseek:deepseek-v4-pro';
assert.notEqual(freeze(await fixture('unauthorized-pro51', pro)).status, 0);
const report = { status: 'FUTURE51_FINITE_ENTRY_ONLY_NOT_FORMAL_FREEZE', passed: 3, failed: 0, actualModelCalls: 0,
  privateTemporaryFixture: base, checks: ['51exactVersion12capFlashAccepted', 'unknown52Rejected', 'Pro51WithoutNewProfileRejected'],
  noOldFullMatrixRepeated: true };
await writeFile(resolve(here, 'finite-entry-offline-validation.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify(report));
