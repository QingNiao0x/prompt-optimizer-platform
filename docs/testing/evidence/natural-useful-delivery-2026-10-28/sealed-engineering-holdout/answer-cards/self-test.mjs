import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { evaluateArtifact, projectRoot } from './checker.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const reference = JSON.parse(fs.readFileSync(path.join(here, 'reference-files.json'), 'utf8'));
const refFile = (filename) => reference.files.find((item) => item.path === filename).content;
const changeReference = (filename, content) => ({ files: reference.files.map((item) =>
  item.path === filename ? { path: filename, content } : { ...item }) });
const results = [];
const run = (id, artifact, expected, options = {}) => {
  const result = evaluateArtifact(artifact, options);
  assert.equal(result.passed, expected, `${id} unexpected acceptance`);
  results.push({ id, accepted: result.passed, stage: result.stage,
    checks: result.checks.length, passedChecks: result.checks.filter((item) => item.passed).length,
    failedIds: result.checks.filter((item) => !item.passed).map((item) => item.id), error: result.error });
  return result;
};

const baseline = run('original', { files: [] }, false);
assert.equal(baseline.checks.length, 28);
assert.ok(baseline.checks.some((item) => item.passed));
const valid = run('reference', reference, true);
assert.equal(valid.checks.length, 28);

const originalService = fs.readFileSync(path.join(projectRoot, 'src/application/loanDeskService.ts'), 'utf8');
const staleService = originalService.replace('{ siteId: loan.siteId, kitId: loan.kitId });',
  '{ siteId: loan.siteId, kitId: loan.kitId, excludeLoanId: loan.id });');
const stale = run('mutant-stale-preview', changeReference('src/application/loanDeskService.ts', staleService), false);
assert.ok(stale.checks.some((item) => item.id === 'B11' && !item.passed));
assert.ok(stale.checks.some((item) => item.id === 'B12' && !item.passed));

const incorrectStatus = refFile('src/domain/availability.ts').replace(
  "(loan.status === 'CONFIRMED' || loan.status === 'COLLECTED')", "loan.status === 'CONFIRMED'");
run('mutant-status', changeReference('src/domain/availability.ts', incorrectStatus), false);
const incorrectScope = refFile('src/domain/availability.ts').replace('loan.siteId === query.siteId && ', '');
run('mutant-scope', changeReference('src/domain/availability.ts', incorrectScope), false);
const incorrectSelf = refFile('src/application/loanDeskService.ts').replace(', excludeLoanId: loan.id', '');
run('mutant-self-exclusion', changeReference('src/application/loanDeskService.ts', incorrectSelf), false);
const incorrectQuantity = refFile('src/domain/quantity.ts').replace('Number.isSafeInteger(quantity)', 'Number.isFinite(quantity)');
run('mutant-quantity', changeReference('src/domain/quantity.ts', incorrectQuantity), false);

for (const [id, filename] of [
  ['guard-protected-module', 'src/legacy/availability.ts'],
  ['guard-traversal', '../outside.ts'],
  ['guard-package', 'package.json'],
]) {
  const result = run(id, { files: [{ path: filename, content: 'export const changed = true;' }] }, false);
  assert.equal(result.stage, 'artifact');
}
const duplicate = reference.files[0];
assert.equal(run('guard-duplicate', { files: [duplicate, duplicate] }, false).stage, 'artifact');

const quantity = refFile('src/domain/quantity.ts');
const nativeImport = run('sandbox-native-module', changeReference('src/domain/quantity.ts',
  "import fs from 'node:fs';\nconst forbidden = fs;\n" + quantity), false);
assert.match(nativeImport.error ?? '', /EXTERNAL_MODULE_DENIED/);
for (const [id, expression] of [
  ['sandbox-constructor', "({}).constructor.constructor('return typeof process')()"],
  ['sandbox-global-constructor', "globalThis.constructor.constructor('return typeof process')()"],
]) {
  const result = run(id, changeReference('src/domain/quantity.ts', `const forbidden = ${expression};\n${quantity}`), false);
  assert.match(result.error ?? '', /Code generation from strings disallowed/);
}
const timeout = run('sandbox-timeout', changeReference('src/domain/quantity.ts',
  `while (true) {}\n${quantity}`), false, { timeout: 150 });
assert.match(timeout.error ?? '', /timed out/);

const report = {
  passed: true,
  baselineFailed: !baseline.passed,
  referencePassed: valid.passed,
  businessAndUiChecks: valid.checks.length,
  mutationCases: 5,
  pathGuards: 4,
  sandboxProbes: 4,
  noHostObjectsOrFunctionsInjected: true,
  virtualCompilerFilesystemOnly: true,
  dependencyDownloads: 0,
  realModelCalls: 0,
  results,
};
fs.writeFileSync(path.join(here, 'self-test-result.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ passed: report.passed, checks: report.businessAndUiChecks,
  mutationCases: report.mutationCases, pathGuards: report.pathGuards,
  sandboxProbes: report.sandboxProbes, modelCalls: 0, dependencyDownloads: 0 }));
