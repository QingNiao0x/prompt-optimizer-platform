import {createRequire} from 'node:module';
import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import {resolve} from 'node:path';
const root=resolve('tmp/enhancement-quality-20261012');
const [version='baseline',ids='all',round='1',models='flash,pro']=process.argv.slice(2);
if(!/^[a-z0-9-]+$/.test(version)||!/^\d+$/.test(round))throw new Error('Invalid run');
const require=createRequire(new URL('../../apps/web/package.json',import.meta.url));
const {chromium}=require('@playwright/test');
const browser=await chromium.connectOverCDP('http://127.0.0.1:9325');
const context=browser.contexts()[0];const page=context.pages().find(p=>p.url().startsWith('http://127.0.0.1:9001/'));
if(!page)throw new Error('Authenticated isolated browser required');
const cases=JSON.parse(await readFile(resolve(root,'cases.json'),'utf8')).filter(c=>ids==='all'||ids.split(',').includes(c.id));
const save=async(path,data)=>writeFile(path,typeof data==='string'?data:JSON.stringify(data,null,2),{flag:'wx'});
async function api(path,body){return page.evaluate(async({path,body})=>{const token=document.cookie.split('; ').find(x=>x.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');const start=performance.now();const r=await fetch(path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{'X-XSRF-TOKEN':decodeURIComponent(token)}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(240000)});return {status:r.status,elapsedMs:Math.round(performance.now()-start),body:await r.json()};},{path,body});}
async function uiEnhance(request,dir){
 const ui=context.pages().find(p=>p.url()==='http://127.0.0.1:5177/workbench');
 if(!ui)throw new Error('Same-account workbench tab required');
 await ui.reload();await ui.getByLabel('原始提示词',{exact:true}).fill(request.rawPrompt);
 const uiIdentity=await ui.evaluate(async()=>{const r=await fetch('/api/v1/auth/me');return {status:r.status,body:await r.json()};});
 const expectedIdentity=await api('/api/v1/auth/me');
 if(uiIdentity.status!==200||uiIdentity.body.data.userId!==expectedIdentity.body.data.userId)throw new Error('Browser account differs');
 for(const name of ['Plan 确认','要求回答包含示例']) {const s=ui.getByRole('switch',{name,exact:true});if(await s.getAttribute('aria-checked')==='true')await s.click();}
 const responsePromise=ui.waitForResponse(r=>r.url().endsWith('/api/v1/optimizations')&&r.request().method()==='POST',{timeout:240000});
 const start=performance.now();await ui.getByRole('button',{name:'直接增强提示词',exact:true}).click();
 const response=await responsePromise;const result={status:response.status(),elapsedMs:Math.round(performance.now()-start),body:await response.json()};
 await save(resolve(dir,'browser-response.json'),result);
 await save(resolve(dir,'browser-request.json'),response.request().postDataJSON());
 if(result.status!==200)return result;
 if(response.request().postDataJSON().modelId!==request.modelId)throw new Error('Browser selected a different model');
 const display=ui.getByTestId('compact-prompt');await display.waitFor();
 if(await display.textContent()!==result.body.data.optimizedPrompt)throw new Error('Rendered body differs');
 const session=await context.newCDPSession(ui);const {targetInfo}=await session.send('Target.getTargetInfo');const bs=await browser.newBrowserCDPSession();
 await bs.send('Browser.grantPermissions',{origin:'http://127.0.0.1:5177',permissions:['clipboardReadWrite','clipboardSanitizedWrite'],browserContextId:targetInfo.browserContextId});
 await ui.getByRole('button',{name:'复制提示词',exact:true}).click();
 const clipboard=await ui.evaluate(()=>navigator.clipboard.readText());await save(resolve(dir,'browser-clipboard.md'),clipboard);
 if(clipboard.replace(/\r\n/g,'\n')!==result.body.data.optimizedPrompt.replace(/\r\n/g,'\n'))throw new Error('Clipboard differs from actual response beyond Windows newline conversion');
 await ui.screenshot({path:resolve(dir,'browser-compact.png'),fullPage:true});
 await ui.getByRole('button',{name:'编辑',exact:true}).click();const background=ui.locator('#section-BACKGROUND');const task=ui.locator('#section-TASK');
 const originalBackground=await background.inputValue();const draft=(await task.inputValue())+'\n语气保持友好。';
 await task.fill(draft);await background.fill('');await ui.getByRole('button',{name:'保存修改',exact:true}).click();
 await ui.getByText(/标题和内容不能为空，其他修改已保留/).first().waitFor();
 if(await task.inputValue()!==draft)throw new Error('Failed save lost other section edits');
 await background.fill(originalBackground);await ui.getByRole('button',{name:'保存修改',exact:true}).click();await display.waitFor();
 if(!(await display.textContent()).includes('语气保持友好。')||(await display.textContent()).includes('## 任务'))throw new Error('Compact edit was not preserved');
 await save(resolve(dir,'browser-checks.json'),{sameAccount:true,actualRequest:true,renderEqualsResponse:true,clipboardEqualsResponseAfterOnlyCrlfNormalization:true,failedSaveRetainsAllDraft:true,successfulEditKeepsCompact:true,undo:'covered by store regression; no UI entry'});
 return result;
}
async function downstream(prompt,model,label){
 let path;
  for(let i=1;i<=310;i++){
  const prefix=String(i).padStart(3,'0');
  try{await access(resolve(root,'traces',`${prefix}-enhancement.json`));continue;}catch{}
  path=resolve(root,'traces',`${prefix}-downstream.json`);try{await save(path,{label,startedAt:new Date().toISOString()});break;}catch{path=undefined;}
 }
 if(!path)throw new Error('Budget exhausted');
 const request={model:model==='flash'?'deepseek-flash':'deepseek-v4-pro',messages:[{role:'user',content:prompt}],temperature:0.2,max_tokens:8192,thinking:{type:'disabled'},stream:false};
 const start=performance.now();const response=await fetch('https://api.deepseek.com/chat/completions',{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${process.env.MODEL_DEEPSEEK_API_KEY||process.env.MODEL_API_KEY}`},body:JSON.stringify(request),signal:AbortSignal.timeout(240000)});
 const result=await response.json();for(const choice of result.choices??[])delete choice.message?.reasoning_content;
 const evidence={label,httpStatus:response.status,elapsedMs:Math.round(performance.now()-start),request,response:result};await writeFile(path,JSON.stringify(evidence,null,2));
 if(!response.ok)throw new Error(`Downstream HTTP ${response.status}`);
 return {text:result.choices?.[0]?.message?.content??'',trace:path,finishReason:result.choices?.[0]?.finish_reason};
}
try{
 const me=await api('/api/v1/auth/me');if(me.status!==200)throw new Error('Normal login required');
 await api('/api/v1/auth/csrf');
 const catalog=await api('/api/v1/models');
 const run=resolve(root,`${version}-r${round}`);await mkdir(run,{recursive:true});
 try{await save(resolve(run,'identity.json'),{status:me.status,identity:me.body.data});await save(resolve(run,'catalog.json'),catalog);}catch(e){if(e.code!=='EEXIST')throw e;}
 for(const c of cases)for(const model of models.split(',')){
  const label=`${c.id}-${model}`;const dir=resolve(run,label);await mkdir(dir);
  const request={rawPrompt:c.rawPrompt,context:{customDescription:c.context.description,files:c.context.files},enhancement:c.enhancement,conversationHistory:c.conversationHistory,modelId:model==='flash'?'deepseek:deepseek-flash':'deepseek:deepseek-v4-pro'};
  await save(resolve(dir,'input.json'),request);
  console.log(`BEGIN ${version} ${label} round=${round}`);
  try{
   const result=['candidate4','candidate5'].includes(version)&&round==='2'&&c.id==='Q03'&&model==='flash' ? await uiEnhance(request,dir) : await api('/api/v1/optimizations',request);await save(resolve(dir,'platform.json'),result);
   if(result.status!==200)throw new Error(`Enhancement HTTP ${result.status} ${result.body.error?.code} ${result.body.error?.details?.validationReason??''}`);
   const copied=result.body.data.optimizedPrompt;await save(resolve(dir,'copied.md'),copied);
   const history=await api('/api/v1/optimization-history?current=1&size=10');
   const saved=history.body.data?.records?.find(row=>row.rawPromptPreview===c.rawPrompt.slice(0,120))??history.body.data?.records?.[0];
   if(saved){const detail=await api(`/api/v1/optimization-history/${saved.id}`);await save(resolve(dir,'history.json'),detail);if(detail.status!==200||detail.body.data.optimizedPrompt!==copied)throw new Error('History copied-body mismatch');}
   const work=await downstream(copied,model,`${version}-r${round}-${label}`);
   await save(resolve(dir,'work.md'),work.text);await save(resolve(dir,'execution.json'),work);
   console.log(`DONE ${label} copyChars=${copied.length} workChars=${work.text.length} finish=${work.finishReason}`);
  }catch(e){await save(resolve(dir,'failure.json'),{message:e.message});console.log(`FAILED ${label} ${e.message}`);}
 }
}catch(error){console.error(error.message);process.exitCode=1;}finally{process.stdout.write('',()=>process.exit(process.exitCode??0));}
