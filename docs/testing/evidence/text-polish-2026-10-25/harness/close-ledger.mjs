import {readFile,writeFile,copyFile,mkdir,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
const out=resolve('docs/testing/evidence/text-polish-2026-10-25');
const ledgerPath=resolve(out,'ledger.json'),ledger=JSON.parse(await readFile(ledgerPath,'utf8'));
if(ledger.closed||ledger.actualCalls!==48||ledger.reserved!==0||ledger.entries.some(e=>e.status!=='COMPLETED'))throw new Error('Budget not settled');
const business=JSON.parse(await readFile(resolve(out,'business.json'),'utf8'));
const log=await readFile('tmp/text-polish-acceptance/backend.out.log','utf8');
const trace=business.response.body.requestId;
const attempts=log.split(/\r?\n/).filter(l=>/event=model.call.(completed|failed)/.test(l)&&l.includes('requestId='+trace+' ')&&l.includes('operation=text.polish '))
 .map(l=>Object.fromEntries([...l.matchAll(/([A-Za-z]+)=([^ ]+)/g)].map(m=>[m[1],m[2]])));
const live=ledger.entries.find(e=>e.id==='live-current');
if(attempts.length!==live.actualCalls)throw new Error('Continuation audit does not match counted calls');
Object.assign(live,{requestId:business.request.requestId,httpRequestId:trace,attempts});
const sum=ledger.entries.reduce((n,e)=>n+e.actualCalls,0);if(sum!==ledger.actualCalls)throw new Error('Cumulative mismatch');
const percentile=(xs,p)=>{xs=xs.filter(Number.isFinite).sort((a,b)=>a-b);return xs.length?xs[Math.ceil(xs.length*p)-1]:null;};
const groups=new Map();
for(const entry of ledger.entries){
 const review=entry.phase==='blindReview';
 const data=review?JSON.parse(await readFile(resolve(out,entry.id+'.json'),'utf8')):null;
 const attempt=entry.attempts?.[0];const model=attempt?.model??entry.model;
 const group=groups.get(model)??{model,actualCalls:0,generation:0,review:0,inputTokens:0,outputTokens:0,totalTokens:0,usageKnown:0,generationDurationsMs:[],reviewDurationsMs:[]};
 group.actualCalls+=entry.actualCalls;
 if(review){group.review++;group.reviewDurationsMs.push(entry.elapsedMs);}else{group.generation+=entry.actualCalls;group.generationDurationsMs.push(...entry.attempts.map(a=>Number(a.durationMs)));}
 const usages=review?[data.response.body.usage]:entry.attempts.map(a=>({prompt_tokens:Number(a.inputTokens),completion_tokens:Number(a.outputTokens),total_tokens:Number(a.totalTokens)}));
 for(const usage of usages)if([usage?.prompt_tokens,usage?.completion_tokens,usage?.total_tokens].every(Number.isFinite)){group.usageKnown++;group.inputTokens+=usage.prompt_tokens;group.outputTokens+=usage.completion_tokens;group.totalTokens+=usage.total_tokens;}
 groups.set(model,group);
}
const stats=[...groups.values()].map(g=>({...g,generationP50Ms:percentile(g.generationDurationsMs,.5),generationP95Ms:percentile(g.generationDurationsMs,.95),reviewP50Ms:percentile(g.reviewDurationsMs,.5),reviewP95Ms:percentile(g.reviewDurationsMs,.95)}));
Object.assign(ledger,{closed:true,closedAt:new Date().toISOString(),releasedUnusedCalls:50-ledger.actualCalls,closureReason:'工程检查和限定真实评测完成；保留v2承诺遗漏及v3目的省略限制，不继续同义候选或采样，不宣称全面质量通过。后续真实批次须重新冻结并沿用用户明确授权范围。'});
await writeFile(ledgerPath,JSON.stringify(ledger,null,2));
await writeFile(resolve(out,'statistics.json'),JSON.stringify({actualCalls:sum,generationCalls:42,paidReviewCalls:6,failedCalls:0,protocolRepairs:0,qualityRetries:0,currencyCost:null,currencyCostReason:'未核对供应商账单和该路由实际结算价格，不推算单篇费用',groups:stats},null,2),{flag:'wx'});
await mkdir(resolve(out,'harness'),{recursive:true});
for(const name of ['evaluate-v2.mjs','evaluate.mjs','freeze-v3.mjs','freeze-regression.mjs','review.mjs','review.ps1','business.mjs','clipboard.mjs','browser.mjs','browser.ps1','start.ps1','close-ledger.mjs'])await copyFile(resolve('tmp/text-polish-acceptance',name),resolve(out,'harness',name));
console.log(JSON.stringify({closed:true,actualCalls:sum,releasedUnusedCalls:ledger.releasedUnusedCalls,statistics:stats.map(({generationDurationsMs,reviewDurationsMs,...g})=>g)}));
