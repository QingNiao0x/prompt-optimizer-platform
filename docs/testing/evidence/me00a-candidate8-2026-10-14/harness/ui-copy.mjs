// Replay genuine saved API results through the existing workbench copy button; never generate another result.
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
const require = createRequire(resolve('apps/web/package.json'));
const { chromium } = require('@playwright/test');
const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
const context = browser.contexts()[0];
const frontend = 'http://localhost:5173';
const out = resolve('tmp/me00a-20261012/candidate8-ui-copy');
await mkdir(out, { recursive: true });
const loginPage = context.pages().find(p => p.url().startsWith('http://127.0.0.1:9001/'));
if (!loginPage) throw new Error('Original authenticated login target missing');
const cdp = await context.newCDPSession(loginPage);
const cookies = (await cdp.send('Network.getCookies', { urls: ['http://127.0.0.1:9001'] })).cookies;
if (!cookies.some(c => c.httpOnly)) throw new Error('Authenticated target session cookies missing');
await cdp.detach();
await context.addCookies(cookies.map(({ name, value, path, httpOnly, secure, sameSite, expires }) =>
  ({ name, value, domain: 'localhost', path, httpOnly, secure, sameSite, expires })));
await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: frontend });
const requests = [];
await context.route('http://localhost:5173/api/**', async route => {
  const request = route.request();
  const path = new URL(request.url()).pathname;
  if (request.method() !== 'GET' && !path.includes('/analytics/')) {
    await route.abort('blockedbyclient'); return;
  }
  const target = 'http://127.0.0.1:9001' + new URL(request.url()).pathname + new URL(request.url()).search;
  const headers = { ...request.headers(), cookie: cookies.map(c => `${c.name}=${c.value}`).join('; ') };
  const response = await route.fetch({ url: target, headers });
  requests.push({ path, status: response.status(), method: request.method() });
  await route.fulfill({ response });
});
const page = await context.newPage();
try {
  await page.goto(`${frontend}/workbench`, { waitUntil: 'domcontentloaded' });
  await page.locator('.workbench-grid').waitFor({ state: 'visible', timeout: 30000 });
  await page.screenshot({ path: resolve(out, 'ready.png'), fullPage: true });
  await writeFile(resolve(out, 'api-statuses.json'), JSON.stringify(requests, null, 2), { flag: 'wx' });
  await writeFile(resolve(out, 'ready.json'), JSON.stringify({ url: page.url(), title: await page.title() }, null, 2), { flag: 'wx' });
  const buttons = await page.getByRole('button').allTextContents();
  await writeFile(resolve(out, 'rendered-buttons-before.json'), JSON.stringify(buttons, null, 2), { flag: 'wx' });
  const base = 'tmp/me00a-20261012/candidate8-plan-r1/Q05-flash';
  const checks = [];
  for (const [arm, suffix] of [['plan', ''], ['direct', '/direct-equal-information']]) {
    const dir = resolve(base + suffix);
    const platform = JSON.parse(await readFile(resolve(dir, 'platform.json'), 'utf8'));
    const input = JSON.parse(await readFile(resolve(dir, 'input.json'), 'utf8'));
    if (platform.status !== 200) { checks.push({ arm, status: 'NO_PLATFORM_BODY_NO_CLIPBOARD_CHECK', platformStatus: platform.status }); continue; }
    await page.evaluate(async ({ result, rawPrompt }) => {
      const { useOptimizationStore } = await import('/src/stores/optimization.ts');
      const store = useOptimizationStore();
      store.rawPrompt = rawPrompt;
      store.applyReoptimized({ result });
    }, { result: platform.body.data, rawPrompt: input.rawPrompt });
    await writeFile(resolve(out, `${arm}-rendered-buttons.json`), JSON.stringify(await page.getByRole('button').allTextContents(), null, 2), { flag: 'wx' });
    await writeFile(resolve(out, `${arm}-rendered-text.txt`), await page.locator('#app').innerText(), { flag: 'wx' });
    const copy = page.getByRole('button', { name: '复制提示词', exact: true });
    await copy.waitFor({ state: 'visible', timeout: 15000 });
    await page.bringToFront();
    await copy.click();
    await page.getByRole('button', { name: '已复制', exact: true }).waitFor({ state: 'visible', timeout: 15000 });
    const text = await page.evaluate(() => navigator.clipboard.readText());
    await writeFile(resolve(out, `${arm}-clipboard-observed.md`), text, { flag: 'wx' });
    const apiText = platform.body.data.optimizedPrompt;
    if (text.replace(/\r\n/g, '\n') !== apiText.replace(/\r\n/g, '\n')) throw new Error(`Actual clipboard content differs for ${arm}`);
    await writeFile(resolve(out, `${arm}-clipboard.md`), text, { flag: 'wx' });
    await page.locator('.result-panel').screenshot({ path: resolve(out, `${arm}-copy.png`) });
    checks.push({ arm, clipboardContentMatchesAfterLineEndingNormalization: true,
      byteForByteMatch: text === apiText, lineEndingDifference: { clipboardCRLF: (text.match(/\r\n/g) || []).length, apiCRLF: (apiText.match(/\r\n/g) || []).length },
      sha256: createHash('sha256').update(text).digest('hex') });
  }
  await writeFile(resolve(out, 'checks.json'), JSON.stringify({ frontend, backend: 'http://127.0.0.1:9001',
    scope: 'Read-only replay of genuine saved API results in current workbench; existing copy handler and actual clipboard; no UI generation or full edit lifecycle claimed',
    newModelCalls: 0, newAccountsCreated: 0, checks }, null, 2), { flag: 'wx' });
  process.stdout.write('Actual workbench copy matched the saved successful API body; new model calls0\n');
} catch (error) { await writeFile(resolve(out, 'failure.json'), JSON.stringify({ message: error.message, newModelCalls: 0 }, null, 2), { flag: 'wx' }); process.exitCode = 1; } finally { await page.close(); }

process.exit(process.exitCode || 0);
