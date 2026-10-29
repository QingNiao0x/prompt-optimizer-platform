import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Only metadata was overlaid before opening. Original sealed requirements and material bytes stay intact.
const evidence = dirname(fileURLToPath(import.meta.url));
const sealed = resolve(evidence, 'sealed-registry54-holdout2');
const prepared = resolve(evidence, 'registry63-holdout-preparation');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const opening = await json(resolve(evidence, 'registry63-holdout-opening.json'));
const overlay = await json(resolve(evidence, 'registry63-holdout-metadata-overlay.json'));
const manifest = await json(resolve(sealed, 'manifest.json'));
assert.equal(opening.candidate, '63');
assert.equal(sha(await readFile(resolve(sealed, 'manifest.json'))), opening.sourceManifestSha256);
assert.equal(sha(await readFile(resolve(sealed, 'seal.json'))), opening.sourceSealSha256);
for (const file of manifest.payloadFiles) {
  const bytes = await readFile(resolve(sealed, file.path));
  assert.equal(bytes.length, file.byteLength);
  assert.equal(sha(bytes), file.sha256);
}
assert.equal(overlay.candidateArm, '63');
assert.equal(overlay.executionProfile.maxTokens, 8192);
const samples = (await json(resolve(sealed, 'cases.json'))).cases;
const template = (await json(resolve(evidence, 'registry63-raw-reservation/jobs.json')))[0].approvedEnhancementInput;
const jobs = [], inputs = [];
for (const sample of samples) {
  assert.equal(sample.actualAnswers.length, 0);
  const input = structuredClone(template);
  input.rawPrompt = sample.rawPrompt;
  input.context = { customDescription: sample.contextDescription, files: [] };
  for (const path of sample.materialPaths) input.context.files.push({ path, language: 'markdown', content: await readFile(resolve(sealed, path), 'utf8') });
  input.conversationHistory = structuredClone(sample.conversationHistory);
  input.planConfirmation = null;
  inputs.push({ caseId: sample.id, orderedMaterialSha256: input.context.files.map(file => ({ path: file.path, sha256: sha(file.content) })) });
  for (const version of ['31', '63']) jobs.push({ id: `${sample.id}_V${version}_GEN`, caseId: sample.id, phase: 'GENERATION', version, approvedEnhancementInput: structuredClone(input) });
}
await mkdir(prepared, { recursive: true });
await save(resolve(prepared, 'input-provenance.json'), { openingSha256: sha(await readFile(resolve(evidence, 'registry63-holdout-opening.json'))), materialSelection: 'All original sealed material paths in order; no answer cards or substantive criteria enter generation', inputs, actualCalls: 0 });
await save(resolve(prepared, 'generation-bundle.json'), {
  syntheticOnly: true, description: 'Opened internal holdout2; actual native31 versus frozen63. No topic tuning, no quality retries; originals unchanged under pre-opening metadata overlay.',
  cases: samples, jobs,
  phaseLimits: { GENERATION: 16, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 8192, thinking: 'disabled', stream: false },
  preparationArtifacts: ['registry63-holdout-opening.json', 'registry63-holdout-metadata-overlay.json', 'registry63-preholdout-gates.json', 'sealed-registry54-holdout2/manifest.json', 'sealed-registry54-holdout2/review-criteria.json'],
});
console.log(JSON.stringify({ prepared: true, actualCalls: 0, caseCount: samples.length, jobCount: jobs.length, generationMaximum: 16 }));
