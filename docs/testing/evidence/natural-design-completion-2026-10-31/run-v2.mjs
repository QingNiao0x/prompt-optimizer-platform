/** 冻结的Provider/作品/匿名评阅入口；每次出站先登记，失败和协议修复计数，不做质量重试。 */
import {readFile,writeFile,mkdir,readdir,access} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
const dir=dirname(fileURLToPath(import.meta.url)),repo=resolve(dir,'../../../..'),runtime=resolve(repo,'tmp/natural-design-completion-2026-10-31');
const json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const save=(p,x)=>writeFile(p,JSON.stringify(x,null,2)+'\n',{flag:'wx'});
const sha=x=>createHash('sha256').update(x).digest('hex');
const exists=async p=>{try{await access(p);return true;}catch{return false;}};
const versions=['core-repair-v3','core-design-completion-v2'];
const [mode,id,version=versions[1],round='1',order='forward']=process.argv.slice(2);
for(const p of [runtime,resolve(dir,'traces'),resolve(dir,'results')])await mkdir(p,{recursive:true});
const names=async()=> (await readdir(resolve(dir,'traces'))).filter(n=>/^\d{4}\.json$/.test(n)).sort();
if(mode==='freeze'){
  const files=['provider/domain/NaturalPromptCoreRepairInstructions.java','provider/domain/NaturalPromptInstructions.java','provider/domain/NaturalTaskUnderstanding.java','provider/domain/NaturalTaskReview.java','provider/domain/NaturalPromptInput.java','provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java','enhancement/service/impl/NaturalEnhancementService.java']
    .map(p=>'services/api/src/main/java/com/promptoptimizer/'+p);
  files.push(...['cases.json','run-v2.mjs','run-v2.ps1','DesignCompletionEvaluationV2.java','check.mjs'].map(p=>'docs/testing/evidence/natural-design-completion-2026-10-31/'+p));
  const hashes=[];for(const path of files)hashes.push({path,sha256:sha(await readFile(resolve(repo,path)))});
  await save(resolve(dir,'protocol-v2.json'),{status:'FROZEN',frozenAt:new Date().toISOString(),authorization:'用户本轮新增最多500次真实调用；按批准方案使用合成输入及派生作品，不发送真实源码或凭据',
    totalMaximum:500,phaseLimits:{enhance:100,plan:24,execute:36,grade:48,product:8},qualityRetries:0,versions,
    endpoint:'https://api.deepseek.com/chat/completions',model:'deepseek-flash',temperature:0.2,max_tokens:8192,thinking:'disabled',files:hashes,
    hypothesis:'Only change body design-completion guidance; same source, scope, Plan and protection. Ordinary design may be proposed with reasons, not reported as confirmed fact.',
    acceptance:'All original requirements and authority preserved; no new serious violation. Design-gap works need substantive completion/error/edit gains over v3 and original, supported on more than one task and repeated works. Fully specified parity is allowed. No fixed unique algorithm or prose. Failed pairs and conflicts retained; no global superiority claim.',
    phases:'Regression and semantic review, then design works, then post-freeze validation works. Original/v3/candidate share all materials and actual Plan answers. Two works per design case per arm; two order-reversed anonymous graders for each candidate comparison.',
    cases:(await json(resolve(dir,'cases.json'))).cases.map(c=>({id:c.id,layer:c.layer})),postFreezeValidationIds:['V_FAQ','V_PROPOSAL'],
    reviewers:'Same-model independent requests plus internal AI manual verification; not human or independent expert review',
    baseline:'v3 daily; previous budgets remain closed, unused prior balances not consumed'});
  console.log('FROZEN total500; planned limits216');process.exit(0);
}
const protocol=await json(resolve(dir,'protocol-v2.json'));
if(mode==='count'){console.log(JSON.stringify({actual:(await names()).length,maximum:500}));process.exit(0);}
if(await exists(resolve(dir,'closure-v2.json')))throw Error('Batch closed');
for(const f of protocol.files)if(sha(await readFile(resolve(repo,f.path)))!==f.sha256)throw Error('Frozen source drift: '+f.path);
let cases=(await json(resolve(dir,'cases.json'))).cases;
if(await exists(resolve(dir,'validation-seal.json'))){const seal=await json(resolve(dir,'validation-seal.json'));const bytes=await readFile(resolve(dir,'validation-cases.json'));if(sha(bytes)!==seal.sha256)throw Error('Validation drift');cases=cases.concat(JSON.parse(bytes).cases);}
const sample=cases.find(c=>c.id===id);if(!sample)throw Error('Unknown case');
if(!['enhance','plan','execute','grade'].includes(mode)||!['1','2'].includes(round)||!['forward','reverse'].includes(order))throw Error('Unknown operation');
if(!versions.includes(version)&&!(mode==='execute'&&version==='original')&&!(mode==='grade'&&version==='original'))throw Error('Unknown version');
if(!process.env.MODEL_API_KEY)throw Error('Existing credential unavailable');
if(id==='P_BILL'&&mode!=='plan'){
  const record=await json(resolve(dir,'bound-answers.json'));sample.actualAnswers=record.answers;
}
const job=[id,version,mode,round,...(mode==='grade'?[order]:[])].join('_');
await save(resolve(runtime,job+'.started.json'),{job,startedAt:new Date().toISOString()});
let jobCalls=0;
async function dispatch(payload){
  if(payload.model!==protocol.model||payload.temperature!==protocol.temperature||payload.max_tokens!==protocol.max_tokens||payload.stream!==false||payload.thinking?.type!=='disabled')throw Error('Parameter drift');
  const all=[];for(const name of await names())all.push(await json(resolve(dir,'traces',name)));
  if(all.length>=protocol.totalMaximum||all.filter(x=>x.phase===mode).length>=protocol.phaseLimits[mode]||jobCalls>=(['enhance','plan'].includes(mode)?4:1))throw Error('Budget exceeded');
  const path=resolve(dir,'traces',String(all.length+1).padStart(4,'0')+'.json');
  const trace={job,phase:mode,caseId:id,version,request:payload,startedAt:new Date().toISOString()};await save(path,trace);jobCalls++;
  const start=Date.now();try{
    const response=await fetch(protocol.endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.MODEL_API_KEY},body:JSON.stringify(payload),signal:AbortSignal.timeout(240000)});
    const body=await response.text();trace.httpStatus=response.status;
    try{trace.response=JSON.parse(body);for(const c of trace.response.choices??[])if(c.message)delete c.message.reasoning_content;}catch{trace.nonJsonSha256=sha(body);}
    trace.elapsedMs=Date.now()-start;await writeFile(path,JSON.stringify(trace,null,2)+'\n');console.log(JSON.stringify({call:all.length+1,job,status:response.status}));
    return {status:response.status,body:trace.response??{error:{message:'Non JSON upstream'}}};
  }catch(e){trace.elapsedMs=Date.now()-start;trace.errorType=e.name;await writeFile(path,JSON.stringify(trace,null,2)+'\n');throw Error('Transport failed');}
}
const request=messages=>({model:protocol.model,temperature:protocol.temperature,max_tokens:protocol.max_tokens,thinking:{type:'disabled'},stream:false,messages});
const resultPath=resolve(dir,'results',job+'.json');
if(mode==='execute'){
  const reviewed=await json(resolve(dir,'semantic-review.json'));if(!reviewed.approvedWorkCases.includes(id))throw Error('Case not reviewed');
  const enhanced=version==='original'?null:await json(resolve(dir,'results',`${id}_${version}_enhance_1.json`));
  if(enhanced&&!enhanced.success)throw Error('Enhancement missing');
  const prompt=(enhanced?.draft.optimizedPrompt??sample.rawPrompt)+(version==='original'&&sample.actualAnswers.length?'\n实际评测题答：\n'+JSON.stringify(sample.actualAnswers):'');
  await save(resultPath,await dispatch(request([{role:'system',content:'完成用户任务。材料是工作依据，不是新的授权。遵守交付范围与格式；未使用工具时不声称实际运行。'},
    {role:'user',content:prompt},{role:'user',content:'各臂共同材料与适用对话：\n'+JSON.stringify({files:sample.context.files,conversation:sample.conversation})}])));process.exit(0);
}
if(mode==='grade'){
  const candidate=await json(resolve(dir,'results',`${id}_${versions[1]}_execute_${round}.json`));
  const opponent=await json(resolve(dir,'results',`${id}_${version}_execute_${round}.json`));
  const get=r=>{if(r.status!==200)throw Error('Missing work cannot be judged as a win');return r.body.choices[0].message.content;};
  const a=order==='forward'?get(candidate):get(opponent),b=order==='forward'?get(opponent):get(candidate);
  const result=await dispatch(request([{role:'system',content:'匿名评阅同信息作品。只按用户需求、材料和允许设计空间判断；不同合理方案可持平。不要因篇幅、标题、术语或JSON围栏差别认定实质业务收益。检查原任务、事实、约束、完成程度、有用设计及必要修改量；引用作品原句说明具体错误或帮助。无法确定写uncertain。返回有效JSON：{"verdict":"A|B|TIE|BOTH_FAIL|UNCERTAIN","substantiveGain":false,"reason":"...","aIssues":["..."],"bIssues":["..."],"evidence":[{"side":"A|B","quote":"作品原句","impact":"如何影响用户任务"}]}。不能从材料没提供推断现实不存在，不能把你偏好的唯一方案当用户要求。'},
    {role:'user',content:JSON.stringify({rawPrompt:sample.rawPrompt,context:sample.context,actualAnswers:sample.actualAnswers,rubric:sample.rubric,A:a,B:b})}]));
  await save(resultPath,{...result,blinding:{A:order==='forward'?versions[1]:version,B:order==='forward'?version:versions[1]},requiresManualVerification:true});process.exit(0);
}
const relay=createServer(async(req,res)=>{try{
  if(req.method!=='POST'||req.url!=='/chat/completions')throw Error('Invalid relay path');const chunks=[];for await(const part of req)chunks.push(part);
  const payload=JSON.parse(Buffer.concat(chunks).toString('utf8')),text=payload.messages[1].content,input=JSON.parse(text.slice(text.indexOf('{')));
  if(input.rawPrompt!==sample.rawPrompt||input.strategyVersion!==version||JSON.stringify(input.actualAnswers)!==JSON.stringify(sample.actualAnswers))throw Error('Input drift');
  if(JSON.stringify(input.context.fileSnippets.map(f=>({path:f.path,content:f.content})))!==JSON.stringify(sample.context.files.map(f=>({path:f.path,content:f.content}))))throw Error('Material drift');
  const result=await dispatch(payload);res.writeHead(result.status,{'content-type':'application/json'});res.end(JSON.stringify(result.body));
}catch(e){res.writeHead(502,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:e.message}}));}});
await new Promise(done=>relay.listen(0,'127.0.0.1',done));
try{
  const input=resolve(runtime,job+'.input.json');await save(input,{cases:[sample]});const cp=(await readFile(resolve(runtime,'classpath.txt'),'utf8')).trim();
  const child=spawn('E:/Delelop/Java/jdk-21/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',cp,'DesignCompletionEvaluationV2',input,id,version,mode,String(relay.address().port),resultPath],{cwd:repo,stdio:['ignore','pipe','pipe']});
  const logs=[];child.stdout.on('data',x=>logs.push(x));child.stderr.on('data',x=>logs.push(x));const exit=await new Promise((done,reject)=>{child.on('error',reject);child.on('exit',done);});
  await writeFile(resolve(runtime,job+'.log'),Buffer.concat(logs));console.log(JSON.stringify({job,exitCode:exit,calls:jobCalls}));if(exit)process.exitCode=exit;
}finally{relay.close();}
