import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkR2 } from '../checkers/code-work-r2.mjs';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),repo=resolve(evidence,'../../../..');
const jobs=JSON.parse(await readFile(resolve(evidence,'registry61-works/jobs.json'),'utf8'));
const all=await Promise.all(jobs.map(async job=>({job,result:JSON.parse(await readFile(resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry61-works/results',job.id+'.json'),'utf8'))})));
assert.equal(all.length,8);
const destination=resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry61-works-code-checks');await mkdir(destination,{recursive:true});
const checks=[];
for(const {job,result} of all.filter(x=>x.job.caseId.startsWith('C'))){
  let outcome;
  try{assert.equal(result.status,200);assert.equal(result.body.data.finishReason,'stop');outcome=await checkR2(JSON.parse(result.body.data.content),job.caseId,resolve(destination,job.id));}
  catch(error){outcome={passed:false,deliveryOrCheckFailure:String(error.message)};}
  await writeFile(resolve(destination,job.id+'.json'),JSON.stringify(outcome,null,2)+'\n',{flag:'wx'});
  checks.push({jobId:job.id,caseId:job.caseId,version:job.version,repeat:job.repeat,passed:outcome.passed,delivery:outcome.deliveryContractPassed??false,business:outcome.business?.passed??false,
    suppliedToday:outcome.additionalBusiness?.passed??false,optionalToday:outcome.optionalToday?.passed??false,detectsOriginalBug:outcome.regressionTests?.detectsOriginalBug??false,
    ...(outcome.deliveryOrCheckFailure?{failure:outcome.deliveryOrCheckFailure}:{})});
}
await writeFile(resolve(here,'code-check-results.json'),JSON.stringify({actualCalls:0,newCodeWorks:checks.length,checks,baselineChecks:'Unchanged closed registry58-works-preparation/code-check-results.json, no historical rescore.'},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({codeWorks:checks.length,passed:checks.filter(x=>x.passed).length,checks}));
