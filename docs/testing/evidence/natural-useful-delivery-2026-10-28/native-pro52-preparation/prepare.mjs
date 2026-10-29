import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateNativePro52Bundle,NATIVE_PRO52_PROFILE} from '../native-pro52-profile.mjs';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),base=resolve(evidence,'registry52-plan-completion');
const hash=x=>createHash('sha256').update(x).digest('hex');
const save=(name,value)=>writeFile(resolve(here,name),JSON.stringify(value,null,2)+'\n',{flag:'wx'});
const jobs=[],cases=[],sources=[];
for(const[id,number]of[['P02_CONFIRMED','001'],['P02_UNKNOWN','003']]){
 const bytes=await readFile(resolve(base,'traces/'+number+'.json')),trace=JSON.parse(bytes),source=trace.request;
 const request=structuredClone(source);request.model='deepseek-v4-pro';
 const reverted=structuredClone(request);reverted.model='deepseek-flash';assert.deepEqual(reverted,source);
 const text=source.messages[1].content,input=JSON.parse(text.slice(text.indexOf('{')));
 assert.equal(input.modelId,'deepseek:deepseek-flash');assert.equal(input.strategyVersion,'52');assert.equal(source.messages.length,2);
 cases.push({id,rawPrompt:input.rawPrompt,executorMaterials:[]});
 jobs.push({id:id+'_V52_PRO_PREPARE',caseId:id,phase:'EXECUTION',version:'52',purpose:'MECHANISM_ONLY_NATIVE_PRO_ORIGINAL52_PREPARATION',approvedModelRequest:request});
 await save(id+'.request.json',request);
 sources.push({caseId:id,sourceTrace:'registry52-plan-completion/traces/'+number+'.json',sourceTraceSha256:hash(bytes),requestSha256:hash(await readFile(resolve(here,id+'.request.json'))),
   onlyTopLevelModelChanged:true,innerModelIdIsHistoricalMetadata:'deepseek:deepseek-flash',actualUpstreamModel:'deepseek-v4-pro',notAProductProPlan:true});
}
const bundle={syntheticOnly:true,description:'Native Pro mechanism only: immutable52 PREPARE requests solely change top-level model; inner Flash modelId remains historical metadata, not product Pro Plan routing.',
 modelSelectionProfile:{name:NATIVE_PRO52_PROFILE,modelId:'deepseek:deepseek-v4-pro',baselineStage:'registry52-plan-completion',
 baselineJobsSha256:hash(await readFile(resolve(base,'jobs.json'))),baselineProtocolSha256:hash(await readFile(resolve(base,'protocol.json')))},
 parameters:{generationMaxTokens:8192,executionMaxTokens:8192,temperature:.2,thinking:'disabled',stream:false},
 phaseLimits:{GENERATION:0,PLAN:0,EXECUTION:2,REVIEW:0},cases,jobs};
const profile=await validateNativePro52Bundle(bundle,{evidence});await save('bundle.draft.json',bundle);
let passed=1;
const rejects=[b=>b.jobs[0].approvedModelRequest.model='deepseek-flash',b=>b.modelSelectionProfile.endpoint='https://example.invalid',
 b=>b.jobs[0].id='UNFROZEN_JOB',b=>b.jobs[0].approvedModelRequest.messages[1].content+=' ',b=>b.phaseLimits.EXECUTION=3,
 b=>b.jobs[0].approvedModelRequest.messages.push({role:'user',content:'extra'}),b=>b.jobs[1].caseId='P02_CONFIRMED',b=>b.jobs[0].approvedModelRequest.max_tokens=10000];
for(const mutate of rejects){const changed=structuredClone(bundle);mutate(changed);await assert.rejects(()=>validateNativePro52Bundle(changed,{evidence}));passed++;}
await save('provenance.json',{actualModelCalls:0,taskActual:198,sources,profile,scope:'No hint or additional message; no new facts, registry, policy, system, parameters, endpoint or product change.'});
await save('js-boundary-validation.json',{status:'ZERO_CALL_PREPARATION_NOT_FROZEN_OR_LIVE',passed,failed:0,actualModelCalls:0,
 bundleSha256:hash(await readFile(resolve(here,'bundle.draft.json'))),sources,currentCatalogRouteCheck:'Pending explicit root freeze/start/GET-only approval; not assumed from historical route.'});
console.log(JSON.stringify({actualModelCalls:0,passed,bundleSha256:hash(await readFile(resolve(here,'bundle.draft.json'))),sources}));
