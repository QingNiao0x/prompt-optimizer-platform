import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const sealed = resolve(here, '../sealed-engineering-holdout');
const hash = (value) => createHash('sha256').update(value).digest('hex');
const manifestBytes = await readFile(resolve(sealed, 'manifest.json'));
assert.equal(hash(manifestBytes), 'a7f9c19244af011966e6558e2aeb7877e3d8d466b7a6f4f6a2fa3be2be4311af');
const manifest = JSON.parse(manifestBytes);
const dto = JSON.parse(await readFile(resolve(here, 'capture/r3-E01/input.json'), 'utf8'));
const selected = new Set(dto.context.files.map((file) => file.path.split('#chunk-')[0]));
const language = (path) => path.endsWith('.vue') ? 'vue' : path.endsWith('.ts') ? 'typescript'
  : path.endsWith('.css') ? 'css' : path.endsWith('.json') ? 'json'
  : path.endsWith('.html') ? 'html' : path.endsWith('.md') ? 'markdown' : 'text';
const files = [];
for (const entry of manifest.files.filter((item) => item.path.startsWith('inputs/project/'))) {
  const bytes = await readFile(resolve(sealed, entry.path));
  assert.equal(hash(bytes), entry.sha256);
  const path = entry.path.slice('inputs/project/'.length);
  files.push({ path, content: bytes.toString('utf8'), language: language(path) });
}
assert.equal(files.length, 30);
const executorMaterials = files.filter((file) => !selected.has(file.path));
assert.equal(executorMaterials.length, 17);
const sourcePrompt = await readFile(resolve(sealed, 'inputs/prompt.md'), 'utf8');
assert.equal(sourcePrompt, dto.rawPrompt + '\n');
const executionProject = {
  caseId: 'E01', sourceManifestSha256: hash(manifestBytes), rawPrompt: sourcePrompt,
  sourcePromptSha256: hash(sourcePrompt), files,
  fileHashes: files.map((file) => ({ path: file.path, sha256: hash(file.content), bytes: Buffer.byteLength(file.content) })),
  allArmsUseTheseIdenticalWholeFiles: true,
  reconstructedFromChunks: false, containsAnswerCards: false,
};
await writeFile(resolve(here, 'execution-project.json'), JSON.stringify(executionProject, null, 2) + '\n', { flag: 'wx' });
const sample = {
  id: 'E01', rawPrompt: dto.rawPrompt, contextDescription: dto.context.customDescription ?? '', executorMaterials,
};
const bundle = {
  syntheticOnly: true, cases: [sample],
  jobs: ['31', '47'].map((version) => ({ id: `E01_V${version}_GEN`, caseId: 'E01',
    phase: 'GENERATION', version, approvedEnhancementInput: structuredClone(dto) })),
  phaseLimits: { GENERATION: 8, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  parameters: { generationMaxTokens: 8192, executionMaxTokens: 10000 },
};
for (const job of bundle.jobs) {
  assert.deepEqual(job.approvedEnhancementInput, dto);
  assert.ok(!sample.executorMaterials.some((file) => selected.has(file.path)));
}
const bundleText = JSON.stringify(bundle, null, 2) + '\n';
await writeFile(resolve(here, 'generation-bundle.json'), bundleText, { flag: 'wx' });
await writeFile(resolve(here, 'preparation-verdict.json'), JSON.stringify({
  caseId: 'E01', preparedOnly: true, stageFrozenByThisScript: false,
  modelCalls: 0, sourceManifestSha256: hash(manifestBytes), dtoFromActualWorkbench: true,
  enhancementContextFiles: dto.context.files.length, enhancementContextWasNotEnriched: true,
  executorOnlyUnseenFiles: executorMaterials.length, completeWholeExecutionFiles: files.length,
  executionProjectRebuiltFromChunks: false, allArmsMustUseSameExecutionProject: true,
  originalExecutionPromptIsSealedFullText: true, answerMaterialsIncluded: false,
  generationJobs: bundle.jobs.map((job) => job.id), phaseLimits: bundle.phaseLimits,
  generationMaxTokens: 8192, executionMaxTokens: 10000,
  bundleSha256: hash(bundleText), executionProjectSha256: hash(await readFile(resolve(here, 'execution-project.json'))),
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ caseId: 'E01', enhancementFiles: dto.context.files.length,
  executorOnlyFiles: executorMaterials.length, completeExecutionFiles: files.length,
  generationJobs: bundle.jobs.map((job) => job.id), generationMaxTokens: 8192,
  executionMaxTokens: 10000, modelCalls: 0, bundleSha256: hash(bundleText) }));
