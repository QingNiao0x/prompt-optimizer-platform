// Real browser + simulated local-capacity error. No model I/O and no history mutation.
import {createRequire} from 'node:module';
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
const require=createRequire(resolve('apps/web/package.json'));
const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
const page=await browser.contexts()[0].newPage();
let intercepted=0;
try{
  await page.route('**/api/v1/optimizations**',async route=>{
    intercepted++;
    await route.fulfill({status:400,contentType:'application/json',json:{requestId:'offline-capacity-preservation',
      error:{code:'MODEL_CAPACITY_EXCEEDED',message:'本次需求与材料超过所选模型的安全请求预算，请减少非必要材料后重试。',
        retryable:false,details:{upstreamCalled:false,countMethod:'UTF8_BYTES_ESTIMATE_V1'}}}});
  });
  await page.goto('http://127.0.0.1:5174/workbench',{waitUntil:'domcontentloaded'});
  await page.locator('.workbench-grid').waitFor({state:'visible',timeout:30000});
  const source=JSON.parse(await readFile('tmp/me00a-candidate21-live-20261016/real/requests-title/platform.json','utf8')).body.data;
  await page.evaluate(async result=>{
    const{useOptimizationStore}=await import('/src/stores/optimization.ts');
    const store=useOptimizationStore();store.resetTask();store.rawPrompt='使用requests库爬取网页标题。';store.applyReoptimized({result});
  },source);
  await page.locator('.copy-main-button').waitFor({state:'visible'});
  const state=await page.evaluate(async()=>{
    const{useOptimizationStore}=await import('/src/stores/optimization.ts');
    const store=useOptimizationStore(),before=store.result.optimizedPrompt,raw=store.rawPrompt;
    const success=await store.runOptimization();
    return{success:Boolean(success),rawPreserved:store.rawPrompt===raw,resultPreserved:store.result?.optimizedPrompt===before,error:store.errorMessage};
  });
  if(state.success||!state.rawPreserved||!state.resultPreserved||intercepted!==1)throw new Error('Capacity failure preservation check failed');
  await writeFile('docs/testing/evidence/me00a-candidate21-live-2026-10-16/browser-capacity-preservation.json',
    JSON.stringify({kind:'REAL_BROWSER_WITH_SIMULATED_400',...state,intercepted,newModelCalls:0,newHistories:0},null,2),{flag:'wx'});
  console.log('Browser capacity error preserves raw prompt and result; 0 model calls');
}finally{await page.close();await browser.close();}
