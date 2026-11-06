/** 恢复官方直连后的单次日常入口核实。固定充分需求，失败保留，不做质量重试。 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { directory, root, runtime, readJson, save, exists } from './evaluation.mjs';

const output = resolve(directory, 'workflow/direct_R_SUFFICIENT.json');
assert.equal(await exists(output), false, '直连结果已存在，不重新生成');
const reservation = await readJson(resolve(directory, 'direct-reservation.json'));
assert.equal(reservation.maximumCalls, 4);
const require = createRequire(resolve(root, 'apps/web/package.json'));
const { chromium, expect } = require('@playwright/test');
const account = await readJson(resolve(root, 'tmp/local-acceptance/account.json'));
const sample = (await readJson(resolve(directory, 'cases.json'))).cases.find(item => item.id === 'R_SUFFICIENT');
let password = process.env.POP_EVAL_PASSWORD;
delete process.env.POP_EVAL_PASSWORD;
assert(account.identifier.startsWith('qa_') && password);
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 },
  permissions: ['clipboard-read', 'clipboard-write'], locale: 'zh-CN' });
const page = await context.newPage();
const lines = createInterface({ input: process.stdin, output: process.stdout, terminal: false });
const frontend = 'http://127.0.0.1:5173';

try {
  await page.goto(frontend + '/login');
  await context.request.get(frontend + '/api/v1/auth/csrf');
  let authenticated = false;
  for (let attempt = 0; attempt < 3; attempt++) {
    const image = await context.request.get(frontend + '/api/v1/auth/captcha');
    assert.equal(image.status(), 200);
    const path = resolve(runtime, `direct-${Date.now()}-captcha-${attempt}.png`);
    await writeFile(path, await image.body(), { flag: 'wx' });
    console.log(JSON.stringify({ event: 'CAPTCHA', path }));
    const captcha = (await lines.question('')).trim();
    const token = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN')?.value;
    const response = await context.request.post(frontend + '/api/v1/auth/login', {
      headers: { 'X-XSRF-TOKEN': decodeURIComponent(token ?? '') },
      data: { identifier: account.identifier, password, captcha },
    });
    if (response.status() === 200) { authenticated = true; break; }
    assert.equal((await response.json()).error?.code, 'CAPTCHA_INVALID');
  }
  password = undefined;
  assert(authenticated);
  const actor = (await (await context.request.get(frontend + '/api/v1/auth/me')).json()).data;
  for (const key of ['userId', 'tenantId', 'workspaceId']) assert.equal(actor[key], account[key]);
  await page.addInitScript(() => localStorage.setItem('prompt-optimizer.plan-mode.v1',
    JSON.stringify({ enabled: false, introSeen: true })));
  await page.goto(frontend + '/workbench');
  await page.getByLabel('原始提示词', { exact: true }).fill(sample.rawPrompt);
  const pending = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v2/optimizations', { timeout: 250_000 });
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  const response = await pending;
  const record = { input: response.request().postDataJSON(), status: response.status(), body: await response.json() };
  await save(output, record);
  assert.equal(record.input.modelId, 'deepseek:deepseek-flash');
  assert.equal(record.status, 200);
  const result = record.body.data;
  assert.equal(result.chainVersion, 'core-repair-v4');
  assert.equal(result.provider.mock, false);
  assert(result.processing.completedStages.includes('BODY_GENERATION'));
  await expect(page.getByTestId('compact-prompt')).toHaveText(result.optimizedPrompt);
  await page.bringToFront();
  await page.locator('.copy-main-button').click();
  await expect.poll(async () => (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n'))
    .toBe(result.optimizedPrompt.replace(/\r\n/g, '\n'));
  const historyResponse = await context.request.get(frontend + '/api/v2/optimization-history/' + result.recordId);
  assert.equal(historyResponse.status(), 200);
  const history = (await historyResponse.json()).data;
  assert.equal(history.optimizedPrompt, result.optimizedPrompt);
  await save(resolve(directory, 'workflow/direct_R_SUFFICIENT_history.json'), { copiedExact: true, history });
  await page.screenshot({ path: resolve(runtime, 'direct_R_SUFFICIENT.png'), fullPage: true });
  console.log(JSON.stringify({ event: 'DIRECT_DAILY_VERIFIED', requestId: record.body.requestId,
    version: result.chainVersion, mock: result.provider.mock }));
} catch (error) {
  await save(resolve(runtime, `direct-${Date.now()}-failure.json`), { type: error.name, message: error.message });
  console.log(JSON.stringify({ event: 'DIRECT_DAILY_FAILED', type: error.name, message: error.message }));
  process.exitCode = 1;
} finally {
  password = undefined;
  lines.close();
  await browser.close();
}
