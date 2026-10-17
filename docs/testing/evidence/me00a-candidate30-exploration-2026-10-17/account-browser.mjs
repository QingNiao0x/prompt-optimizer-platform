import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
const root = resolve('tmp/me00a-candidate30-live-20261017');
const require = createRequire(resolve('apps/web/package.json'));
const account = JSON.parse(await readFile(resolve('tmp/local-acceptance/account.json'), 'utf8'));
const context = await require('@playwright/test').chromium.launchPersistentContext(
  resolve('tmp/me00a-candidate9-20261014/browser-profile'), { headless: true, channel: 'chrome',
    args: ['--remote-debugging-port=9325', '--remote-debugging-address=127.0.0.1'] });
const pause = ms => new Promise(done => setTimeout(done, ms));
try {
  const page = context.pages()[0] ?? await context.newPage();
  await page.goto('http://127.0.0.1:9001/');
  await context.request.get('http://127.0.0.1:9001/api/v1/auth/csrf');
  let me = await context.request.get('http://127.0.0.1:9001/api/v1/auth/me');
  let normalCaptchaLogin = false;
  if (me.status() === 401) {
    console.log('NEEDS_EXISTING_ACCOUNT_CREDENTIAL');
    let credential;
    for (let poll = 0; poll < 180; poll++) {
      try { credential = JSON.parse(await readFile(resolve(root, 'credential.json'), 'utf8')); break; }
      catch { await pause(1000); }
    }
    if (!credential?.password) throw new Error('Existing fixture credential unavailable; no replacement account');
    let authenticated = false;
    for (let attempt = 1; attempt <= 3; attempt++) {
      const captchaResponse = await context.request.get('http://127.0.0.1:9001/api/v1/auth/captcha');
      if (!captchaResponse.ok()) throw new Error('Captcha unavailable');
      await writeFile(resolve(root, `captcha-${attempt}.png`), await captchaResponse.body(), { flag: 'wx' });
      console.log(`CAPTCHA_READY_${attempt}`);
      let captcha;
      for (let poll = 0; poll < 180; poll++) {
        try { captcha = (await readFile(resolve(root, `captcha-answer-${attempt}.txt`), 'utf8')).trim(); break; }
        catch { await pause(1000); }
      }
      if (!captcha) throw new Error('Captcha answer timeout');
      const status = await page.evaluate(async payload => {
        const token = document.cookie.split('; ').find(item => item.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
        const response = await fetch('/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json',
          'X-XSRF-TOKEN': decodeURIComponent(token ?? '') }, body: JSON.stringify(payload) });
        return { status: response.status, code: (await response.json()).error?.code };
      }, { identifier: account.identifier, password: credential.password, captcha });
      if (status.status === 200) { authenticated = true; normalCaptchaLogin = true; break; }
      if (status.code !== 'CAPTCHA_INVALID') throw new Error(`Existing fixture login failed: ${status.status} ${status.code}`);
    }
    credential = undefined;
    if (!authenticated) throw new Error('Captcha attempts exhausted');
    me = await context.request.get('http://127.0.0.1:9001/api/v1/auth/me');
  }
  const data = (await me.json()).data;
  if (!me.ok() || data.userId !== account.userId || data.tenantId !== account.tenantId) throw new Error('Canonical fixture identity mismatch');
  await writeFile(resolve(root, 'browser-identity-check.json'), JSON.stringify({ sameAccount: true,
    newAccountsCreated: 0, normalCaptchaLogin, userHash: createHash('sha256').update(account.userId).digest('hex') }), { flag: 'wx' });
  console.log('SAME_TEST_ACCOUNT_AUTHENTICATED');
  for (;;) {
    try { await readFile(resolve(root, 'close-browser')); break; }
    catch { await pause(3000); }
  }
} finally { await context.close(); }
