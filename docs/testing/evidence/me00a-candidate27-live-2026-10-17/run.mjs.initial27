// Explicit stages only. Preflight is model-free; paid stages require the numerical batch approval.
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
import {capturedExecutionResponse,completedExecutionWork,rawExecutionInput} from '../../../../scripts/me00a-eval.mjs';
import {rubricInstruction} from '../../../../scripts/me00a-rubric.mjs';
const stage=process.argv[2];
if(!['natural-plan','captured-plan','enhance','copy','execute','review'].includes(stage))throw new Error('Explicit frozen stage required; use preflight.mjs for model-free preparation');
const version='candidate27-live',ledger=resolve('tmp/me00a-20261012');
const evidence=resolve('docs/testing/evidence/me00a-candidate27-live-2026-10-17');
const output=resolve('tmp/me00a-candidate27-live-20261017/real');
const load=async path=>JSON.parse(await readFile(path,'utf8'));
const hash=text=>createHash('sha256').update(text).digest('hex');
const save=async(path,value)=>{await mkdir(resolve(path,'..'),{recursive:true});await writeFile(path,typeof value==='string'?value:JSON.stringify(value,null,2),{flag:'wx'});};
const cases=await load(resolve(evidence,'cases.json'));
const tools=await load(resolve(evidence,'tools-frozen.json'));
for(const item of tools.tools)if(hash(await readFile(resolve(item.path)))!==item.sha256)throw new Error('Frozen evaluation tool drift');
const manifest=await load('docs/testing/evidence/me00a-candidate27-offline-2026-10-17/candidate27-source.json');
for(const item of manifest.sourceFiles)if(hash(await readFile(resolve(item.path)))!==item.sha256)throw new Error('Frozen source drift');
for(const item of manifest.runtimeFiles)if(hash(await readFile(resolve(manifest.runtimeSnapshot,item.path)))!==item.sha256)throw new Error('Frozen runtime drift');
async function approvedProtocol(){
  const protocol=await load(resolve(ledger,'protocol.json')),batch=protocol.activeBatch;
  if(batch.version!==version||batch.status!=='OPEN'||batch.authorizationStatus!=='APPROVED_BY_USER'
    ||batch.maximumNewCalls!==18||protocol.maximumActualModelCalls!==371||protocol.phaseLimits.candidateValidation!==291
    ||protocol.phaseLimits.baseline!==80||batch.callsBefore!==332||batch.secondRoundAuthorized!==true)
    throw new Error('Approved open numerical allocation required before model I/O');
  if(!/^[A-Za-z0-9_-]+\.json$/.test(batch.casesFile)||hash(await readFile(resolve(ledger,batch.casesFile)))!==batch.casesSha256
    ||hash(await readFile(resolve(ledger,batch.casesFile)))!==hash(await readFile(resolve(evidence,'cases.json'))))throw new Error('Frozen case scope mismatch');
  const exact={PLANNING:3,PLATFORM_GENERATION:9,EXECUTION:4,REVIEW:2};
  if(Object.keys(exact).some(key=>batch.purposeMaximums?.[key]!==exact[key]))throw new Error('Purpose allocation drift');
  return protocol;
}
const protocol=await approvedProtocol(),batch=protocol.activeBatch;
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
    for(const field of ['ambiguities','appliedConstraints'])if(JSON.stringify(detail.body.data[field])!==JSON.stringify(result.body.data[field]))throw new Error(`History ${field} changed`);
    await save(resolve(folder,'history.private.json'),detail);
    await save(resolve(folder,'checks.json'),{copiedEqualsHistory:true,sectionsEqual:true,ambiguitiesEqual:true,appliedConstraintsEqual:true,
      planAnswersInApiRequest:true,historyPublicApiExposesPlanAnswers:false,noScriptAnswerRepair:true,copySha256:hash(result.body.data.optimizedPrompt)});
    console.log(`DONE platform ${id}`);return true;
  }
  throw new Error('New same-account history missing or inconsistent');
}
async function reserve(purpose,label){
  const current=await approvedProtocol(),batch=current.activeBatch;
  const names=(await readdir(resolve(ledger,'traces'))).filter(n=>/^\d+\.json$/.test(n));
  const traces=await Promise.all(names.map(n=>load(resolve(ledger,'traces',n))));
  if(traces.some(t=>t.version===version&&t.label===label))throw new Error('Submission already reserved; no quality retry');
  if(traces.filter(t=>t.version===version&&t.purpose===purpose).length>=batch.purposeMaximums[purpose]
    ||traces.filter(t=>t.version===version).length>=batch.maximumNewCalls
    ||traces.filter(t=>t.phase==='candidateValidation').length>=current.phaseLimits.candidateValidation
    ||names.length>=Math.min(current.maximumActualModelCalls,batch.callsBefore+batch.maximumNewCalls,batch.maximumActualCallsAfterBatch))throw new Error('Purpose, phase or batch limit exhausted');
  const number=Math.max(0,...names.map(n=>Number(n.slice(0,-5))))+1;
  const path=resolve(ledger,'traces',`${String(number).padStart(3,'0')}.json`);
  await save(path,{version,phase:'candidateValidation',purpose,label,reservedAt:new Date().toISOString()});return path;
}
async function upstream(model,text,purpose,label){
  if(!['EXECUTION','REVIEW'].includes(purpose)||!['deepseek-flash','deepseek-v4-pro'].includes(model))throw new Error('Model outside frozen role');
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

// Actual natural questions are frozen before enhancement. The already declared answer policy cannot silently change.
if(stage==='natural-plan'||stage==='captured-plan')await browserAction(async api=>{
  const natural=stage==='natural-plan',id=natural?'Q05-NATURAL':(process.argv[3]??'Q05-PARTIAL-SCORING');
  if(!['Q05-NATURAL','Q05-PARTIAL-SCORING','Q05-PARTIAL-SCORING-R2'].includes(id))throw Error('Frozen round required');
  const sample=cases.find(item=>item.id===id),folder=resolve(output,id);
  await writeFile(resolve(output,'..','planning-mode.txt'),natural?'NATURAL':'CAPTURED_ACTUAL_321_REPLAY');
  const input={rawPrompt:sample.rawPrompt,contextDescription:'',conversationHistory:[],modelId:'deepseek:deepseek-flash'};
  await save(resolve(folder,'plan-input.json'),input);
  const result=await api('/api/v1/optimizations/plan',input);
  await save(resolve(folder,'plan-platform.json'),result);
  if(result.status!==200)throw new Error(`${stage} failed; no replacement questions or quality retry`);
  const questions=result.body.data.questions;
  if(!Array.isArray(questions)||!questions.length)throw new Error('No actual retained questions; inspect before enhancement');
  let answers;
  if(natural)answers=questions.map(question=>({questionId:question.id,question:question.question,answer:sample.frozenAnswer}));
  else{
    answers=sample.planAnswers;
    if(answers.length!==questions.length||answers.some(answer=>!questions.some(question=>question.id===answer.questionId&&question.question===answer.question)))
      throw new Error('Captured question filtering differs; no substituted answers');
  }
  await save(resolve(folder,'actual-answers.json'),answers);
  await save(resolve(folder,'planning-lineage.json'),{kind:natural?'NEW_NATURAL_PLAN':'CAPTURED_ACTUAL_321_REPLAY',
    newNaturalPlanningAcceptance:natural,answersAreControlledSyntheticFixture:true,actualRetainedQuestions:questions,
    noSelectedRealParameters:true,notAnAutomaticFallback:true});
  // Compute exact input from frozen production classes, not a script's reinterpretation of the answers.
  const cp=(await readFile(resolve(output,'..','runner/classpath.txt'),'utf8')).trim();
  const projected=resolve(folder,'expected-task-view.json');
  const run=spawnSync('E:/Delelop/Java/jdk-21/bin/java.exe',['-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8','-cp',cp,'TaskViewFixture',
    resolve(evidence,'cases.json'),id,resolve(folder,'actual-answers.json'),projected],{encoding:'utf8'});
  if(run.status!==0)throw new Error('Frozen task view helper failed; no enhancement request');
  const view=await load(projected),current=await approvedProtocol();
  current.activeBatch.expectedTaskViews??=[];
  const alreadyBoundSameView=current.activeBatch.expectedTaskViews.some(item=>JSON.stringify(item)===JSON.stringify(view));
  if(!alreadyBoundSameView)current.activeBatch.expectedTaskViews.push(view);
  await save(resolve(folder,'protocol-before-answer-binding.json'),await load(resolve(ledger,'protocol.json')));
  await writeFile(resolve(ledger,'protocol.json'),JSON.stringify(current,null,2)+'\n');
  await save(resolve(folder,'protocol-after-answer-binding.json'),current);
  await save(resolve(folder,'answer-binding.json'),{policyFrozenBeforeModelCall:true,answersSha256:hash(await readFile(resolve(folder,'actual-answers.json'))),
    taskViewSha256:hash(await readFile(projected)),callLimitsUnchanged:true});
  console.log(`DONE ${stage}: ${questions.length} actual questions bound; inspect saved questions and task view before enhancement`);
});

if(stage==='enhance')await browserAction(async api=>{
  const id=process.argv[3];
  if(!['Q05-NATURAL','Q05-PARTIAL-SCORING','Q05-PARTIAL-SCORING-R2'].includes(id))throw new Error('Explicit frozen case required');
  const sample=cases.find(item=>item.id===id),folder=resolve(output,id);
  const planResult=await load(resolve(folder,'plan-platform.json'));
  if(planResult.status!==200)throw new Error('Actual successful Plan session required');
  const plan=planResult.body.data,answers=await load(resolve(folder,'actual-answers.json'));
  const expected=id==='Q05-NATURAL'?plan.questions.map(q=>({questionId:q.id,question:q.question,answer:sample.frozenAnswer})):sample.planAnswers;
  if(JSON.stringify(answers)!==JSON.stringify(expected))throw new Error('Answers changed after binding');
  const confirmation={planId:plan.planId,planningContext:plan.planningContext??null,answers};
  await save(resolve(folder,'confirmation.json'),confirmation);
  await enhance(api,id,sample,sample.rawPrompt,confirmation);
});

if(stage==='copy')await browserAction(async(_api,context)=>{
  const origin='http://127.0.0.1:5174';await context.grantPermissions(['clipboard-read','clipboard-write'],{origin});
  const page=await context.newPage(),checks=[];
  try{
    await page.route('**/api/v1/optimizations**',route=>route.abort('blockedbyclient'));
    await page.goto(origin+'/workbench',{waitUntil:'domcontentloaded'});await page.locator('.workbench-grid').waitFor({state:'visible',timeout:30000});
    for(const id of cases.map(sample=>sample.id)){
      const folder=resolve(output,id);let platform;
      try{platform=await load(resolve(folder,'platform.json'));}catch{checks.push({id,status:'NO_PLATFORM_RESULT'});continue;}
      if(platform.status!==200){checks.push({id,status:'NO_SUCCESSFUL_PLATFORM_RESULT'});continue;}
      const input=await load(resolve(folder,'input.json'));
      await page.evaluate(async({raw,result})=>{const {useOptimizationStore}=await import('/src/stores/optimization.ts');const s=useOptimizationStore();s.resetTask();s.rawPrompt=raw;s.applyReoptimized({result});},{raw:input.rawPrompt,result:platform.body.data});
      await page.bringToFront();await page.locator('.copy-main-button').click();await page.getByRole('button',{name:'已复制',exact:true}).waitFor({timeout:10000});
      const copied=await page.evaluate(()=>navigator.clipboard.readText()),apiText=platform.body.data.optimizedPrompt;
      if(copied.replace(/\r\n/g,'\n')!==apiText.replace(/\r\n/g,'\n'))throw new Error('Native clipboard differs beyond line endings');
      await save(resolve(folder,'execution-input.md'),copied);
      await save(resolve(folder,'clipboard-lock.json'),{kind:'NATIVE_CLIPBOARD',sha256:hash(copied),apiSha256:hash(apiText),noAppendedAnswers:true});
      await page.locator('.result-panel').screenshot({path:resolve(folder,'native-copy.png')});checks.push({id,pass:true,copySha256:hash(copied)});
    }
    await save(resolve(output,'native-copy-checks.json'),{checks,noNewModelCalls:true,apiGenerationThenSeededResultCopy:true,
      fullClickGenerationEndToEnd:false});
  }finally{await page.close();}
});

if(stage==='execute'){
  const folder=resolve(output,'Q05-PARTIAL-SCORING'),sample=cases.find(item=>item.id==='Q05-PARTIAL-SCORING');
  const answers=await load(resolve(folder,'actual-answers.json'));
  if(JSON.stringify(answers)!==JSON.stringify(sample.planAnswers))throw new Error('Same-information comparator answers differ');
  const raw=rawExecutionInput(sample,answers),plan=await readFile(resolve(folder,'execution-input.md'),'utf8');
  const lock=await load(resolve(folder,'clipboard-lock.json'));
  if(lock.kind!=='NATIVE_CLIPBOARD'||lock.noAppendedAnswers!==true||hash(plan)!==lock.sha256)throw new Error('Frozen native clipboard required');
  // Fixed interleaving avoids silently selecting a better work; each planned submission is reserved once.
  const jobs=[['F1','deepseek-flash','raw'],['P2','deepseek-v4-pro','plan'],['F2','deepseek-flash','plan'],['P1','deepseek-v4-pro','raw']];
  for(const[id,model,arm]of jobs){
    const target=resolve(output,'works',id),text=arm==='raw'?raw:plan;
    await save(resolve(target,'input.md'),text);
    await save(resolve(target,'binding.json'),{id,model,arm,inputSha256:hash(text),sameInformation:true,noPlanAnswerRepair:true});
    try{const work=await upstream(model,text,'EXECUTION',`${version}-${id}`);await save(resolve(target,'work.md'),work.text);await save(resolve(target,'execution.json'),work);console.log(`DONE work ${id}`);}
    catch(error){await save(resolve(target,'failure.json'),{failureType:error.name});console.log(`FAILED work ${id}; counted, no retry`);}
  }
}

if(stage==='review'){
  const sample=cases.find(item=>item.id==='Q05-PARTIAL-SCORING'),answers=await load(resolve(output,sample.id,'actual-answers.json'));
  let text=rubricInstruction()+'\n你是独立AI辅助评审，不是人工领域专家。只看原始要求、相同实际题答及匿名作品，不查看优化提示词或其他评审结果，不推测模型或来源。准确性、完整性、清晰度、可执行性分别评分。不要奖励篇幅或标题；原目标、未确认类别、局部评分委托、研究未实施状态、双评及同信息公平流程分别核对。不要把建议当已确认事实，不要求用户禁止的假设数据。篇幅由独立工具判断，模型自报不是合规证据。每个问题必须引用该作品的连续原句，说明来源及严重性；分歧和不确定性保留，不补写作品。只输出严格JSON：{"reviewerType":"AI_ASSISTED","scores":{"U":{"accuracy":1,"completeness":1,"clarity":1,"executability":1},"V":{},"X":{},"Y":{}},"issues":[{"work":"U","evidence":"原句","reason":"原因","origin":"INPUT|WORK|MIXED|UNDETERMINED","severity":"SEVERE|MAJOR|MINOR"}],"pairs":[{"left":"U","right":"V","verdict":"LEFT_WIN|RIGHT_WIN|TIE|BOTH_FAIL","reason":"依据"},{"left":"X","right":"Y","verdict":"LEFT_WIN|RIGHT_WIN|TIE|BOTH_FAIL","reason":"依据"}]}。四份作品均需完整四维整数1—5评分。严重错误独立阻断，不以均分抵消。\n';
  text+=`原始要求：\n${sample.rawPrompt}\n实际题答（两组相同）：\n${JSON.stringify(answers)}\n`;
  const mapping={U:'F2',V:'F1',X:'P1',Y:'P2'};
  for(const[label,id]of Object.entries(mapping)){
    const work=await readFile(resolve(output,'works',id,'work.md'),'utf8');
    text+=`\n作品${label}：\n${work}\n`;
  }
  await save(resolve(output,'review-input.md'),text);
  await save(resolve(output,'review-mapping.private.json'),mapping);
  await save(resolve(output,'review-lock.json'),{sha256:hash(text),models:['deepseek-flash','deepseek-v4-pro'],independentCalls:true,sameProvider:true,
    promptAndWorkAudit:false,workOnlyBlinding:true,armLabelsHidden:true,priorReviewerResultsSupplied:false});
  for(const model of ['deepseek-flash','deepseek-v4-pro']){
    try{const review=await upstream(model,text,'REVIEW',`${version}-review-${model}`);await save(resolve(output,`review-${model}.md`),review.text);await save(resolve(output,`review-${model}-execution.json`),review);console.log(`DONE independent AI review ${model}`);}
    catch(error){await save(resolve(output,`review-${model}-failure.json`),{failureType:error.name});console.log(`FAILED review ${model}; counted, no retry`);}
  }
}
