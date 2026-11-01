/** 来源引用修复的定向验证：只用已授权合成DATE材料；准备失败可离线注入，实际调用逐次落账。 */
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir,readdir,access} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
const dir=dirname(fileURLToPath(import.meta.url)),repo=resolve(dir,'../../../..');
const runtime=resolve(repo,'tmp/natural-material-evidence-repair-2026-11-01');
const json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const save=(p,v)=>writeFile(p,JSON.stringify(v,null,2)+'\n',{flag:'wx'});
const exists=async p=>{try{await access(p);return true;}catch{return false;}};
const hash=async p=>createHash('sha256').update(await readFile(resolve(repo,p))).digest('hex');
const mode=process.argv[2];
if(mode==='freeze'){
  assert(!await exists(resolve(dir,'protocol.json')),'Already frozen');
  for(const p of ['traces','results','reservations'])await mkdir(resolve(dir,p),{recursive:true});
  await mkdir(resolve(runtime,'classes'),{recursive:true});
  const prior=await json(resolve(dir,'../natural-daily-v4-2026-11-01/cases.json'));
  const item=structuredClone(prior.cases.find(x=>x.id==='DATE'));
  item.actualAnswers=[];
  await save(resolve(dir,'cases.json'),{source:'Previously approved synthetic DATE; all 37 original synthetic files, no private business source.',cases:[item]});
  const paths=['evaluate.mjs','cases.json'].map(x=>'docs/testing/evidence/natural-material-evidence-repair-2026-11-01/'+x);
  paths.push('docs/testing/evidence/natural-plan-context-repair-2026-11-01/PlanContextEvaluation.java');
  for(const p of ['provider/domain/NaturalTaskScopeSources.java','provider/domain/NaturalPromptPlanContextInstructions.java',
    'provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java','provider/infrastructure/openai/NaturalPromptRepairContext.java',
    'provider/domain/ProviderResponseValidationException.java','common/logging/ModelCallObservation.java'])paths.push('services/api/src/main/java/com/promptoptimizer/'+p);
  const files=[];for(const path of paths)files.push({path,sha256:await hash(path)});
  await save(resolve(dir,'protocol.json'),{status:'FROZEN',at:new Date().toISOString(),taskMaximum:200,priorRecordedActual:59,
    observedManualDiagnosticCalls:6,stageMaximum:12,perJobMaximum:4,qualityRetries:0,
    endpoint:'https://api.deepseek.com/chat/completions',model:'deepseek-flash',temperature:0.2,max_tokens:8192,
    jobs:['controlled-enhance','fresh-plan'],files,
    hypothesis:'The existing one repair can correct multiple material source references when it receives exact fields and the invalid candidate as untrusted data.',
    scope:'Provider direct regression, not browser/history acceptance or overall writing-quality evaluation. Manual retries of private input are metadata-only and never replayed here.'});
  console.log('Frozen: 12 calls maximum; existing synthetic material only.');process.exit(0);
}
assert(!await exists(resolve(dir,'closure.json')),'Stage closed');
const p=await json(resolve(dir,'protocol.json'));
for(const f of p.files)assert.equal(await hash(f.path),f.sha256,'Frozen content changed: '+f.path);
assert(p.jobs.includes(mode),'Unknown job');
assert(process.env.MODEL_API_KEY,'Model credential unavailable');
const reservations=[];for(const name of await readdir(resolve(dir,'reservations')))reservations.push(await json(resolve(dir,'reservations',name)));
assert(reservations.reduce((n,x)=>n+(x.actual??x.reserved),0)+p.perJobMaximum<=p.stageMaximum,'Budget exhausted');
const reservation=resolve(dir,'reservations',mode+'.json');
await save(reservation,{mode,reserved:p.perJobMaximum,at:new Date().toISOString()});
let actual=0,received=0;
const relay=createServer(async(req,res)=>{
  try{
    assert(req.method==='POST'&&req.url==='/chat/completions');
    const chunks=[];for await(const part of req)chunks.push(part);
    const payload=JSON.parse(Buffer.concat(chunks).toString('utf8'));
    assert.equal(payload.model,p.model);assert.equal(payload.temperature,p.temperature);assert.equal(payload.max_tokens,p.max_tokens);
    const user=payload.messages[1].content,data=JSON.parse(user.slice(user.indexOf('{')));
    assert.equal(data.context.fileSnippets.length,37);
    if(++received===1&&mode==='controlled-enhance'){
      const evidence=[{sourceId:'rawPrompt',quote:'请修改实现'}];
      const invalid={actions:[{text:'修改新增和导入的调查时间校验',timing:'CURRENT',condition:'',userEvidence:evidence,
        detailEvidence:[{sourceId:'context.fileSnippets.0',quote:'两个入口统一验证日期并返回错误。'},
          {sourceId:'context.fileSnippets.1',quote:'当前方法已校验日历，但未比较上限。'}]}],
        response:{basis:'EXPLICIT',description:'实际修改文件的JSON',userEvidence:[{sourceId:'rawPrompt',quote:'返回一个JSON对象'}],detailEvidence:[]}};
      const response={choices:[{finish_reason:'stop',message:{content:JSON.stringify(invalid)}}]};
      await save(resolve(dir,'traces',mode+'-offline.json'),{actualModelCalls:0,syntheticFault:true,request:payload,response});
      res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(response));return;
    }
    if(mode==='controlled-enhance'&&received===2){
      assert(payload.messages[0].content.includes('actions[0].detailEvidence[0].quote'));
      assert(payload.messages[0].content.includes('actions[0].detailEvidence[1].quote'));
      assert.equal(data.protocolRepair.candidateTrust,'UNTRUSTED_MODEL_OUTPUT');
    }
    assert(actual<p.perJobMaximum,'Per-job maximum exceeded');
    const path=resolve(dir,'traces',mode+'-'+String(++actual).padStart(2,'0')+'.json');
    const trace={startedAt:new Date().toISOString(),request:payload};await save(path,trace);
    try{
      const response=await fetch(p.endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.MODEL_API_KEY},
        body:JSON.stringify(payload),signal:AbortSignal.timeout(180000)});
      trace.httpStatus=response.status;trace.response=await response.json();
      for(const choice of trace.response.choices??[])delete choice.message.reasoning_content;
      await writeFile(path,JSON.stringify(trace,null,2)+'\n');
      res.writeHead(response.status,{'content-type':'application/json'});res.end(JSON.stringify(trace.response));
      console.log(JSON.stringify({mode,actual,status:response.status}));
    }catch(error){trace.failureType=error.name;await writeFile(path,JSON.stringify(trace,null,2)+'\n');throw error;}
  }catch(error){console.log(JSON.stringify({mode,toolFailureType:error.name}));res.writeHead(502,{'content-type':'application/json'});res.end('{"error":{"message":"Evaluation relay failed"}}');}
});
await new Promise(done=>relay.listen(0,'127.0.0.1',done));
try{
  const cp=resolve(runtime,'classes')+';'+(await readFile(resolve(repo,'tmp/natural-plan-context-repair-2026-11-01/classpath.txt'),'utf8')).trim();
  const child=spawn('E:/Delelop/Java/jdk-21/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',cp,'PlanContextEvaluation',resolve(dir,'cases.json'),'DATE',
    'core-repair-v4',mode==='fresh-plan'?'PLAN':'ENHANCE',String(relay.address().port),resolve(dir,'results',mode+'.json')],{cwd:repo,stdio:['ignore','pipe','pipe']});
  const logs=[];child.stdout.on('data',b=>logs.push(b));child.stderr.on('data',b=>logs.push(b));
  const code=await new Promise((done,reject)=>{child.on('error',reject);child.on('exit',done);});
  await writeFile(resolve(runtime,mode+'.log'),Buffer.concat(logs));
  console.log(JSON.stringify({mode,code,actual,offlineReplays:received-actual}));
}finally{
  relay.close();await writeFile(reservation,JSON.stringify({...await json(reservation),actual,settledAt:new Date().toISOString()},null,2)+'\n');
}
