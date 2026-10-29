import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkDeliveredWork } from './code-work.mjs';
import { executeProvidedTests } from './test-realm.mjs';

/** 新R2共同夹具明确today可选；保留旧业务/文件范围验收，增加无注入基准时的独立行为检查。 */
export async function checkR2(work, caseId, destination) {
  const original = await checkDeliveredWork(work, caseId, destination);
  const sources = Object.fromEntries(await Promise.all(['dateRules.js', 'create.js', 'import.js'].map(async name =>
    [`src/views/records/${name}`, await readFile(resolve(destination, 'src/views/records', name), 'utf8')])));
  const testSource = `import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createSurvey } from './create.js';
import { importSurveys } from './import.js';
test('optional-today-create-still-limits-future', () => {
  assert.equal(createSurvey({surveyDate:'2026-10-27'}).ok, true);
  assert.equal(createSurvey({surveyDate:'2026-10-28'}).ok, false);
});
test('optional-today-import-keeps-valid-row', () => {
  const valid = {id:'a',surveyDate:'2026-10-27'};
  const result = importSurveys([valid,{id:'b',surveyDate:'2026-10-28'}]);
  assert.deepEqual(result.inserted,[valid]); assert.equal(result.errors.length,1);
  assert.equal(result.errors[0].row,2); assert.equal(result.errors[0].field,'surveyDate');
});`;
  // 复用现有隔离realm及冻结在2026-10-27的Date；不执行模型自报测试结果或任意系统命令。
  const payload = resolve(dirname(destination), `${caseId}-${destination.split(/[\\/]/).pop()}-optional-today.json`);
  await writeFile(payload, JSON.stringify({ sources, testSource }), { flag: 'wx' });
  const optionalToday = await executeProvidedTests(payload);
  return { ...original, fixtureRevision: 'R2_EXPLICIT_OPTIONAL_TODAY_SHARED_INFORMATION', optionalToday,
    passed: original.passed && optionalToday.passed, originalHistoricalResultsUnchanged: true };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [file, caseId, destination] = process.argv.slice(2); assert.ok(file && ['C01','C02'].includes(caseId) && destination);
  const response = JSON.parse(await readFile(file, 'utf8'));
  const work = response.body?.data?.content ? JSON.parse(response.body.data.content) : response;
  console.log(JSON.stringify(await checkR2(work, caseId, destination), null, 2));
}
