import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { here, repo, webRoot, backend, frontend, json, inspectFutureStage, installProjectPicker,
  inspectIndex, inspectRecall, selectNativePro } from './support.mjs';

if (process.argv.includes('--help')) {
  console.log('node capture-plan.mjs <still-open-native-Pro-normal-stage> P01|P02 [--capture-context]. Default preflight. Future explicit capture selects published Pro in UI, prepares real owned context, and blocks Plan/final generation before model.'); process.exit(0);
}
const stage = process.argv[2], caseId = process.argv[3]; assert.ok(['P01', 'P02'].includes(caseId));
const sample = (await json(resolve(here, 'plan-cases.json'))).cases.find(item => item.id === caseId);
const frozen = await inspectFutureStage(stage, 'NATIVE_PRO50_NORMAL_GEN_V1', 20);
if (!process.argv.includes('--capture-context')) {
  console.log(JSON.stringify({ stage, caseId, preflightOnly: true, actualModelCalls: 0 })); process.exit(0);
}
const output = resolve(here, 'plan-captures', stage, caseId); await mkdir(output, { recursive: true });
await writeFile(resolve(output, 'invocation.json'), JSON.stringify({ stage, caseId, startedAt: new Date().toISOString(), modelCallsAllowed: 0 }, null, 2) + '\n', { flag: 'wx' });
const require = createRequire(resolve(webRoot, 'package.json')), { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
let server, browser, context, page, preparation, prepareInput, planInput, routeFailed = false, prepareRequests = 0, planRequests = 0;
try {
  server = await createServer({ root: webRoot, logLevel: 'warn', server: { host: '127.0.0.1', port: 5184, strictPort: true } }); await server.listen();
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
  context = await browser.newContext({ storageState: resolve(frozen.runtime, 'auth/browser-storage-state.json'), locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const identity = await context.request.get(backend + '/api/v1/auth/me'); assert.equal(identity.status(), 200);
  const actor = (await identity.json()).data, account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
  assert.ok(['userId', 'tenantId', 'workspaceId'].every(key => actor[key] === account[key] && account[key]));
  page = await context.newPage(); const fixture = await installProjectPicker(page, true);
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url());
    if (request.method() === 'POST' && url.pathname.startsWith('/api/v2/optimizations')) {
      if (url.pathname !== '/api/v2/optimizations/plan' || ++planRequests !== 1) routeFailed = true;
      else {
        planInput = request.postDataJSON();
        await writeFile(resolve(output, 'plan-input.json'), JSON.stringify(planInput, null, 2) + '\n', { flag: 'wx' });
      }
      await route.fulfill({ status: 409, json: { error: { message: 'Real Plan DTO captured only; model-bearing POST blocked.' } } }); return;
    }
    if (request.method() === 'POST' && url.pathname === '/api/v2/context/planning') {
      if (++prepareRequests !== 1) { routeFailed = true; await route.fulfill({ status: 409, json: { error: { message: 'Repeated context preparation blocked.' } } }); return; }
      prepareInput = request.postDataJSON(); inspectRecall(prepareInput, fixture);
      await inspectFutureStage(stage, 'NATIVE_PRO50_NORMAL_GEN_V1', 20);
      await writeFile(resolve(output, 'prepare-input.json'), JSON.stringify(prepareInput, null, 2) + '\n', { flag: 'wx' });
      const response = await route.fetch({ url: backend + url.pathname, maxRetries: 0, maxRedirects: 0, timeout: 30000 });
      preparation = { status: response.status(), body: await response.json() };
      await writeFile(resolve(output, 'prepare-response.json'), JSON.stringify(preparation, null, 2) + '\n', { flag: 'wx' });
      await route.fulfill({ response }); return;
    }
    const response = await route.fetch({ url: backend + url.pathname + url.search, maxRetries: 0, maxRedirects: 0, timeout: 30000 });
    await route.fulfill({ response });
  });
  await page.goto(frontend + '/workbench'); await page.waitForFunction(() => window.__registry50FixtureReady === true);
  const selection = await selectNativePro(page, context, expect);
  await page.getByRole('button', { name: '添加上下文', exact: true }).click(); await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await expect(page.getByText('79 个源码文件已建立本地索引', { exact: true })).toBeVisible({ timeout: 30000 });
  const paths = await inspectIndex(page, fixture);
  await writeFile(resolve(output, 'indexed-paths.json'), JSON.stringify(paths, null, 2) + '\n', { flag: 'wx' });
  await page.getByLabel('原始提示词', { exact: true }).fill(sample.rawPrompt);
  await page.getByRole('button', { name: '先确认并增强', exact: true }).click();
  const consent = page.getByRole('dialog', { name: '确认发送上下文', exact: true }); await expect(consent).toBeVisible();
  await consent.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => Boolean(planInput) || routeFailed, { timeout: 45000 }).toBe(true);
  assert.equal(routeFailed, false); assert.equal(prepareRequests, 1); assert.equal(planRequests, 1); assert.equal(preparation.status, 200);
  assert.equal(planInput.rawPrompt, sample.rawPrompt); assert.equal(planInput.modelId, 'deepseek:deepseek-v4-pro');
  assert.deepEqual(planInput.planningContext, { contextId: preparation.body.data.contextId, version: preparation.body.data.version });
  assert.ok(Date.parse(preparation.body.data.expiresAt) > Date.now());
  await writeFile(resolve(output, 'verdict.json'), JSON.stringify({ stage, caseId, capturedAt: new Date().toISOString(),
    actualModelCalls: 0, realAuthentication: true, realDeterministicPreparation: true, planBlockedBeforeModel: true,
    indexedFiles: 79, contextExpiresAt: preparation.body.data.expiresAt, actualUiModelSelection: selection,
    sourceManifestSha256: frozen.sourceManifestSha256, finalPlanOrGenerationExecuted: false }, null, 2) + '\n', { flag: 'wx' });
  await page.screenshot({ path: resolve(output, 'capture.png'), fullPage: true });
  console.log(JSON.stringify({ event: 'NATIVE_PRO50_PLAN_CONTEXT_CAPTURED', caseId, actualModelCalls: 0 }));
} finally { await context?.close(); await browser?.close(); await server?.close(); }
