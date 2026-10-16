// Replay saved real API results in the existing result panel; block all new generation requests.
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
const require=createRequire(resolve('apps/web/package.json'));
const root=resolve('tmp/me00a-candidate16-20261016/real');
const load=async p=>JSON.parse(await readFile(p,'utf8'));
const hash=b=>createHash('sha256').update(b).digest('hex');
const save=(p,v)=>writeFile(p,typeof v==='string'?v:JSON.stringify(v,null,2),{flag:'wx'});
const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
const context=browser.contexts()[0],frontend='http://localhost:5173';
const source=context.pages().find(p=>p.url().startsWith('http://127.0.0.1:9001/'));
if(!source)throw new Error('Authenticated local source missing');
const cookies=await context.cookies('http://127.0.0.1:9001/');
if(!cookies.some(c=>c.httpOnly))throw new Error('Same-account session missing');
await context.addCookies(cookies.map(({name,value,path,httpOnly,secure,sameSite,expires})=>({name,value,domain:'localhost',path,httpOnly,secure,sameSite,expires})));
await context.grantPermissions(['clipboard-read','clipboard-write'],{origin:frontend});
const statuses=[],blocked=[];
const routeHandler=async route=>{
  const request=route.request(),url=new URL(request.url());
  if(request.method()!=='GET'&&!url.pathname.startsWith('/api/v1/analytics/')){blocked.push(url.pathname);await route.abort('blockedbyclient');return;}
  const response=await route.fetch({url:'http://localhost:9001'+url.pathname+url.search,
    headers:{...request.headers(),cookie:cookies.map(c=>`${c.name}=${c.value}`).join('; ')}});
  statuses.push({path:url.pathname,status:response.status(),method:request.method()});await route.fulfill({response});
};
await context.route('**/api/**',routeHandler);
const page=await context.newPage(),checks=[];
try{
  await page.goto(frontend+'/workbench',{waitUntil:'domcontentloaded'});
  await page.locator('.workbench-grid').waitFor({state:'visible',timeout:30000});
  const lock=await load(resolve(root,'copies-lock.json'));
  await save(resolve(root,'copies-lock-before-native-copy.json'),lock);
  for(const id of ['requests-title','mortality-2015-2025']){
    const folder=resolve(root,id),path=id==='requests-title'?resolve(folder,'recovered-after-harness/platform.json'):resolve(folder,'platform.json');
    const platform=await load(path),input=await load(resolve(folder,'input.json'));
    if(platform.status!==200)throw new Error('No successful API result to copy');
    await page.evaluate(async({result,raw})=>{
      const {useOptimizationStore}=await import('/src/stores/optimization.ts');
      const store=useOptimizationStore();store.rawPrompt=raw;store.applyReoptimized({result});
    },{result:platform.body.data,raw:input.rawPrompt});
    const button=page.locator('.copy-main-button');await button.waitFor({state:'visible',timeout:15000});
    await page.bringToFront();await button.click();await page.getByRole('button',{name:'已复制',exact:true}).waitFor({state:'visible',timeout:10000});
    const copied=await page.evaluate(()=>navigator.clipboard.readText());
    const api=platform.body.data.optimizedPrompt;
    if(copied.replace(/\r\n/g,'\n')!==api.replace(/\r\n/g,'\n'))throw new Error('Clipboard differs beyond Windows line endings');
    await save(resolve(folder,'clipboard-observed.md'),copied);
    await writeFile(resolve(folder,'execution-input.md'),copied);
    lock[id]={...lock[id],apiCopiedSha256:hash(api),enhancedSha256:hash(copied),enhancedInputKind:'NATIVE_CLIPBOARD'};
    checks.push({id,byteForByteEqual:copied===api,onlyLineEndingsDiffer:copied!==api,
      clipboardSha256:hash(copied),apiSha256:hash(api),executedBodyWillBeExactObservedClipboard:true});
    await page.locator('.result-panel').screenshot({path:resolve(folder,'real-copy.png')});
    console.log(`COPIED actual platform result ${id}`);
  }
  if(blocked.length)throw new Error('Unexpected write was blocked');
  await writeFile(resolve(root,'copies-lock.json'),JSON.stringify(lock,null,2));
  await save(resolve(root,'native-copy-checks.json'),{checks,statuses,blocked,newModelCalls:0,newAccounts:0,
    scope:'Real API result replayed through current workbench copy handler; history separately checked, no full edit lifecycle claim'});
}finally{await page.close();await context.unroute('**/api/**',routeHandler);await browser.close();}
