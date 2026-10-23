import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { isDeepStrictEqual } from 'node:util';
import { jsonFile, sha256File, verifiedReuse } from './reuse-evidence.mjs';

const base = dirname(fileURLToPath(import.meta.url));
const repo = resolve(base, '../../../..');
const targetStage = process.argv[2];
const sourceStage = process.argv[3] ?? 'development';
assert.match(targetStage ?? '', /^[a-z0-9-]+$/);
assert.match(sourceStage, /^[a-z0-9-]+$/);
assert.notEqual(targetStage, sourceStage);
const targetEvidence = resolve(base, targetStage);
const targetPrivate = resolve(repo, 'tmp/natural-useful-completion-2026-10-23', targetStage);
const sourceEvidence = resolve(base, sourceStage);
const sourcePrivate = resolve(repo, 'tmp/natural-useful-completion-2026-10-23', sourceStage);
for (const path of [resolve(targetEvidence, 'closure.json'), resolve(targetPrivate, 'closure.json'),
  resolve(targetEvidence, 'source-manifest.json')]) {
  let exists = false;
  try { await stat(path); exists = true; } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  assert.equal(exists, false, 'Cannot prepare reuse after closure/runtime freeze');
}
try { assert.notEqual((await jsonFile(resolve(targetEvidence, 'protocol.json'))).status, 'FROZEN', 'Root must freeze after reuse preparation'); }
catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
const sourceClosure = resolve(sourceEvidence, 'closure.json');
assert.equal((await jsonFile(sourceClosure)).status, 'CLOSED');
const sourceCases = resolve(sourceEvidence, 'cases.json');
const cases = (await jsonFile(sourceCases)).cases;
assert.deepEqual(cases.map(sample => sample.id), ['D01', 'D02', 'D03', 'D04']);
const sourcePublicResults = resolve(sourceEvidence, 'http-results.json');
const exported = await jsonFile(sourcePublicResults);
const pointer = async path => ({ stage: sourceStage, path: relative(repo, path).replaceAll('\\', '/'), sha256: await sha256File(path) });

/** 已存在且完全相同可安全续准备；不同内容不覆盖，原失败同样逐字保留。 */
async function consistentWrite(path, data) {
  await mkdir(dirname(path), { recursive: true });
  let recorded;
  try { recorded = await readFile(path); } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  if (recorded) { assert.equal(Buffer.compare(recorded, Buffer.from(data)), 0, 'Existing reuse evidence differs'); return; }
  await writeFile(path, data, { flag: 'wx' });
}
const encode = value => JSON.stringify(value, null, 2) + '\n';
await consistentWrite(resolve(targetEvidence, 'cases.json'), await readFile(sourceCases));
const results = [];
const historyChecks = [];
const files = [];
for (const sample of cases) {
  const ids = [sample.id + '_V31_GEN', ...['ORIGINAL', 'V31'].flatMap(arm => [1, 2].map(repeat => `${sample.id}_${arm}_EXEC_R${repeat}`))];
  for (const id of ids) {
    const source = resolve(sourcePrivate, 'results', id + '.json');
    const original = await jsonFile(source);
    assert.ok(isDeepStrictEqual(original, exported.find(row => row.id === id)), 'Source public/private differs: ' + id);
    const reusedFrom = await pointer(source);
    const target = resolve(targetPrivate, 'results', id + '.json');
    await consistentWrite(target, encode({ ...original, reusedFrom }));
    const entry = { id, path: relative(repo, target).replaceAll('\\', '/'), sha256: await sha256File(target), reusedFrom };
    results.push(entry);
    files.push(reusedFrom, { path: entry.path, sha256: entry.sha256 });
    if (original.phase === 'GENERATION' && original.status === 200) {
      const recordId = original.body.data.recordId;
      const sourceHistory = resolve(sourceEvidence, 'history-checks', recordId + '.json');
      const historyFrom = await pointer(sourceHistory);
      const historyTarget = resolve(targetEvidence, 'history-checks', recordId + '.json');
      await consistentWrite(historyTarget, encode({ ...await jsonFile(sourceHistory), reusedFrom: historyFrom }));
      const entry = { recordId, path: relative(repo, historyTarget).replaceAll('\\', '/'), sha256: await sha256File(historyTarget), reusedFrom: historyFrom };
      historyChecks.push(entry);
      files.push(historyFrom, { path: entry.path, sha256: entry.sha256 });
    }
  }
}
const manifest = {
  sourceStage, targetStage, sourceClosure: await pointer(sourceClosure), sourceCases: await pointer(sourceCases),
  sourcePublicResults: await pointer(sourcePublicResults), results, historyChecks,
  files: [...files, await pointer(sourceClosure), await pointer(sourceCases), await pointer(sourcePublicResults)],
  reusedLogicalResults: results.length, copiedTraces: 0, newActualCallsFromReuse: 0,
  note: '仅复用已闭批D01-D04原始臂/31基线原件；失败不替换，历史计数留原批，本阶段只计新增traces。'
};
await consistentWrite(resolve(targetEvidence, 'reuse-manifest.json'), encode(manifest));
const checked = await verifiedReuse(repo, targetEvidence, targetPrivate, cases);
console.log(JSON.stringify({ event:'CLOSED_EVIDENCE_REUSED_OFFLINE', targetStage, reusedResults:checked.results.length,
  historyChecks:checked.historyChecks.length, copiedTraces:0, modelCalls:0 }));
