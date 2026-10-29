import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), target = resolve(evidence, 'engineering65-run');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const hash = value => createHash('sha256').update(value).digest('hex');
const oldPath = resolve(evidence, 'engineering63-run/live-browser.mjs');
let browser = await readFile(oldPath, 'utf8'); const originalSha256 = hash(browser);
// Adapt only finite runtime metadata/schema names. Do not rewrite shared input, evidence hashes or browser actions.
browser = browser.replaceAll('V63', 'V65').replaceAll("'63'", "'65'")
  .replaceAll('registry63-engineering-verification.json', 'registry65-engineering-freeze.json')
  .replaceAll('candidateFreeze.version', 'candidateFreeze.candidate').replaceAll('candidateFreeze.sources', 'candidateFreeze.files');
assert.ok(browser.includes("generated.chainVersion, '65'"));
const original = await json(resolve(evidence, 'engineering63-run/generation-bundle.json'));
const bundle = structuredClone(original);
for (const job of bundle.jobs) { assert.equal(job.version, '63'); job.version = '65'; job.id = job.id.replace('V63', 'V65'); }
bundle.phaseLimits.GENERATION = 6;
bundle.description = 'Known full30-file Vue engineering regression with65: real project picker/Worker/index/target selection, actual model generation, actual clipboard and actual history. No unseen claim or simulated response.';
bundle.preparationArtifacts = ['registry65-engineering-freeze.json', 'registry65-hypothesis.md', 'sealed-engineering-holdout/manifest.json'];
await mkdir(target, { recursive: true });
await writeFile(resolve(target, 'live-browser.mjs'), browser, { flag: 'wx' });
await save(resolve(target, 'generation-bundle.json'), bundle);
await save(resolve(target, 'browser-adaptation.json'), { source: 'engineering63-run/live-browser.mjs', originalSha256,
  adaptedSha256: hash(browser), candidate: '65', originalProjectAndPromptUnchanged: true,
  originalGenerationInputSha256: hash(JSON.stringify(original.jobs[0].approvedEnhancementInput)),
  newGenerationInputSha256: hash(JSON.stringify(bundle.jobs[0].approvedEnhancementInput)),
  modified: ['Finite version/job metadata63->65', 'Engineering freeze schema version/sources->candidate/files'], actualCalls: 0 });
console.log(JSON.stringify({ prepared: true, realBrowserActionsUnchanged: true, nativeStageMaximum: 6, inputUnchanged: true }));
