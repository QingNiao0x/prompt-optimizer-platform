import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, posix, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../../../../..');
const sealed = resolve(here, '../sealed-engineering-holdout');
const results = resolve(here, 'work-results/engineering-works47');
const hash = (value) => createHash('sha256').update(value).digest('hex');
const json = async (file) => JSON.parse(await readFile(file, 'utf8'));
const require = createRequire(resolve(repo, 'apps/web/package.json'));
const ts = require('typescript');
const sfc = require('@vue/compiler-sfc');
const manifestBytes = await readFile(resolve(sealed, 'manifest.json'));
assert.equal(hash(manifestBytes), 'a7f9c19244af011966e6558e2aeb7877e3d8d466b7a6f4f6a2fa3be2be4311af');
const manifest = JSON.parse(manifestBytes);
for (const entry of manifest.files) assert.equal(hash(await readFile(resolve(sealed, entry.path))), entry.sha256);
const project = Object.fromEntries(await Promise.all(manifest.files.filter((file) => file.path.startsWith('inputs/project/'))
  .map(async (file) => [file.path.slice('inputs/project/'.length), await readFile(resolve(sealed, file.path), 'utf8')])));
const contract = await json(resolve(here, 'execution-contract.json'));
const allowed = contract.allowedReplacementPaths;
const summaryBytes = await readFile(resolve(results, 'summary.json'));
const summary = JSON.parse(summaryBytes);
assert.equal(summary.allPassed, true);
const dangerousNames = new Set(['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'Worker', 'SharedWorker',
  'eval', 'Function', 'require', 'process', 'Buffer', 'globalThis', 'window', 'document', 'navigator',
  'localStorage', 'sessionStorage', 'indexedDB']);
const uniqueSources = new Set();
const arms = [];
const parsedArtifacts = {};

for (const arm of ['ORIGINAL', '31', '47']) {
  const jobId = `E01_${arm === 'ORIGINAL' ? arm : `V${arm}`}_EXEC`;
  const filename = `${jobId}-artifact.json`;
  const artifactBytes = await readFile(resolve(results, filename));
  const artifact = JSON.parse(artifactBytes);
  parsedArtifacts[arm] = artifact;
  const existingCheck = summary.results.find((item) => item.jobId === jobId);
  assert.equal(existingCheck.finishReason, 'stop');
  assert.equal(existingCheck.passedChecks, 28);
  assert.equal(existingCheck.checksExecuted, 28);
  assert.ok(artifact.files.every((file) => allowed.includes(file.path) && posix.normalize(file.path) === file.path));
  assert.equal(new Set(artifact.files.map((file) => file.path)).size, artifact.files.length);
  const overlaid = { ...project };
  const files = [];
  for (const file of artifact.files) {
    overlaid[file.path] = file.content;
    uniqueSources.add(hash(file.content));
    const pathLine = artifactBytes.toString('utf8').split('\n').findIndex((line) => line.includes(`"path": "${file.path}"`)) + 1;
    const script = file.path.endsWith('.vue')
      ? (() => { const parsed = sfc.parse(file.content, { filename: file.path });
        assert.equal(parsed.errors.length, 0); return parsed.descriptor.scriptSetup?.content ?? parsed.descriptor.script?.content ?? ''; })()
      : file.content;
    const ast = ts.createSourceFile(file.path.replace(/\.vue$/, '.ts'), script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    assert.equal(ast.parseDiagnostics.length, 0);
    const imports = [], dynamicImports = [], dangerousReferences = [];
    const walk = (node) => {
      if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier
        && ts.isStringLiteral(node.moduleSpecifier)) imports.push(node.moduleSpecifier.text);
      if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) dynamicImports.push(node.getText(ast));
      if (ts.isIdentifier(node) && dangerousNames.has(node.text)) dangerousReferences.push(node.text);
      ts.forEachChild(node, walk);
    };
    walk(ast);
    files.push({ path: file.path, artifactPhysicalPathLine: pathLine,
      sourceSha256: hash(project[file.path]), artifactContentSha256: hash(file.content),
      actuallyChanged: file.content !== project[file.path], imports,
      nonVueExternalImports: imports.filter((specifier) => !specifier.startsWith('.') && specifier !== 'vue'),
      dynamicImports, directDangerousIdentifierReferences: [...new Set(dangerousReferences)] });
  }
  const effectiveChanges = Object.keys(project).filter((path) => overlaid[path] !== project[path]);
  const unchangedReturned = files.filter((file) => !file.actuallyChanged).map((file) => file.path);
  const protectedPaths = Object.keys(project).filter((path) => !allowed.includes(path));
  const uiPaths = Object.keys(project).filter((path) => path.endsWith('.vue') || path.endsWith('.css'));
  arms.push({ arm, jobId, artifactFile: `work-results/engineering-works47/${filename}`, artifactSha256: hash(artifactBytes),
    upstreamAndSealedChecksPreviouslyRecorded: { finishReason: 'stop', checksExecuted: 28, passedChecks: 28 },
    returnedFiles: artifact.files.length, effectiveChangedFiles: effectiveChanges, unchangedReturnedFiles: unchangedReturned,
    allowedScopeOnly: files.every((file) => allowed.includes(file.path)),
    protectedFilesCompared: protectedPaths.length,
    protectedFilesByteUnchanged: protectedPaths.every((path) => overlaid[path] === project[path]),
    uiAndStyleFilesCompared: uiPaths.length,
    uiAndStyleFilesByteUnchanged: uiPaths.every((path) => overlaid[path] === project[path]),
    unrelatedExternalImportsOrDirectOperationsFound: files.some((file) => file.nonVueExternalImports.length
      || file.dynamicImports.length || file.directDangerousIdentifierReferences.length), files });
}
const originalAnd31SameContents = parsedArtifacts.ORIGINAL.files.every((file) =>
  parsedArtifacts['31'].files.find((other) => other.path === file.path)?.content === file.content)
  && parsedArtifacts.ORIGINAL.files.length === parsedArtifacts['31'].files.length;
assert.equal(originalAnd31SameContents, true);
assert.ok(arms.every((arm) => arm.allowedScopeOnly && arm.protectedFilesByteUnchanged && arm.uiAndStyleFilesByteUnchanged
  && !arm.unrelatedExternalImportsOrDirectOperationsFound));
const finding = {
  id: 'E01_STATIC_01', priority: 'P3', severity: 'minor delivery-contract deviation',
  title: 'Returned package includes unchanged files',
  originatingRequirement: 'files 只列本次真的修改的文件', specFile: '../sealed-engineering-holdout/inputs/prompt.md', specLine: 11,
  description: 'ORIGINAL and31 return six byte-identical replacements;47 returns two. Every returned path is permitted, but these entries do not satisfy the explicit only-actually-modified-files instruction.',
  evidence: arms.map((arm) => ({ arm: arm.arm, artifactFile: arm.artifactFile,
    unchangedReturnedFiles: arm.unchangedReturnedFiles, unchangedReturnedCount: arm.unchangedReturnedFiles.length,
    firstUnchangedPathLine: arm.files.find((file) => !file.actuallyChanged)?.artifactPhysicalPathLine })),
  runtimeImpactObserved: 'No behavioral loss: unchanged content is preserved; all existing28 checks pass.',
  checkerCoverageGap: 'The sealed package guard checks permitted paths and execution, but does not reject byte-identical replacements.',
  actionTaken: 'None. Original outputs, sealed input, and checker were retained without modification.',
  materialEnhancementBenefit: false,
};
const report = {
  caseId: 'E01', candidateVersion: '47', executionStage: 'engineering-works47', reviewType: 'read-only independent static review of actual artifact source',
  sourceManifestSha256: hash(manifestBytes), existingSummarySha256: hash(summaryBytes),
  checkerRerun: false, originalResultsOverwritten: false, modelCalls: 0, artifactHostExecution: false,
  reviewerLimit: 'Internal agent who authored this sealed task; not an external or blind expert reviewer. No48 instruction/source was read.',
  method: { fixedPoint: 'Sealed engineering source bytes and original prompt',
    actualFileEntriesRead: arms.reduce((sum, arm) => sum + arm.returnedFiles, 0), uniqueFullSourceTextsRead: uniqueSources.size,
    standardsAndSpecPasses: 'Performed separately in this agent; all concurrency slots were occupied.',
    staticParsingOnly: 'Existing TypeScript AST and Vue SFC parse; no source execution or new behavioral checks.',
    allActualSourcesManuallyRead: true },
  standardsAxis: { hardViolationsFound: [], smellJudgmentsRequiringAction: [],
    observations: ['The actual edits stay in the three shared domain/service files; public signatures and existing error codes remain.',
      'Imports remain relative project dependencies or the existing Vue package; no new dependency, host I/O, network, dynamic import, or dynamic code generation is present.',
      'Shared occupancy states are centralized and documented;47 adds comments explaining fresh snapshots and self-exclusion.'] },
  specAxis: { coreBehaviorViolationsFound: [], materialScopeViolationsFound: [], minorDeliveryFindings: [finding],
    observations: ['Site+kit matching, confirmed/collected occupancy, own-loan exclusion and nonnegative remainder are implemented in the shared rule.',
      'Positive safe-integer validation precedes writes; current snapshots replace preview cache use. readonly and missing-record/stock errors remain.',
      'Repository writes follow validation. Existing trim/revision behavior is delegated to the unchanged repository.',
      'Every Vue file and stylesheet is byte-identical to the sealed project. Unchanged composables retain failed input and success refresh.'] },
  arms, comparisons: { originalAnd31AllReturnedSourceContentsByteIdentical: originalAnd31SameContents,
    allThreeEffectiveChangedPathSetsIdentical: arms.every((arm) => JSON.stringify(arm.effectiveChangedFiles.slice().sort())
      === JSON.stringify(arms[0].effectiveChangedFiles.slice().sort())),
    coreBusinessOutcome: 'tie on this held-out engineering case',
    observed47Differences: 'Equivalent predicate ordering, direct shared-rule calls with fresh snapshots, explanatory comments, and fewer unchanged replacement entries.',
    substantiveEnhancementBenefitEstablished: false,
    smallerOutputPackageCountedAsBusinessGain: false },
  evidenceLimits: ['Existing28 results cover the frozen synthetic in-memory workflow and real Vue events in a VM host tree; they were not rerun here.',
    'Static review adds source-level scope and external-operation inspection, not a proof over all possible programs or all inputs.',
    'No native-browser geometry/CSS run, full TypeScript semantic typecheck, Vite production build, real backend concurrency, or production deployment is claimed.',
    'The current task has no backend; future asynchronous storage/locking behavior is outside this sealed project and is not introduced as a new gate.'],
  conclusion: 'All three retain the current UI and meet the core held-out behavior without out-of-scope edits or external operations. All three share a minor unchanged-file-list deviation. This case establishes no substantive47 enhancement gain.',
};
assert.equal(hash(await readFile(resolve(results, 'summary.json'))), hash(summaryBytes));
for (const entry of manifest.files) assert.equal(hash(await readFile(resolve(sealed, entry.path))), entry.sha256);
await writeFile(resolve(here, 'static-review-independent.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ caseId: 'E01', checkerRerun: false, modelCalls: 0,
  entriesRead: report.method.actualFileEntriesRead, uniqueSources: uniqueSources.size,
  standardsFindings: 0, coreSpecFindings: 0, minorSpecFindings: 1,
  unchangedReturnedCounts: arms.map((arm) => ({ arm: arm.arm, count: arm.unchangedReturnedFiles.length })),
  originalAnd31AllContentsIdentical: originalAnd31SameContents, coreBusinessOutcome: 'tie' }));
