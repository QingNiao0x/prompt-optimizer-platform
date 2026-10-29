import assert from 'node:assert/strict';
import { copyFile, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { validateOfficialModelSelectionBundle } from '../official-model-selection-profile.mjs';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
const original = JSON.parse(await readFile(resolve(here, 'development-bundle.json'), 'utf8'));
const results = [];
await validateOfficialModelSelectionBundle(original, { evidence }); results.push('exact-native-Pro50-draft-accepted');
for (const [name, mutate] of [
  ['endpoint-override', value => { value.modelSelectionProfile.endpoint = 'https://unapproved.invalid'; }],
  ['new-route', value => { value.modelSelectionProfile.modelId = 'tokenhub:deepseek-v4-pro-0813'; }],
  ['temperature-override', value => { value.parameters.temperature = 0.8; }],
  ['output-budget-override', value => { value.parameters.generationMaxTokens = 32768; }],
  ['effort-override', value => { value.parameters.reasoning_effort = 'high'; }],
  ['new51-code', value => { value.jobs[0].version = '51'; }],
  ['altered-input-material', value => { value.jobs[0].approvedEnhancementInput.context.customDescription = 'unapproved synthetic material'; }],
  ['baseline-hash-drift', value => { value.modelSelectionProfile.baselineJobsSha256 = '0'.repeat(64); }],
]) {
  const bundle = structuredClone(original); mutate(bundle);
  await assert.rejects(validateOfficialModelSelectionBundle(bundle, { evidence })); results.push(`${name}-rejected`);
}
const base = await mkdtemp(resolve(repo, 'tmp/native-model-selection-profile-check-'));
const target = resolve(base, 'docs/testing/evidence/fixture'); await mkdir(resolve(target, 'registry50-development'), { recursive: true });
for (const name of ['freeze-live.mjs', 'writer-thinking-profile.mjs', 'official-model-selection-profile.mjs']) await copyFile(resolve(evidence, name), resolve(target, name));
for (const name of ['protocol.json', 'jobs.json', 'closure.json']) await copyFile(resolve(evidence, 'registry50-development', name), resolve(target, 'registry50-development', name));
const draft = resolve(base, 'bundle.json'); await writeFile(draft, JSON.stringify(original));
const freeze = stage => spawnSync(process.execPath, [resolve(target, 'freeze-live.mjs'), stage, 'bundle.json'], { cwd: base, encoding: 'utf8' });
const first = freeze('model-choice-fixture'); assert.equal(first.status, 0, first.stderr);
const protocol = JSON.parse(await readFile(resolve(target, 'model-choice-fixture/protocol.json'), 'utf8'));
assert.equal(protocol.modelId, 'deepseek:deepseek-v4-pro'); assert.equal(protocol.upstreamModel, 'deepseek-v4-pro');
assert.equal(protocol.endpoint, 'https://api.deepseek.com/chat/completions'); assert.equal(protocol.maximumNewActualCalls, 12);
assert.equal(protocol.experimentProfile, undefined); assert.equal(protocol.modelSelectionProfile.name, 'OFFICIAL_MODEL_SELECTION_ABLATION_V1');
results.push('private-offline-freeze-derives-only-the-approved-native-route-and-unchanged-cap');
assert.notEqual(freeze('second-model-choice-fixture').status, 0); results.push('native-model-quality-repeat-blocked');
const report = { status: 'FUTURE_NATIVE_MODEL_PROFILE_BOUNDARIES_NOT_QUALITY', actualModelCalls: 0, passed: results.length, failed: 0,
  formalTaskFrozen: false, temporaryFixture: base, results };
await writeFile(resolve(here, 'profile-offline-validation.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify(report));
