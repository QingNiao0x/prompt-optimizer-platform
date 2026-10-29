import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { here, webRoot, backend, frontend, inspectStage, installProjectPicker } from './browser-support.mjs';
const stage = process.argv[2];
const frozen = await inspectStage(stage);
const output = resolve(here, 'live-tool-diagnosis', stage); await mkdir(output, { recursive: true });
const require = createRequire(resolve(webRoot, 'package.json')), { chromium } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
const server = await createServer({ root: webRoot, logLevel: 'warn', server: { host: '127.0.0.1', port: 5184, strictPort: true } });
let browser, context;
try {
  await server.listen(); browser = await chromium.launch({ channel: 'chrome', headless: true });
  context = await browser.newContext({ storageState: resolve(frozen.runtime, 'auth/browser-storage-state.json'), locale: 'zh-CN' });
  const page = await context.newPage(); await installProjectPicker(page, false);
  let blockedPosts = 0;
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url());
    if (request.method() !== 'GET') { blockedPosts++; await route.fulfill({ status: 409, json: { error: { message: 'DOM diagnosis is GET-only; POST blocked.' } } }); return; }
    const response = await route.fetch({ url: backend + url.pathname + url.search, maxRetries: 0, maxRedirects: 0, timeout: 30000 }); await route.fulfill({ response });
  });
  await page.goto(frontend + '/workbench'); await page.waitForFunction(() => window.__registry50FixtureReady === true);
  await page.locator('#workbench-model').waitFor({ state: 'visible' });
  const snapshot = { at: 'BEFORE_ANY_MODEL_SELECTION_OR_GENERATION', actualModelCalls: 0, allNonGetRequestsBlocked: true,
    fixtureReady: await page.evaluate(() => window.__registry50FixtureReady === true),
    originalNamedComboboxCount: await page.getByRole('combobox', { name: '选择模型版本', exact: true }).count(),
    stableIdCount: await page.locator('#workbench-model').count(),
    controls: await page.getByRole('combobox').evaluateAll(elements => elements.map(element => ({ tagName: element.tagName,
      id: element.id, role: element.getAttribute('role'), ariaLabel: element.getAttribute('aria-label'),
      ariaLabelledBy: element.getAttribute('aria-labelledby'), placeholder: element.getAttribute('placeholder') }))), blockedPosts };
  if (process.argv.includes('--options')) {
    const catalogResponse = await context.request.get(backend + '/api/v1/models');
    assert.equal(catalogResponse.status(), 200);
    snapshot.publicTarget = (await catalogResponse.json()).data.filter(item => item.id === 'deepseek:deepseek-flash')
      .map(item => ({ id: item.id, displayName: item.displayName, provider: item.provider }));
    await page.locator('#workbench-model').click();
    snapshot.options = await page.getByRole('option').evaluateAll(elements => elements.map(element => ({
      text: element.textContent, role: element.getAttribute('role'), ariaLabel: element.getAttribute('aria-label'),
      ariaSelected: element.getAttribute('aria-selected') })));
    snapshot.exactExpectedOptionCount = await page.getByRole('option', { name: snapshot.publicTarget[0].displayName.trim(), exact: true }).count();
  }
  assert.equal(snapshot.actualModelCalls, 0);
  await writeFile(resolve(output, process.argv.includes('--options') ? 'model-option-dom.json' : 'model-control-dom.json'), JSON.stringify(snapshot, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify(snapshot));
} finally { await context?.close(); await browser?.close(); await server.close(); }
