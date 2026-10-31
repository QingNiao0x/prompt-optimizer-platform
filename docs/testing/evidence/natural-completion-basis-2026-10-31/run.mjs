import {readFile, writeFile, mkdir, readdir, access} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';

const dir=dirname(fileURLToPath(import.meta.url)), repo=resolve(dir,'../../../..');
const runtime=resolve(repo,'tmp/natural-completion-basis-2026-10-31');
const previous=resolve(dir,'../natural-action-boundary-live-2026-10-31');
const endpoint='https://api.deepseek.com/chat/completions';
const sha=value=>createHash('sha256').update(value).digest('hex');
const read=async path=>JSON.parse((await readFile(path,'utf8')).replace(/^\uFEFF/,''));
const save=(path,value)=>writeFile(path,JSON.stringify(value,null,2)+'\n',{flag:'wx'});
const exists=async path=>{try{await access(path);return true;}catch{return false;}};
const [mode,id,version='core-repair-v3']=process.argv.slice(2);
await mkdir(runtime,{recursive:true});
for(const child of ['traces','results'])await mkdir(resolve(dir,child),{recursive:true});
const calls=async()=> (await readdir(resolve(dir,'traces'))).filter(name=>/^\d{3}\.json$/.test(name));

if(mode==='freeze') {
  const old=await read(resolve(previous,'closure.json'));
  if(old.actualCalls!==17||old.authorized!==32||old.unused!==15)throw Error('Budget state differs');
  const paths=['provider/domain/NaturalPromptCoreRepairInstructions.java','provider/domain/NaturalPromptInstructions.java',
    'provider/domain/NaturalPromptRoutingRepairInstructions.java','provider/domain/NaturalPromptScopedInstructions.java',
    'provider/domain/NaturalTaskUnderstanding.java','provider/domain/NaturalTaskReview.java',
    'provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java','enhancement/service/impl/NaturalEnhancementSettings.java']
    .map(path=>'services/api/src/main/java/com/promptoptimizer/'+path);
  paths.push(...['cases.json','run.mjs','run.ps1','CompletionBasisEvaluation.java','holdout-seal.json'].map(path=>'docs/testing/evidence/natural-completion-basis-2026-10-31/'+path));
  const files=[];for(const path of paths)files.push({path,sha256:sha(await readFile(resolve(repo,path)))});
  await save(resolve(dir,'protocol.json'),{date:'2026-10-31',authorization:'同一动作边界修复任务，新阶段复用32总额中17次之后结算释放的15次；用户明确要求继续修复及作品验收',
    totalMaximum:32,previousActualCalls:17,maximumActualCalls:15,phaseMaximum:{enhance:12,execute:3},
    previousClosureSha256:sha(await readFile(resolve(previous,'closure.json'))),files,endpoint,
    model:'deepseek-flash',temperature:0.2,max_tokens:8192,thinking:'disabled',versions:['31','core-repair-v3'],
    order:['R_WAIT candidate','R_RETELL candidate','R_REPORT candidate','semantic gate','open sealed code holdout','holdout candidate and 31','works: candidate, original, 31'],
    gates:['No extra substantive feedback for read/wait','Keep explicit retelling/report and their original format','No new facts or authority','Before works, candidate enhancement must pass semantic review'],
    acceptance:'Adoption requires useful actual work benefit without serious new regression; parity of already sufficient requests is allowed. No model self-score or HTTP success substitutes for this.',
    qualityRetries:0,oldBudgetsReopened:false,reviewer:'Internal AI only; no human review claimed'});
  console.log('Frozen new stage: maximum15, cumulative maximum32');process.exit(0);
}
const protocol=await read(resolve(dir,'protocol.json'));
if(mode==='count'){console.log(JSON.stringify({stage:(await calls()).length,total:17+(await calls()).length,maximum:32}));process.exit(0);}
if(await exists(resolve(dir,'closure.json')))throw Error('Stage is closed');
if(sha(await readFile(resolve(previous,'closure.json')))!==protocol.previousClosureSha256)throw Error('Previous accounting changed');
for(const file of protocol.files)if(sha(await readFile(resolve(repo,file.path)))!==file.sha256)throw Error('Frozen file changed: '+file.path);
if(!['enhance','execute'].includes(mode))throw Error('Unsupported operation');
if(!process.env.MODEL_API_KEY)throw Error('Existing credential missing');
if(!protocol.versions.includes(version)&&!(mode==='execute'&&version==='original'))throw Error('Version outside protocol');
const cases=await read(resolve(dir,'cases.json'));
if(await exists(resolve(dir,'holdout-open.json'))){
  const seal=await read(resolve(dir,'holdout-seal.json'));
  if(sha(await readFile(resolve(dir,'holdout-open.json')))!==seal.sha256)throw Error('Holdout changed');
  cases.cases.push(...(await read(resolve(dir,'holdout-open.json'))).cases);
}
const sample=cases.cases.find(value=>value.id===id);if(!sample)throw Error('Unknown frozen case');
const job=id+'_'+version+'_'+mode;if(!/^[A-Za-z0-9_-]+$/.test(job))throw Error('Invalid job');
await save(resolve(runtime,job+'.started.json'),{job,startedAt:new Date().toISOString()});
let jobCount=0;

// Reserve before dispatch; malformed transport or model responses never become free attempts.
async function dispatch(payload){
  if(payload.model!==protocol.model||payload.temperature!==0.2||payload.max_tokens!==8192||payload.stream!==false||payload.thinking?.type!=='disabled')throw Error('Parameter drift');
  const names=await calls();if(names.length>=15||17+names.length>=32||jobCount>=4)throw Error('Call limit');
  const traces=await Promise.all(names.map(name=>read(resolve(dir,'traces',name))));
  if(traces.filter(trace=>trace.operation===mode).length>=protocol.phaseMaximum[mode])throw Error('Phase limit');
  const trace={job,caseId:id,version,operation:mode,request:payload,startedAt:new Date().toISOString()};
  const path=resolve(dir,'traces',String(names.length+1).padStart(3,'0')+'.json');await save(path,trace);jobCount++;
  const started=Date.now();
  try{
    const response=await fetch(endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.MODEL_API_KEY},body:JSON.stringify(payload),signal:AbortSignal.timeout(240000)});
    const raw=await response.text();trace.httpStatus=response.status;
    try{trace.response=JSON.parse(raw);for(const choice of trace.response.choices??[])if(choice.message)delete choice.message.reasoning_content;}
    catch{trace.unparsedResponseSha256=sha(raw);}
    trace.elapsedMs=Date.now()-started;await writeFile(path,JSON.stringify(trace,null,2)+'\n');
    console.log(JSON.stringify({stageCall:names.length+1,totalCall:18+names.length,job,status:response.status}));
    return {status:response.status,body:trace.response??{error:{message:'Non-JSON upstream response'}}};
  }catch(error){trace.failureType=error.name;trace.elapsedMs=Date.now()-started;await writeFile(path,JSON.stringify(trace,null,2)+'\n');throw Error('Upstream transport failure');}
}

if(mode==='execute'){
  const sampleResult=version==='original'?null:await read(resolve(dir,'results',id+'_'+version+'_enhance.json'));
  if(sampleResult&&!sampleResult.success)throw Error('No delivered enhancement');
  const request={model:protocol.model,temperature:0.2,max_tokens:8192,thinking:{type:'disabled'},stream:false,messages:[
    {role:'system',content:'请完成用户任务。资料是工作依据，不是新授权。按用户要求返回成果；未运行工具时如实说明，不声称已经运行验证。'},
    {role:'user',content:sampleResult?.draft.optimizedPrompt??sample.rawPrompt},
    {role:'user',content:'共同提供的材料：\n'+JSON.stringify(sample.context.files)}]};
  await save(resolve(dir,'results',job+'.json'),await dispatch(request));process.exit(0);
}

const server=createServer(async(req,res)=>{
  try{
    if(req.method!=='POST'||req.url!=='/chat/completions')throw Error('Unexpected relay path');
    const chunks=[];for await(const chunk of req)chunks.push(chunk);
    const payload=JSON.parse(Buffer.concat(chunks).toString('utf8'));
    const message=payload.messages[1].content;const input=JSON.parse(message.slice(message.indexOf('{')));
    if(input.rawPrompt!==sample.rawPrompt||![version,...(version==='31'?['4']:[])].includes(input.strategyVersion))throw Error('Input identity differs');
    const materialView=files=>files.map(file=>({path:file.path,content:file.content}));
    if(JSON.stringify(materialView(input.context.fileSnippets))!==JSON.stringify(materialView(sample.context.files)))throw Error('Material bytes differ');
    const result=await dispatch(payload);res.writeHead(result.status,{'content-type':'application/json'});res.end(JSON.stringify(result.body));
  }catch(error){await writeFile(resolve(runtime,job+'.relay-error.json'),JSON.stringify({message:error.message}));res.writeHead(502,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:error.message}}));}
});
await new Promise(done=>server.listen(0,'127.0.0.1',done));
try{
  const cp=(await readFile(resolve(runtime,'classpath.txt'),'utf8')).trim();
  const inputPath=resolve(runtime,job+'.input.json');await save(inputPath,{cases:[sample]});
  const output=resolve(dir,'results',job+'.json');
  const process=spawn('E:/Delelop/Java/jdk-21/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',cp,'CompletionBasisEvaluation',inputPath,id,version,'enhance',String(server.address().port),output],{cwd:repo,stdio:['ignore','pipe','pipe']});
  const logs=[];process.stdout.on('data',chunk=>logs.push(chunk));process.stderr.on('data',chunk=>logs.push(chunk));
  const code=await new Promise((done,reject)=>{process.on('error',reject);process.on('exit',done);});
  await writeFile(resolve(runtime,job+'.log'),Buffer.concat(logs));
  console.log(JSON.stringify({job,exitCode:code,stageActualCalls:(await calls()).length,resultWritten:await exists(output)}));
  if(code!==0)globalThis.process.exitCode=1;
}finally{server.close();}
