import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
const root = resolve('tmp/me00a-candidate8-20261014');
const require = createRequire(resolve('apps/web/package.json'));
const { chromium } = require('@playwright/test');
// 持久测试上下文是 CDP 的首个上下文，避免工具连接到未登录的默认上下文。
const context = await chromium.launchPersistentContext(resolve(root, 'browser-profile'), {
  headless: true, channel: 'chrome', args: ['--remote-debugging-port=9325', '--remote-debugging-address=127.0.0.1']
});
const page = context.pages()[0] ?? await context.newPage();
const runId = Date.now();
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
try {
  await page.goto('http://127.0.0.1:9001/api/v1/health');
  await context.request.get('http://127.0.0.1:9001/api/v1/auth/csrf');
  let authenticated = false;
  for (let attempt = 1; attempt <= 3; attempt++) {
    const response = await context.request.get('http://127.0.0.1:9001/api/v1/auth/captcha');
    if (!response.ok()) throw new Error('Captcha service unavailable');
    await writeFile(resolve(root, `captcha-${runId}-${attempt}.png`), await response.body(), { flag: 'wx' });
    console.log(`CAPTCHA_READY_${runId}_${attempt}`);
    let captcha;
    for (let poll = 0; poll < 180; poll++) {
      try { captcha = (await readFile(resolve(root, `answer-${runId}-${attempt}.txt`), 'utf8')).trim(); break; }
      catch { await pause(1000); }
    }
    if (!captcha) throw new Error('Captcha timeout');
    const status = await page.evaluate(async payload => {
      const csrf = document.cookie.split('; ').find(x => x.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
      const r = await fetch('/api/v1/auth/login', { method: 'POST', headers: {
        'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(csrf ?? '')
      }, body: JSON.stringify(payload) });
      return { status: r.status, code: (await r.json()).error?.code };
    }, { identifier: process.env.PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER,
      password: process.env.PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD, captcha });
    if (status.status === 200) { authenticated = true; break; }
    if (status.code !== 'CAPTCHA_INVALID') throw new Error(`Fixture login failed: ${status.status} ${status.code}`);
  }
  if (!authenticated) throw new Error('Fixture captcha attempts exhausted');
  delete process.env.PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD;
  delete process.env.PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER;
  const account = JSON.parse(await readFile(resolve('tmp/enhancement-quality-20261012/account.json'), 'utf8'));
  const me = await context.request.get('http://127.0.0.1:9001/api/v1/auth/me');
  const data = (await me.json()).data;
  if (!me.ok() || data?.userId !== account.userId || data?.tenantId !== account.tenantId)
    throw new Error('Fixture identity mismatch');
  await writeFile(resolve(root, 'browser-identity-check.json'), JSON.stringify({
    sameAccount: true, newAccountsCreated: 0, normalCaptchaLogin: true,
    userHash: createHash('sha256').update(account.userId).digest('hex')
  }, null, 2), { flag: 'wx' });
  console.log('SAME_TEST_ACCOUNT_AUTHENTICATED');
  for (;;) {
    try { await readFile(resolve(root, 'close-candidate8-browser')); break; }
    catch { await pause(3000); }
  }
} finally { await context.close(); }
