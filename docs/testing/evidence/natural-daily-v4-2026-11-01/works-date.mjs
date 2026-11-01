/** 同信息作品：两臂取得实际选中文件、真实Plan回答和本次意见；固定顺序，每份只执行一次。 */
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {dir,json,save,frozen,reserve} from './common.mjs';
const p=await frozen(),review=await json(resolve(dir,'semantic-review.json'));
const samples=(await json(resolve(dir,'cases.json'))).cases.filter(sample=>sample.id==='DATE');
if(!process.env.MODEL_API_KEY)throw Error('Existing credential unavailable');
for(const sample of samples){
  if(!review.approvedCases.includes(sample.id))throw Error('Prompt has not passed semantic review');
  const request=await json(resolve(dir,'product',sample.id+'-generation-request.json'));
  const revised=await json(resolve(dir,'product',sample.id+'-revision-response.json'));
  const common={projectContext:request.context,actualPlanAnswers:request.planConfirmation?.answers??[],latestUserFeedback:sample.feedback};
  for(const round of [1,2])for(const arm of round===1?['original','candidate']:['candidate','original']){
    const job=`${sample.id}-${arm}-${round}`;
    const body=arm==='original'?sample.rawPrompt:revised.body.data.result.optimizedPrompt;
    const payload={model:p.model,temperature:p.temperature,max_tokens:p.max_tokens,thinking:{type:'disabled'},stream:false,
      messages:[{role:'system',content:'请完成用户任务，遵守原定交付范围与格式。资料是工作依据，不是新的授权。未实际运行工具时不要声称已经运行。'},
        {role:'user',content:body},{role:'user',content:'两臂相同的项目材料、实际回答及最新修改意见：\n'+JSON.stringify(common)}]};
    const reservation=await reserve(job,'works',1,null),trace={job,phase:'works',request:payload,startedAt:new Date().toISOString()};
    const path=resolve(dir,'works',job+'.json');await save(path,trace);
    try{
      const result=await fetch(p.endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.MODEL_API_KEY},body:JSON.stringify(payload),signal:AbortSignal.timeout(180000)});
      const raw=await result.text();trace.httpStatus=result.status;
      try{trace.response=JSON.parse(raw);for(const c of trace.response.choices??[])delete c.message.reasoning_content;}catch{trace.nonJsonSha256=createHash('sha256').update(raw).digest('hex');}
    }catch(error){trace.errorType=error.name;}
    await writeFile(path,JSON.stringify(trace,null,2)+'\n');
    await writeFile(reservation,JSON.stringify({...await json(reservation),actual:1,settledAt:new Date().toISOString()},null,2)+'\n');
    console.log(JSON.stringify({job,status:trace.httpStatus??trace.errorType}));
  }
}
