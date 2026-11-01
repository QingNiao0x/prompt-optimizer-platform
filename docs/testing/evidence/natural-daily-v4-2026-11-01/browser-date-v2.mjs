/** 通过既有5173/9000日常入口验证；浏览器只选择冻结的合成目录，API响应与历史均来自真实服务。 */
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createInterface} from 'node:readline/promises';
import {randomUUID} from 'node:crypto';
import {dir,repo,runtime,json,save,frozen,reserve,settleProduct} from './common.mjs';
const protocol=await frozen(),samples=(await json(resolve(dir,'cases.json'))).cases.filter(sample=>sample.id==='DATE');
const account=await json(resolve(repo,'tmp/local-acceptance/account.json'));
let password=process.env.POP_EVAL_PASSWORD;delete process.env.POP_EVAL_PASSWORD;
assert(account.identifier.startsWith('qa_')&&password);
await mkdir(runtime,{recursive:true});
const {chromium,expect}=createRequire(resolve(repo,'apps/web/package.json'))('@playwright/test');
const browser=await chromium.launch({channel:'chrome',headless:true});
const context=await browser.newContext({permissions:['clipboard-read','clipboard-write'],viewport:{width:1440,height:1000},locale:'zh-CN'});
const lines=createInterface({input:process.stdin,output:process.stdout,terminal:false}),front=protocol.frontend;
let page=await context.newPage();
try{
  await page.goto(front+'/login');await context.request.get(front+'/api/v1/auth/csrf');
  let authenticated=false;
  for(let attempt=0;attempt<3;attempt++){
    const response=await context.request.get(front+'/api/v1/auth/captcha');assert.equal(response.status(),200);
    const path=resolve(runtime,'captcha-'+Date.now()+'.png');await writeFile(path,await response.body(),{flag:'wx'});
    console.log(JSON.stringify({event:'CAPTCHA',path}));
    const captcha=(await lines.question('')).trim();assert.match(captcha,/^[A-Za-z0-9]{4,8}$/);
    const token=(await context.cookies()).find(c=>c.name==='XSRF-TOKEN')?.value;
    const login=await context.request.post(front+'/api/v1/auth/login',{headers:{'X-XSRF-TOKEN':decodeURIComponent(token??'')},data:{identifier:account.identifier,password,captcha}});
    if(login.status()===200){authenticated=true;break;}
    assert.equal((await login.json()).error?.code,'CAPTCHA_INVALID','Fixed synthetic account login failed');
  }
  password=undefined;lines.close();assert(authenticated);
  const actor=(await (await context.request.get(front+'/api/v1/auth/me')).json()).data;
  for(const field of ['userId','tenantId','workspaceId'])assert.equal(actor[field],account[field]);
  const catalog=(await (await context.request.get(front+'/api/v1/models')).json()).data;
  const model=catalog.find(m=>m.id==='deepseek:deepseek-flash');assert.equal(model?.provider,'deepseek');
  await save(resolve(dir,'product/DATE-session.json'),{canonicalSyntheticAccount:true,frontend:front,backend:protocol.backend,modelId:model.id,authSecretsSaved:false});
  for(const sample of samples){
    if(page)await page.close();page=await context.newPage();
    const records={},seen={},output=resolve(dir,'product');let routeError;
    await page.addInitScript(async sample=>{
      localStorage.setItem('prompt-optimizer.plan-mode.v1',JSON.stringify({enabled:sample.planEnabled,introSeen:true}));
      if(sample.context.files.length){
        const storage=await navigator.storage.getDirectory(),root=await storage.getDirectoryHandle('synthetic-daily-'+sample.id,{create:true});
        for(const file of sample.context.files){let folder=root;const parts=file.path.split('/');for(const part of parts.slice(0,-1))folder=await folder.getDirectoryHandle(part,{create:true});const handle=await folder.getFileHandle(parts.at(-1),{create:true}),writer=await handle.createWritable();await writer.write(file.content);await writer.close();}
        Object.defineProperty(window,'showDirectoryPicker',{configurable:true,value:async()=>root});
      }
      window.__dailyFixtureReady=true;
    },sample);
    const inspect=async route=>{
      const request=route.request();if(request.method()!=='POST'){await route.continue();return;}
      const path=new URL(request.url()).pathname;
      const kind=path.endsWith('/context/planning')?'context':path.endsWith('/revise')?'revision':path.endsWith('/plan')?'plan':'generation';
      try{
        seen[kind]=(seen[kind]??0)+1;assert.equal(seen[kind],1,'No business quality retries');
        const input=request.postDataJSON();
        if(kind==='revision')assert.equal(input.feedback,sample.feedback);
        else assert.equal(input.rawPrompt,sample.rawPrompt);
        if(kind!=='context')assert.equal(input.modelId??model.id,model.id);
        const supplied=input.context?.files??input.files??[];
        for(const file of supplied){
          const path=file.path.replace(/#chunk-\d+$/,'');
          const source=sample.context.files.find(f=>f.path===path);assert(source,'Unapproved file: '+path);
          assert(source.content.includes(file.content),'Unapproved contents');
        }
        if(sample.id==='DATE'&&kind==='context'){
          for(const path of ['src/people/date.mjs','src/people/create.mjs','src/people/import.mjs','src/people/rules.md'])assert(supplied.some(f=>f.path.replace(/#chunk-\d+$/,'')===path),'Missing target '+path);
          console.log(JSON.stringify({event:'PROJECT_CONTEXT',selected:supplied.map(f=>f.path),characters:supplied.reduce((n,f)=>n+f.content.length,0)}));
        }
        await save(resolve(output,`${sample.id}-${kind}-request.json`),input);
        const requestId=randomUUID(),reservation=kind==='context'?null:await reserve(sample.id+'-'+kind,'product',4,requestId);
        const response=await route.fetch({headers:{...request.headers(),'X-Request-ID':requestId},maxRetries:0,maxRedirects:0,timeout:240000});
        const value={status:response.status(),requestId:response.headers()['x-request-id']??requestId,body:await response.json()};
        await save(resolve(output,`${sample.id}-${kind}-response.json`),value);
        if(reservation){assert.equal(value.requestId,requestId);const calls=await settleProduct(reservation);console.log(JSON.stringify({event:'MODEL_CALLS',caseId:sample.id,kind,calls:calls.length}));}
        records[kind]=value;await route.fulfill({response});
      }catch(error){routeError=error;await save(resolve(output,`${sample.id}-${kind}-harness-failure.json`),{name:error.name,message:error.message});await route.abort().catch(()=>{});}
    };
    for(const pattern of ['**/api/v2/optimizations','**/api/v2/optimizations/plan','**/api/v2/context/planning','**/api/v2/optimization-history/*/revise'])await page.route(pattern,inspect);
    await page.goto(front+'/workbench');await expect(page.getByLabel('原始提示词',{exact:true})).toBeVisible({timeout:30000});
    await expect.poll(()=>page.evaluate(()=>window.__dailyFixtureReady),{timeout:30000}).toBe(true);
    await page.locator('.model-control .el-select').click();await page.getByRole('option',{name:model.displayName.trim(),exact:true}).click();
    await page.getByLabel('自定义项目描述',{exact:true}).fill(sample.context.customDescription);
    if(sample.context.files.length){
      await page.getByRole('button',{name:'添加上下文',exact:true}).click();await page.getByRole('menuitem',{name:'选择文件夹',exact:true}).click();
      await expect(page.getByText(`${sample.context.files.length} 个文本文件已完整建立本地索引`,{exact:true})).toBeVisible({timeout:60000});
    }
    await page.getByLabel('原始提示词',{exact:true}).fill(sample.rawPrompt);
    await page.getByRole('button',{name:sample.planEnabled?'先确认并增强':'直接增强提示词',exact:true}).click();
    const consent=page.getByRole('dialog',{name:'确认发送上下文',exact:true});
    if(sample.context.files.length){await expect(consent).toBeVisible();await consent.getByRole('button',{name:'确认发送',exact:true}).click();}
    const waitRecord=async kind=>{await expect.poll(()=>Boolean(records[kind]||routeError),{timeout:300000}).toBe(true);if(routeError)throw routeError;assert.equal(records[kind].status,200,kind+' must deliver');return records[kind].body.data;};
    if(sample.planEnabled){
      const plan=await waitRecord('plan');await save(resolve(output,sample.id+'-actual-questions.json'),plan.questions);
      assert.equal(plan.questions.length,1,'Only unresolved today boundary requires user decision');
      assert.match(plan.questions[0].question,/今天|当天|当日|今日|today/i);
      const dialog=page.getByRole('dialog',{name:'确认关键细节'});
      await dialog.locator('textarea').fill(sample.planAnswerFixture);
      await dialog.getByRole('button',{name:'生成最终提示词',exact:true}).click();
      // Plan回答后资料范围可能变化；只确认该次界面展示的冻结材料范围。
      await Promise.race([consent.waitFor({state:'visible',timeout:15000}).then(()=>consent.getByRole('button',{name:'确认发送',exact:true}).click()),new Promise(done=>setTimeout(done,15000))]).catch(()=>{});
    }
    const first=await waitRecord('generation');assert.equal(first.chainVersion,'core-repair-v4');assert.equal(first.provider.mock,false);
    assert(first.processing.completedStages.includes('BODY_GENERATION'));
    assert(!first.optimizedPrompt.includes('提供的材料：'));assert(!first.optimizedPrompt.includes('export function'));
    await expect(page.getByTestId('compact-prompt')).toHaveText(first.optimizedPrompt);
    await save(resolve(output,sample.id+'-first.json'),first);
    await page.getByTestId('revise-prompt').click();const edit=page.getByRole('dialog',{name:'按意见修改提示词'});
    await page.getByLabel('本次修改意见').fill(sample.feedback);await edit.getByRole('button',{name:'应用修改意见'}).click();
    if(sample.context.files.length){await consent.waitFor({state:'visible',timeout:5000}).then(()=>consent.getByRole('button',{name:'确认发送',exact:true}).click()).catch(()=>{});}
    const next=(await waitRecord('revision')).result;assert.equal(next.chainVersion,'core-repair-v4');assert.notEqual(next.recordId,first.recordId);
    await expect(edit).not.toBeVisible();await expect(page.getByTestId('compact-prompt')).toHaveText(next.optimizedPrompt);
    await page.bringToFront();await page.locator('.copy-main-button').click();await expect(page.locator('.copy-main-button')).toContainText('已复制');
    const copy=(await page.evaluate(()=>navigator.clipboard.readText())).replace(/\r\n/g,'\n');assert.equal(copy,next.optimizedPrompt.replace(/\r\n/g,'\n'));
    const history=(await (await context.request.get(front+'/api/v2/optimization-history/'+next.recordId)).json()).data;
    const old=(await (await context.request.get(front+'/api/v2/optimization-history/'+first.recordId)).json()).data;
    assert.equal(history.optimizedPrompt,next.optimizedPrompt);assert.equal(old.optimizedPrompt,first.optimizedPrompt);assert.equal(history.chainVersion,'core-repair-v4');
    await save(resolve(output,sample.id+'-history.json'),history);await page.screenshot({path:resolve(output,sample.id+'-workbench.png'),fullPage:true});
    await page.goto(front+'/history');await page.getByRole('button',{name:'查看',exact:true}).filter({visible:true}).first().click();
    await expect(page.getByRole('dialog',{name:'历史记录详情'}).locator('pre').nth(1)).toHaveText(next.optimizedPrompt);
    await save(resolve(output,sample.id+'-verdict.json'),{actualDailyEntry:true,version:next.chainVersion,realModel:true,bodyGenerated:true,displayCopyHistoryEqual:true,originalRecordUnchanged:true,planQuestions:records.plan?.body.data.questions.length??0,availableFiles:sample.context.files.length,bodyCharacters:next.optimizedPrompt.length,recordId:next.recordId});
    console.log(JSON.stringify({event:'DAILY_VERIFIED',caseId:sample.id,version:next.chainVersion}));
  }
}catch(error){await save(resolve(dir,'product','failure-'+Date.now()+'.json'),{name:error.name,message:error.message});throw error;}
finally{password=undefined;lines.close();await browser.close();}
