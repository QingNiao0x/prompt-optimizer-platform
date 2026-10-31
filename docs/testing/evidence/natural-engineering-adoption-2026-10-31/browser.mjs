/** 普通工作台验收：真实登录、目录索引、正常增强接口、剪贴板与历史；仅加载本轮合成目录。 */
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createInterface} from 'node:readline/promises';
import {isDeepStrictEqual} from 'node:util';
const dir=dirname(fileURLToPath(import.meta.url)),repo=resolve(dir,'../../../..');
const runtime=resolve(repo,'tmp/natural-engineering-adoption-2026-10-31/browser');
const output=resolve(dir,'product'),frontend='http://127.0.0.1:5198';
const json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const save=(p,value)=>writeFile(p,JSON.stringify(value,null,2)+'\n',{flag:'wx'});
const cases=(await json(resolve(dir,'cases.json'))).cases;
try{await access(resolve(dir,'closure.json'));throw Error('Batch closed');}catch(e){if(e.code!=='ENOENT')throw e;}
const account=await json(resolve(repo,'tmp/local-acceptance/account.json'));
let password=process.env.POP_EVAL_PASSWORD;delete process.env.POP_EVAL_PASSWORD;
assert.ok(account.identifier.startsWith('qa_')&&password);
await mkdir(runtime,{recursive:true});await mkdir(output,{recursive:true});
const require=createRequire(resolve(repo,'apps/web/package.json'));
const {chromium,expect}=require('@playwright/test');
console.log(JSON.stringify({event:'BROWSER_SETUP',port:5198}));
const {createServer}=await import(new URL('../../../../apps/web/node_modules/vite/dist/node/index.js',import.meta.url));
const server=await createServer({root:resolve(repo,'apps/web'),logLevel:'error',server:{host:'127.0.0.1',port:5198,strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:9000',changeOrigin:true}}}});
await server.listen();
console.log(JSON.stringify({event:'VITE_READY',port:5198}));
const browser=await chromium.launch({channel:'chrome',headless:true});
console.log(JSON.stringify({event:'BROWSER_READY'}));
const context=await browser.newContext({permissions:['clipboard-read','clipboard-write'],viewport:{width:1440,height:1000},locale:'zh-CN'});
const page=await context.newPage(),lines=createInterface({input:process.stdin,output:process.stdout,terminal:false});
try{
  await page.goto(frontend+'/login');
  console.log(JSON.stringify({event:'LOGIN_PAGE_READY'}));
  await context.request.get(frontend+'/api/v1/auth/csrf');
  let authenticated=false;
  for(let attempt=1;attempt<=3;attempt++){
    const captchaResponse=await context.request.get(frontend+'/api/v1/auth/captcha');assert.equal(captchaResponse.status(),200);
    const path=resolve(runtime,`captcha-${Date.now()}.png`);await writeFile(path,await captchaResponse.body(),{flag:'wx'});
    console.log(JSON.stringify({event:'CAPTCHA',path}));
    const captcha=(await lines.question('')).trim();assert.match(captcha,/^[a-zA-Z0-9]{4,8}$/);
    const token=(await context.cookies()).find(x=>x.name==='XSRF-TOKEN')?.value;
    const response=await context.request.post(frontend+'/api/v1/auth/login',{headers:{'X-XSRF-TOKEN':decodeURIComponent(token??'')},data:{identifier:account.identifier,password,captcha}});
    if(response.status()===200){authenticated=true;break;}
    const body=await response.json();assert.equal(body.error?.code,'CAPTCHA_INVALID','Synthetic account login failed');
  }
  password=undefined;assert.ok(authenticated);lines.close();
  const actor=(await (await context.request.get(frontend+'/api/v1/auth/me')).json()).data;
  for(const key of ['userId','tenantId','workspaceId'])assert.equal(actor[key],account[key]);
  const catalog=(await (await context.request.get(frontend+'/api/v1/models')).json()).data;
  const model=catalog.find(x=>x.id==='deepseek:deepseek-flash');assert.equal(model?.provider,'deepseek');
  await save(resolve(output,'session-check-'+Date.now()+'.json'),{canonicalSyntheticAccount:true,endpoint:'http://127.0.0.1:9000',modelId:model.id,provider:model.provider,modelCallsMaximum:8,maxCallsPerBusinessRequest:4,authSecretsRecorded:false});
  for(const sample of cases){
    let count=0,responseRecord;
    await page.addInitScript(async({files,id})=>{
      localStorage.setItem('prompt-optimizer.plan-mode.v1',JSON.stringify({enabled:false,introSeen:true}));
      const storage=await navigator.storage.getDirectory(),root=await storage.getDirectoryHandle('synthetic-'+id,{create:true});
      for(const file of files){let d=root;const parts=file.path.split('/');for(const p of parts.slice(0,-1))d=await d.getDirectoryHandle(p,{create:true});const handle=await d.getFileHandle(parts.at(-1),{create:true}),writer=await handle.createWritable();await writer.write(file.content);await writer.close();}
      Object.defineProperty(window,'showDirectoryPicker',{configurable:true,value:async()=>root});
    },{files:sample.context.files,id:sample.id});
    const raw=sample.rawPrompt+'\n\n'+sample.context.customDescription+'\n'+sample.executionContract;
    const routeHandler=async route=>{
      const request=route.request();if(request.method()!=='POST'){await route.continue();return;}
      count++;assert.equal(count,1,'No business quality retries');
      const input=request.postDataJSON();assert.equal(input.rawPrompt,raw);assert.equal(input.modelId,'deepseek:deepseek-flash');
      const files=input.context?.files??[];assert.ok(files.length>0);
      // 当前本地检索把片段标记为 path#chunk-N；本夹具每文件仅一片，只接受已观察到的第一片。
      const relative=path=>path.endsWith('#chunk-1')?path.slice(0,-8):path;
      console.log(JSON.stringify({event:'SELECTED_SYNTHETIC_PATHS',paths:files.map(f=>f.path)}));
      for(const f of files){const approved=sample.context.files.find(x=>x.path===relative(f.path));assert.ok(approved,'Unapproved path');assert.ok(approved.content.includes(f.content),'Unapproved source content');}
      if(sample.id==='DATE')for(const path of ['src/people/date.mjs','src/people/create.mjs','src/people/import.mjs'])assert.ok(files.some(x=>relative(x.path)===path),'Missing target '+path);
      await save(resolve(output,sample.id+'-input.json'),input);
      // 不替换接口或响应。每次请求在出站前记账，至多两次业务请求，各自最多四次生产调用。
      await save(resolve(output,sample.id+'-started.json'),{startedAt:new Date().toISOString(),reservedActualCalls:4});
      const response=await route.fetch({maxRetries:0,maxRedirects:0,timeout:240000});
      responseRecord={status:response.status(),requestId:response.headers()['x-request-id'],body:await response.json()};
      await save(resolve(output,sample.id+'-response.json'),responseRecord);await route.fulfill({response});
    };
    await page.route('**/api/v2/optimizations',routeHandler);
    await page.goto(frontend+'/workbench');
    await expect(page.getByLabel('原始提示词',{exact:true})).toBeVisible({timeout:30000});
    await page.locator('.model-control .el-select').click();
    await page.getByRole('option',{name:model.displayName.trim(),exact:true}).click();
    await page.getByRole('button',{name:'添加上下文',exact:true}).click();
    await page.getByRole('menuitem',{name:'选择文件夹',exact:true}).click();
    await expect(page.getByText(`${sample.context.files.length} 个文本文件已完整建立本地索引`,{exact:true})).toBeVisible({timeout:30000});
    await page.getByLabel('原始提示词',{exact:true}).fill(raw);
    await page.getByRole('button',{name:'直接增强提示词',exact:true}).click();
    const consent=page.getByRole('dialog',{name:'确认发送上下文',exact:true});
    await expect(consent).toBeVisible();await consent.getByRole('button',{name:'确认发送',exact:true}).click();
    await expect.poll(()=>responseRecord?.status,{timeout:240000}).toBe(200);
    const generated=responseRecord.body.data;
    assert.equal(generated.chainVersion,'core-repair-v3');assert.equal(generated.provider.mock,false);
    assert.ok(generated.processing.completedStages.includes('BODY_GENERATION'));
    assert.ok(!generated.optimizedPrompt.includes('提供的材料：'));assert.ok(!generated.optimizedPrompt.includes('export function'));
    await expect(page.getByTestId('compact-prompt')).toHaveText(generated.optimizedPrompt);
    await page.bringToFront();await page.locator('.copy-main-button').click();
    await expect(page.locator('.copy-main-button')).toContainText('已复制');
    const copied=(await page.evaluate(()=>navigator.clipboard.readText())).replace(/\r\n/g,'\n');
    assert.equal(copied,generated.optimizedPrompt.replace(/\r\n/g,'\n'));
    const history=(await (await context.request.get(frontend+'/api/v2/optimization-history/'+generated.recordId)).json()).data;
    assert.equal(history.optimizedPrompt,generated.optimizedPrompt);assert.equal(history.chainVersion,generated.chainVersion);assert.ok(isDeepStrictEqual(history.processing,generated.processing));
    await page.screenshot({path:resolve(output,sample.id+'-workbench.png'),fullPage:true});
    await page.goto(frontend+'/history');await page.getByRole('button',{name:'查看',exact:true}).filter({visible:true}).first().click();
    await expect(page.getByRole('dialog',{name:'历史记录详情',exact:true}).locator('pre').nth(1)).toHaveText(generated.optimizedPrompt);
    await page.screenshot({path:resolve(output,sample.id+'-history.png'),fullPage:true});
    await save(resolve(output,sample.id+'-verdict.json'),{caseId:sample.id,defaultVersion:generated.chainVersion,completedStages:generated.processing.completedStages,providerMock:generated.provider.mock,businessRequests:count,displayCopyHistoryEqual:true,contextFiles:sample.context.files.length,copiedLength:copied.length,historyRecordId:generated.recordId});
    console.log(JSON.stringify({event:'PRODUCT_VERIFIED',caseId:sample.id,chainVersion:generated.chainVersion}));
    await page.unroute('**/api/v2/optimizations',routeHandler);
    // 新页面隔离下一题的目录选择器与待编辑文本，仍使用同一个合法登录会话。
    if(sample!==cases.at(-1)){console.log('PRODUCT_NEXT_CASE_READY');break;}
  }
}catch(e){await save(resolve(output,'failure-'+Date.now()+'.json'),{name:e.name,message:e.message});throw e;}
finally{password=undefined;lines.close();await browser.close();await server.close();}
