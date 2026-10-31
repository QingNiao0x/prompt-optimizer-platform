/** 本轮共用计数与冻结入口；失败、协议修复均保留，每项只运行一次，不做质量重试。 */
import {readFile,writeFile,mkdir,readdir,access} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
export const dir=dirname(fileURLToPath(import.meta.url)),repo=resolve(dir,'../../../..'),runtime=resolve(repo,'tmp/natural-plan-context-repair-2026-11-01');
export const json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
export const save=(p,x)=>writeFile(p,JSON.stringify(x,null,2)+'\n',{flag:'wx'});
const sha=x=>createHash('sha256').update(x).digest('hex');
export const exists=async p=>{try{await access(p);return true;}catch{return false;}};
export const names=async()=> (await readdir(resolve(dir,'traces'))).filter(n=>/^\d{4}\.json$/.test(n)).sort();
export async function checkFrozen(){
 if(await exists(resolve(dir,'closure.json')))throw Error('Closed evaluation');
 const p=await json(resolve(dir,'protocol.json'));
 for(const file of p.files)if(sha(await readFile(resolve(repo,file.path)))!==file.sha256)throw Error('Frozen source drift: '+file.path);
 return p;
}
let pending=false;
/** 串行出站，先持久化计数；不将凭据写入请求原件或子进程参数。 */
export async function dispatch(payload,phase,job){
 if(pending)throw Error('Concurrent evaluation not permitted');pending=true;
 try{
  const protocol=await checkFrozen();
  if(!process.env.MODEL_API_KEY)throw Error('Credential unavailable');
  for(const key of ['model','temperature','max_tokens'])if(payload[key]!==protocol[key])throw Error('Parameter drift');
  if(payload.stream!==false||payload.thinking?.type!=='disabled')throw Error('Parameter drift');
  const records=[];for(const name of await names())records.push(await json(resolve(dir,'traces',name)));
  if(records.length>=protocol.totalMaximum||records.length>=protocol.stageMaximum||records.filter(x=>x.phase===phase).length>=protocol.phaseLimits[phase])throw Error('Budget exceeded');
  const path=resolve(dir,'traces',String(records.length+1).padStart(4,'0')+'.json');
  const trace={phase,job,request:payload,startedAt:new Date().toISOString()};await save(path,trace);
  const start=Date.now();
  try{
   const response=await fetch(protocol.endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.MODEL_API_KEY},body:JSON.stringify(payload),signal:AbortSignal.timeout(180000)});
   trace.httpStatus=response.status;const text=await response.text();
   try{trace.response=JSON.parse(text);for(const c of trace.response.choices??[])if(c.message)delete c.message.reasoning_content;}catch{trace.nonJsonSha256=sha(text);}
   trace.elapsedMs=Date.now()-start;await writeFile(path,JSON.stringify(trace,null,2)+'\n');
   console.log(JSON.stringify({call:records.length+1,phase,job,status:response.status}));
   return {status:response.status,body:trace.response??{error:{message:'Non JSON upstream'}}};
  }catch(e){trace.elapsedMs=Date.now()-start;trace.errorType=e.name;await writeFile(path,JSON.stringify(trace,null,2)+'\n');throw e;}
 }finally{pending=false;}
}
async function main(){
 const [mode,id,version='core-repair-v4']=process.argv.slice(2);
 for(const p of [runtime,resolve(dir,'traces'),resolve(dir,'results'),resolve(dir,'product')])await mkdir(p,{recursive:true});
 if(mode==='freeze'){
  const source=['provider/domain/NaturalPromptPlanContextInstructions.java','provider/domain/NaturalTaskScopeSources.java','provider/domain/NaturalTaskScope.java','provider/domain/NaturalPromptInstructions.java','provider/domain/NaturalPromptStageInstructions.java','provider/domain/NaturalPromptRevision.java','provider/domain/NaturalTaskSource.java','provider/domain/NaturalTaskReview.java','provider/domain/NaturalPromptPhasePayloads.java','provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java','enhancement/service/impl/NaturalEnhancementService.java','history/service/impl/MybatisOptimizationHistoryService.java'].map(x=>'services/api/src/main/java/com/promptoptimizer/'+x);
  const files=[...source,...['run.mjs','run.ps1','PlanContextEvaluation.java','cases.json','product-cases.json','product-relay.mjs','product.ps1','product-browser.mjs'].map(x=>'docs/testing/evidence/natural-plan-context-repair-2026-11-01/'+x)];
  const hashes=[];for(const path of files)hashes.push({path,sha256:sha(await readFile(resolve(repo,path)))});
  await save(resolve(dir,'protocol.json'),{status:'FROZEN',frozenAt:new Date().toISOString(),authorization:'本轮用户新增最多200次，合成材料及派生结果至DeepSeek官方端点，旧批次不重开',totalMaximum:200,stageMaximum:96,phaseLimits:{enhance:40,plan:8,product:36,execute:12},qualityRetries:0,endpoint:'https://api.deepseek.com/chat/completions',model:'deepseek-flash',temperature:0.2,max_tokens:8192,thinking:'disabled',versions:['core-repair-v3','core-repair-v4'],files:hashes,hypothesis:'Same v3 prose strategy with phase-specific protocols, answered interaction state and explicit user/context evidence roles',acceptance:'No repeated request for already answered decisions; original task/limits/unknowns retained; context supports authorized action; valid phase delivery; real Plan-to-revision-copy-history consistency. Engineering adoption independent of broad prose quality.',review:'Deterministic checks and internal developer semantic review, not independent human evaluation.'});
  console.log('FROZEN task200 stage96');return;
 }
 if(mode==='count'){console.log(JSON.stringify({actual:(await names()).length,maximum:200}));return;}
 await checkFrozen();
 if(!['enhance','plan'].includes(mode)||!['core-repair-v3','core-repair-v4'].includes(version))throw Error('Invalid job');
 const sample=(await json(resolve(dir,'cases.json'))).cases.find(x=>x.id===id);if(!sample)throw Error('Unknown case');
 const job=[id,version,mode].join('_');await save(resolve(runtime,job+'.started.json'),{job,startedAt:new Date().toISOString()});
 let calls=0;
 const relay=createServer(async(req,res)=>{try{
  if(req.method!=='POST'||req.url!=='/chat/completions')throw Error('Invalid relay path');const chunks=[];for await(const part of req)chunks.push(part);
  const payload=JSON.parse(Buffer.concat(chunks).toString('utf8')),text=payload.messages[1].content,input=JSON.parse(text.slice(text.indexOf('{')));
  if(calls++>=4||input.rawPrompt!==sample.rawPrompt||input.strategyVersion!==version||JSON.stringify(input.actualAnswers)!==JSON.stringify(sample.actualAnswers))throw Error('Input or budget drift');
  if(input.context.customDescription!==sample.context.customDescription||JSON.stringify(input.context.fileSnippets.map(x=>({path:x.path,content:x.content})))!==JSON.stringify(sample.context.files.map(x=>({path:x.path,content:x.content}))))throw Error('Material drift');
  const result=await dispatch(payload,mode,job);res.writeHead(result.status,{'content-type':'application/json'});res.end(JSON.stringify(result.body));
 }catch(e){res.writeHead(502,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:e.message}}));}});
 await new Promise(done=>relay.listen(0,'127.0.0.1',done));
 try{
  const input=resolve(runtime,job+'.input.json');await save(input,{cases:[sample]});const cp=(await readFile(resolve(runtime,'classpath.txt'),'utf8')).trim();
  const child=spawn('E:/Delelop/Java/jdk-21/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',cp,'PlanContextEvaluation',input,id,version,mode,String(relay.address().port),resolve(dir,'results',job+'.json')],{cwd:repo,stdio:['ignore','pipe','pipe']});
  const logs=[];child.stdout.on('data',x=>logs.push(x));child.stderr.on('data',x=>logs.push(x));const code=await new Promise((done,reject)=>{child.on('error',reject);child.on('exit',done);});
  await writeFile(resolve(runtime,job+'.log'),Buffer.concat(logs));console.log(JSON.stringify({job,exitCode:code,calls}));if(code)process.exitCode=code;
 }finally{relay.close();}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))await main();
