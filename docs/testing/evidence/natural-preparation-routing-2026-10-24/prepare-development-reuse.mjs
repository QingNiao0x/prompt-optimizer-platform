import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { isDeepStrictEqual } from 'node:util';
import { jsonFile, sha256File, verifiedReuse } from './reuse-evidence.mjs';

const base = dirname(fileURLToPath(import.meta.url));
const repo = resolve(base, '../../../..');
const targetStage = process.argv[2];
assert.equal(targetStage, 'development');
const targetEvidence = resolve(base, targetStage);
const targetPrivate = resolve(repo, 'tmp/natural-preparation-routing-2026-10-24', targetStage);
for (const path of [resolve(targetEvidence, 'closure.json'), resolve(targetPrivate, 'closure.json'),
  resolve(targetEvidence, 'source-manifest.json')]) {
  let exists = false;
  try { await stat(path); exists = true; } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  assert.equal(exists, false, 'Cannot prepare reuse after closure/runtime freeze');
}
try { assert.notEqual((await jsonFile(resolve(targetEvidence, 'protocol.json'))).status, 'FROZEN'); }
catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
const cases = (await jsonFile(resolve(targetEvidence, 'cases.json'))).cases;
const pointer = async (path, stage) => ({ stage, path: relative(repo, path).replaceAll('\\', '/'), sha256: await sha256File(path) });
const encode = value => JSON.stringify(value, null, 2) + '\n';

/** 一致复制可续准备；不同结果拒绝覆盖，历史失败与来源一并保留。 */
async function consistentWrite(path, data) {
  await mkdir(dirname(path), { recursive: true });
  let recorded;
  try { recorded = await readFile(path); } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  if (recorded) { assert.equal(Buffer.compare(recorded, Buffer.from(data)), 0, 'Existing evidence differs'); return; }
  await writeFile(path, data, { flag: 'wx' });
}

const sources = [];
for (const source of [{stage:'development', ids:['D01','D03','D04'], executions:['D03']},
                     {stage:'capacity', ids:['D02'], executions:['D02']}]) {
  const sourceEvidence = resolve(base, '../natural-execution-boundary-2026-10-23', source.stage);
  const sourcePrivate = resolve(repo, 'tmp/natural-execution-boundary-2026-10-23', source.stage);
  const sourceClosure = resolve(sourceEvidence, 'closure.json');
  const sourceCases = resolve(sourceEvidence, 'cases.json');
  const sourcePublicResults = resolve(sourceEvidence, 'http-results.json');
  assert.equal((await jsonFile(sourceClosure)).status, 'CLOSED');
  const oldCases = (await jsonFile(sourceCases)).cases;
  const exported = await jsonFile(sourcePublicResults);
  for (const id of source.ids) assert.deepEqual(oldCases.find(c => c.id === id), cases.find(c => c.id === id));
  const results = [], historyChecks = [], files = [];
  for (const id of source.ids) {
    const jobs = [id + '_V31_GEN'];
    if (source.executions.includes(id)) for (const arm of ['ORIGINAL','V31']) for (const repeat of [1,2]) jobs.push(`${id}_${arm}_EXEC_R${repeat}`);
    for (const job of jobs) {
      const originalPath = resolve(sourcePrivate, 'results', job + '.json');
      const original = await jsonFile(originalPath);
      assert.ok(isDeepStrictEqual(original, exported.find(row => row.id === job)), 'Public/private source differs');
      const reusedFrom = await pointer(originalPath, source.stage);
      const target = resolve(targetPrivate, 'results', job + '.json');
      await consistentWrite(target, encode({...original,reusedFrom}));
      const entry = {id:job, path:relative(repo,target).replaceAll('\\','/'), sha256:await sha256File(target), reusedFrom};
      results.push(entry); files.push(reusedFrom,{path:entry.path,sha256:entry.sha256});
      if (original.phase === 'GENERATION' && original.status === 200) {
        const recordId = original.body.data.recordId;
        const oldPath = resolve(sourceEvidence, 'history-checks', recordId + '.json');
        const historyFrom = await pointer(oldPath, source.stage);
        const target = resolve(targetEvidence, 'history-checks', recordId + '.json');
        await consistentWrite(target, encode({...await jsonFile(oldPath),reusedFrom:historyFrom}));
        const item = {recordId,path:relative(repo,target).replaceAll('\\','/'),sha256:await sha256File(target),reusedFrom:historyFrom};
        historyChecks.push(item); files.push(historyFrom,{path:item.path,sha256:item.sha256});
      }
    }
  }
  const sourceManifest = resolve(sourceEvidence, 'source-manifest.json');
  files.push(...await Promise.all([sourceClosure,sourceCases,sourcePublicResults,sourceManifest].map(path => pointer(path,source.stage))));
  sources.push({sourceStage:source.stage,targetStage,reusedCaseIds:source.ids,sourceClosure:await pointer(sourceClosure,source.stage),
    sourceCases:await pointer(sourceCases,source.stage),sourcePublicResults:await pointer(sourcePublicResults,source.stage),
    sourceManifest:await pointer(sourceManifest,source.stage),results,historyChecks,files});
}
await consistentWrite(resolve(targetEvidence,'reuse-manifest.json'),encode({sources,files:sources.flatMap(source => source.files),
  copiedTraces:0,newActualCallsFromReuse:0,note:'D01/D03/D04来自旧development；D02来自旧capacity。逐字核对题目、全部执行材料、模型参数和公开/私有结果，不修改旧批，不复制traces，不挪用余额。'}));
const checked = await verifiedReuse(repo,targetEvidence,targetPrivate,cases);
console.log(JSON.stringify({event:'CLOSED_MULTI_STAGE_EVIDENCE_REUSED_OFFLINE',targetStage,reusedResults:checked.results.length,
  historyChecks:checked.historyChecks.length,copiedTraces:0,modelCalls:0}));
