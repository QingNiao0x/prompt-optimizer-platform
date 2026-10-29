import assert from 'node:assert/strict';import {createHash}from'node:crypto';import{readFile,writeFile}from'node:fs/promises';import{dirname,resolve}from'node:path';import{fileURLToPath}from'node:url';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),hash=x=>createHash('sha256').update(x).digest('hex');
const jobs=[],cases=[],proof=[];
for(const[id,n]of[['P02_CONFIRMED','001'],['P02_UNKNOWN','003']]){
 const source=JSON.parse(await readFile(resolve(evidence,'registry52-plan-completion/traces/'+n+'.json'),'utf8')),request=JSON.parse(await readFile(resolve(here,'captures/'+id+'.request.json'),'utf8'));
 const a=source.request.messages[1].content,b=request.messages[1].content,old=JSON.parse(a.slice(a.indexOf('{'))),current=JSON.parse(b.slice(b.indexOf('{')));
 assert.equal(current.strategyVersion,'24');assert.ok(!current.taskScope&&!current.preparedRequirements&&!current.inputBoundary);
 const reverted=structuredClone(current);reverted.strategyVersion='52';const core=structuredClone(old);delete core.inputBoundary;assert.deepEqual(reverted,core);
 const expected=structuredClone(source.request);expected.messages=request.messages;assert.deepEqual(expected,request);
 cases.push({id,rawPrompt:current.rawPrompt,executorMaterials:[]});jobs.push({id:id+'_V24_SINGLE_EXEC',caseId:id,phase:'EXECUTION',version:'24',purpose:'MECHANISM_ONLY_NATIVE24_SINGLE_CALL_BUSINESS_AND_PROTOCOL_DIFFERENCES',approvedModelRequest:request});
 proof.push({caseId:id,actualProvider24Capture:true,coreInputOnlyVersionChanged:true,existing24TransportOmitsDerived52InputBoundary:true,
  protocolAndBusinessBothDifferFrom52Or53:true,requestSha256:hash(await readFile(resolve(here,'captures/'+id+'.request.json')))});
}
const reviewProtocol={inspectionOrder:['OPTIMIZED_PROMPT','TASK_RECORD'],historicalEvidenceIsNotCurrentInstruction:true,responsibilitiesReportedSeparately:true,stopOnSevereViolationOrOriginalProtectionFailure:true};
const bundle={syntheticOnly:true,description:'Existing24 actual single-call Provider body+task mechanism, not53 one-variable or product adoption.',parameters:{executionMaxTokens:8192},
 phaseLimits:{GENERATION:0,PLAN:0,EXECUTION:2,REVIEW:0},cases,jobs,reviewProtocol};
for(const[file,value]of[['bundle.json',bundle],['provenance.json',{actualModelCalls:0,taskActualBefore:201,proof,reviewProtocol}]])await writeFile(resolve(here,file),JSON.stringify(value,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({actualModelCalls:0,bundleSha256:hash(await readFile(resolve(here,'bundle.json'))),provenanceSha256:hash(await readFile(resolve(here,'provenance.json')))}));
