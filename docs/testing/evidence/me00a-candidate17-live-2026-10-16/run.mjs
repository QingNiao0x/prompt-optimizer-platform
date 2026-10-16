// Explicit stages only. Preflight is model-free; paid stages require the numerical batch approval.
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {capturedExecutionResponse,completedExecutionWork,rawExecutionInput} from '../../../../scripts/me00a-eval.mjs';
const stage=process.argv[2];
if(!['preflight','direct','plan','plan-final','direct-parity','copy','execute','review'].includes(stage))throw new Error('Explicit frozen stage required');
const version='candidate17-r2',ledger=resolve('tmp/me00a-20261012');
const evidence=resolve('docs/testing/evidence/me00a-candidate17-live-2026-10-16');
const output=resolve('tmp/me00a-candidate17-live-20261016/real');
const load=async path=>JSON.parse(await readFile(path,'utf8'));
const hash=text=>createHash('sha256').update(text).digest('hex');
const save=async(path,value)=>{await mkdir(resolve(path,'..'),{recursive:true});await writeFile(path,typeof value==='string'?value:JSON.stringify(value,null,2),{flag:'wx'});};
const cases=await load(resolve(evidence,'cases.json'));
const manifest=await load('docs/testing/evidence/me00a-candidate17-2026-10-16/candidate17-source.json');
for(const item of manifest.sourceFiles)if(hash(await readFile(resolve(item.path)))!==item.sha256)throw new Error('Frozen source drift');
for(const item of manifest.runtimeFiles)if(hash(await readFile(resolve(manifest.runtimeSnapshot,item.path)))!==item.sha256)throw new Error('Frozen runtime drift');
const protocol=await load(resolve(ledger,'protocol.json')),batch=protocol.activeBatch;
if(stage!=='preflight'&&(batch.version!==version||batch.status!=='OPEN'||batch.authorizationStatus!=='APPROVED_BY_USER'
  ||batch.maximumNewCalls!==24||protocol.maximumActualModelCalls!==299||protocol.phaseLimits.candidateValidation!==219
  ||batch.callsBefore!==262||batch.secondRoundAuthorized!==false))throw new Error('Approved open numerical allocation required before model I/O');
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
  if(purpose==='EXECUTION'&&model!=='deepseek-flash'||purpose==='REVIEW'&&!['deepseek-flash','deepseek-v4-pro'].includes(model))throw new Error('Model outside frozen role');
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
if(stage==='direct')await browserAction(async api=>{for(const sample of cases.filter(c=>c.id!=='Q05'))await enhance(api,sample.id,sample);});
if(stage==='plan')await browserAction(async api=>{
  const sample=cases.find(c=>c.id==='Q05');
  const input={rawPrompt:sample.rawPrompt,contextDescription:sample.context.description,conversationHistory:[],modelId:'deepseek:deepseek-flash'};
  await save(resolve(output,'Q05-plan/plan-input.json'),input);
  const result=await api('/api/v1/optimizations/plan',input);
  await save(resolve(output,'Q05-plan/plan-platform.json'),result);
  if(result.status!==200)throw new Error('Natural Plan failed; no replacement with old questions');
  console.log(`DONE natural Plan: ${result.body.data.questions.length} actual questions; inspect each before binding fixture answers`);
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
  await save(resolve(output,'Q05-raw-same-info/input.md'),rawExecutionInput(sample,answers));
  await enhance(api,'Q05-plan',sample,sample.rawPrompt,confirmation);
});
if(stage==='direct-parity')await browserAction(async api=>{
  const sample=cases.find(c=>c.id==='Q05'),raw=await readFile(resolve(output,'Q05-raw-same-info/input.md'),'utf8');
  if(!batch.allowedRawPromptSha256.includes(hash(raw)))throw new Error('Exact actual-answer comparator input must be frozen before I/O');
  await enhance(api,'Q05-direct-same-info',sample,raw);
});
if(stage==='copy')await browserAction(async(_api,context)=>{
  const origin='http://127.0.0.1:5173';await context.grantPermissions(['clipboard-read','clipboard-write'],{origin});
  const page=await context.newPage(),checks=[];
  try{
    await page.route('**/api/v1/optimizations**',route=>route.abort('blockedbyclient'));
    await page.goto(origin+'/workbench',{waitUntil:'domcontentloaded'});await page.locator('.workbench-grid').waitFor({state:'visible',timeout:30000});
    for(const id of ['requests-title','mortality-2015-2025','Q05-plan','Q05-direct-same-info']){
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
  const jobs=[['requests-title','raw'],['mortality-2015-2025','enhanced'],['Q05-plan','enhanced'],['requests-title','enhanced'],['Q05-raw-same-info','raw'],['mortality-2015-2025','raw'],['Q05-direct-same-info','enhanced']];
  for(const[id,arm]of jobs){
    const folder=resolve(output,id);let text;
    if(arm==='enhanced'){
      const lock=await load(resolve(folder,'clipboard-lock.json'));text=await readFile(resolve(folder,'execution-input.md'),'utf8');
      if(lock.kind!=='NATIVE_CLIPBOARD'||hash(text)!==lock.sha256)throw new Error('Frozen native clipboard required');
    }else text=id==='Q05-raw-same-info'?await readFile(resolve(folder,'input.md'),'utf8'):cases.find(c=>c.id===id).rawPrompt;
    await save(resolve(folder,`${arm}-actual-execution-input.md`),text);
    try{const work=await upstream('deepseek-flash',text,'EXECUTION',`${version}-${id}-${arm}`);await save(resolve(folder,`${arm}-work.md`),work.text);await save(resolve(folder,`${arm}-execution.json`),work);console.log(`DONE work ${id} ${arm}`);}
    catch(error){await save(resolve(folder,`${arm}-failure.json`),{failureType:error.name});console.log(`FAILED work ${id} ${arm}; counted, no retry`);}
  }
}
if(stage==='review'){
  let text='你是独立AI辅助评审，不是人工领域专家。比较同一要求和信息条件下的匿名作品。准确性、完整性、清晰度、可执行性分别按1—5评分：1无法使用，2需大改，3基本可用但有缺口，4满足要求仅小改，5充分且精确。不要奖励长度或标题。原交付、事实、明确规则、篇幅、代码行为和格式严重失败独立记录，不能被平均分抵消。资料不足不允许编造，也不应把整篇退回问卷或空表。仅按用户原要求及实际信息评价，不新增代码、示例、量表或资料要求；作品自报合规不是证据。给每对比较A胜/B胜/平/双方失败，引用所属作品的完整原句，说明不确定性和无法判定之处。仅输出JSON：cases数组，每项含id、scoresA、scoresB、verdict、severeFailures、evidence（含work与quote）及uncertainties。不要补写作品或答案。\n';
  const mapping={};
  const pairs=[['requests-title','requests-title','raw','requests-title','enhanced'],['mortality-2015-2025','mortality-2015-2025','raw','mortality-2015-2025','enhanced'],['Q05-direct','Q05-raw-same-info','raw','Q05-direct-same-info','enhanced'],['Q05-plan','Q05-raw-same-info','raw','Q05-plan','enhanced']];
  for(const[i,[id,rawId,rawArm,enhId,enhArm]]of pairs.entries()){
    const sample=cases.find(c=>c.id===id)||(cases.find(c=>c.id==='Q05'));
    const raw=await readFile(resolve(output,rawId,`${rawArm}-work.md`),'utf8'),enh=await readFile(resolve(output,enhId,`${enhArm}-work.md`),'utf8');
    const swap=i%2===0;mapping[id]={A:swap?'enhanced':'raw',B:swap?'raw':'enhanced'};
    const answers=id.startsWith('Q05')?await load(resolve(output,'Q05-plan/actual-answers.json')):[];
    text+=`\n案例${id}\n原始要求：\n${sample.rawPrompt}\n实际确认信息：\n${answers.length?JSON.stringify(answers):'无'}\n作品A：\n${swap?enh:raw}\n作品B：\n${swap?raw:enh}\n`;
  }
  await save(resolve(output,'review-input.md'),text);await save(resolve(output,'review-mapping.private.json'),mapping);
  await save(resolve(output,'review-lock.json'),{sha256:hash(text),models:['deepseek-flash','deepseek-v4-pro'],independentCalls:true,sameProvider:true});
  for(const model of ['deepseek-flash','deepseek-v4-pro']){
    try{const review=await upstream(model,text,'REVIEW',`${version}-review-${model}`);await save(resolve(output,`review-${model}.md`),review.text);await save(resolve(output,`review-${model}-execution.json`),review);console.log(`DONE independent AI review ${model}`);}
    catch(error){await save(resolve(output,`review-${model}-failure.json`),{failureType:error.name});console.log(`FAILED review ${model}; counted, no retry`);}
  }
}
