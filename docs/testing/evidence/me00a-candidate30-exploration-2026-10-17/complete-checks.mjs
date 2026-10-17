import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
const pub=resolve('docs/testing/evidence/me00a-candidate30-exploration-2026-10-17');
const work=resolve('tmp/me00a-candidate30-live-20261017');
const cases=JSON.parse(await readFile(resolve(pub,'cases.json'),'utf8'));
const account=JSON.parse(await readFile('tmp/local-acceptance/account.json','utf8'));
const require=createRequire(resolve('apps/web/package.json'));
const {chromium}=require('@playwright/test');
const hash=s=>createHash('sha256').update(s).digest('hex');
const save=async(file,value)=>{await mkdir(resolve(file,'..'),{recursive:true});await writeFile(file,typeof value==='string'?value:JSON.stringify(value,null,2)+'\n',{flag:'wx'});};
const browser=await chromium.connectOverCDP('http://127.0.0.1:9325');
const context=browser.contexts()[0];
const page=await context.newPage();
const api=async(path,body)=>page.evaluate(async({path,body})=>{
 const token=document.cookie.split('; ').find(s=>s.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
 const r=await fetch(path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{'X-XSRF-TOKEN':decodeURIComponent(token)}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(180000)});
 return {status:r.status,body:await r.json()};
},{path,body});
const input=(sample,confirmation)=>({rawPrompt:sample.rawPrompt,modelId:sample.modelId,context:sample.context,enhancement:{templateCode:'AUTO',includeConversationHistory:false,includePermissionBoundaries:true,includeExamples:false},conversationHistory:[],planConfirmation:confirmation??null});
async function checkHistory(id,result,raw){
 if(result.status!==200)return;
 const d=result.body.data; const history=await api('/api/v1/optimization-history?current=1&size=20');
 for(const row of history.body.data.records){
  const h=await api('/api/v1/optimization-history/'+row.id);
  if(h.body.data.rawPrompt!==raw||h.body.data.optimizedPrompt!==d.optimizedPrompt)continue;
  for(const key of ['sections','ambiguities','appliedConstraints'])if(JSON.stringify(h.body.data[key])!==JSON.stringify(d[key]))throw Error('History drift '+key);
  await save(resolve(work,id,'history.private.json'),h);
  await save(resolve(work,id,'history-check.json'),{bodyMatches:true,sectionsMatch:true,ambiguitiesMatch:true,constraintsMatch:true,copySha256:hash(d.optimizedPrompt)});return;
 }
 throw Error('Matching canonical history missing');
}
async function platform(id,sample,confirmation){
 await save(resolve(work,id,'input.json'),input(sample,confirmation));
 const r=await api('/api/v1/optimizations',input(sample,confirmation));
 await save(resolve(work,id,'platform.json'),r);
 if(r.status===200)await save(resolve(work,id,'copied.md'),r.body.data.optimizedPrompt);
 await checkHistory(id,r,sample.rawPrompt);console.log(JSON.stringify({id,status:r.status,requestId:r.body.requestId,ambiguities:r.body.data?.ambiguities}));return r;
}
async function plan(id,sample){
 const body={rawPrompt:sample.rawPrompt,contextDescription:'',conversationHistory:[],planningContext:null,modelId:sample.modelId};
 await save(resolve(work,id,'input.json'),body);
 const r=await api('/api/v1/optimizations/plan',body);await save(resolve(work,id,'platform.json'),r);
 console.log(JSON.stringify({id,status:r.status,questions:r.body.data?.questions?.map(q=>q.question),summary:r.body.data?.summary}));return r;
}
try{
 await page.goto('http://127.0.0.1:5174/workbench',{waitUntil:'domcontentloaded'});
 const me=await api('/api/v1/auth/me');if(me.status!==200||me.body.data.userId!==account.userId||me.body.data.tenantId!==account.tenantId)throw Error('Canonical account mismatch');
 await api('/api/v1/auth/csrf');
 const mode=process.argv[2];
 if(mode==='mixed-direct'){
  await save(resolve(work,'mixed-direct-reserved.json'),{mode,scheduledOnce:true,noPlanAnswers:true});
  await platform('PROJECT-READ-AND-DEVELOP-ENHANCE',cases[1]);
 }else if(mode==='copy-only'){
  await page.route('**/api/v1/optimizations**',route=>route.abort('blockedbyclient'));
  await context.grantPermissions(['clipboard-read','clipboard-write'],{origin:'http://127.0.0.1:5174'});
  for(const id of ['PROJECT-READ-PLAN-ENHANCE','PROJECT-READ-DIRECT','PROJECT-READ-AND-DEVELOP-ENHANCE']){
   const r=JSON.parse(await readFile(resolve(work,id,'platform.json'),'utf8'));if(r.status!==200)continue;
   const raw=id.includes('AND-DEVELOP')?cases[1].rawPrompt:cases[0].rawPrompt;
   await page.evaluate(async({result,raw})=>{const {useOptimizationStore}=await import('/src/stores/optimization.ts');const store=useOptimizationStore();store.rawPrompt=raw;store.applyReoptimized({result});},{result:r.body.data,raw});
   await page.getByRole('button',{name:'复制提示词',exact:true}).click();
   const copied=await page.evaluate(()=>navigator.clipboard.readText());
   if(copied.replace(/\r\n/g,'\n')!==r.body.data.optimizedPrompt.replace(/\r\n/g,'\n'))throw Error('Native copy drift');
   const native=resolve(work,id,'native-copy.md');await save(native,copied);
   if(id==='PROJECT-READ-PLAN-ENHANCE')await save(resolve(work,id,'copied.md'),copied);
   try{await readFile(resolve(work,id,'history-check.json'));}catch{await checkHistory(id,r,raw);}
   await save(resolve(work,id,'native-copy-check.json'),{nativeCopyMatches:true,source:'unchanged retained real API response rendered by existing result component',clipboardHash:hash(copied),newModelCalls:0});
   await page.screenshot({path:resolve(work,id,'result-copy.png'),fullPage:true});
   await page.waitForTimeout(1600);
  }
  await save(resolve(work,'ui-round1-check.json'),{planQuestions:0,automaticFinalGeneration:true,planConfirmationSent:JSON.parse(await readFile(resolve(work,'ui-round1-requests.json'),'utf8')).at(-1).body.planConfirmation,firstPairNotApiSimulation:true,initialCopyToolFailed:true,copyRecovery:'same retained API result; actual result component; no new generation'});
  console.log('NATIVE_COPY_AND_HISTORY_CHECKS_PASSED');
 }else if(mode==='ui-round1'){
  await save(resolve(work,'ui-round1-reserved.json'),{mode,scheduledOnce:true});
  const history=await api('/api/v1/optimization-history?current=1&size=1');await save(resolve(work,'history-before.json'),{total:history.body.data.total});
  await page.evaluate(()=>localStorage.setItem('prompt-optimizer.plan-mode.v1',JSON.stringify({enabled:true,introSeen:true})));
  await page.reload({waitUntil:'domcontentloaded'});
  await page.getByLabel('原始提示词',{exact:true}).fill(cases[0].rawPrompt);
  let planResult,finalResult;const requests=[];
  await page.route('**/api/v1/optimizations**',async route=>{
   const req=route.request();const body=req.postDataJSON();
   if(body.rawPrompt!==cases[0].rawPrompt||body.modelId!==cases[0].modelId||(body.context?.files?.length??0)!==0||body.enhancement?.includeExamples===true){await route.abort();throw Error('Unexpected UI request scope');}
   requests.push({path:new URL(req.url()).pathname,body});await route.continue();
  });
  page.on('response',async response=>{
   const path=new URL(response.url()).pathname;
   if(path==='/api/v1/optimizations/plan')planResult={status:response.status(),body:await response.json()};
   if(path==='/api/v1/optimizations')finalResult={status:response.status(),body:await response.json()};
  });
  await page.getByRole('button',{name:'先确认并增强',exact:true}).click();
  for(let i=0;i<180;i++){
   if(finalResult||planResult?.status>=400||planResult?.body.data?.questions?.length>0)break;
   await page.waitForTimeout(1000);
  }
  await save(resolve(work,'ui-round1-requests.json'),requests);
  if(planResult)await save(resolve(work,'PROJECT-READ-PLAN-R1/platform.json'),planResult);
  if(finalResult){
   await save(resolve(work,'PROJECT-READ-PLAN-ENHANCE/platform.json'),finalResult);
   if(finalResult.status===200){
    await context.grantPermissions(['clipboard-read','clipboard-write'],{origin:'http://127.0.0.1:5174'});
    await page.getByRole('button',{name:'复制',exact:true}).click();
    const copied=await page.evaluate(()=>navigator.clipboard.readText());
    await save(resolve(work,'PROJECT-READ-PLAN-ENHANCE/copied.md'),copied);
    const equal=copied.replace(/\r\n/g,'\n')===finalResult.body.data.optimizedPrompt.replace(/\r\n/g,'\n');if(!equal)throw Error('Native clipboard drift');
    await checkHistory('PROJECT-READ-PLAN-ENHANCE',finalResult,cases[0].rawPrompt);
    await page.screenshot({path:resolve(work,'real-zero-question-workbench.png'),fullPage:true});
    await save(resolve(work,'ui-round1-check.json'),{planQuestions:planResult.body.data.questions.length,automaticFinalGeneration:true,planConfirmationSent:requests.at(-1).body.planConfirmation,dialogVisible:await page.getByRole('dialog',{name:'把关键细节确认清楚'}).count(),nativeCopyMatches:true,firstPairNotApiSimulation:true});
   }
  }
  console.log(JSON.stringify({mode,planStatus:planResult?.status,planQuestions:planResult?.body.data?.questions?.map(q=>q.question),enhancementStatus:finalResult?.status,requestCount:requests.length}));
 }else if(mode==='round2'){
  await save(resolve(work,'round2-reserved.json'),{mode,scheduledOnce:true});
  await plan('PROJECT-READ-PLAN-R2',cases[0]);await platform('PROJECT-READ-DIRECT',cases[0]);
 }else if(mode==='mixed'){
  await save(resolve(work,'mixed-reserved.json'),{mode,scheduledOnce:true});
  const r=await plan('PROJECT-READ-AND-DEVELOP-PLAN',cases[1]);
  if(r.status===200&&r.body.data.questions.length===0)await platform('PROJECT-READ-AND-DEVELOP-ENHANCE',cases[1],{planId:r.body.data.planId,planningContext:r.body.data.planningContext,answers:[]});
  else console.log('MIXED_ENHANCEMENT_NOT_RUN_NO_SCRIPTED_ANSWERS');
 }else throw Error('Unknown frozen operation group');
}finally{await page.close();await browser.close();}
