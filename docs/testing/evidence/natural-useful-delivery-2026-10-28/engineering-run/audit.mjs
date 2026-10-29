import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, posix, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { editablePaths } from '../sealed-engineering-holdout/answer-cards/checker.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const sealed = resolve(here, '../sealed-engineering-holdout');
const capture = resolve(here, 'capture/r3-E01');
const hash = (value) => createHash('sha256').update(value).digest('hex');
const readJson = async (file) => JSON.parse(await readFile(file, 'utf8'));
const manifestBytes = await readFile(resolve(sealed, 'manifest.json'));
assert.equal(hash(manifestBytes), 'a7f9c19244af011966e6558e2aeb7877e3d8d466b7a6f4f6a2fa3be2be4311af');
const manifest = JSON.parse(manifestBytes);
const manifestChecks = [];
for (const entry of manifest.files) {
  const actual = await readFile(resolve(sealed, entry.path));
  const passed = actual.length === entry.bytes && hash(actual) === entry.sha256;
  manifestChecks.push({ path: entry.path, passed, expectedSha256: entry.sha256, actualSha256: hash(actual) });
  assert.ok(passed);
}
const entries = manifest.files.filter((entry) => entry.path.startsWith('inputs/project/'));
const project = Object.fromEntries(await Promise.all(entries.map(async (entry) =>
  [entry.path.slice('inputs/project/'.length), await readFile(resolve(sealed, entry.path), 'utf8')])));
const dto = await readJson(resolve(capture, 'input.json'));
const retrieval = await readJson(resolve(capture, 'retrieval.json'));
const indexed = await readJson(resolve(capture, 'indexed.json'));
const verdict = await readJson(resolve(capture, 'verdict.json'));
const originalPrompt = await readFile(resolve(sealed, 'inputs/prompt.md'), 'utf8');
const uiPrompt = await readFile(resolve(capture, 'ui-prompt.txt'), 'utf8');
assert.equal(uiPrompt, originalPrompt);
assert.equal(dto.rawPrompt + '\n', originalPrompt);
const indexedPaths = indexed.files.map((entry) => entry.path).sort();
assert.deepEqual(indexedPaths, Object.keys(project).sort());
const selectedPaths = [...new Set(dto.context.files.map((entry) => entry.path.split('#chunk-')[0]))];
const selectedChecks = dto.context.files.map((entry) => {
  const path = entry.path.split('#chunk-')[0];
  const wholeFileMatch = Object.hasOwn(project, path) && entry.content === project[path];
  assert.ok(wholeFileMatch);
  return { path, dtoPath: entry.path, characters: entry.content.length,
    wholeFileMatch, sourceSha256: hash(project[path]), dtoContentSha256: hash(entry.content) };
});

const resolveRelative = (from, specifier) => {
  const candidate = posix.normalize(posix.join(posix.dirname(from), specifier));
  return [candidate, `${candidate}.ts`, `${candidate}.vue`, `${candidate}/index.ts`]
    .find((entry) => Object.hasOwn(project, entry));
};
const graph = Object.fromEntries(Object.entries(project).map(([path, content]) => {
  const dependencies = [...content.matchAll(/(?:from\s+|import\s*)['"](\.[^'"]+)['"]/g)]
    .map((match) => ({ specifier: match[1], resolved: resolveRelative(path, match[1]) }));
  return [path, dependencies];
}));
const closure = new Set(editablePaths);
const queue = [...editablePaths];
for (let index = 0; index < queue.length; index += 1) {
  for (const edge of graph[queue[index]] ?? []) {
    assert.ok(edge.resolved, 'The sealed relative dependency must resolve');
    if (!closure.has(edge.resolved)) { closure.add(edge.resolved); queue.push(edge.resolved); }
  }
}
const dependencyPaths = [...closure].filter((path) => !editablePaths.includes(path)).sort();
const targetMiss = editablePaths.filter((path) => !selectedPaths.includes(path));
const dependencyMiss = dependencyPaths.filter((path) => !selectedPaths.includes(path));
const noisePaths = Object.keys(project).filter((path) => path.startsWith('src/legacy/') || path.startsWith('src/events/')
  || path === 'src/composables/useDepositPreview.ts' || path === 'src/views/DepositPage.vue').sort();
const audit = {
  caseId: 'E01', captureDirectory: 'capture/r3-E01', sourceManifestSha256: hash(manifestBytes),
  manifestChecks, projectFileCount: entries.length, indexedFileCount: indexedPaths.length,
  indexedPaths, indexedMiss: Object.keys(project).filter((path) => !indexedPaths.includes(path)),
  actualDtoContextFileCount: dto.context.files.length, selectedPaths,
  actualDtoContextCharacters: dto.context.files.reduce((sum, entry) => sum + entry.content.length, 0),
  selectedChecks, editablePaths, targetMiss, dependencyPaths, dependencyMiss,
  noisePaths, selectedNoise: noisePaths.filter((path) => selectedPaths.includes(path)),
  notSentToEnhancer: Object.keys(project).filter((path) => !selectedPaths.includes(path)).sort(),
  targetCoverage: targetMiss.length ? 'FAIL' : 'PASS',
  recursiveDependencyCoverage: dependencyMiss.length ? 'PARTIAL' : 'COMPLETE',
  fullProjectSentToEnhancer: selectedPaths.length === entries.length,
  absentFilesNotClaimedAnalyzed: true, dtoWasNotEnrichedOrReplaced: true,
  atSyntaxValidation: false, sourcePromptSha256: hash(originalPrompt), uiPromptSha256: hash(uiPrompt),
  normalizedDtoPromptSha256: hash(dto.rawPrompt), onlyPromptNormalization: 'existing platform trim removed one terminal LF',
  mockIdentity: verdict.mockIdentity, realAuthenticationVerified: false,
  zeroModelCaptureOnly: true, enhancementBlocked: true, modelCalls: 0, generatedBody: false, qualityEvidence: false,
  unexpectedApiRequests: verdict.unexpectedApiRequests, pageErrors: verdict.pageErrors,
  referenceResolutions: retrieval.references,
};
await writeFile(resolve(here, 'audit.json'), JSON.stringify(audit, null, 2) + '\n', { flag: 'wx' });
const executionContract = {
  caseId: 'E01', source: '../sealed-engineering-holdout/RUN_CONTRACT.md',
  sourceSha256: hash(await readFile(resolve(sealed, 'RUN_CONTRACT.md'))),
  rawPromptSource: '../sealed-engineering-holdout/inputs/prompt.md', rawPromptSourceSha256: hash(originalPrompt),
  sourceProject: '../sealed-engineering-holdout/inputs/project', projectFiles: entries.length,
  generationMaxTokens: 8192, executionMaxTokens: 10000,
  executionArms: ['ORIGINAL', 'V31', 'V47'],
  identicalExecutionSourceFilesForAllArms: true, sourceBytesFromSealedProjectOnly: true,
  allowedReplacementPaths: editablePaths, outputFormat: 'JSON full-file replacement package',
  hostCapabilitiesForArtifacts: [], artifactValidation: 'sealed checker, in-memory TS/SFC compilation and isolated real Vue VM renderer',
  realCallsPerformedByThisPreparation: 0,
};
await writeFile(resolve(here, 'execution-contract.json'), JSON.stringify(executionContract, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ caseId: 'E01', verifiedFiles: manifestChecks.length,
  indexed: indexedPaths.length, selected: selectedPaths.length, targetMiss, dependencyMiss,
  selectedNoise: audit.selectedNoise, sourceFilesAddedToDto: 0, modelCalls: 0 }));
