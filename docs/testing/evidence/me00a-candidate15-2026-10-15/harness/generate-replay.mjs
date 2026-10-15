import{readFile,writeFile,mkdir,cp}from'node:fs/promises';
import{resolve}from'node:path';import{createHash}from'node:crypto';import{createRequire}from'node:module';
const require=createRequire(resolve('apps/web/package.json'));
const root=resolve('tmp/me00a-20261012'),output=resolve(root,'candidate15-real'),publicRoot=resolve('docs/testing/evidence/me00a-candidate15-2026-10-15');
const load=async path=>JSON.parse(await readFile(path,'utf8')),hash=b=>createHash('sha256').update(b).digest('hex');
const save=async(path,value)=>{await mkdir(resolve(path,'..'),{recursive:true});await writeFile(path,typeof value==='string'?value:JSON.stringify(value,null,2),{flag:'wx'});};
const protocol=await load(resolve(root,'protocol.json')),batch=protocol.activeBatch,manifest=await load(resolve(publicRoot,'candidate15-source.json'));
if(batch.version!=='candidate15'||batch.status!=='OPEN'||batch.maximumGenerationCalls!==0||batch.maximumNewCalls!==4)throw new Error('Replay-only approved scope required');
for(const f of [...manifest.sourceFiles,...manifest.classFiles,...manifest.policyFiles])if(hash(await readFile(f.path))!==f.sha256)throw new Error('Source freeze drift');
const fixture=async(fileKey,hashKey)=>{const bytes=await readFile(resolve(root,batch[fileKey]));if(hash(bytes)!==batch[hashKey])throw new Error('Fixture drift');return JSON.parse(bytes);};
const original=await fixture('planInputFile','planInputSha256'),answers=(await fixture('answersFile','answersSha256')).answers;
const account=await load('tmp/local-acceptance/account.json');
const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
try{
 const page=browser.contexts()[0].pages().find(p=>p.url().startsWith('http://127.0.0.1:9001/'));if(!page)throw new Error('Same-account browser missing');
 const api=(path,body)=>page.evaluate(async({path,body})=>{const token=document.cookie.split('; ').find(i=>i.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');const r=await fetch(path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{'X-XSRF-TOKEN':decodeURIComponent(token)}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});return{status:r.status,body:await r.json()};},{path,body});
 const me=await api('/api/v1/auth/me');if(me.status!==200||me.body.data.userId!==account.userId||me.body.data.tenantId!==account.tenantId)throw new Error('Canonical identity mismatch');
 const restored=await api('/api/v1/local-evaluation/replay-plan',{});if(restored.status!==200||restored.body.questionCount!==4)throw new Error('Original question registration failed');
 const input={...original,planConfirmation:{planId:restored.body.planId,planningContext:restored.body.planningContext,answers}};
 await save(resolve(output,'plan/input.json'),input);const start=Date.now();
 const result=await api('/api/v1/optimizations',input);await save(resolve(output,'plan/platform.json'),result);
 if(result.status!==200)throw new Error('Captured response replay rejected; no retry');
 const copied=result.body.data.optimizedPrompt;
 if(!copied.includes('拟议评分尺度')||!copied.includes('公平对照组的确认信息获取流程')||copied.includes('- 已委托提出拟议设计，实际采用参数仍未确认：'))throw new Error('Execution obligation not in actual copy');
 await save(resolve(output,'plan/copied.md'),copied);await save(resolve(output,'plan/execution-input.md'),copied);
 const list=await api('/api/v1/optimization-history?current=1&size=20');let history;
 for(const row of list.body.data?.records??[]){if(Date.parse(row.createdAt)<start-1000)continue;const detail=await api('/api/v1/optimization-history/'+row.id);if(detail.status===200&&detail.body.data.rawPrompt===input.rawPrompt&&detail.body.data.optimizedPrompt===copied){history=detail;break;}}
 if(!history)throw new Error('Normal API history differs from current copied body');await save(resolve(output,'plan/history.json'),history);
 const receipt=await load('tmp/me00a-candidate15-20261015/captured-response-replay.json');if(receipt.newEnhancementModelRequests!==0||receipt.sourceAttempt!==240)throw new Error('Replay provenance mismatch');
 await save(resolve(output,'plan/checks.json'),{mode:'CAPTURED_RESPONSE_REPLAY_THROUGH_NORMAL_AUTHENTICATED_API',sourceAttempt:240,newEnhancementModelRequests:0,newPlanningModelRequests:0,noScriptAnswerRepair:true,copyEqualsRealHistory:true,actualAnswers:4,newAccountsCreated:0});
 const lock={plan:{sha256:hash(copied),literalCopiedBody:true,sourceVersion:'candidate15',sourceEnhancementAttempt:240,enhancementResponseReplayed:true}};
 for(const arm of ['raw','direct']){await cp(resolve(root,'candidate14-real',arm),resolve(output,arm),{recursive:true,filter:p=>!/(?:input|history)\.json$/.test(p),force:false});lock[arm]={sha256:hash(await readFile(resolve(output,arm,'execution-input.md'))),sourceVersion:'candidate14',unchangedReusedControl:true};}
 await save(resolve(output,'copies-lock.json'),lock);
 await save(resolve(publicRoot,'authenticated-replay-checks.json'),{sameCanonicalAccount:true,newAccountsCreated:0,newRealHistoryRecords:1,sourceProviderAttempt:240,providerNetworkRequests:0,copiedEqualsHistory:true,copySha256:hash(copied),reusedControlsVersion:'candidate14'});
 console.log(JSON.stringify({replayThroughNormalApi:true,newModelRequests:0,copiedEqualsRealHistory:true,lock}));
}finally{await browser.close();}
