/** 首次响应强制重放已保存失败，后续才真实调用，隔离修复反馈效果；不重试采样挑结果。 */
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {dir,repo,runtime,json,save,frozen,reserve} from './common.mjs';
const p=await frozen();if(!process.env.MODEL_API_KEY)throw Error('Credential unavailable');
const saved=await json(resolve(dir,'../natural-daily-v4-2026-11-01/diagnostic/call-1.json'));
const reservation=await reserve('controlled-repair','product',3,null);
let requests=0,actual=0;
const relay=createServer(async(req,res)=>{
  try{
    assert(req.method==='POST'&&req.url==='/chat/completions');
    const chunks=[];for await(const part of req)chunks.push(part);const payload=JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if(++requests===1){
      assert.deepEqual(payload,saved.request,'First request must be identical to the saved failed run');
      await save(resolve(dir,'diagnostic/offline-first-response.json'),{source:'../natural-daily-v4-2026-11-01/diagnostic/call-1.json',requestIdentical:true,actualModelCalls:0});
      res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(saved.response));return;
    }
    assert(actual<3);for(const key of ['model','temperature','max_tokens'])assert.equal(payload[key],p[key]);
    if(requests===2)assert(payload.messages[0].content.includes('actions[0].userEvidence[1].sourceId引用了助手生成内容'));
    const path=resolve(dir,'diagnostic',`live-${++actual}.json`),trace={request:payload,startedAt:new Date().toISOString()};await save(path,trace);
    try{
      const response=await fetch(p.endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.MODEL_API_KEY},body:JSON.stringify(payload),signal:AbortSignal.timeout(180000)});
      trace.httpStatus=response.status;trace.response=await response.json();for(const c of trace.response.choices??[])delete c.message.reasoning_content;
      await writeFile(path,JSON.stringify(trace,null,2)+'\n');res.writeHead(response.status,{'content-type':'application/json'});res.end(JSON.stringify(trace.response));
    }catch(error){trace.errorType=error.name;await writeFile(path,JSON.stringify(trace,null,2)+'\n');throw error;}
    console.log(JSON.stringify({actual,status:trace.httpStatus}));
  }catch(error){console.log(JSON.stringify({failure:error.message}));res.writeHead(502,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:'Controlled repair validation failed'}}));}
});
await new Promise(done=>relay.listen(0,'127.0.0.1',done));await mkdir(runtime,{recursive:true});
try{
  const cp=resolve(runtime,'classes')+';'+(await readFile(resolve(repo,'tmp/natural-plan-context-repair-2026-11-01/classpath.txt'),'utf8')).trim();
  const child=spawn('E:/Delelop/Java/jdk-21/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',cp,'ScopeRepairEvaluation',resolve(dir,'diagnostic/case.json'),'D_NOTICE_REVISION','core-repair-v4','ENHANCE',String(relay.address().port),resolve(dir,'diagnostic/result.json')],{cwd:repo,stdio:['ignore','pipe','pipe']});
  const logs=[];child.stdout.on('data',x=>logs.push(x));child.stderr.on('data',x=>logs.push(x));
  const code=await new Promise((done,reject)=>{child.on('error',reject);child.on('exit',done);});
  await writeFile(resolve(runtime,'controlled-repair.log'),Buffer.concat(logs));console.log(JSON.stringify({code,actual,offlineReplays:requests-actual}));
}finally{relay.close();await writeFile(reservation,JSON.stringify({...await json(reservation),actual,settledAt:new Date().toISOString()},null,2)+'\n');}
