import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateCodeWork, checkDeliveredWork } from './code-work.mjs';
import { targetPaths, testPath } from '../fixtures/project.mjs';

const here = dirname(fileURLToPath(import.meta.url)), repo = resolve(here, '../../../../..');
const temporary = resolve(repo, `tmp/natural-useful-delivery-2026-10-28/code-checker-selftest-${Date.now()}`);
await mkdir(temporary, { recursive: true });
const source = await readFile(new URL('../fixtures/project/src/views/records/dateRules.js', import.meta.url), 'utf8');
const tests = await readFile(new URL('../fixtures/project/src/views/records/dateRules.spec.js', import.meta.url), 'utf8');
const correct = source.replace('  return null;', "  if (value > today) return 'FUTURE_DATE';\n  return null;");
const correctFile = { path: targetPaths[0], content: correct };
const regression = { path: testPath, content: tests + `\n
test('新增拒绝未来日期，接受今天', () => {
  assert.equal(createSurvey({surveyDate:'2026-10-28'}, '2026-10-27').ok, false);
  assert.equal(createSurvey({surveyDate:'2026-10-27'}, '2026-10-27').ok, true);
});
test('导入拒绝未来行并保持有效行', () => {
  const result=importSurveys([{surveyDate:'2026-10-26'},{surveyDate:'2026-10-28'}], '2026-10-27');
  assert.equal(result.inserted.length,1); assert.equal(result.errors[0].row,2);
});\n` };
const baseline = await checkDeliveredWork({ files: [] }, 'C01', resolve(temporary, 'baseline'));
assert.equal(baseline.passed, false);
assert.deepEqual(baseline.business.checks.filter(item => !item.passed).map(item => item.id),
  ['create-future', 'import-row-errors-and-partial-success', 'cross-year-future']);
const legalTests = await checkDeliveredWork({ files: [correctFile, regression] }, 'C01', resolve(temporary, 'legal-tests'));
assert.equal(legalTests.passed, true); assert.equal(legalTests.regressionTests.detectsOriginalBug, true);
const noTests = await checkDeliveredWork({ files: [correctFile] }, 'C02', resolve(temporary, 'no-tests'));
assert.equal(noTests.passed, true);
const wrongClock = await checkDeliveredWork({ files: [{ path: targetPaths[0],
  content: correct.replace('value > today', "value > new Date().toISOString().slice(0, 10)") }] }, 'C02', resolve(temporary, 'wrong-clock'));
assert.equal(wrongClock.passed, false);
assert.deepEqual(wrongClock.additionalBusiness.checks.filter(item => !item.passed).map(item => item.name),
  ['create-supplied-today-independent-of-system-clock', 'import-supplied-today-independent-of-system-clock']);
assert.throws(() => validateCodeWork({ files: [correctFile, regression] }, 'C02'));
for (const path of ['../outside.js', '/outside.js', 'C:/outside.js', 'package.json', 'src/views/records-old/create.js']) {
  assert.throws(() => validateCodeWork({ files: [{ path, content: '' }] }, 'C01'));
}
assert.throws(() => validateCodeWork({ files: [correctFile, correctFile] }, 'C01'));
assert.equal(validateCodeWork({ files: [correctFile], summary: '尚未运行；修改日期校验。' }, 'C02').length, 1);
assert.throws(() => validateCodeWork({ files: [correctFile], summary: ['不能混入额外字段'] }, 'C02'));
assert.throws(() => validateCodeWork({ files: [correctFile], unrelated: '不能混入额外字段' }, 'C02'));
const report = { modelCalls: 0, baselineRejected: true, correctWithUsefulTestsPassed: true,
  correctNoTestsPassed: true, testFileRejectedUnderC02: true, traversalAndOtherFilesRejected: true,
  duplicateRejected: true, baseline: baseline.business, legalTests: { business: legalTests.business, regressionTests: legalTests.regressionTests },
  wrongClockRejected: true, wrongClock: wrongClock.additionalBusiness, originalFailedArtifactUntouched: true };
await writeFile(new URL('./self-test-result-r3.json', import.meta.url), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ modelCalls: 0, controlsPassed: true, directory: temporary }));
