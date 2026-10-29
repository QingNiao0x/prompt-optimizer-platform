import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkDeliveredWork } from './code-work.mjs';

const evidence = resolve(dirname(fileURLToPath(import.meta.url)), '..'), repo = resolve(evidence, '../../../..');
const stage = process.argv[2];
if (!/^[a-z0-9-]+$/.test(stage ?? '')) throw Error('需要冻结作品阶段名');
const input = resolve(evidence, stage, 'results'), output = resolve(evidence, 'checkers/results', stage);
const temporary = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', 'code-works', `${stage}-${Date.now()}`);
await mkdir(output, { recursive: true }); await mkdir(temporary, { recursive: true });
const records = [];
for (const filename of (await readdir(input)).filter(name => /^C0[12]_(?:ORIGINAL|V\d+)_EXEC_R[12]\.json$/.test(name)).sort()) {
  const result = JSON.parse(await readFile(resolve(input, filename), 'utf8'));
  let verdict;
  try {
    if (result.status !== 200 || result.body?.data?.finishReason !== 'stop') throw Error('作品未正常完整返回');
    verdict = await checkDeliveredWork(JSON.parse(result.body.data.content), result.caseId, resolve(temporary, result.id));
  } catch (error) { verdict = { caseId: result.caseId, passed: false, deliveryContractPassed: false, error: String(error.message).slice(0, 1500) }; }
  const record = { id: result.id, version: result.version, ...verdict };
  await writeFile(resolve(output, filename), JSON.stringify(record, null, 2) + '\n', { flag: 'wx' });
  records.push({ id: record.id, passed: record.passed, deliveryContractPassed: record.deliveryContractPassed,
    businessPassed: record.business?.passed ?? null, testChanges: record.regressionTests?.changed ?? null,
    additionalBusinessPassed: record.additionalBusiness?.passed ?? null,
    testsCatchOriginalBug: record.regressionTests?.detectsOriginalBug ?? null, error: record.error ?? null });
}
await writeFile(resolve(output, 'summary.json'), JSON.stringify({ stage, actualNewModelCalls: 0, records }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ stage, actualNewModelCalls: 0, records }, null, 2));
