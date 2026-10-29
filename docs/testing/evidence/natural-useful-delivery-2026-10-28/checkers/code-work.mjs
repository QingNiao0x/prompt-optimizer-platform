import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { basename, dirname, isAbsolute, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cases, materializeSyntheticProject, targetPaths, testPath } from '../fixtures/project.mjs';
import { checkCodeWork } from '../../natural-context-rewrite-2026-10-27/check-code-work.mjs';
import { executeProvidedTests } from './test-realm.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '../../../../..');
const TMP = resolve(REPO, 'tmp');
const hash = text => createHash('sha256').update(text).digest('hex');

/** 根据事先写进原始需求和执行合同的范围判定，禁止事后过滤文件让作品通过。 */
export function validateCodeWork(work, caseId) {
  const sample = cases.find(item => item.id === caseId); assert.ok(sample, '未知冻结题号');
  const allowed = new Set(sample.allowedWritePaths);
  assert.ok(work && typeof work === 'object' && !Array.isArray(work));
  assert.ok(Object.keys(work).every(key => ['files', 'summary'].includes(key)) && Object.hasOwn(work, 'files'));
  if (work.summary !== undefined) assert.equal(typeof work.summary, 'string');
  assert.ok(Array.isArray(work.files) && work.files.length <= allowed.size);
  const seen = new Set();
  return work.files.map(file => {
    assert.deepEqual(Object.keys(file).sort(), ['content', 'path']); assert.equal(typeof file.path, 'string');
    const path = file.path.replace(/\\/g, '/');
    assert.ok(!isAbsolute(path) && !/^[A-Za-z]:/.test(path) && path.split('/').every(part => part && part !== '.' && part !== '..') && allowed.has(path), '输出文件超出原始需求的冻结范围');
    assert.ok(!seen.has(path), '重复文件'); seen.add(path);
    assert.equal(typeof file.content, 'string'); assert.ok(Buffer.byteLength(file.content) <= 256 * 1024);
    return { path, content: file.content };
  });
}

/** 只向新的tmp合成副本落盘；业务检查和模型测试独立，未交付额外测试本身不是质量失败。 */
export async function checkDeliveredWork(work, caseId, destination) {
  const changes = validateCodeWork(work, caseId), root = resolve(destination);
  assert.ok(root.startsWith(`${TMP}${sep}`), '作品副本仅限工作区tmp');
  await mkdir(dirname(root), { recursive: true });
  const parent = await realpath(dirname(root)), tmp = await realpath(TMP);
  assert.ok(parent === tmp || parent.startsWith(`${tmp}${sep}`)); await mkdir(root);
  const fixture = await materializeSyntheticProject(root);
  for (const file of changes) await writeFile(resolve(root, file.path), file.content);
  const changed = new Set(changes.map(file => file.path)), preserved = [];
  for (const file of fixture.files.filter(file => !changed.has(file.path))) {
    const actual = await readFile(resolve(root, file.path), 'utf8'); assert.equal(actual, file.content);
    preserved.push({ path: file.path, sha256: hash(actual) });
  }
  const business = await checkCodeWork(root);
  const sources = Object.fromEntries(await Promise.all(targetPaths.map(async path => [path, await readFile(resolve(root, path), 'utf8')])));
  // 宿主时钟固定在2026-10-27，而调用方today故意不同；只读系统时钟会错过此类越界。
  // 独立检查来自原项目接口与稳定错误码，不采用模型自己编写的断言作为业务金标。
  const independentSource = `import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createSurvey } from './create.js';
import { importSurveys } from './import.js';
test('create-supplied-today-independent-of-system-clock', () => {
  assert.equal(createSurvey({surveyDate:'2025-01-02'}, '2025-01-01').ok, false);
  assert.equal(createSurvey({surveyDate:'2025-01-01'}, '2025-01-01').ok, true);
});
test('import-supplied-today-independent-of-system-clock', () => {
  const first={surveyDate:'2025-01-01'};
  const result=importSurveys([first,{surveyDate:'2025-01-02'}], '2025-01-01');
  assert.deepEqual(result.inserted,[first]); assert.equal(result.errors[0].row,2);
});
test('create-preserves-invalid-date-code', () => {
  assert.equal(createSurvey({surveyDate:'2026-02-30'}, '2026-10-27').fieldErrors.surveyDate, 'INVALID_DATE');
});
test('import-preserves-invalid-date-code', () => {
  assert.equal(importSurveys([{surveyDate:'2026-02-30'}], '2026-10-27').errors[0].code, 'INVALID_DATE');
});`;
  const independentPayload = resolve(dirname(root), `${basename(root)}-independent-behavior.json`);
  await writeFile(independentPayload, JSON.stringify({ sources, testSource: independentSource }));
  const additionalBusiness = await executeProvidedTests(independentPayload);
  const testSource = await readFile(resolve(root, testPath), 'utf8');
  const baselineSources = Object.fromEntries(fixture.files.filter(file => targetPaths.includes(file.path)).map(file => [file.path, file.content]));
  const testRuns = {};
  for (const [label, material] of [['actual', sources], ['beforeFix', baselineSources]]) {
    const payload = resolve(dirname(root), `${basename(root)}-${label}-test-realm.json`);
    await writeFile(payload, JSON.stringify({ sources: material, testSource }));
    testRuns[label] = await executeProvidedTests(payload);
  }
  return { caseId, changedPaths: [...changed], preservedCount: preserved.length, preserved,
    deliveryContractPassed: true, business, additionalBusiness, regressionTests: { changed: changed.has(testPath), ...testRuns,
      detectsOriginalBug: changed.has(testPath) && testRuns.actual.passed && testRuns.beforeFix.checks.some(item => !item.passed) },
    passed: business.passed && additionalBusiness.passed && testRuns.actual.passed,
    scope: '实际业务代码与受限同步测试realm；不代表真实用户Vue页面或真人修改耗时' };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [file, caseId, destination] = process.argv.slice(2);
  if (!file || !caseId || !destination) throw Error('用法：node code-work.mjs <作品JSON> <C01|C02> <新的tmp目录>');
  const value = JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));
  const work = value.body?.data?.content ? JSON.parse(value.body.data.content) : value;
  console.log(JSON.stringify(await checkDeliveredWork(work, caseId, destination), null, 2));
}
