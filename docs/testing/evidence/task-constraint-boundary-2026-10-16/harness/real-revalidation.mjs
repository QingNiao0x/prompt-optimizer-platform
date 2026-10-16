// Isolated opt-in evaluation only. Credentials are inherited from local server configuration, never logged.
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
const { capturedExecutionResponse, completedExecutionWork } = await import(pathToFileURL(resolve('scripts/me00a-eval.mjs')));
const stage = process.argv[2];
if (!['preflight','generate','resume-not-sent','execute','review','resume-review'].includes(stage)) throw new Error('Explicit stage required');
const runtime=resolve('tmp/me00a-20261012'), own=resolve('tmp/me00a-candidate16-20261016');
const output=resolve(own,'real'), pub=resolve('docs/testing/evidence/task-constraint-boundary-2026-10-16');
const load=async p=>JSON.parse(await readFile(p,'utf8'));
const hash=b=>createHash('sha256').update(b).digest('hex');
const save=(p,v)=>writeFile(p,typeof v==='string'?v:JSON.stringify(v,null,2),{flag:'wx'});
const protocol=await load(resolve(runtime,'protocol.json')), batch=protocol.activeBatch;
if(batch.version!=='candidate16'||batch.status!=='OPEN'||batch.authorizationStatus!=='APPROVED_BY_USER'
  ||batch.maximumNewCalls!==12||protocol.maximumActualModelCalls!==275||protocol.phaseLimits.candidateValidation!==195)
  throw new Error('Approved frozen numerical budget required');
const manifest=await load(resolve(pub,'candidate16-source.json'));
for(const file of [...manifest.sourceFiles,...manifest.fixtures,...manifest.classFiles])
  if(hash(await readFile(resolve(file.path)))!==file.sha256) throw new Error('Frozen source/runtime drift');
if(!/^[A-Za-z0-9_-]+\.json$/.test(batch.casesFile)) throw new Error('Frozen case path outside whitelist');
const caseBytes=await readFile(resolve(runtime,batch.casesFile));
if(hash(caseBytes)!==batch.casesSha256) throw new Error('Frozen case mismatch');
const cases=JSON.parse(caseBytes), account=await load('tmp/local-acceptance/account.json');
await mkdir(output,{recursive:true});
async function reserve(purpose,label){
  const names=(await readdir(resolve(runtime,'traces'))).filter(n=>/^\d+\.json$/.test(n));
  const traces=await Promise.all(names.map(n=>load(resolve(runtime,'traces',n))));
  const current=traces.filter(t=>t.version==='candidate16');
  if(current.filter(t=>t.purpose===purpose).length>=batch.purposeMaximums[purpose]
    ||traces.filter(t=>t.phase==='candidateValidation').length>=protocol.phaseLimits.candidateValidation)
    throw new Error('Purpose/phase limit exhausted');
  const limit=Math.min(protocol.maximumActualModelCalls,batch.callsBefore+batch.maximumNewCalls,batch.maximumActualCallsAfterBatch);
  for(let n=1;n<=limit;n++){
    const path=resolve(runtime,'traces',`${String(n).padStart(3,'0')}.json`);
    try{await save(path,{phase:'candidateValidation',version:'candidate16',purpose,label,reservedAt:new Date().toISOString()});return path;}
    catch(error){if(error.code!=='EEXIST')throw error;}
  }
  throw new Error('Actual request cap exhausted');
}
async function upstream(model,text,purpose,label){
  if(!batch.executionModels.includes(model)||purpose==='EXECUTION'&&model!=='deepseek-flash'
    ||purpose==='REVIEW'&&!['deepseek-flash','deepseek-pro','deepseek-v4-pro'].includes(model))
    throw new Error('Model outside approved role');
  const key=process.env.MODEL_DEEPSEEK_API_KEY||process.env.MODEL_API_KEY;
  if(!key)throw new Error('Local configured server key unavailable');
  const path=await reserve(purpose,label), start=performance.now();
  const request={model,messages:[{role:'user',content:text}],...batch.executionParameters,stream:false};
  const record={phase:'candidateValidation',version:'candidate16',purpose,label,request,startedAt:new Date().toISOString()};
  try{
    const response=await fetch('https://api.deepseek.com/chat/completions',{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${key}`},body:JSON.stringify(request),signal:AbortSignal.timeout(240000)});
    Object.assign(record,await capturedExecutionResponse(response));
    record.elapsedMs=Math.round(performance.now()-start);
    await writeFile(path,JSON.stringify(record,null,2));
    return {...completedExecutionWork(record),trace:path,inputSha256:hash(text)};
  }catch(error){record.elapsedMs=Math.round(performance.now()-start);record.failure=error.message;
    await writeFile(path,JSON.stringify(record,null,2));throw error;}
}
async function withBrowser(action){
  const require=createRequire(resolve('apps/web/package.json'));
  const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
  try{
    const page=browser.contexts()[0].pages().find(p=>p.url().startsWith('http://127.0.0.1:9001/'));
    if(!page)throw new Error('Normal canonical browser required');
    const api=(path,body)=>page.evaluate(async({path,body})=>{
      const token=document.cookie.split('; ').find(v=>v.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
      const start=performance.now();const response=await fetch(path,{method:body===undefined?'GET':'POST',
        headers:{'Content-Type':'application/json',...(token?{'X-XSRF-TOKEN':decodeURIComponent(token)}:{})},
        ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(240000)});
      return{status:response.status,elapsedMs:Math.round(performance.now()-start),requestId:response.headers.get('X-Request-Id'),body:await response.json()};
    },{path,body});
    const me=await api('/api/v1/auth/me');
    if(me.status!==200||me.body.data.userId!==account.userId||me.body.data.tenantId!==account.tenantId)
      throw new Error('Canonical identity mismatch');
    await api('/api/v1/auth/csrf');
    await action(api,page);
  }finally{await browser.close();}
}
if(stage==='preflight')await withBrowser(async api=>{
  const models=await api('/api/v1/models');if(models.status!==200)throw new Error('Catalog unavailable');
  const history=await api('/api/v1/optimization-history?current=1&size=1');
  await save(resolve(output,'preflight.json'),{sameAccount:true,newAccountsCreated:0,accountHash:hash(account.userId),
    models:models.body.data,historyBefore:history.body.data?.total,requestScope:'direct, examples off, no materials or confirmations'});
  console.log('PREFLIGHT same account; catalog and history frozen; no model calls');
});
if(stage==='generate'||stage==='resume-not-sent')await withBrowser(async api=>{
  const resume=stage==='resume-not-sent';
  const lock=resume?await load(resolve(output,'copies-lock.json')):{};let failed=0;
  if(resume){
    const old=await load(resolve(output,'requests-title/platform.json'));
    const proof=await load(resolve(own,'harness-input-scope-red.json'));
    const names=(await readdir(resolve(runtime,'traces'))).filter(n=>/^\d+\.json$/.test(n));
    for(const name of names){const trace=await load(resolve(runtime,'traces',name));
      if(trace.requestId===old.requestId)throw new Error('Upstream attempt already exists; no quality retry');}
    if(old.status!==502||proof.actualTemplate!=='GENERAL'||proof.frozenRequestsInputAccepted!==false)
      throw new Error('Only proven pre-I/O harness rejection can resume');
    await save(resolve(output,'copies-lock-research-only.json'),lock);
  }
  for(const sample of cases.filter(sample=>!resume||sample.id==='requests-title')){
    const folder=resolve(output,sample.id),capture=resume?resolve(folder,'recovered-after-harness'):folder;
    await mkdir(capture,{recursive:true});
    const input={rawPrompt:sample.rawPrompt,context:{customDescription:'',files:[]},enhancement:sample.enhancement,conversationHistory:[],modelId:'deepseek:deepseek-flash'};
    if(resume&&JSON.stringify(await load(resolve(folder,'input.json')))!==JSON.stringify(input))throw new Error('Original input changed');
    await save(resolve(capture,'input.json'),input);const started=Date.now();
    const response=await api('/api/v1/optimizations',input);await save(resolve(capture,'platform.json'),response);
    if(response.status!==200){await save(resolve(folder,'generation-failure.json'),{status:response.status,code:response.body.error?.code});failed++;console.log(`FAILED generation ${sample.id} HTTP ${response.status}`);continue;}
    const copied=response.body.data.optimizedPrompt;
    await save(resolve(folder,'copied.md'),copied);await save(resolve(folder,'execution-input.md'),copied);
    const list=await api('/api/v1/optimization-history?current=1&size=20');let history;
    for(const row of list.body.data?.records??[]){if(Date.parse(row.createdAt)<started-1000)continue;
      const detail=await api(`/api/v1/optimization-history/${row.id}`);
      if(detail.status===200&&detail.body.data.rawPrompt===sample.rawPrompt&&detail.body.data.optimizedPrompt===copied){history=detail;break;}}
    if(!history)throw new Error('New canonical history differs from copied body');
    await save(resolve(folder,'history.private.json'),history);
    await save(resolve(folder,'checks.json'),{copiedEqualsHistory:true,copySha256:hash(copied),noScriptAnswerRepair:true,
      sectionsEqual:JSON.stringify(history.body.data.sections)===JSON.stringify(response.body.data.sections)});
    lock[sample.id]={rawSha256:hash(sample.rawPrompt),enhancedSha256:hash(copied)};
    console.log(`DONE generation ${sample.id}; original=${sample.rawPrompt.length}, copy=${copied.length}`);
  }
  if(resume)await writeFile(resolve(output,'copies-lock.json'),JSON.stringify(lock,null,2));
  else await save(resolve(output,'copies-lock.json'),lock);
  if(failed)throw new Error(`${failed} generation(s) failed; no manual retry`);
});
if(stage==='execute'){
  const lock=await load(resolve(output,'copies-lock.json'));
  // Fixed interleaving; no condition gets extra information or a quality retry.
  for(const [id,arm]of [['requests-title','raw'],['mortality-2015-2025','enhanced'],['requests-title','enhanced'],['mortality-2015-2025','raw']]){
    const sample=cases.find(c=>c.id===id),folder=resolve(output,id),text=arm==='raw'?sample.rawPrompt:await readFile(resolve(folder,'execution-input.md'),'utf8');
    if(arm==='enhanced'&&lock[id]?.enhancedInputKind!=='NATIVE_CLIPBOARD')throw new Error('Actual copied-body check required before execution');
    if(hash(text)!==lock[id]?.[arm==='raw'?'rawSha256':'enhancedSha256'])throw new Error('Literal execution input drift');
    await save(resolve(folder,`${arm}-actual-execution-input.md`),text);
    try{const work=await upstream('deepseek-flash',text,'EXECUTION',`candidate16-${id}-${arm}`);
      await save(resolve(folder,`${arm}-work.md`),work.text);await save(resolve(folder,`${arm}-execution.json`),work);
      console.log(`DONE work ${id} ${arm}`);
    }catch(error){await save(resolve(folder,`${arm}-execution-failure.json`),{error:error.message});console.log(`FAILED work ${id} ${arm}: ${error.message}`);}
  }
}
if(stage==='review'){
  let text='你是独立AI辅助评审，不是人工领域专家。比较同一原始要求下的匿名作品A/B；看准确性、完整性、清晰度、可执行性，分别按1—5打分。1无法使用，2需大改，3基本可用但有缺口，4满足要求仅小改，5充分且精确。不要奖励篇幅或标题，不得借缺少真实数据要求编造；用户要求的原交付不能变成问卷或空架子。硬性格式、代码行为、事实与重要规则反转独立阻断，不能被均分抵消。每题给A胜/B胜/平/双方失败并引用作品原句说明，保留无法判定。输出JSON对象，含cases数组，每项含id、scoresA、scoresB、verdict、severeFailures、evidence、uncertainties。只按给出的原始要求评价，不补写用户确认或额外材料。\n';
  const mapping={};
  for(const [i,sample] of cases.entries()){
    const folder=resolve(output,sample.id),raw=await readFile(resolve(folder,'raw-work.md'),'utf8'),enhanced=await readFile(resolve(folder,'enhanced-work.md'),'utf8');
    const swap=i===0;mapping[sample.id]={A:swap?'enhanced':'raw',B:swap?'raw':'enhanced'};
    text+=`\n案例 ${sample.id}\n原始要求：\n${sample.rawPrompt}\n条件：没有额外资料或实际确认信息。\n作品A：\n${swap?enhanced:raw}\n作品B：\n${swap?raw:enhanced}\n`;
  }
  await save(resolve(output,'review-input.md'),text);await save(resolve(output,'review-mapping.private.json'),mapping);
  await save(resolve(output,'review-input-lock.json'),{sha256:hash(text),reviewers:['deepseek-flash','deepseek-pro'],independent:true});
  for(const model of ['deepseek-flash','deepseek-pro']){
    try{const review=await upstream(model,text,'REVIEW',`candidate16-review-${model}`);
      await save(resolve(output,`review-${model}.md`),review.text);await save(resolve(output,`review-${model}-execution.json`),review);console.log(`DONE independent AI review ${model}`);
    }catch(error){await save(resolve(output,`review-${model}-failure.json`),{error:error.message});console.log(`FAILED independent AI review ${model}: ${error.message}`);}
  }
}
// Only an explicitly approved purpose reallocation can replace the rejected alias.
// The original 400 and first review remain intact; the second reviewer sees only
// the same frozen anonymous pack, never the first review or local findings.
if(stage==='resume-review'){
  if(batch.maximumGenerationCalls!==5||batch.maximumReviewCalls!==3
    ||batch.authorizationAmendment!=='protocol-amendment-14a.json')
    throw new Error('Explicit purpose reallocation required before any I/O');
  const amendment=await load(resolve(pub,'protocol-amendment-14a.json'));
  if(amendment.authorizationStatus!=='APPROVED_BY_USER'||amendment.maximumNewCalls!==12)
    throw new Error('User allocation approval record required');
  const failed=await load(resolve(runtime,'traces/261.json'));
  const text=await readFile(resolve(output,'review-input.md'),'utf8');
  const lock=await load(resolve(output,'review-input-lock.json'));
  if(hash(text)!==lock.sha256||hash(text)!==hash(failed.request.messages.at(-1).content)
    ||failed.httpStatus!==400||failed.request.model!=='deepseek-pro'
    ||!failed.response?.error?.message?.includes('deepseek-v4-pro'))
    throw new Error('Frozen failed-alias review proof mismatch');
  const review=await upstream('deepseek-v4-pro',text,'REVIEW','candidate16-review-corrected-pro-alias');
  await save(resolve(output,'review-deepseek-v4-pro.md'),review.text);
  await save(resolve(output,'review-deepseek-v4-pro-execution.json'),review);
  console.log('DONE independent AI review with corrected upstream identifier');
}
