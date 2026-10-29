import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { here, repo, webRoot, backend, frontend, json, hash, branchIds, loadCases, inspectCaptureStage,
  installProjectPicker, inspectIndex, inspectRecall, selectFlash } from './browser-support.mjs';

if (process.argv.includes('--help')) {
  console.log('node live-browser.mjs <open-frozen-captureOnly61-stage> P02_CONFIRMED [--capture-context]. Default preflight only; real context preparation then final Plan409, zero model calls.'); process.exit(0);
}
const stage = process.argv[2], caseId = process.argv[3]; assert.ok(branchIds.includes(caseId));
const frozen = await inspectCaptureStage(stage), sample = (await loadCases()).find(item => item.id === caseId);
const frozenCases = (await json(resolve(frozen.stageEvidence, 'cases.json'))).cases;
assert.deepEqual(frozenCases.map(item => item.id).sort(), [...branchIds].sort());
for (const item of frozenCases) assert.equal(item.rawPrompt, sample.rawPrompt);
if (!process.argv.includes('--capture-context')) {
  console.log(JSON.stringify({ stage, caseId, preflightOnly: true, browserStarted: false, actualModelCalls: 0 })); process.exit(0);
}
const output = resolve(here, 'plan-captures', stage, caseId); await mkdir(output, { recursive: true });
const save = (name, value) => writeFile(resolve(output, name), typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
await save('invocation.json', { stage, caseId, startedAt: new Date().toISOString(), automaticRetryAllowed: false, modelForwardingSupported: false });
const require = createRequire(resolve(webRoot, 'package.json')), { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
let server, browser, context, page, planInput, prepareInput, preparation, preparations = 0, posts = 0, routeFailed = false;
try {
  server = await createServer({ root: webRoot, logLevel: 'warn', server: { host: '127.0.0.1', port: 5184, strictPort: true } }); await server.listen();
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
  context = await browser.newContext({ storageState: resolve(frozen.runtime, 'auth/browser-storage-state.json'), locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const auth = await context.request.get(backend + '/api/v1/auth/me'); assert.equal(auth.status(), 200);
  const actor = (await auth.json()).data, account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
  assert.ok(['userId', 'tenantId', 'workspaceId'].every(key => actor[key] === account[key] && account[key]));
  const actorScopeSha256 = hash(JSON.stringify(['userId', 'tenantId', 'workspaceId'].map(key => actor[key])));
  page = await context.newPage(); const fixture = await installProjectPicker(page, true);
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url());
    if (request.method() === 'POST' && url.pathname === '/api/v2/context/planning') {
      if (++preparations !== 1) { routeFailed = true; await route.fulfill({ status: 409, json: { error: { message: 'Extra context preparation blocked.' } } }); return; }
      try {
        await inspectCaptureStage(stage);
        prepareInput = request.postDataJSON(); assert.equal(prepareInput.rawPrompt, sample.rawPrompt); inspectRecall(prepareInput, fixture);
        await save('prepare-input.json', prepareInput);
        const response = await route.fetch({ url: backend + url.pathname, maxRetries: 0, maxRedirects: 0, timeout: 30000 });
        preparation = { status: response.status(), body: await response.json() }; await save('prepare-response.json', preparation); await route.fulfill({ response });
      } catch { routeFailed = true; await route.fulfill({ status: 502, json: { error: { message: 'Context preparation failed; no automatic retry.' } } }); }
      return;
    }
    if (request.method() === 'POST' && url.pathname === '/api/v2/optimizations/plan') {
      posts += 1;
      if (posts !== 1) routeFailed = true;
      else { planInput = request.postDataJSON(); await save('plan-input.json', planInput); }
      await route.fulfill({ status: 409, json: { error: { message: 'Actual Plan input captured only; no model request forwarded.' } } }); return;
    }
    if (request.method() !== 'GET') {
      routeFailed = true; await route.fulfill({ status: 409, json: { error: { message: 'Only one deterministic context request is allowed.' } } }); return;
    }
    const response = await route.fetch({ url: backend + url.pathname + url.search, maxRetries: 0, maxRedirects: 0, timeout: 30000 }); await route.fulfill({ response });
  });
  await page.goto(frontend + '/workbench'); await page.waitForFunction(() => window.__registry50FixtureReady === true);
  const actualUiModelSelection = await selectFlash(page, context, expect); await save('model-selection.json', actualUiModelSelection);
  await page.getByRole('button', { name: '添加上下文', exact: true }).click(); await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await expect(page.getByText('79 个源码文件已建立本地索引', { exact: true })).toBeVisible({ timeout: 30000 });
  await save('indexed-paths.json', await inspectIndex(page, fixture));
  await page.getByLabel('原始提示词', { exact: true }).fill(sample.rawPrompt);
  await page.getByRole('button', { name: '先确认并增强', exact: true }).click();
  const consent = page.getByRole('dialog', { name: '确认发送上下文', exact: true }); await expect(consent).toBeVisible();
  await consent.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => Boolean(planInput) || routeFailed, { timeout: 45000 }).toBe(true);
  assert.equal(routeFailed, false); assert.equal(posts, 1); assert.equal(preparations, 1); assert.equal(preparation.status, 200);
  assert.equal(planInput.rawPrompt, sample.rawPrompt); assert.equal(planInput.modelId, 'deepseek:deepseek-flash');
  assert.deepEqual(planInput.planningContext, { contextId: preparation.body.data.contextId, version: preparation.body.data.version });
  assert.ok(Date.parse(preparation.body.data.expiresAt) > Date.now());
  await save('verdict.json', { stage, caseId, strategyVersion: '61', capturedAt: new Date().toISOString(), actualModelCalls: 0, realAuthentication: true,
    actorScopeSha256, realDeterministicPreparation: true, planBlockedBeforeModel: true, indexedFiles: 79,
    contextExpiresAt: preparation.body.data.expiresAt, actualUiModelSelection, sourceManifestSha256: frozen.sourceManifestSha256,
    fullPlanAnswerFlowChecked: false, actualOwnershipValidationPending: true });
  await page.screenshot({ path: resolve(output, 'capture.png'), fullPage: true });
  console.log(JSON.stringify({ stage, caseId, captured: true, contextExpiresAt: preparation.body.data.expiresAt, actualModelCalls: 0, planForwarded: false }));
} catch {
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {});
  await save('failure.json', { posts, preparations, routeFailed, actualModelCalls: 0, retryAllowed: false, rawExceptionOmitted: true });
  throw Error('Capture-only61 UI preparation failed; original attempt retained.');
} finally { await context?.close(); await browser?.close(); await server?.close(); }
