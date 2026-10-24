import {readFile,writeFile,mkdir,stat} from 'node:fs/promises';
import {resolve,dirname,relative} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {jsonFile,sha256File,verifiedReuse} from './reuse-evidence.mjs';
import {executionRequest} from './evaluation-inputs.mjs';

const base=dirname(fileURLToPath(import.meta.url));
const repo=resolve(base,'../../../..');
const stage='boundary38-works';
const target=resolve(base,stage);
const local=resolve(repo,'tmp/natural-intent-completion-2026-10-24',stage);
const encode=v=>JSON.stringify(v,null,2)+'\n';
for(const source of ['boundary38-regression','boundary38-heldout']){
 const gate=await jsonFile(resolve(base,source,'regression-gate.json'));
 assert.equal(gate.status,'PASS');assert.equal(gate.candidateVersion,'38');
 assert.equal(gate.unresolvedSevereRegressions,0);
 assert.equal((await jsonFile(resolve(base,source,'closure.json'))).status,'CLOSED');
}
for(const path of [resolve(base,'closure.json'),resolve(target,'cases.json'),resolve(target,'source-manifest.json')]){
 let exists=false;try{await stat(path);exists=true}catch(e){if(e.code!=='ENOENT')throw e}
 assert.equal(exists,false,'Closed task or already prepared stage');
}
const task=await jsonFile(resolve(base,'task-protocol-revision-144.json'));
const prior=await jsonFile(resolve(base,'boundary38-heldout/protocol.json'));
const protocol=structuredClone(prior);
const primary=['CS-H01','CS-H02'];
const auxiliary=['GC-H01','GC-H02'];
const ids=[...primary,...auxiliary];
Object.assign(protocol,{batch:'natural-intent-content-boundary38-works-2026-10-24',status:'DRAFT',
 maximumNewActualCalls:25,taskPreviouslyActual:(await jsonFile(resolve(base,'boundary38-heldout/closure.json'))).taskActual,
 phaseLimits:{GENERATION:0,PLAN:0,EXECUTION:25,REVIEW:0},
 plannedPrimaryCalls:{GENERATION:0,PLAN:0,EXECUTION:16,REVIEW:0},
 generation:ids,generationVersions:['31'],generationVersionsByCase:Object.fromEntries(ids.map(id=>[id,[]])),
 requiredGenerationVersionsByCase:Object.fromEntries(ids.map(id=>[id,['31','38']])),
 regressionGeneration:[],plan:[],confirmedPlanGeneration:[],execution:ids,
 executionArms:['ORIGINAL','V31','V38'],executionRepeats:2,
 prerequisiteStages:['boundary38-regression','boundary38-heldout'],
 adoptionEligibleCaseIds:primary,auxiliaryCaseIds:auxiliary,
 order:'Both CS cases: synchronous ORIGINAL/31/38 two works each. GC development cases: reuse exact closed ORIGINAL/31 works and execute38 twice. No re-enhancement or quality retry.',
 criteria:task.adoptionCriteria,
 draftNotes:['Normal16 calls, max25 is a hard ceiling, not a sampling target.',
 'CS is independent internal holdout. GC was already opened development material and cannot count as unseen adoption evidence.',
 'GC old arms are across-time artifact reuse; exact cases, all supplied material, full requests and model parameters must match. Report that boundary separately.']});
delete protocol.frozenAt;
protocol.allowedJobs=[...primary.flatMap(id=>protocol.executionArms.flatMap(arm=>[1,2].map(n=>`${id}_${arm}_EXEC_R${n}`))),
 ...auxiliary.flatMap(id=>[1,2].map(n=>`${id}_V38_EXEC_R${n}`))];
const samples=new Map();
const sources=[];
const pointer=async(path,sourceStage)=>({stage:sourceStage,path:relative(repo,path).replaceAll('\\','/'),sha256:await sha256File(path)});
async function save(path,value){await mkdir(dirname(path),{recursive:true});await writeFile(path,encode(value),{flag:'wx'})}
for(const selection of [
 {stage:'boundary38-heldout',ids:primary,arms:['31','38'],works:[]},
 {stage:'boundary38-regression',ids:auxiliary,arms:['38'],works:[]},
 {stage:'completion',ids:auxiliary,arms:['31'],works:['ORIGINAL','V31']}
]){
 const source=resolve(base,selection.stage);
 const sourceLocal=resolve(repo,'tmp/natural-intent-completion-2026-10-24',selection.stage);
 const closure=resolve(source,'closure.json');assert.equal((await jsonFile(closure)).status,'CLOSED');
 const casesPath=resolve(source,'cases.json');
 const available=(await jsonFile(casesPath)).cases;
 const resultsPath=resolve(source,'http-results.json');const exported=await jsonFile(resultsPath);
 const results=[],historyChecks=[],files=[];
 for(const id of selection.ids){
  const sample=available.find(c=>c.id===id);assert.ok(sample);
  if(samples.has(id))assert.deepEqual(sample,samples.get(id),'Reused case/material mismatch');else samples.set(id,sample);
  const jobs=[...selection.arms.map(v=>`${id}_V${v}_GEN`),
   ...selection.works.flatMap(arm=>[1,2].map(n=>`${id}_${arm}_EXEC_R${n}`))];
  for(const job of jobs){
   const old=exported.find(r=>r.id===job);assert.ok(old&&old.status===200,'Missing successful source '+job);
   const originalPath=resolve(sourceLocal,'results',job+'.json');assert.deepEqual(await jsonFile(originalPath),old);
   if(old.phase==='EXECUTION'){
    const prompt=old.version==='ORIGINAL'?sample.rawPrompt:exported.find(r=>r.id===`${id}_${old.version}_GEN`).body.data.optimizedPrompt;
    assert.deepEqual(old.request,executionRequest(sample,prompt,protocol),'Full reused execution request differs');
   }
   const reusedFrom=await pointer(originalPath,selection.stage);
   const targetPath=resolve(local,'results',job+'.json');await save(targetPath,{...old,reusedFrom});
   const item={id:job,path:relative(repo,targetPath).replaceAll('\\','/'),sha256:await sha256File(targetPath),reusedFrom};
   results.push(item);files.push(reusedFrom,{path:item.path,sha256:item.sha256});
   if(old.phase==='GENERATION'){
    const recordId=old.body.data.recordId;
    const oldHistory=resolve(source,'history-checks',recordId+'.json');
    const historyFrom=await pointer(oldHistory,selection.stage);
    const targetHistory=resolve(target,'history-checks',recordId+'.json');
    await save(targetHistory,{...await jsonFile(oldHistory),reusedFrom:historyFrom});
    const item={recordId,path:relative(repo,targetHistory).replaceAll('\\','/'),sha256:await sha256File(targetHistory),reusedFrom:historyFrom};
    historyChecks.push(item);files.push(historyFrom,{path:item.path,sha256:item.sha256});
   }
  }
 }
 const manifest=resolve(source,'source-manifest.json');
 files.push(...await Promise.all([closure,casesPath,resultsPath,manifest].map(path=>pointer(path,selection.stage))));
 sources.push({sourceStage:selection.stage,targetStage:stage,reusedCaseIds:selection.ids,
  sourceClosure:await pointer(closure,selection.stage),sourceCases:await pointer(casesPath,selection.stage),
  sourcePublicResults:await pointer(resultsPath,selection.stage),sourceManifest:await pointer(manifest,selection.stage),results,historyChecks,files});
}
const ordered=ids.map(id=>samples.get(id));assert.ok(ordered.every(Boolean));
await save(resolve(target,'cases.json'),{origin:'Exact closed-stage cases; two unopened-until-gate CS primary and two already-known GC auxiliary cases.',cases:ordered});
await save(resolve(target,'reuse-manifest.json'),{sources,files:sources.flatMap(s=>s.files),copiedTraces:0,newActualCallsFromReuse:0,
 note:'GC old executions checked against the complete new execution request. All two repeats preserved; no old batch reopened.'});
await save(resolve(target,'protocol-draft.json'),protocol);
const checked=await verifiedReuse(repo,target,local,ordered);
console.log(JSON.stringify({event:'BOUNDARY38_WORKS_PREPARED',primaryCases:2,auxiliaryCases:2,
 reusedLogicalResults:checked.results.length,plannedActualCalls:16,maximumActualCalls:25,modelCalls:0}));
