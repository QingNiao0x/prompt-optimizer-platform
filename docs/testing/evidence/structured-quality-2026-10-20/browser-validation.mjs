import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createInterface } from 'node:readline/promises';

const root = resolve('tmp/structured-quality-20261020');
const evidence = resolve('docs/testing/evidence/structured-quality-2026-10-20');
const origin = 'http://127.0.0.1:9002';
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const protocol = await json(resolve(evidence, 'protocol.json'));
const cases = (await json(resolve(evidence, 'cases.json'))).cases;
const account = await json(resolve('tmp/local-acceptance/account.json'));
const terminal = createInterface({ input: process.stdin, output: process.stdout });
const cookies = new Map();
let csrf;
let currentJob;
const log = value => console.log(JSON.stringify(value));
const encode = value => JSON.stringify(value, null, 2) + '\n';
const save = async (path, value) => {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, typeof value === 'string' ? value : encode(value), { flag: 'wx' });
};

/** 正常CSRF、验证码及固定合成账号登录；密码和Cookie仅在进程内存，不进入公开证据。 */
async function api(path, body) {
  const headers = { Cookie: [...cookies].map(([key, value]) => key + '=' + value).join('; ') };
  if (currentJob) headers['X-Evaluation-Job'] = currentJob;
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    headers[csrf?.headerName ?? 'X-XSRF-TOKEN'] = csrf?.token ?? '';
  }
  const response = await fetch(origin + path, { method: body === undefined ? 'GET' : 'POST', headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(240_000) });
  for (const line of response.headers.getSetCookie()) {
    const pair = line.split(';', 1)[0]; const index = pair.indexOf('=');
    cookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
  return { status: response.status, body: await response.json() };
}

async function login() {
  if (!process.env.POP_EVAL_PASSWORD) throw Error('Canonical credential must be injected');
  csrf = (await api('/api/v1/auth/csrf')).body.data;
  const challenge = await fetch(origin + '/api/v1/auth/captcha', {
    headers: { Cookie: [...cookies].map(([key, value]) => key + '=' + value).join('; ') }
  });
  for (const line of challenge.headers.getSetCookie()) {
    const pair = line.split(';', 1)[0]; const index = pair.indexOf('=');
    cookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
  const path = resolve(root, 'auth/captcha-' + Date.now() + '.png');
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, Buffer.from(await challenge.arrayBuffer()), { flag: 'wx' });
  log({ event: 'CAPTCHA_READY', path });
  const captcha = (await terminal.question('CAPTCHA_INPUT> ')).trim();
  const result = await api('/api/v1/auth/login', { identifier: account.identifier,
    password: process.env.POP_EVAL_PASSWORD, captcha });
  delete process.env.POP_EVAL_PASSWORD;
  if (result.status !== 200) throw Error('Canonical login failed: HTTP ' + result.status);
  csrf = (await api('/api/v1/auth/csrf')).body.data;
}

import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
const { chromium } = createRequire(resolve('apps/web/package.json'))('@playwright/test');
let browser;
try {
  await login();
  browser = await chromium.launch({ channel:'msedge', headless:true });
  const context = await browser.newContext({ viewport:{width:1600,height:1200}, permissions:['clipboard-read','clipboard-write'] });
  await context.addCookies([...cookies].map(([name,value])=>({name,value,url:'http://127.0.0.1:5175',httpOnly:name!=='XSRF-TOKEN'})));
  await context.addInitScript(()=>localStorage.setItem('prompt-optimizer.plan-mode.v1',JSON.stringify({enabled:false,introSeen:true})));
  const page=await context.newPage();
  const checks=[];
  let selected;
  let replayPosts=0;
  await page.route('**/api/v2/optimizations', async route=>{
    const body=route.request().postDataJSON();
    assert.equal(body.enhancement.promptFormat,'STRUCTURED');
    assert.equal(body.rawPrompt,selected.sample.rawPrompt);
    replayPosts++;
    // 仅回放已保存的真实响应以隔离显示／编辑／复制；不伪装成浏览器新增模型调用。
    await route.fulfill({status:200,json:selected.response});
  });
  for(const sample of cases) {
    const id=sample.id+(sample.id==='EVENT_PLAN'?'_C3_GEN':'_C2_GEN');
    const generated=await json(resolve(root,'results',id+'.json'));
    if(generated.status!==200) throw Error('No valid real result to replay');
    selected={sample,response:generated.body};
    await page.goto('http://127.0.0.1:5175/workbench');
    await page.getByLabel('原始提示词',{exact:true}).fill(sample.rawPrompt);
    const toggle=page.getByRole('switch',{name:'结构化提示词',exact:true});
    if(await toggle.getAttribute('aria-checked')!=='true') await page.locator('label.switch-control').filter({hasText:'结构化提示词'}).click();
    await page.getByRole('button',{name:'直接增强提示词',exact:true}).click();
    await page.locator('.section-list').waitFor({state:'visible'});
    await page.locator('.copy-main-button').click();
    await page.getByText('复制成功',{exact:true}).waitFor({state:'visible',timeout:3000}).catch(()=>{});
    const copied=(await page.evaluate(()=>navigator.clipboard.readText())).replace(/\r\n/g,'\n');
    assert.equal(copied,generated.body.data.optimizedPrompt);
    await save(resolve(evidence,sample.id+'-browser-copy.md'),copied);
    await page.getByRole('button',{name:'编辑',exact:true}).click();
    await page.getByRole('button',{name:'保存修改',exact:true}).click();
    await page.locator('.section-list').waitFor({state:'visible'});
    await page.locator('.copy-main-button').click();
    const afterEdit=(await page.evaluate(()=>navigator.clipboard.readText())).replace(/\r\n/g,'\n');
    assert.equal(afterEdit,copied);
    const realHistory=await api('/api/v2/optimization-history/'+generated.body.data.recordId);
    assert.equal(realHistory.status,200);
    assert.equal(realHistory.body.data.optimizedPrompt,copied);
    if(sample.id==='EVENT_PLAN') await page.screenshot({path:resolve(evidence,'structured-plan-result.png'),fullPage:true});
    checks.push({caseId:sample.id,recordId:generated.body.data.recordId,nativeClipboardEqual:true,
      unchangedEditRoundTrip:true,realOwnerHistoryEqual:true,visibleSections:generated.body.data.sections.length});
  }
  await save(resolve(evidence,'browser-checks.json'),{controlledBrowserReplayOfRealApiResults:true,
    realAuthentication:true,newModelCalls:0,newAccounts:0,replayedPosts:replayPosts,checks});
  log({browserChecks:checks.length,allPassed:true,newModelCalls:0});
} finally { if(browser) await browser.close(); terminal.close(); }
