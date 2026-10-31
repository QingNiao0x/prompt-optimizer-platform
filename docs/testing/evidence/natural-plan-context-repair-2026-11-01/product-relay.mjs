/** 真实工作台的计数转发，只允许冻结合成任务和本进程见过的优化稿进入外部模型。 */
import {createServer} from 'node:http';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
import {dir,json,dispatch,checkFrozen} from './run.mjs';
await checkFrozen();
const samples=(await json(resolve(dir,'product-cases.json'))).cases,bodies=new Set();
const counts=new Map();
createServer(async(req,res)=>{try{
 assert.equal(req.url,'/chat/completions');assert.equal(req.method,'POST');
 const chunks=[];for await(const chunk of req)chunks.push(chunk);
 const payload=JSON.parse(Buffer.concat(chunks).toString('utf8'));
 const text=payload.messages[1].content,input=JSON.parse(text.slice(text.indexOf('{')));
 const sample=samples.find(c=>c.rawPrompt===input.rawPrompt);assert.ok(sample,'Unapproved input');
 assert.equal(input.strategyVersion,'core-repair-v4');
 assert.equal(input.context.fileSnippets.length,0);assert.equal(input.context.customDescription,'');
 assert.equal(input.conversation.length,0);assert.equal(input.mandatoryRules.length,0);
 for(const a of input.actualAnswers)assert.ok((sample.allowedAnswers??[]).includes(a.answer));
 if(input.revision){assert.equal(input.revision.feedback,sample.feedback);assert.ok(bodies.has(input.revision.previousPrompt.trim()));assert.equal(input.revision.earlierFeedback.length,0);}
 const key=sample.id+(input.revision?'_revision':input.actualAnswers.length?'_answered':'_initial');
 const count=counts.get(key)??0;assert.ok(count<(key.endsWith('_initial')?8:4));counts.set(key,count+1);
 const result=await dispatch(payload,'product',key);
 for(const c of result.body.choices??[])try{const value=JSON.parse(c.message.content);if(value.optimizedPrompt)bodies.add(value.optimizedPrompt.trim());}catch{}
 res.writeHead(result.status,{'content-type':'application/json'});res.end(JSON.stringify(result.body));
}catch(e){console.log(JSON.stringify({gateFailure:e.name,message:e.message}));res.writeHead(502,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:'Frozen evaluation gate rejected request'}}));}
}).listen(9077,'127.0.0.1',()=>console.log('PRODUCT_RELAY_READY_9077'));
