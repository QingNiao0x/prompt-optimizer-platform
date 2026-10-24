import { readFile, writeFile, mkdir, stat } from 'node:fs/promises';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { jsonFile, sha256File, verifiedReuse } from './reuse-evidence.mjs';

const base = dirname(fileURLToPath(import.meta.url));
const repo = resolve(base, '../../../..');
const ids = (process.argv[2] ?? '').split(',');
assert.ok(ids.length >= 2 && ids.length <= 4 && ids.every(id => /^[A-Z0-9_-]+$/.test(id)));
assert.equal(new Set(ids).size, ids.length);
const task = await jsonFile(resolve(base, 'task-protocol.json'));
assert.equal(task.status, 'FROZEN');
const target = resolve(base, 'works');
const targetPrivate = resolve(repo, 'tmp/natural-intent-completion-2026-10-24/works');
for (const path of [resolve(base,'closure.json'),resolve(target,'cases.json'),resolve(target,'source-manifest.json')]) {
  let exists = false;
  try { await stat(path); exists = true; } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  assert.equal(exists, false, 'Task closed or works already prepared');
}

// 先核对门禁，再读取任何留出题面或其作品；不能为了分配额度绕开严重回归。
for (const stage of ['regression','heldout']) {
  const gate = await jsonFile(resolve(base,stage,'regression-gate.json'));
  assert.equal(gate.status,'PASS'); assert.equal(gate.candidateVersion,'37'); assert.equal(gate.unresolvedSevereRegressions,0);
  assert.equal((await jsonFile(resolve(base,stage,'closure.json'))).status,'CLOSED');
}
const pointer = async (path,stage) => ({stage,path:relative(repo,path).replaceAll('\\','/'),sha256:await sha256File(path)});
const encode = value => JSON.stringify(value,null,2)+'\n';
async function save(path,value) {
  await mkdir(dirname(path),{recursive:true});
  await writeFile(path,encode(value),{flag:'wx'});
}
const sources = [], selectedCases = [], missing31 = [];
let heldoutSelected = 0;
for (const stage of ['regression','heldout']) {
  const source = resolve(base,stage);
  const sourcePrivate = resolve(repo,'tmp/natural-intent-completion-2026-10-24',stage);
  const sourceCases = resolve(source,'cases.json');
  const available = (await jsonFile(sourceCases)).cases;
  const cases = available.filter(sample => ids.includes(sample.id));
  if (!cases.length) continue;
  const sourceProtocol = await jsonFile(resolve(source,'protocol.json'));
  assert.ok(cases.every(sample => sourceProtocol.generation.includes(sample.id)), 'Works require direct-generation cases');
  if (stage === 'heldout') heldoutSelected += cases.length;
  selectedCases.push(...cases);
  const sourcePublicResults = resolve(source,'http-results.json');
  const exported = await jsonFile(sourcePublicResults);
  const results = [], historyChecks = [], files = [];
  for (const sample of cases) {
    for (const version of ['31','37']) {
      const id = sample.id+'_V'+version+'_GEN';
      const original = exported.find(row => row.id === id);
      if (!original) { assert.equal(version,'31','Candidate must be generated before works'); missing31.push(sample.id); continue; }
      if (version === '37') assert.ok(original.status === 200 && original.body?.data?.optimizedPrompt?.trim(), 'Candidate not delivered');
      const originalPath = resolve(sourcePrivate,'results',id+'.json');
      assert.deepEqual(await jsonFile(originalPath),original,'Public/private source mismatch');
      const reusedFrom = await pointer(originalPath,stage);
      const targetPath = resolve(targetPrivate,'results',id+'.json');
      await save(targetPath,{...original,reusedFrom});
      const entry = {id,path:relative(repo,targetPath).replaceAll('\\','/'),sha256:await sha256File(targetPath),reusedFrom};
      results.push(entry); files.push(reusedFrom,{path:entry.path,sha256:entry.sha256});
      if (original.status === 200) {
        const recordId = original.body.data.recordId;
        const oldPath = resolve(source,'history-checks',recordId+'.json');
        const historyFrom = await pointer(oldPath,stage);
        const targetPath = resolve(target,'history-checks',recordId+'.json');
        await save(targetPath,{...await jsonFile(oldPath),reusedFrom:historyFrom});
        const item = {recordId,path:relative(repo,targetPath).replaceAll('\\','/'),sha256:await sha256File(targetPath),reusedFrom:historyFrom};
        historyChecks.push(item);files.push(historyFrom,{path:item.path,sha256:item.sha256});
      }
    }
  }
  const sourceClosure = resolve(source,'closure.json'), sourceManifest = resolve(source,'source-manifest.json');
  files.push(...await Promise.all([sourceClosure,sourceCases,sourcePublicResults,sourceManifest].map(path => pointer(path,stage))));
  sources.push({sourceStage:stage,targetStage:'works',reusedCaseIds:cases.map(sample => sample.id),sourceClosure:await pointer(sourceClosure,stage),
    sourceCases:await pointer(sourceCases,stage),sourcePublicResults:await pointer(sourcePublicResults,stage),sourceManifest:await pointer(sourceManifest,stage),
    results,historyChecks,files});
}
assert.equal(selectedCases.length,ids.length,'Unknown case ID');
assert.ok(heldoutSelected >= 1,'Adoption requires at least one heldout work case');
selectedCases.sort((left,right) => ids.indexOf(left.id)-ids.indexOf(right.id));
await save(resolve(target,'cases.json'),{origin:'从已关闭回归和新留出逐字复用所选题目，作品三臂收到相同材料；不复制旧作品、不重新生成已有提示词。',cases:selectedCases});
await save(resolve(target,'reuse-manifest.json'),{sources,files:sources.flatMap(source => source.files),copiedTraces:0,newActualCallsFromReuse:0});
const checked = await verifiedReuse(repo,target,targetPrivate,selectedCases);
const protocol = structuredClone(await jsonFile(resolve(base,'regression/protocol.json')));
protocol.batch='natural-intent-completion-works-2026-10-24';protocol.status='DRAFT';delete protocol.frozenAt;
protocol.maximumNewActualCalls=48;protocol.taskPreviouslyActual=(await jsonFile(resolve(base,'heldout/closure.json'))).taskActual;
protocol.parameters.executionMaxTokens=8192;
protocol.phaseLimits={GENERATION:16,PLAN:0,EXECUTION:32,REVIEW:0};
protocol.plannedPrimaryCalls={GENERATION:missing31.length*2,PLAN:0,EXECUTION:ids.length*6,REVIEW:0};
protocol.prerequisiteStages=['regression','heldout'];
protocol.generation=ids;protocol.generationVersions=['31'];protocol.regressionGeneration=[];
protocol.generationVersionsByCase=Object.fromEntries(ids.map(id => [id,missing31.includes(id)?['31']:[]]));
protocol.requiredGenerationVersionsByCase=Object.fromEntries(ids.map(id => [id,['31','37']]));
protocol.plan=[];protocol.confirmedPlanGeneration=[];protocol.execution=ids;
protocol.executionArms=['ORIGINAL','V31','V37'];protocol.executionRepeats=2;
protocol.allowedJobs=[...missing31.map(id => id+'_V31_GEN'),...ids.flatMap(id => protocol.executionArms.flatMap(arm => [id+'_'+arm+'_EXEC_R1',id+'_'+arm+'_EXEC_R2']))];
protocol.order='复用已有增强，补齐事前未生成的31基线；随后原始/31/37同信息三臂各两份作品，不按质量重试。';
protocol.criteria=task.adoptionCriteria;
protocol.draftNotes=['相对31至少两题清楚改善，其中至少一留出；原始产品收益另列，严重退步不能抵消。',
  '本批最多选择四题，全部重复作品均保留，不挑最优样本。除填补确实未生成过的31外不重新增强。',
  '任何评审仅作证据线索；来源、完整交付与必要修改项由独立复核核实，不把得分当事实。'];
await save(resolve(target,'protocol-draft.json'),protocol);
console.log(JSON.stringify({event:'WORKS_DRAFT_PREPARED_FROM_CLOSED_PHASES',cases:ids.length,heldoutCases:heldoutSelected,
  missing31Prompts:missing31.length,reusedLogicalResults:checked.results.length,plannedActualCalls:Object.values(protocol.plannedPrimaryCalls).reduce((a,b)=>a+b,0),modelCalls:0}));
