import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)), evidence=resolve(here,'..'), repo=resolve(evidence,'../../../..');
const sha=x=>createHash('sha256').update(x).digest('hex');
const json=async p=>JSON.parse((await readFile(resolve(evidence,p),'utf8')).replace(/^\uFEFF/,''));
async function closed(stage,name){
  const path=resolve(evidence,stage,name),bytes=await readFile(path),closure=await json(`${stage}/closure.json`);
  assert.equal(closure.status,'CLOSED'); assert.equal(closure.files.find(x=>resolve(repo,x.path)===path)?.sha256,sha(bytes));
  return {value:JSON.parse(bytes),source:{path:`${stage}/${name}`,sha256:sha(bytes)}};
}
const output=resolve(evidence,'registry63-works-preparation'); await mkdir(output,{recursive:true});
const oldCases=(await json('registry58-works/cases.json')).cases,oldJobs=await json('registry58-works/jobs.json');
const newGen=await json('registry61-development/jobs.json'),oldGen=await json('registry58-development/jobs.json');
const information=x=>({rawPrompt:x.rawPrompt,context:x.context,conversationHistory:x.conversationHistory??[],permissionPolicy:x.permissionPolicy??null,planConfirmation:x.planConfirmation??null});
const cases=[],jobs=[],reuse=[],provenance=[];
for(const id of ['C01','C02','Q01','U02']){
  const input=newGen.find(x=>x.caseId===id).approvedEnhancementInput;
  assert.deepEqual(information(input),information(oldGen.find(x=>x.caseId===id).approvedEnhancementInput));
  const generated=await closed('registry61-development',`results/${id}_V61_GEN.json`);
  assert.equal(generated.value.status,200);assert.deepEqual(generated.value.body.data.processing.completedStages,['BODY_GENERATION','RECORD_LINKING']);
  cases.push({...oldCases.find(x=>x.id===id),eligibleArms:id==='C02'?['ORIGINAL','63']:['ORIGINAL','31','63'],candidate:'63'});
  for(const oldJob of oldJobs.filter(x=>x.caseId===id&&x.version!=='58')){
    const result=await closed('registry58-works',`results/${oldJob.id}.json`);
    assert.equal(result.value.status,200); assert.equal(result.value.body.data.finishReason,'stop');
    reuse.push({jobId:oldJob.id,caseId:id,arm:oldJob.version,repeat:oldJob.repeat,source:result.source,reason:'Exact same information, system instruction, model, parameters and output scope. Reuse all fixed baseline repetitions, never select best.'});
  }
  for(const repeat of [1,2]){
    const baseline=oldJobs.find(x=>x.caseId===id&&x.version==='ORIGINAL'&&x.repeat===repeat);
    const request=structuredClone(baseline.approvedModelRequest);
    assert.equal(request.messages[1].role,'user'); assert.equal(request.messages[1].content,input.rawPrompt);
    request.messages[1].content=generated.value.body.data.optimizedPrompt;
    jobs.push({id:`${id}_V63_EXEC_R${repeat}`,caseId:id,version:'63',phase:'EXECUTION',repeat,promptSha256:sha(request.messages[1].content),approvedModelRequest:request});
  }
  provenance.push({caseId:id,candidate:generated.source,informationEqualTo58AndBaseline:true,executorMaterialsSha256:sha(JSON.stringify(cases.at(-1).executorMaterials))});
}
const seed='registry63-shared61-body-first-works';jobs.sort((a,b)=>sha(seed+a.id).localeCompare(sha(seed+b.id)));
const all=[...reuse.map(x=>({...x,reused:true})),...jobs.map(x=>({jobId:x.id,caseId:x.caseId,arm:x.version,repeat:x.repeat,reused:false}))];
const mapping=all.sort((a,b)=>sha(seed+'blind'+a.jobId).localeCompare(sha(seed+'blind'+b.jobId))).map((x,i)=>({workId:`B${String(i+1).padStart(2,'0')}`,...x}));
const privateRoot=resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry63-works-review');await mkdir(privateRoot,{recursive:true});
await writeFile(resolve(privateRoot,'coordinator-key.json'),JSON.stringify(mapping,null,2)+'\n',{flag:'wx'});
const criteria=await json('registry58-works-preparation/criteria.json');
criteria.state='FROZEN_BEFORE_ANY_63_WORK';criteria.cases=cases.map(x=>x.id);criteria.procedure.arms=['ORIGINAL','31','63'];
criteria.procedure.baselineReuse='All14 eligible closed RAW/31 works reused with exact-information and closed-byte checks. Prior58 quality decision remains unchanged. Baselines already seen by root; only the new AI review is blinded, root verification is not claimed blind.';
delete criteria.U03;
await writeFile(resolve(output,'criteria.json'),JSON.stringify(criteria,null,2)+'\n',{flag:'wx'});
await writeFile(resolve(output,'bundle.json'),JSON.stringify({syntheticOnly:true,cases,jobs,phaseLimits:{EXECUTION:jobs.length,GENERATION:0,PLAN:0,REVIEW:0},parameters:{executionMaxTokens:8192},qualityRetriesAllowed:false,
  purpose:'Same-information actual work comparison; fixed existing baseline reuse, first works from the unchanged no-answer61 writing path, retained under their original provenance; not a release.',baselineReuse:reuse,candidatePromptSourceVersion:'61',noAnswerPathReuse:'63 has the same61 writer instruction and two-stage flow when actualAnswers is empty; existing first generated texts retain61 source IDs. No61 work was executed. Fresh63 UI and unseen generation remain separate.',criterionSha256:sha(JSON.stringify(criteria,null,2)+'\n')},null,2)+'\n',{flag:'wx'});
await writeFile(resolve(output,'provenance.json'),JSON.stringify({actualCalls:0,provenance,reusedWorks:reuse.length,newWorks:jobs.length,coordinatorKeySha256:sha(JSON.stringify(mapping,null,2)+'\n'),rootPriorBaselineKnowledge:true},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({prepared:true,newWorks:jobs.length,reusedWorks:reuse.length,actualCalls:0}));
