import {readFile,writeFile,readdir} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const dir=dirname(fileURLToPath(import.meta.url)),read=async p=>JSON.parse(await readFile(p,'utf8'));
const protocol=await read(resolve(dir,'protocol.json'));
const result=await read(resolve(dir,'product/D_NOTICE-revision-response.json'));
const prompt=result.body.data.result.optimizedPrompt;
const source=await read(resolve(dir,'product-cases.json'));
const seal={endpoint:protocol.endpoint,model:protocol.model,temperature:protocol.temperature,max_tokens:protocol.max_tokens,previousCalls:82,maximum:500,planned:2,qualityRetries:0,promptSha256:createHash('sha256').update(prompt).digest('hex'),case:source.cases[0].id,purpose:'Verify 80-character revised notification remains usable; not a new generic prose-quality contest'};
await writeFile(resolve(dir,'feedback-work-protocol.json'),JSON.stringify(seal,null,2)+'\n',{flag:'wx'});
for(let round=1;round<=2;round++){
 const names=(await readdir(resolve(dir,'traces'))).filter(x=>/^\d{4}\.json$/.test(x));
 assert.ok(names.length+protocol.priorCalls<500 && names.length<protocol.stageMaximum);
 const payload={model:seal.model,temperature:seal.temperature,max_tokens:seal.max_tokens,thinking:{type:'disabled'},stream:false,messages:[{role:'system',content:'完成用户任务，保持已有事实与交付限制，不声明未实际执行的操作。'},{role:'user',content:prompt}]};
 const path=resolve(dir,'traces',String(names.length+1).padStart(4,'0')+'.json');
 const trace={phase:'execute',caseId:'D_NOTICE_REVISED',round,request:payload,startedAt:new Date().toISOString()};
 await writeFile(path,JSON.stringify(trace,null,2)+'\n',{flag:'wx'});
 try {
 const response=await fetch(seal.endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.MODEL_API_KEY},body:JSON.stringify(payload),signal:AbortSignal.timeout(180000)});
 trace.httpStatus=response.status;trace.response=await response.json();for(const c of trace.response.choices??[])if(c.message)delete c.message.reasoning_content;
 await writeFile(path,JSON.stringify(trace,null,2)+'\n');
 const body=trace.response.choices?.[0]?.message?.content??'';
 await writeFile(resolve(dir,`product/D_NOTICE-work-${round}.json`),JSON.stringify({round,body,characters:Array.from(body).length,within80:Array.from(body).length<=80,requiredFacts:['11月4日','数据同步','11月3日18:00','已交付'].every(x=>body.includes(x)),needsHumanReview:true},null,2)+'\n',{flag:'wx'});
 console.log(JSON.stringify({total:protocol.priorCalls+names.length+1,status:response.status,round}));
 }catch(e){trace.errorType=e.name;await writeFile(path,JSON.stringify(trace,null,2)+'\n');throw e;}
}
