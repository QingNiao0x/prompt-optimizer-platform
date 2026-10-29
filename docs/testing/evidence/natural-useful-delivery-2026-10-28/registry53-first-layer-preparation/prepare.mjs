import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),repo=resolve(evidence,'../../../..');
const hash=x=>createHash('sha256').update(x).digest('hex'),json=async file=>JSON.parse((await readFile(file,'utf8')).replace(/^\uFEFF/,''));
const save=(name,value)=>writeFile(resolve(here,name),JSON.stringify(value,null,2)+'\n',{flag:'wx'});
await mkdir(resolve(here,'engineering'),{recursive:true});
const originals=[['registry53-engineering-verification.json','87917b78bcd5f580f11f6a9836e351a7efd6a35b4dc37fefbf12e7e8c223dfe9'],
 ['registry53-success-path-verification.json','1476f6c8fbb99d5ca287cc49adf47ee9c993c2aa5044ba4208a1390549199cd0']];
const preserved=[];
for(const[name,expected]of originals){const bytes=await readFile(resolve(repo,'tmp/natural-useful-delivery-2026-10-28',name));assert.equal(hash(bytes),expected);await writeFile(resolve(here,'engineering',name),bytes,{flag:'wx'});preserved.push({file:'engineering/'+name,sha256:expected});}
const success=await json(resolve(here,'engineering/registry53-success-path-verification.json'));
for(const[name,ref]of[['previous-38-tests.xml',success.previousJUnitArchive],['current-40-tests.xml',success.junit]]){
 const bytes=await readFile(resolve(repo,ref.path));assert.equal(hash(bytes),ref.sha256);await writeFile(resolve(here,'engineering',name),bytes,{flag:'wx'});preserved.push({file:'engineering/'+name,sha256:ref.sha256,tests:ref.tests});
}
const jobs=[],cases=[],proof=[];
for(const[id,n]of[['P02_CONFIRMED','001'],['P02_UNKNOWN','003']]){
 const source=await json(resolve(evidence,'registry52-plan-completion/traces/'+n+'.json'));
 const captured=await json(resolve(here,'captures/'+id+'.request.json'));
 const oldUser=source.request.messages[1].content,newUser=captured.messages[1].content;
 const oldInput=JSON.parse(oldUser.slice(oldUser.indexOf('{'))),newInput=JSON.parse(newUser.slice(newUser.indexOf('{')));
 assert.equal(newInput.strategyVersion,'53');const reverted=structuredClone(newInput);reverted.strategyVersion='52';assert.deepEqual(reverted,oldInput);
 assert.ok(!('taskScope'in newInput)&&!('preparedRequirements'in newInput)&&!('boundQuestionHints'in newInput));
 const expected=structuredClone(source.request);expected.messages[0].content=captured.messages[0].content;expected.messages[1].content=captured.messages[1].content;assert.deepEqual(captured,expected);
 cases.push({id,rawPrompt:newInput.rawPrompt,executorMaterials:[]});jobs.push({id:id+'_V53_REQUIREMENTS_PREPARE',caseId:id,phase:'EXECUTION',version:'53',
  purpose:'MECHANISM_ONLY_ACTUAL_PROVIDER53_REQUIREMENTS_ONLY_PREPARATION',approvedModelRequest:captured});
 proof.push({caseId:id,sourceTrace:'registry52-plan-completion/traces/'+n+'.json',sourceVersion:'52',actualProviderCapturedVersion:'53',
  requestSha256:hash(await readFile(resolve(here,'captures/'+id+'.request.json'))),completeInputOnlyVersionChanged:true,allOtherModelParametersUnchanged:true,
  taskScopeOrHintOrManufacturedRegistry:false,actualUpstreamCapture:false,historicalBoundAnswersNotFreshPlan:true});
}
const bundle={syntheticOnly:true,description:'ActualProvider53 first-request construction, original52 complete inputs only version53; no response oracle, scope, hint or public current Plan claim.',
 parameters:{executionMaxTokens:8192},phaseLimits:{GENERATION:0,PLAN:0,EXECUTION:2,REVIEW:0},cases,jobs,preservedEngineeringEvidence:preserved,writerNotAuthorized:true};
await save('bundle.json',bundle);await save('provenance.json',{actualModelCalls:0,taskActualBefore:200,proof,preserved});
console.log(JSON.stringify({actualModelCalls:0,bundleSha256:hash(await readFile(resolve(here,'bundle.json'))),provenanceSha256:hash(await readFile(resolve(here,'provenance.json'))),preserved}));
