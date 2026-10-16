// Only this batch's synthetic evidence is public; credentials and complete private histories stay in tmp.
import {readFile,writeFile,readdir,mkdir,copyFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
const root=resolve('tmp/me00a-candidate23-r2-live-20261016/real'),runtime=resolve('tmp/me00a-20261012');
const pub=resolve('docs/testing/evidence/me00a-candidate23-r2-live-2026-10-16'),dest=resolve(pub,'real');
const hash=b=>createHash('sha256').update(b).digest('hex');
const load=async p=>JSON.parse(await readFile(p,'utf8'));
const save=async(p,v)=>{await mkdir(resolve(p,'..'),{recursive:true});await writeFile(p,typeof v==='string'?v:JSON.stringify(v,null,2)+'\n',{flag:'wx'});};
const copy=async(from,to)=>{const bytes=await readFile(from);const text=bytes.toString('utf8');if(/Bearer\s+[A-Za-z0-9._-]{12,}|"Authorization"\s*:|sk-[A-Za-z0-9]{24,}/.test(text))throw new Error('Protected credential pattern in archive');await mkdir(resolve(to,'..'),{recursive:true});try{const existing=await readFile(to);if(!existing.equals(bytes))throw new Error('Archive collision');}catch(error){if(error.code!=='ENOENT')throw error;await copyFile(from,to,1);}};
const names=(await readdir(resolve(runtime,'traces'))).filter(n=>/^\d+\.json$/.test(n));
const traces=await Promise.all(names.map(async n=>({number:Number(n.slice(0,-5)),value:await load(resolve(runtime,'traces',n))})));
const current=traces.filter(x=>x.value.version==='candidate23-r2').sort((a,b)=>a.number-b.number),table=[];
for(const{number,value:t}of current){
  if(t.response?.choices?.some(c=>c.message?.reasoning_content))throw new Error('Unexpected hidden reasoning in capture');
  await copy(resolve(runtime,'traces',`${String(number).padStart(3,'0')}.json`),resolve(dest,'traces',`${number}.json`));
  const usage=t.response?.usage;table.push({trace:number,purpose:t.purpose,label:t.label??null,requestId:t.requestId??null,model:t.request?.model,responseModel:t.response?.model,httpStatus:t.httpStatus,elapsedMs:t.elapsedMs,inputTokens:usage?.prompt_tokens??null,outputTokens:usage?.completion_tokens??null,totalTokens:usage?.total_tokens??null,finishReason:t.response?.choices?.[0]?.finish_reason??null});
}
for(const id of ['Q05-plan','Q05-plan-pro']){
  let names;try{names=await readdir(resolve(root,id));}catch(error){if(error.code==='ENOENT')continue;throw error;}
  const files=names.filter(n=>/\.md$|^input\.json$|^platform\.json$|^checks\.json$|^clipboard-lock\.json$|^native-copy\.png$|^actual-answers\.json$|^confirmation\.json$|^plan-(?:input|platform)\.json$/.test(n));
  for(const n of files)await copy(resolve(root,id,n),resolve(dest,id,n));
  try{
    const privateHistory=await load(resolve(root,id,'history.private.json'));
    const h=privateHistory.body.data;
    await save(resolve(dest,id,'history-projection.json'),{sourceKind:'ACTUAL_SAME_ACCOUNT_HISTORY',privateOriginalRetained:true,rawPrompt:h.rawPrompt,optimizedPrompt:h.optimizedPrompt,sections:h.sections,appliedConstraints:h.appliedConstraints??h.metadata?.appliedConstraints,metadata:h.metadata,historySourceSha256:hash(await readFile(resolve(root,id,'history.private.json')))});
  }catch(error){if(error.code!=='ENOENT')throw error;}
}
for(const n of ['native-copy-checks.json'])await copy(resolve(root,n),resolve(dest,n.replace('.private','')));
const require=createRequire(resolve('apps/web/package.json'));
const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
try{
  const page=browser.contexts()[0].pages().find(p=>p.url().startsWith('http://127.0.0.1:9001/'));
  const data=await page.evaluate(async()=>{const me=await fetch('/api/v1/auth/me'),history=await fetch('/api/v1/optimization-history?current=1&size=1');return{meStatus:me.status,me:(await me.json()).data,historyStatus:history.status,total:(await history.json()).data.total};});
  const actor=await load('tmp/local-acceptance/account.json');
  if(data.meStatus!==200||data.historyStatus!==200||data.me.userId!==actor.userId||data.me.tenantId!==actor.tenantId)throw new Error('Canonical identity mismatch at completion');
  const before=(await load(resolve(root,'preflight.json'))).historyBefore;
  await save(resolve(dest,'identity-history-final.json'),{sameAccount:true,newAccounts:0,newTenants:0,newWorkspaces:0,historyBefore:before,historyAfter:data.total,newHistories:data.total-before,userHash:hash(actor.userId),historyApiOwnershipFields:'Not exposed; not claimed as a separate tenant-field comparison'});
}finally{await browser.close();}
const purposes=Object.fromEntries(['PLATFORM_GENERATION','PLANNING','EXECUTION','REVIEW'].map(p=>[p,current.filter(x=>x.value.purpose===p).length]));
const budget={actual:{total:traces.length,baseline:traces.filter(t=>t.value.phase==='baseline').length,candidateValidation:traces.filter(t=>t.value.phase==='candidateValidation').length},formalLimits:{total:338,baseline:80,candidateValidation:258},batch:{version:'candidate23-r2',maximum:8,actual:current.length,purposes,transportOrHttpFailures:current.filter(x=>x.value.httpStatus!==200).length,qualityRetries:0},knownTokens:table.reduce((s,r)=>s+(r.totalTokens??0),0),table,priceAndCurrencyCost:'UNKNOWN',source:'Frozen candidate23-r2; actual upstream response model is an alias, catalog display version is not proof of provider build'};
await save(resolve(pub,'budget-live.json'),budget);
console.log(JSON.stringify({actualTotal:budget.actual.total,newCalls:current.length,purposes,knownTokens:budget.knownTokens,archivesSafe:true}));
