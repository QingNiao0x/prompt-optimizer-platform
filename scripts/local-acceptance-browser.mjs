/**
 * 保持独立的本地验收浏览器，会话只驻留内存；通过正常 CSRF、图形验证码和密码接口登录。
 * 密码来自临时进程环境，证据只保存验证码图像，不保存 Cookie、密码或 storageState。
 */
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { stdin, stdout } from 'node:process';
import { fileURLToPath } from 'node:url';

if (process.argv.includes('--help')) {
  console.log('node scripts/local-acceptance-browser.mjs <new-workspace-tmp-directory>\nRequires ephemeral PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER/PASSWORD. Enter the displayed captcha via stdin; close ends the owned browser.');
  process.exit(0);
}
const root = fileURLToPath(new URL('../', import.meta.url));
const output = resolve(process.argv[2] ?? '');
if (!output.startsWith(resolve(root, 'tmp') + sep)) throw new Error('Only workspace tmp evidence is allowed.');
const identifier = process.env.PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER;
let password = process.env.PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD;
if (!identifier?.startsWith('qa_') || !password) throw new Error('Synthetic account environment missing.');
delete process.env.PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD;
const require = createRequire(new URL('../apps/web/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const browser = await chromium.launch({ headless: true, channel: 'chrome',
  args: ['--remote-debugging-port=9325', '--remote-debugging-address=127.0.0.1'] });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
const lines = createInterface({ input: stdin, output: stdout, terminal: false });
try {
  await page.goto('http://127.0.0.1:5175/workbench', { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.evaluate(async () => { await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' }); });
  const challenge = await context.request.get('http://127.0.0.1:5175/api/v1/auth/captcha');
  if (!challenge.ok()) throw new Error('CAPTCHA_UNAVAILABLE');
  await mkdir(output, { recursive: true });
  const path = resolve(output, 'captcha.png');
  await writeFile(path, await challenge.body(), { flag: 'wx' });
  console.log(JSON.stringify({ event: 'acceptance.captcha.ready', path }));
  const captcha = (await lines.question('')).trim();
  if (!/^[a-zA-Z0-9]{4,8}$/.test(captcha)) throw new Error('INVALID_CAPTCHA_INPUT');
  const status = await page.evaluate(async payload => {
    const token = document.cookie.split('; ').find(item => item.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
    const response = await fetch('/api/v1/auth/login', { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', ...(token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {}) },
      body: JSON.stringify(payload) });
    const data = await response.json();
    return { status: response.status, code: data.error?.code };
  }, { identifier, password, captcha });
  password = undefined;
  if (status.status !== 200) throw new Error(`LOGIN_FAILED_${status.code ?? status.status}`);
  await page.reload({ waitUntil: 'domcontentloaded' });
  console.log(JSON.stringify({ event: 'acceptance.browser.authenticated', port: 9325, synthetic: true }));
  for await (const line of lines) {
    if (line.trim() === 'close') break;
    console.log(JSON.stringify({ event: 'acceptance.browser.alive' }));
  }
} finally {
  password = undefined;
  lines.close();
  await browser.close();
}
