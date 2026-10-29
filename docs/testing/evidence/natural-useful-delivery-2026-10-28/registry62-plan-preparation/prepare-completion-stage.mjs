import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { here, repo, loadCases, json } from './browser-support.mjs';
import { readCapture, build62Completion } from './build-plan-completion.mjs';
const samples=await loadCases(),jobs=[],cases=[];
const normal=await json(resolve(here,'../registry61-development/jobs.json'));
const empty=normal.find(job=>job.caseId==='V01').approvedEnhancementInput;
const selections=resolve(here,'operator-selections');await mkdir(selections,{recursive:true});
for(const sample of samples){
  const capture=await readCapture(resolve(here,'plan-captures/registry62-plan-context-capture',sample.id));
  const result=await json(resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry62-plan/results',sample.futurePlanJobId+'.json'));
  assert.equal(result.status,200); const plan=result.body.data;
  assert.equal(plan.questions.length,1,'Unexpected extra question: stop and inspect, never auto-select');
  const question=plan.questions[0]; assert.match(question.question,/今天/);
  const confirmed=sample.id==='P02_CONFIRMED';
  const option=confirmed?question.options.find(x=>x.id==='allow_today' && x.label==='允许今日'):null;
  if(confirmed){assert.ok(option);assert.equal(option.answer,'允许填写和导入今天（等于 today）的调查日期，校验只拒绝晚于 today 的日期');assert.equal(option.recommended,false);}
  const answer={questionId:question.id,question:question.question,optionIds:confirmed?[option.id]:[],customAnswer:confirmed?'':'今天是否允许尚未决定',answer:confirmed?option.answer:'今天是否允许尚未决定'};
  // This records the root's explicit synthetic operator choice after reading the actual question.
  // No recommended default, fabricated question, or old/expired Plan is substituted.
  const operator={kind:'EXPLICIT_OPERATOR_SELECTION',caseId:sample.id,planId:plan.planId,planningContext:plan.planningContext,
    actorScopeSha256:capture.verdict.actorScopeSha256,selectedAt:new Date().toISOString(),decisionQuestionId:question.id,answers:[answer]};
  await writeFile(resolve(selections,sample.id+'.json'),JSON.stringify(operator,null,2)+'\n',{flag:'wx'});
  const base={...structuredClone(empty),rawPrompt:sample.rawPrompt,context:structuredClone(capture.prepareInput.context),conversationHistory:structuredClone(capture.planInput.conversationHistory),
    permissionPolicy:structuredClone(capture.prepareInput.permissionPolicy??empty.permissionPolicy),planConfirmation:null};
  const job=build62Completion(sample,capture,result,operator,base);jobs.push(job);cases.push(sample);
}
const zero=await json(resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry62-plan/results/P00_V62_PLAN.json'));
assert.equal(zero.status,200);assert.deepEqual(zero.body.data.questions,[]);assert.ok(Date.parse(zero.body.data.expiresAt)>Date.now());
const input=structuredClone(empty);input.planConfirmation={planId:zero.body.data.planId,planningContext:null,answers:[]};
jobs.unshift({id:'P00_V62_GEN',caseId:'P00',phase:'GENERATION',version:'62',approvedEnhancementInput:input});cases.unshift({id:'P00',rawPrompt:input.rawPrompt});
await writeFile(resolve(here,'completion-bundle.json'),JSON.stringify({syntheticOnly:true,cases,jobs,phaseLimits:{GENERATION:18,PLAN:0,EXECUTION:0,REVIEW:0},qualityRetriesAllowed:false,
  purpose:'Actual fresh Plan ids and exact operator answers; service validates ownership, original conditions, all answer sources and immutable generated-body coverage.'},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({prepared:true,jobs:jobs.length,actualCalls:0}));
