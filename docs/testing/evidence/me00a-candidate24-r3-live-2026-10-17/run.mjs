// Explicit stages only. Preflight is model-free; paid stages require the numerical batch approval.
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {capturedExecutionResponse,completedExecutionWork,rawExecutionInput} from '../../../../scripts/me00a-eval.mjs';
const stage=process.argv[2];
if(!['preflight','replay-plan','plan-final','copy','execute'].includes(stage))throw new Error('Explicit frozen stage required');
const version='candidate24-r3',ledger=resolve('tmp/me00a-20261012');
const evidence=resolve('docs/testing/evidence/me00a-candidate24-r3-live-2026-10-17');
const output=resolve('tmp/me00a-candidate24-r3-live-20261017/real');
const load=async path=>JSON.parse(await readFile(path,'utf8'));
const hash=text=>createHash('sha256').update(text).digest('hex');
const save=async(path,value)=>{await mkdir(resolve(path,'..'),{recursive:true});await writeFile(path,typeof value==='string'?value:JSON.stringify(value,null,2),{flag:'wx'});};
const cases=await load(resolve(evidence,'cases.json'));
const manifest=await load('docs/testing/evidence/me00a-candidate24-offline-2026-10-17/candidate24-source-r3.json');
for(const item of manifest.sourceFiles)if(hash(await readFile(resolve(item.path)))!==item.sha256)throw new Error('Frozen source drift');
for(const item of manifest.runtimeFiles)if(hash(await readFile(resolve(manifest.runtimeSnapshot,item.path)))!==item.sha256)throw new Error('Frozen runtime drift');
const protocol=await load(resolve(ledger,'protocol.json')),batch=protocol.activeBatch;
if(stage!=='preflight'&&(batch.version!==version||batch.status!=='OPEN'||batch.authorizationStatus!=='APPROVED_BY_USER'
  ||batch.maximumNewCalls!==4||protocol.maximumActualModelCalls!==338||protocol.phaseLimits.candidateValidation!==258
  ||batch.callsBefore!==324||batch.secondRoundAuthorized!==false))throw new Error('Approved open numerical allocation required before model I/O');
if(stage!=='preflight'){
  if(!/^[A-Za-z0-9_-]+\.json$/.test(batch.casesFile)||hash(await readFile(resolve(ledger,batch.casesFile)))!==batch.casesSha256
    ||hash(await readFile(resolve(ledger,batch.casesFile)))!==hash(await readFile(resolve(evidence,'cases.json'))))throw new Error('Frozen case scope mismatch');
}
const account=await load('tmp/local-acceptance/account.json');
const require=createRequire(resolve('apps/web/package.json'));
async function browserAction(action){
  const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
  try{
    const context=browser.contexts()[0];
    const page=context.pages().find(p=>p.url().startsWith('http://127.0.0.1:9001/'));
    if(!page)throw new Error('Canonical authenticated browser required');
    const api=(path,body)=>page.evaluate(async({path,body})=>{
      const token=document.cookie.split('; ').find(c=>c.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
      const started=performance.now();
      const response=await fetch(path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{'X-XSRF-TOKEN':decodeURIComponent(token)}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(240000)});
      return{status:response.status,requestId:response.headers.get('X-Request-Id'),elapsedMs:Math.round(performance.now()-started),body:await response.json()};
    },{path,body});
    const me=await api('/api/v1/auth/me');
    if(me.status!==200||me.body.data.userId!==account.userId||me.body.data.tenantId!==account.tenantId)throw new Error('Canonical account mismatch');
    await api('/api/v1/auth/csrf');
    return await action(api,context);
  }finally{await browser.close();}
}
async function enhance(api,id,sample,raw=sample.rawPrompt,confirmation){
  const folder=resolve(output,id);
  const input={rawPrompt:raw,context:{customDescription:sample.context.description,files:sample.context.files},enhancement:sample.enhancement,conversationHistory:[],modelId:'deepseek:deepseek-flash',...(confirmation?{planConfirmation:confirmation}:{})};
  await save(resolve(folder,'input.json'),input);
  const started=Date.now(),result=await api('/api/v1/optimizations',input);
  await save(resolve(folder,'platform.json'),result);
  if(result.status!==200){console.log(`FAILED platform ${id} HTTP ${result.status}`);return false;}
  await save(resolve(folder,'copied.md'),result.body.data.optimizedPrompt);
  const list=await api('/api/v1/optimization-history?current=1&size=20');
  for(const row of list.body.data?.records??[]){
    if(Date.parse(row.createdAt)<started-1000)continue;
    const detail=await api(`/api/v1/optimization-history/${row.id}`);
    if(detail.status!==200||detail.body.data.rawPrompt!==raw||detail.body.data.optimizedPrompt!==result.body.data.optimizedPrompt)continue;
    if(JSON.stringify(detail.body.data.sections)!==JSON.stringify(result.body.data.sections))throw new Error('History sections changed');
    await save(resolve(folder,'history.private.json'),detail);
    await save(resolve(folder,'checks.json'),{copiedEqualsHistory:true,sectionsEqual:true,noScriptAnswerRepair:true,copySha256:hash(result.body.data.optimizedPrompt)});
    console.log(`DONE platform ${id}`);return true;
  }
  throw new Error('New same-account history missing or inconsistent');
}
async function reserve(purpose,label){
  const names=(await readdir(resolve(ledger,'traces'))).filter(n=>/^\d+\.json$/.test(n));
  const traces=await Promise.all(names.map(n=>load(resolve(ledger,'traces',n))));
  if(traces.some(t=>t.version===version&&t.label===label))throw new Error('Submission already reserved; no quality retry');
  if(traces.filter(t=>t.version===version&&t.purpose===purpose).length>=batch.purposeMaximums[purpose]
    ||traces.filter(t=>t.version===version).length>=batch.maximumNewCalls
    ||traces.filter(t=>t.phase==='candidateValidation').length>=protocol.phaseLimits.candidateValidation
    ||names.length>=Math.min(protocol.maximumActualModelCalls,batch.callsBefore+batch.maximumNewCalls,batch.maximumActualCallsAfterBatch))throw new Error('Purpose, phase or batch limit exhausted');
  const number=Math.max(0,...names.map(n=>Number(n.slice(0,-5))))+1;
  const path=resolve(ledger,'traces',`${String(number).padStart(3,'0')}.json`);
  await save(path,{version,phase:'candidateValidation',purpose,label,reservedAt:new Date().toISOString()});return path;
}
async function upstream(model,text,purpose,label){
  if(purpose==='EXECUTION'&&!['deepseek-flash','deepseek-v4-pro'].includes(model)||purpose==='REVIEW'&&!['deepseek-flash','deepseek-v4-pro'].includes(model))throw new Error('Model outside frozen role');
  const key=process.env.MODEL_DEEPSEEK_API_KEY||process.env.MODEL_API_KEY;
  if(!key||text.includes(key))throw new Error('Missing configured key or credential in evaluation input');
  const trace=await reserve(purpose,label),started=performance.now();
  const record={version,phase:'candidateValidation',purpose,label,request:{model,messages:[{role:'user',content:text}],...batch.executionParameters,stream:false},startedAt:new Date().toISOString()};
  try{
    const response=await fetch('https://api.deepseek.com/chat/completions',{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${key}`},body:JSON.stringify(record.request),signal:AbortSignal.timeout(240000)});
    Object.assign(record,await capturedExecutionResponse(response));record.elapsedMs=Math.round(performance.now()-started);
    await writeFile(trace,JSON.stringify(record,null,2));
    return{...completedExecutionWork(record),trace,inputSha256:hash(text)};
  }catch(error){record.elapsedMs=Math.round(performance.now()-started);record.failureType=error.name;await writeFile(trace,JSON.stringify(record,null,2));throw new Error(`Recorded ${purpose} failure; no retry`);}
}
if(stage==='preflight')await browserAction(async api=>{
  const models=await api('/api/v1/models'),history=await api('/api/v1/optimization-history?current=1&size=1');
  if(models.status!==200||history.status!==200)throw new Error('Catalog or canonical history unavailable');
  await save(resolve(output,'preflight.json'),{sameAccount:true,newAccountsCreated:0,userHash:hash(account.userId),historyBefore:history.body.data.total,models:models.body.data,frozenSource:manifest.sourceTreeSha256,frozenRuntime:manifest.runtimeTreeSha256,modelCalls:0,batchStatus:batch.status});
  console.log('PREFLIGHT same account, frozen source/runtime and existing history; 0 model calls');
});
if(stage==='replay-plan')await browserAction(async api=>{
  const sample=cases.find(c=>c.id==='Q05');
  const input={rawPrompt:sample.rawPrompt,contextDescription:sample.context.description,conversationHistory:[],modelId:'deepseek:deepseek-flash'};
  await save(resolve(output,'Q05-plan/plan-input.json'),input);
  const result=await api('/api/v1/optimizations/plan',input);
  await save(resolve(output,'Q05-plan/plan-platform.json'),result);
  if(result.status!==200)throw new Error('Captured Plan replay failed; no fabricated replacement');
  const frozenAnswers=sample.planAnswers;
  if(result.body.data.questions.length!==frozenAnswers.length||frozenAnswers.some(a=>!result.body.data.questions.some(q=>q.id===a.questionId&&q.question===a.question)))throw new Error('Captured current Plan filtering changed; stop before enhancement');
  await save(resolve(output,'Q05-plan/replay-checks.json'),{sourceTrace:321,kind:'CAPTURED_ACTUAL_PLANNING_REPLAY',newPlanningCalls:0,originalModelQuestions:8,currentRetainedQuestions:result.body.data.questions.length,exactQuestionAndAnswerBinding:true,newNaturalPlanningAcceptance:false});
  console.log(`DONE captured Plan replay (0 new planning calls): ${result.body.data.questions.length} actual questions; inspect each before binding fixture answers`);
});
if(stage==='plan-final')await browserAction(async api=>{
  const sample=cases.find(c=>c.id==='Q05'),plan=(await load(resolve(output,'Q05-plan/plan-platform.json'))).body.data;
  const answers=await load(resolve(output,'Q05-plan/actual-answers.json'));
  const source=await load('tmp/me00a-20261012/candidate14-actual-answers.json');
  const approvedDelegation=source.answers[0].answer;
  if(answers.length!==plan.questions.length||new Set(answers.map(a=>a.questionId)).size!==answers.length
    ||answers.some(a=>!plan.questions.some(q=>q.id===a.questionId&&q.question===a.question)||a.answer!==approvedDelegation))throw new Error('Answer not bound to an actual question or frozen prior delegation');
  const confirmation={planId:plan.planId,planningContext:plan.planningContext??null,answers};
  await save(resolve(output,'Q05-plan/confirmation.json'),confirmation);
  await enhance(api,'Q05-plan',sample,sample.rawPrompt,confirmation);
});
if(stage==='copy')await browserAction(async(_api,context)=>{
  const origin='http://127.0.0.1:5174';await context.grantPermissions(['clipboard-read','clipboard-write'],{origin});
  const page=await context.newPage(),checks=[];
  try{
    await page.route('**/api/v1/optimizations**',route=>route.abort('blockedbyclient'));
    await page.goto(origin+'/workbench',{waitUntil:'domcontentloaded'});await page.locator('.workbench-grid').waitFor({state:'visible',timeout:30000});
    for(const id of ['Q05-plan']){
      const folder=resolve(output,id),platform=await load(resolve(folder,'platform.json'));
      if(platform.status!==200){checks.push({id,status:'NO_SUCCESSFUL_PLATFORM_RESULT'});continue;}
      const input=await load(resolve(folder,'input.json'));
      await page.evaluate(async({raw,result})=>{const {useOptimizationStore}=await import('/src/stores/optimization.ts');const s=useOptimizationStore();s.resetTask();s.rawPrompt=raw;s.applyReoptimized({result});},{raw:input.rawPrompt,result:platform.body.data});
      await page.bringToFront();await page.locator('.copy-main-button').click();await page.getByRole('button',{name:'已复制',exact:true}).waitFor({timeout:10000});
      const copied=await page.evaluate(()=>navigator.clipboard.readText()),apiText=platform.body.data.optimizedPrompt;
      if(copied.replace(/\r\n/g,'\n')!==apiText.replace(/\r\n/g,'\n'))throw new Error('Actual clipboard differs beyond line endings');
      await save(resolve(folder,'execution-input.md'),copied);
      await save(resolve(folder,'clipboard-lock.json'),{kind:'NATIVE_CLIPBOARD',sha256:hash(copied),apiSha256:hash(apiText),noAppendedAnswers:true});
      await page.locator('.result-panel').screenshot({path:resolve(folder,'native-copy.png')});checks.push({id,pass:true,copySha256:hash(copied)});
    }
    await save(resolve(output,'native-copy-checks.json'),{checks,noNewModelCalls:true});
  }finally{await page.close();}
});
if(stage==='execute'){
  const jobs=[["Q05-plan", "enhanced", "deepseek-flash", "Q05-plan"], ["Q05-plan-pro", "enhanced", "deepseek-v4-pro", "Q05-plan"]];
  jobs.sort((a,b)=>batch.executionOrder.indexOf(a[0]+'/'+a[1])-batch.executionOrder.indexOf(b[0]+'/'+b[1]));
  for(const[id,arm,model,sourceId]of jobs){
    const folder=resolve(output,id),sourceFolder=resolve(output,sourceId);let text;
    if(arm==='enhanced'){
      const lock=await load(resolve(sourceFolder,'clipboard-lock.json'));text=await readFile(resolve(sourceFolder,'execution-input.md'),'utf8');
      if(lock.kind!=='NATIVE_CLIPBOARD'||hash(text)!==lock.sha256)throw new Error('Frozen native clipboard required');
    }else text=sourceId==='Q05-raw-same-info'?await readFile(resolve(sourceFolder,'input.md'),'utf8'):cases.find(c=>c.id===sourceId).rawPrompt;
    await save(resolve(folder,`${arm}-actual-execution-input.md`),text);
    try{const work=await upstream(model,text,'EXECUTION',`${version}-${id}-${arm}`);await save(resolve(folder,`${arm}-work.md`),work.text);await save(resolve(folder,`${arm}-execution.json`),work);console.log(`DONE work ${id} ${arm}`);}
    catch(error){await save(resolve(folder,`${arm}-failure.json`),{failureType:error.name});console.log(`FAILED work ${id} ${arm}; counted, no retry`);}
  }
}
