import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkR2 } from '../checkers/code-work-r2.mjs';
const here=dirname(fileURLToPath(import.meta.url)), evidence=resolve(here,'..'), repo=resolve(evidence,'../../../..');
const jobs=JSON.parse(await readFile(resolve(evidence,'registry58-works/jobs.json'),'utf8'));
const runtime=resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry58-works');
const all=[];
for(const job of jobs) all.push({job,result:JSON.parse(await readFile(resolve(runtime,'results',job.id+'.json'),'utf8'))});
assert.equal(all.length,28,'Do not review partial cohorts');
const destination=resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry58-works-code-checks');
await mkdir(destination,{recursive:true});
const checks=[];
for(const {job,result} of all.filter(({job})=>job.caseId.startsWith('C'))) {
  let outcome;
  try {
    assert.equal(result.status,200); assert.equal(result.body.data.finishReason,'stop');
    const work=JSON.parse(result.body.data.content);
    outcome=await checkR2(work,job.caseId,resolve(destination,job.id));
  } catch(failure) { outcome={passed:false,deliveryOrCheckFailure:String(failure.message)}; }
  await writeFile(resolve(destination,job.id+'.json'),JSON.stringify(outcome,null,2)+'\n',{flag:'wx'});
  checks.push({jobId:job.id,caseId:job.caseId,version:job.version,repeat:job.repeat,passed:outcome.passed,
    delivery:outcome.deliveryContractPassed??false,business:outcome.business?.passed??false,
    suppliedToday:outcome.additionalBusiness?.passed??false,optionalToday:outcome.optionalToday?.passed??false,
    detectsOriginalBug:outcome.regressionTests?.detectsOriginalBug??false,
    ...(outcome.deliveryOrCheckFailure?{failure:outcome.deliveryOrCheckFailure}:{})});
}
await writeFile(resolve(here,'code-check-results.json'),JSON.stringify({actualModelCalls:0,checks},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({codeWorks:checks.length,passed:checks.filter(value=>value.passed).length,checks}));
