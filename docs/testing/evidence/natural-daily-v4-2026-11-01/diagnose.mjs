/** 一次有响应原件的故障诊断；不把第二次抽样成功当作原失败已修复，不重开闭批。 */
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {dir,repo,runtime,json,save,reserve,frozen} from './common.mjs';
const sha=x=>createHash('sha256').update(x).digest('hex');
const protocol=await frozen(),folder=resolve(dir,'diagnostic');
await mkdir(folder,{recursive:true});
if(process.argv[2]==='freeze'){
  const request=await json(resolve(dir,'product/D_NOTICE-generation-request.json'));
  const previous=await json(resolve(dir,'product/D_NOTICE-first.json'));
  const feedback=await json(resolve(dir,'product/D_NOTICE-revision-request.json'));
  await save(resolve(folder,'case.json'),{cases:[{id:'D_NOTICE_REVISION',rawPrompt:request.rawPrompt,
    context:request.context,actualAnswers:[],conversation:[],revision:{previousPrompt:previous.optimizedPrompt,earlierFeedback:[],feedback:feedback.feedback}}]});
  const files=[];
  for(const name of ['diagnose.mjs','diagnose.ps1','DailyDiagnosticEvaluation.java','diagnostic/case.json'])files.push({path:name,sha256:sha(await readFile(resolve(dir,name)))});
  await save(resolve(folder,'protocol.json'),{purpose:'Capture one provider-direct replay of observed daily revision preparation failure; original uncaptured responses remain unknown',maximum:4,phase:'product',files});
  console.log('Diagnostic frozen; no model calls');process.exit(0);
}
const spec=await json(resolve(folder,'protocol.json'));
for(const f of spec.files)if(sha(await readFile(resolve(dir,f.path)))!==f.sha256)throw Error('Diagnostic drift');
if(!process.env.MODEL_API_KEY)throw Error('Credential unavailable');
const sample=(await json(resolve(folder,'case.json'))).cases[0];
const reservation=await reserve('D_NOTICE-diagnostic','product',spec.maximum,null);
let calls=0,busy=false;
const relay=createServer(async(req,res)=>{
  try{
    if(busy||req.method!=='POST'||req.url!=='/chat/completions')throw Error('Invalid relay request');
    busy=true;const chunks=[];for await(const part of req)chunks.push(part);
    const request=JSON.parse(Buffer.concat(chunks).toString('utf8'));
    for(const k of ['model','temperature','max_tokens'])if(request[k]!==protocol[k])throw Error('Model parameter drift');
    const text=request.messages[1].content,input=JSON.parse(text.slice(text.indexOf('{')));
    if(input.rawPrompt!==sample.rawPrompt||input.strategyVersion!=='core-repair-v4'||JSON.stringify(input.revision)!==JSON.stringify(sample.revision)
      ||input.context.fileSnippets.length||input.actualAnswers.length||calls>=spec.maximum)throw Error('Input or budget drift');
    const path=resolve(folder,`call-${++calls}.json`),trace={request,startedAt:new Date().toISOString()};await save(path,trace);
    try{
      const response=await fetch(protocol.endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.MODEL_API_KEY},body:JSON.stringify(request),signal:AbortSignal.timeout(180000)});
      trace.httpStatus=response.status;const raw=await response.text();
      try{trace.response=JSON.parse(raw);for(const c of trace.response.choices??[])delete c.message.reasoning_content;}catch{trace.nonJsonSha256=sha(raw);}
      await writeFile(path,JSON.stringify(trace,null,2)+'\n');
      res.writeHead(response.status,{'content-type':'application/json'});res.end(JSON.stringify(trace.response??{error:{message:'Non JSON'}}));
    }catch(error){trace.errorType=error.name;await writeFile(path,JSON.stringify(trace,null,2)+'\n');throw error;}
    console.log(JSON.stringify({event:'DIAGNOSTIC_CALL',call:calls,status:trace.httpStatus}));
  }catch(error){res.writeHead(502,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:'Diagnostic transport/input failure'}}));console.log(JSON.stringify({error:error.message}));}
  finally{busy=false;}
});
await new Promise(done=>relay.listen(0,'127.0.0.1',done));
try{
  const cp=resolve(runtime,'diagnostic-classes')+';'+(await readFile(resolve(repo,'tmp/natural-plan-context-repair-2026-11-01/classpath.txt'),'utf8')).trim();
  const child=spawn('E:/Delelop/Java/jdk-21/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',cp,'DailyDiagnosticEvaluation',resolve(folder,'case.json'),sample.id,'core-repair-v4','ENHANCE',String(relay.address().port),resolve(folder,'result.json')],{cwd:repo,stdio:['ignore','pipe','pipe']});
  const logs=[];child.stdout.on('data',x=>logs.push(x));child.stderr.on('data',x=>logs.push(x));
  const code=await new Promise((done,reject)=>{child.on('error',reject);child.on('exit',done);});
  await writeFile(resolve(runtime,'diagnostic.log'),Buffer.concat(logs));
  console.log(JSON.stringify({event:'DIAGNOSTIC_END',code,calls}));
}finally{
  relay.close();await writeFile(reservation,JSON.stringify({...await json(reservation),actual:calls,settledAt:new Date().toISOString()},null,2)+'\n');
}
