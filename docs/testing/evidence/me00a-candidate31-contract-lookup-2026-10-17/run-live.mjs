// Real browser requests only; no fabricated server responses or user answers.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
const pub=resolve('docs/testing/evidence/me00a-candidate31-contract-lookup-2026-10-17');
const work=resolve('tmp/me00a-candidate31-live-20261017/live-results');
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
 const r=await fetch(path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{'X-XSRF-TOKEN':decodeURIComponent(token)}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(210000)});
 return {status:r.status,body:await r.json()};
},{path,body});
const input=sample=>({rawPrompt:sample.rawPrompt,modelId:sample.modelId,context:sample.context,enhancement:{templateCode:'AUTO',includeConversationHistory:false,includePermissionBoundaries:true,includeExamples:false},conversationHistory:[],planConfirmation:null});
async function checkHistory(id,result,raw){
 const d=result.body.data;const history=await api('/api/v1/optimization-history?current=1&size=20');
 for(const row of history.body.data.records){
  const h=await api('/api/v1/optimization-history/'+row.id);
  if(h.body.data.rawPrompt!==raw||h.body.data.optimizedPrompt!==d.optimizedPrompt)continue;
  for(const key of ['sections','ambiguities','appliedConstraints'])if(JSON.stringify(h.body.data[key])!==JSON.stringify(d[key]))throw Error('History drift '+key);
  await save(resolve(work,id,'history.private.json'),h);
  await save(resolve(work,id,'history-check.json'),{bodyMatches:true,sectionsMatch:true,ambiguitiesMatch:true,constraintsMatch:true,copySha256:hash(d.optimizedPrompt)});return;
 }
 throw Error('Matching canonical history missing');
}
async function copyAndHistory(id,result,raw){
 await context.grantPermissions(['clipboard-read','clipboard-write'],{origin:'http://127.0.0.1:5174'});
 await page.getByRole('button',{name:'复制提示词',exact:true}).click();
 const copied=await page.evaluate(()=>navigator.clipboard.readText());
 await save(resolve(work,id,'native-copy.md'),copied);
 if(copied.replace(/\r\n/g,'\n')!==result.body.data.optimizedPrompt.replace(/\r\n/g,'\n'))throw Error('Native clipboard drift');
 await checkHistory(id,result,raw);
 await save(resolve(work,id,'native-copy-check.json'),{nativeCopyMatches:true,clipboardHash:hash(copied),newModelCallsForCopy:0});
 await page.screenshot({path:resolve(work,id,'result-copy.png'),fullPage:true});
}
try{
 await page.goto('http://127.0.0.1:5174/workbench',{waitUntil:'domcontentloaded'});
 const me=await api('/api/v1/auth/me');if(me.status!==200||me.body.data.userId!==account.userId||me.body.data.tenantId!==account.tenantId)throw Error('Canonical account mismatch');
 await api('/api/v1/auth/csrf');
 const mode=process.argv[2];
 if(mode==='inspect'){
  console.log((await page.locator('main').innerText()).slice(0,12000));
 }else if(mode==='existing-ui'){
  await save(resolve(work,'existing-ui-reserved.json'),{scheduledOnce:true});
  const history=await api('/api/v1/optimization-history?current=1&size=1');await save(resolve(work,'history-before.json'),{total:history.body.data.total});
  await page.evaluate(()=>localStorage.setItem('prompt-optimizer.plan-mode.v1',JSON.stringify({enabled:true,introSeen:true})));
  await page.reload({waitUntil:'domcontentloaded'});
  await page.getByLabel('原始提示词',{exact:true}).fill(cases[0].rawPrompt);
  let planResult,finalResult;const requests=[];
  await page.route('**/api/v1/optimizations**',async route=>{
   const req=route.request();const path=new URL(req.url()).pathname;
   if(req.method()!=='POST'||!['/api/v1/optimizations','/api/v1/optimizations/plan'].includes(path)){await route.continue();return;}
   const body=req.postDataJSON();
   if(body.rawPrompt!==cases[0].rawPrompt||body.modelId!==cases[0].modelId||(body.context?.files?.length??0)!==0||body.enhancement?.includeExamples===true){await route.abort();return;}
   requests.push({path,body});await route.continue();
  });
  page.on('response',async response=>{
   const path=new URL(response.url()).pathname;
   if(response.request().method()!=='POST')return;
   if(path==='/api/v1/optimizations/plan')planResult={status:response.status(),body:await response.json()};
   if(path==='/api/v1/optimizations')finalResult={status:response.status(),body:await response.json()};
  });
  await page.getByRole('button',{name:'先确认并增强',exact:true}).click();
  for(let i=0;i<240;i++){
   if(finalResult||planResult?.status>=400||planResult?.body.data?.questions?.length>0)break;
   await page.waitForTimeout(1000);
  }
  await save(resolve(work,'ui-requests.json'),requests);
  if(planResult)await save(resolve(work,'EXISTING-PLAN/platform.json'),planResult);
  if(finalResult){
   await save(resolve(work,'EXISTING-PLAN-ENHANCE/platform.json'),finalResult);
   if(finalResult.status===200)await copyAndHistory('EXISTING-PLAN-ENHANCE',finalResult,cases[0].rawPrompt);
  }
  await save(resolve(work,'ui-check.json'),{planStatus:planResult?.status,planQuestions:planResult?.body.data?.questions,enhancementStatus:finalResult?.status,automaticFinalGeneration:!!finalResult,planConfirmationSent:requests.at(-1)?.body.planConfirmation,actualUiFlow:true});
  console.log(JSON.stringify({mode,planStatus:planResult?.status,questions:planResult?.body.data?.questions,enhancementStatus:finalResult?.status,requestCount:requests.length}));
 }else if(mode==='choice-plan'){
  await save(resolve(work,'choice-reserved.json'),{scheduledOnce:true});
  const body={rawPrompt:cases[1].rawPrompt,contextDescription:'',conversationHistory:[],planningContext:null,modelId:cases[1].modelId};
  await save(resolve(work,'USER-CHOICE-PLAN/input.json'),body);
  const r=await api('/api/v1/optimizations/plan',body);await save(resolve(work,'USER-CHOICE-PLAN/platform.json'),r);
  console.log(JSON.stringify({mode,status:r.status,questions:r.body.data?.questions}));
 }else if(mode==='direct'){
  await save(resolve(work,'direct-reserved.json'),{scheduledOnce:true});
  const body=input(cases[0]);await save(resolve(work,'EXISTING-DIRECT/input.json'),body);
  const r=await api('/api/v1/optimizations',body);await save(resolve(work,'EXISTING-DIRECT/platform.json'),r);
  if(r.status===200){
   await page.evaluate(async({result,raw})=>{const {useOptimizationStore}=await import('/src/stores/optimization.ts');const store=useOptimizationStore();store.rawPrompt=raw;store.applyReoptimized({result});},{result:r.body.data,raw:cases[0].rawPrompt});
   await copyAndHistory('EXISTING-DIRECT',r,cases[0].rawPrompt);
  }
  console.log(JSON.stringify({mode,status:r.status,requestId:r.body.requestId,ambiguities:r.body.data?.ambiguities}));
 }else throw Error('Unknown frozen operation');
}finally{await page.close();await browser.close();}
