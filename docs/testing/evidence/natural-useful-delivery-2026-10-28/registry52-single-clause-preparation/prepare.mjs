import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),repo=resolve(evidence,'../../../..');
const hash=x=>createHash('sha256').update(x).digest('hex');
const save=(name,value)=>writeFile(resolve(here,name),JSON.stringify(value,null,2)+'\n',{flag:'wx'});
const oldText='text与evidence保留同一对象、条件、范围和确定程度';
const newText='未被相应真实确认更新的原有对象、适用条件、范围和状态仍保留';
const engineering=await readFile(resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry53-engineering-verification.json'));
assert.equal(hash(engineering),'87917b78bcd5f580f11f6a9836e351a7efd6a35b4dc37fefbf12e7e8c223dfe9');
const closure=JSON.parse(await readFile(resolve(evidence,'registry52-plan-completion/closure.json'),'utf8'));
assert.equal(closure.status,'CLOSED');assert.ok(closure.entryLockedByClosure);
const cases=[],jobs=[],proof=[];
for(const[id,number]of[['P02_CONFIRMED','001'],['P02_UNKNOWN','003']]){
 const file='registry52-plan-completion/traces/'+number+'.json',bytes=await readFile(resolve(evidence,file)),trace=JSON.parse(bytes);
 assert.equal(hash(bytes),closure.files.find(x=>x.path.replaceAll('\\','/').endsWith('/'+file))?.sha256);
 const original=trace.request,request=structuredClone(original),system=original.messages[0].content;
 assert.equal(system.split(oldText).length-1,1);assert.equal(system.includes(newText),false);
 request.messages[0].content=system.replace(oldText,newText);
 const reverted=structuredClone(request);reverted.messages[0].content=reverted.messages[0].content.replace(newText,oldText);assert.deepEqual(reverted,original);
 assert.equal(request.messages.length,2);assert.equal(request.messages[1].content,original.messages[1].content);
 const data=JSON.parse(original.messages[1].content.slice(original.messages[1].content.indexOf('{')));
 assert.equal(data.strategyVersion,'52');assert.equal(data.modelId,'deepseek:deepseek-flash');
 assert.equal(request.model,'deepseek-flash');assert.equal(request.max_tokens,8192);assert.equal(request.temperature,.2);
 assert.equal(request.thinking.type,'disabled');assert.equal(request.response_format.type,'json_object');assert.equal(request.stream,false);
 cases.push({id,rawPrompt:data.rawPrompt,executorMaterials:[]});
 jobs.push({id:id+'_V52_CLAUSE_PREPARE',caseId:id,phase:'EXECUTION',version:'52',purpose:'MECHANISM_ONLY52_SINGLE_RETENTION_CLAUSE_CLARIFICATION',approvedModelRequest:request});
 proof.push({caseId:id,sourceTrace:file,sourceTraceSha256:hash(bytes),oldText,newText,sourceSystemSha256:hash(system),modifiedSystemSha256:hash(request.messages[0].content),
 changedCharacterOffset:system.indexOf(oldText),occurrencesReplaced:1,allOriginalPunctuationAndOtherPayloadContentUnchanged:true,revertRestoresWholeOriginalObject:true,
 fullOriginalUserMessageUnchanged:true,originalStrategyVersion:'52',executionRuntime:'Approved frozen53 compiled classes; fixed52 request proxy only, not53 enhance/R-only',historicalBoundAnswerReplayNotFreshPlan:true});
}
const bundle={syntheticOnly:true,description:'Only one52 retention clause changed; no new profile/wire/hint/message/registry. Runtime53 proxies fixed52 model payloads without enhance.',
 parameters:{executionMaxTokens:8192},phaseLimits:{GENERATION:0,PLAN:0,EXECUTION:2,REVIEW:0},cases,jobs,
 engineeringRuntime53ReportSha256:hash(engineering),mechanismArm:'52_SINGLE_RETENTION_CLAUSE_CLARIFICATION',writerNotAuthorized:true};
await save('bundle.json',bundle);await save('request-equivalence-proof.json',{actualModelCalls:0,taskActualBefore:199,proof});
console.log(JSON.stringify({actualModelCalls:0,jobs:jobs.length,bundleSha256:hash(await readFile(resolve(here,'bundle.json'))),proofSha256:hash(await readFile(resolve(here,'request-equivalence-proof.json')))}));
