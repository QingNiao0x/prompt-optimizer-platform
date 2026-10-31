/** 普通工作台验收：真实登录、Plan、生成、定向修改、剪贴板和历史；仅使用冻结合成文本。 */
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile,writeFile,mkdir,access,readdir} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createInterface} from 'node:readline/promises';
import {isDeepStrictEqual} from 'node:util';
const dir=dirname(fileURLToPath(import.meta.url)),repo=resolve(dir,'../../../..');
const runtime=resolve(repo,'tmp/natural-plan-context-repair-2026-11-01/browser');
const output=resolve(dir,'product'),frontend='http://127.0.0.1:5197';
const json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const save=(p,value)=>writeFile(p,JSON.stringify(value,null,2)+'\n',{flag:'wx'});
const cases=(await json(resolve(dir,'product-cases.json'))).cases;
try{await access(resolve(dir,'closure.json'));throw Error('Batch closed');}catch(e){if(e.code!=='ENOENT')throw e;}
const account=await json(resolve(repo,'tmp/local-acceptance/account.json'));
let password=process.env.POP_EVAL_PASSWORD;delete process.env.POP_EVAL_PASSWORD;
assert.ok(account.identifier.startsWith('qa_')&&password);
await mkdir(runtime,{recursive:true});await mkdir(output,{recursive:true});
const require=createRequire(resolve(repo,'apps/web/package.json'));
const {chromium,expect}=require('@playwright/test');
console.log(JSON.stringify({event:'BROWSER_SETUP',port:5197}));
const {createServer}=await import(new URL('../../../../apps/web/node_modules/vite/dist/node/index.js',import.meta.url));
const server=await createServer({root:resolve(repo,'apps/web'),logLevel:'error',server:{host:'127.0.0.1',port:5197,strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:9047',changeOrigin:true}}}});
await server.listen();
console.log(JSON.stringify({event:'VITE_READY',port:5197}));
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
  await save(resolve(output,'session-check-'+Date.now()+'.json'),{canonicalSyntheticAccount:true,endpoint:'http://127.0.0.1:9047',modelId:model.id,provider:model.provider,modelCallsMaximum:36,maxCallsPerBusinessRequest:4,authSecretsRecorded:false});
  for(const sample of cases){
    const planOn=sample.id==='D_PLAN';
    await page.addInitScript(enabled=>localStorage.setItem('prompt-optimizer.plan-mode.v1',JSON.stringify({enabled,introSeen:true})),planOn);
    let generated, revised, plan, generationCount=0,revisionCount=0;
    const inspect=async route=>{
      const request=route.request(),path=new URL(request.url()).pathname;
      if(request.method()!=='POST'){await route.continue();return;}
      const input=request.postDataJSON();
      if(path.endsWith('/revise')){revisionCount++;assert.equal(revisionCount,1);assert.equal(input.feedback,sample.feedback);}
      else {assert.equal(input.rawPrompt,sample.rawPrompt);assert.equal(input.modelId,'deepseek:deepseek-flash');if(!path.endsWith('/plan')){generationCount++;assert.equal(generationCount,1);}}
      await save(resolve(output,sample.id+(path.endsWith('/revise')?'-revision':path.endsWith('/plan')?'-plan':'-generation')+'-request.json'),input);
      const response=await route.fetch({maxRetries:0,maxRedirects:0,timeout:300000});
      const value={status:response.status(),body:await response.json()};
      await save(resolve(output,sample.id+(path.endsWith('/revise')?'-revision':path.endsWith('/plan')?'-plan':'-generation')+'-response.json'),value);
      if(path.endsWith('/revise'))revised=value;else if(path.endsWith('/plan'))plan=value;else generated=value;
      await route.fulfill({response});
    };
    await page.route('**/api/v2/optimizations',inspect);await page.route('**/api/v2/optimizations/plan',inspect);await page.route('**/api/v2/optimization-history/*/revise',inspect);
    await page.goto(frontend+'/workbench');await expect(page.getByLabel('原始提示词',{exact:true})).toBeVisible({timeout:30000});
    await page.locator('.model-control .el-select').click();await page.getByRole('option',{name:model.displayName.trim(),exact:true}).click();
    await page.getByLabel('原始提示词',{exact:true}).fill(sample.rawPrompt);
    await page.getByRole('button',{name:planOn?'先确认并增强':'直接增强提示词',exact:true}).click();
    if(planOn){
      await expect.poll(()=>plan!==undefined,{timeout:300000}).toBe(true);assert.equal(plan.status,200);
      assert.ok(plan.body.data.questions.length>0,'Required Plan decisions not asked');
      await save(resolve(output,sample.id+'-actual-questions.json'),plan.body.data.questions);
      const dialog=page.getByRole('dialog',{name:'确认关键细节'});
      for(let i=0;i<plan.body.data.questions.length;i++){
        const q=plan.body.data.questions[i],isDate=/日期|时间|交付/.test(q.question),isPay=/赔偿|补偿/.test(q.question);
        assert.ok(isDate!==isPay,'Fixture requires separate date and compensation questions; do not guess');
        const answer=sample.allowedAnswers[isDate?0:1];
        await dialog.locator('textarea').fill(answer);
        await dialog.getByRole('button',{name:i===plan.body.data.questions.length-1?'生成最终提示词':'下一题',exact:true}).click();
      }
    }
    await expect.poll(()=>generated!==undefined,{timeout:300000}).toBe(true);assert.equal(generated.status,200);
    const first=generated.body.data;assert.equal(first.chainVersion,'core-repair-v4');assert.equal(first.provider.mock,false);
    assert.ok(first.processing.completedStages.includes('BODY_GENERATION'));
    await expect(page.getByTestId('compact-prompt')).toHaveText(first.optimizedPrompt);
    await page.getByTestId('revise-prompt').click();const dialog=page.getByRole('dialog',{name:'按意见修改提示词'});
    await page.getByLabel('本次修改意见').fill(sample.feedback);await dialog.getByRole('button',{name:'应用修改意见'}).click();
    await expect.poll(()=>revised!==undefined,{timeout:300000}).toBe(true);assert.equal(revised.status,200);
    const next=revised.body.data.result;assert.notEqual(next.recordId,first.recordId);assert.equal(next.chainVersion,'core-repair-v4');
    await expect(dialog).not.toBeVisible();await expect(page.getByTestId('compact-prompt')).toHaveText(next.optimizedPrompt);
    assert.ok(next.processing.completedStages.includes('BODY_GENERATION'));
    await page.bringToFront();await page.locator('.copy-main-button').click();await expect(page.locator('.copy-main-button')).toContainText('已复制');
    assert.equal((await page.evaluate(()=>navigator.clipboard.readText())).replace(/\r\n/g,'\n'),next.optimizedPrompt.replace(/\r\n/g,'\n'));
    const history=(await (await context.request.get(frontend+'/api/v2/optimization-history/'+next.recordId)).json()).data;
    const old=(await (await context.request.get(frontend+'/api/v2/optimization-history/'+first.recordId)).json()).data;
    assert.equal(history.optimizedPrompt,next.optimizedPrompt);assert.equal(old.optimizedPrompt,first.optimizedPrompt);assert.equal(history.rawPrompt,sample.rawPrompt);
    await save(resolve(output,sample.id+'-history.json'),history);
    if(planOn){
      // 公开历史DTO不暴露Plan题答；从真实反馈调用的完整输入核对服务端恢复的题答，不能要求增加数据暴露。
      const traceFiles=(await readdir(resolve(dir,'traces'))).filter(name=>/^\d{4}\.json$/.test(name));
      const actualInputs=[];
      for(const name of traceFiles){
        const record=await json(resolve(dir,'traces',name));
        if(record.phase!=='product')continue;
        const text=record.request.messages[1].content,input=JSON.parse(text.slice(text.indexOf('{')));
        if(input.rawPrompt===sample.rawPrompt && input.revision)actualInputs.push(input);
      }
      assert.ok(actualInputs.length>0);
      for(const input of actualInputs){
        assert.deepEqual(input.actualAnswers.map(a=>a.answer),sample.allowedAnswers);
        assert.deepEqual(input.actualAnswers.map(a=>a.questionId),plan.body.data.questions.map(q=>q.id));
        assert.deepEqual(input.actualAnswers.map(a=>a.question),plan.body.data.questions.map(q=>q.question));
      }
      await save(resolve(output,'D_PLAN-answer-preservation.json'),{answersFromSavedHistory:true,questionsAndAnswersExact:true,feedbackCalls:actualInputs.length});
    }
    await page.screenshot({path:resolve(output,sample.id+'-result.png'),fullPage:true});
    await page.goto(frontend+'/history');await page.getByRole('button',{name:'查看',exact:true}).filter({visible:true}).first().click();
    await expect(page.getByRole('dialog',{name:'历史记录详情'}).locator('pre').nth(1)).toHaveText(next.optimizedPrompt);
    await save(resolve(output,sample.id+'-verdict.json'),{realAccount:true,generationCount,revisionCount,planQuestions:plan?.body.data.questions.length??0,default:'core-repair-v4',copyHistoryEqual:true,originalHistoryUnchanged:true,bodyGenerated:true,recordId:next.recordId});
    console.log(JSON.stringify({event:'PRODUCT_VERIFIED',caseId:sample.id,planQuestions:plan?.body.data.questions.length??0}));
    await page.unroute('**/api/v2/optimizations',inspect);await page.unroute('**/api/v2/optimizations/plan',inspect);await page.unroute('**/api/v2/optimization-history/*/revise',inspect);
  }
}finally{await browser.close();await server.close();}
