/** 只接收冻结合成工作台任务；每次出站前记账，先核对代码、参数和剩余预算。 */
import {createServer} from 'node:http';
import {readFile, writeFile, readdir, access} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const dir=dirname(fileURLToPath(import.meta.url)),repo=resolve(dir,'../../../..');
const read=async p=>JSON.parse(await readFile(p,'utf8'));
const protocol=await read(resolve(dir,'protocol.json'));
const samples=(await read(resolve(dir,'product-cases.json'))).cases;
assert.ok(process.env.MODEL_API_KEY);
const sha=x=>createHash('sha256').update(x).digest('hex');
const bodies=new Set();
let queue=Promise.resolve();
async function dispatch(payload){
  try { await access(resolve(dir,'closure.json')); throw Error('Stage closed'); } catch(e) { if(e.code!=='ENOENT')throw e; }
  for(const f of protocol.files)assert.equal(sha(await readFile(resolve(repo,f.path))),f.sha256,'Frozen drift');
  assert.equal(payload.model,protocol.model);assert.equal(payload.temperature,protocol.temperature);
  assert.equal(payload.max_tokens,protocol.max_tokens);assert.equal(payload.stream,false);
  assert.equal(payload.thinking?.type,'disabled');
  const text=payload.messages[1].content,input=JSON.parse(text.slice(text.indexOf('{')));
  const sample=samples.find(c=>c.rawPrompt===input.rawPrompt);assert.ok(sample,'Unapproved raw prompt');
  assert.equal(input.strategyVersion,'core-repair-v3');
  assert.equal(input.context.fileSnippets.length,0);assert.equal(input.context.customDescription,'');
  assert.equal(input.conversation.length,0);assert.equal(input.mandatoryRules.length,0);
  for(const a of input.actualAnswers)assert.ok((sample.allowedAnswers??[]).includes(a.answer),'Unapproved answer');
  if(input.revision){assert.equal(input.revision.feedback,sample.feedback);assert.ok(bodies.has(input.revision.previousPrompt.trim()),'Unknown prior body');assert.equal(input.revision.earlierFeedback.length,0);}
  const files=(await readdir(resolve(dir,'traces'))).filter(x=>/^\d{4}\.json$/.test(x)).sort();
  const traces=await Promise.all(files.map(x=>read(resolve(dir,'traces',x))));
  assert.ok(protocol.priorCalls+traces.length<protocol.totalMaximum && traces.length<protocol.stageMaximum,'Total limit');
  assert.ok(traces.filter(x=>x.phase==='product').length<protocol.phaseLimits.product,'Product limit');
  const index=traces.length+1,path=resolve(dir,'traces',String(index).padStart(4,'0')+'.json');
  const record={phase:'product',caseId:sample.id,revision:!!input.revision,request:payload,startedAt:new Date().toISOString()};
  await writeFile(path,JSON.stringify(record,null,2)+'\n',{flag:'wx'});
  try{
    const response=await fetch(protocol.endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.MODEL_API_KEY},body:JSON.stringify(payload),signal:AbortSignal.timeout(180000)});
    record.httpStatus=response.status;record.response=await response.json();
    for(const c of record.response.choices??[])if(c.message){delete c.message.reasoning_content;try{const value=JSON.parse(c.message.content);if(value.optimizedPrompt)bodies.add(value.optimizedPrompt.trim());}catch{}}
    record.finishedAt=new Date().toISOString();await writeFile(path,JSON.stringify(record,null,2)+'\n');
    console.log(JSON.stringify({call:protocol.priorCalls+index,caseId:sample.id,revision:!!input.revision,status:response.status}));
    return {status:response.status,body:record.response};
  }catch(e){record.errorType=e.name;await writeFile(path,JSON.stringify(record,null,2)+'\n');throw e;}
}
createServer(async(req,res)=>{
  try{
    assert.equal(req.url,'/chat/completions');assert.equal(req.method,'POST');
    const chunks=[];for await(const chunk of req)chunks.push(chunk);
    const payload=JSON.parse(Buffer.concat(chunks).toString('utf8'));
    const work=queue.then(()=>dispatch(payload));queue=work.catch(()=>{});
    const r=await work;res.writeHead(r.status,{'content-type':'application/json'});res.end(JSON.stringify(r.body));
  }catch{res.writeHead(502,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:'Frozen evaluation gate rejected request'}}));}
}).listen(9078,'127.0.0.1',()=>console.log('PRODUCT_RELAY_READY_9078'));
