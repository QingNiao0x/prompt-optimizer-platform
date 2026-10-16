// Exercise current UI with frozen assembler outputs. All generation requests are intercepted.
import {createRequire} from 'node:module';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
const require=createRequire(resolve('apps/web/package.json'));
const out=resolve('docs/testing/evidence/me00a-candidate17-live-2026-10-16/browser-offline');
await mkdir(out,{recursive:true});
const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
const context=browser.contexts()[0],origin='http://127.0.0.1:5173';
await context.grantPermissions(['clipboard-read','clipboard-write'],{origin});
const page=await context.newPage(),checks=[],mockedRequests=[];
const hash=s=>createHash('sha256').update(s).digest('hex');
const save=(name,value)=>writeFile(resolve(out,name),typeof value==='string'?value:JSON.stringify(value,null,2),{flag:'wx'});
const same=(a,b)=>a.replace(/\r\n/g,'\n')===b.replace(/\r\n/g,'\n');
const assert=(value,label)=>{if(!value)throw new Error(label);};
const storeRead=()=>page.evaluate(async()=>{const {useOptimizationStore}=await import('/src/stores/optimization.ts');const s=useOptimizationStore();return{result:s.result,draft:s.resultDraft,error:s.errorMessage};});
const nativeCopy=async expected=>{
  await page.bringToFront();await page.locator('.copy-main-button').click();
  await page.getByRole('button',{name:'已复制',exact:true}).waitFor({timeout:10000});
  const value=await page.evaluate(()=>navigator.clipboard.readText());
  assert(same(value,expected),'Native clipboard changed content beyond line endings');return value;
};
try {
  await page.route('**/api/v1/optimizations**',async route=>{
    mockedRequests.push({path:new URL(route.request().url()).pathname,method:route.request().method()});
    await route.fulfill({status:502,contentType:'application/json',json:{requestId:'offline-failure-preservation',error:{code:'PROVIDER_INVALID_RESPONSE',message:'离线失败保稿验证'}}});
  });
  await page.goto(origin+'/workbench',{waitUntil:'domcontentloaded'});
  await page.locator('.workbench-grid').waitFor({state:'visible',timeout:30000});
  for(const trace of [254,241]) {
    const result=JSON.parse(await readFile(`docs/testing/evidence/me00a-candidate17-2026-10-16/trace${trace}-assembled.json`,'utf8'));
    await page.evaluate(async result=>{const {useOptimizationStore}=await import('/src/stores/optimization.ts');const s=useOptimizationStore();s.resetTask();s.rawPrompt='离线结果展示检查';s.applyReoptimized({result});},result);
    const copied=await nativeCopy(result.optimizedPrompt);
    await save(`trace${trace}-native-copy.md`,copied);
    checks.push({trace,check:'NATIVE_COPY',pass:true,sourceKind:'FROZEN_OFFLINE_ASSEMBLER_RESULT',copySha256:hash(copied),sourceSha256:hash(result.optimizedPrompt),onlyWindowsLineEndingsMayDiffer:true});
    await page.getByRole('button',{name:'编辑',exact:true}).click();
    const originalTask=result.sections.find(s=>s.type==='TASK').content;
    const originalBackground=result.sections.find(s=>s.type==='BACKGROUND').content;
    const changedBackground=originalBackground+'\n离线编辑保稿检查。';
    await page.locator('#section-BACKGROUND').fill(changedBackground);
    await page.locator('#section-TASK').fill('');
    await page.getByRole('button',{name:'保存修改',exact:true}).click();
    await page.getByRole('alert').filter({hasText:'不能为空'}).waitFor({timeout:10000});
    assert(await page.locator('#section-BACKGROUND').inputValue()===changedBackground,'Validation lost another edited section');
    checks.push({trace,check:'INVALID_EDIT_PRESERVES_OTHER_DRAFTS',pass:true});
    await page.locator('#section-TASK').fill(originalTask);
    await page.getByRole('button',{name:'保存修改',exact:true}).click();
    await page.getByRole('button',{name:'编辑',exact:true}).waitFor({timeout:10000});
    const after=await storeRead();
    assert(after.result.optimizedPrompt.includes('离线编辑保稿检查。'),'Saved edit missing');
    assert(JSON.stringify(after.result.appliedConstraints)===JSON.stringify(result.appliedConstraints),'Edit lost constraint provenance');
    await nativeCopy(after.result.optimizedPrompt);
    checks.push({trace,check:'SAVED_EDIT_COPY_AND_CONSTRAINT_PROVENANCE',pass:true});
    const undone=await page.evaluate(async()=>{const {useOptimizationStore}=await import('/src/stores/optimization.ts');return useOptimizationStore().undoResult();});
    assert(undone&&(await storeRead()).result.optimizedPrompt===result.optimizedPrompt,'Store undo did not restore original');
    await nativeCopy(result.optimizedPrompt);
    checks.push({trace,check:'STORE_UNDO_RESTORES_COMPLETE_RESULT',pass:true,scope:'Invoked existing store method; not a visible undo-button acceptance'});
    const outcome=await page.evaluate(async()=>{const {useOptimizationStore}=await import('/src/stores/optimization.ts');return useOptimizationStore().runOptimization();});
    const failed=await storeRead();
    assert(!outcome&&failed.result.optimizedPrompt===result.optimizedPrompt,'Simulated failed generation discarded saved result');
    checks.push({trace,check:'SIMULATED_502_PRESERVES_RESULT',pass:true,scope:'HTTP response intercepted in browser; not real Provider recovery'});
    await page.locator('.result-panel').screenshot({path:resolve(out,`trace${trace}-panel.png`)});
  }
  assert(mockedRequests.length===2,'Unexpected generation request count');
  await save('verification.json',{checks,passed:checks.length,failed:0,mockedRequests,newRealModelCalls:0,newAccounts:0,newDatabaseHistories:0,fullNaturalPlan:'NOT_RUN',liveCandidateWorks:'NOT_RUN'});
  console.log(JSON.stringify({browserChecks:checks.length,passed:checks.length,realModelCalls:0,simulated502:mockedRequests.length}));
} catch(error) {
  await save('failure.json',{name:error.name,message:error.message,completedChecks:checks,mockedRequests,newRealModelCalls:0});
  throw error;
} finally {await page.close();await browser.close();}
