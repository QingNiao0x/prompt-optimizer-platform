/** 同信息实际作品：每臂两次固定执行，包含全部背景/题答/意见；不重试、不用缺失作品判胜。 */
import {readFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {dir,json,save,dispatch,checkFrozen,exists} from './run.mjs';
const hash=x=>createHash('sha256').update(x).digest('hex');
const protocol=await checkFrozen(),planFile=resolve(dir,'works-protocol.json');
const sample=(await json(resolve(dir,'cases.json'))).cases.find(x=>x.id==='W_CODE');
const code=(await json(resolve(dir,'results/W_CODE_core-repair-v4_enhance.json'))).draft.optimizedPrompt;
const planCase=(await json(resolve(dir,'product-cases.json'))).cases.find(x=>x.id==='D_PLAN');
const product=await json(resolve(dir,'product/D_PLAN-revision-response.json'));
const request=await json(resolve(dir,'product/D_PLAN-generation-request.json'));
const candidate=product.body.data.result.optimizedPrompt;
const answers=request.planConfirmation.answers;
const pairs=[
 {id:'W_CODE',original:sample.rawPrompt,candidate:code,common:JSON.stringify({context:sample.context,conversation:sample.conversation}),rubric:sample.rubric},
 {id:'D_PLAN',original:planCase.rawPrompt,candidate,common:JSON.stringify({actualPlanAnswers:answers,latestUserFeedback:planCase.feedback}),rubric:['直接给出通知正文','100字以内且正式','不得编造日期或承诺补偿','不要求再次确认已答问题']}
];
if(process.argv[2]==='freeze'){
 await save(planFile,{frozenAt:new Date().toISOString(),scriptSha256:hash(await readFile(new URL(import.meta.url))),sourceFiles:['cases.json','product-cases.json','results/W_CODE_core-repair-v4_enhance.json','product/D_PLAN-revision-response.json','product/D_PLAN-generation-request.json'].map(path=>({path})),pairs,plannedCalls:8,repetitions:2,qualityRetries:0,endpoint:protocol.endpoint,model:protocol.model,temperature:protocol.temperature,max_tokens:protocol.max_tokens,assessment:'Code behavior tests and developer semantic review; no independent human review; original and candidate receive identical common information.'});
 console.log('FROZEN 8 work calls');
}else{
 const sealed=await json(planFile);if(hash(await readFile(new URL(import.meta.url)))!==sealed.scriptSha256||JSON.stringify(pairs)!==JSON.stringify(sealed.pairs))throw Error('Work protocol drift');
 const output=resolve(dir,'works');await mkdir(output,{recursive:true});
 for(const pair of pairs)for(let round=1;round<=2;round++)for(const arm of (round===1?['original','candidate']:['candidate','original'])){
  const job=pair.id+'_'+arm+'_'+round,path=resolve(output,job+'.json');
  if(await exists(path))throw Error('Existing work cannot be rerun');
  await save(resolve(output,job+'.started.json'),{job,startedAt:new Date().toISOString()});
  const result=await dispatch({model:protocol.model,temperature:protocol.temperature,max_tokens:protocol.max_tokens,thinking:{type:'disabled'},stream:false,messages:[
   {role:'system',content:'完成用户任务，遵守交付范围与格式。相同资料和已完成的实际题答随请求提供；不声明未执行的操作。'},
   {role:'user',content:pair[arm]},{role:'user',content:'本次任务的共同资料及实际确认：\n'+pair.common}
  ]},'execute',job);await save(path,result);
 }
}
