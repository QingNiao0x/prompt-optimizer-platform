import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { isDeepStrictEqual } from 'node:util';
import { here, repo, webRoot, backend, frontend, modelId, json, inspectFutureStage,
  installProjectPicker, inspectIndex, inspectRecall, selectNativePro } from './support.mjs';

if (process.argv.includes('--help')) {
  console.log('node live-browser.mjs <future-frozen-stage> C01_V50_PRO_GEN [--execute]. Default preflight. Requires implemented NATIVE_PRO50_NORMAL_GEN_V1, LOGIN_READY and exclusive authorization. Only C01 receives full real UI coverage.'); process.exit(0);
}
const stage = process.argv[2], id = process.argv[3]; assert.equal(id, 'C01_V50_PRO_GEN');
const guard = () => inspectFutureStage(stage, 'NATIVE_PRO50_NORMAL_GEN_V1', 20, id);
const frozen = await guard(), { runtime, job } = frozen;
assert.equal(job.phase, 'GENERATION'); assert.equal(job.caseId, 'C01');
const proposed = (await json(resolve(here, 'generation-bundle.json'))).jobs.find(item => item.id === id);
assert.ok(isDeepStrictEqual(job.approvedEnhancementInput, proposed.approvedEnhancementInput));
if (!process.argv.includes('--execute')) {
  console.log(JSON.stringify({ stage, id, preflightOnly: true, modelCalls: 0, browserStarted: false })); process.exit(0);
}
const output = resolve(here, 'live', stage, id); await mkdir(output, { recursive: true });
await writeFile(resolve(output, 'invocation.json'), JSON.stringify({ stage, id, sourceManifestSha256: frozen.sourceManifestSha256,
  startedAt: new Date().toISOString(), retryAllowed: false }, null, 2) + '\n', { flag: 'wx' });
const require = createRequire(resolve(webRoot, 'package.json')), { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
let server, browser, context, page, productResponse, routeFailed = false, businessRequests = 0, historyRows = [];
const historyIds = [];
try {
  server = await createServer({ root: webRoot, logLevel: 'warn', server: { host: '127.0.0.1', port: 5184, strictPort: true } }); await server.listen();
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
  context = await browser.newContext({ storageState: resolve(runtime, 'auth/browser-storage-state.json'),
    permissions: ['clipboard-read', 'clipboard-write'], locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const identity = await context.request.get(backend + '/api/v1/auth/me'); assert.equal(identity.status(), 200);
  const actor = (await identity.json()).data, account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
  assert.ok(['userId', 'tenantId', 'workspaceId'].every(key => actor[key] === account[key] && account[key]));
  page = await context.newPage(); const fixture = await installProjectPicker(page, false);
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url());
    if (request.method() === 'POST' && url.pathname.startsWith('/api/v2/optimizations')) {
      businessRequests += 1;
      const actual = request.postDataJSON();
      await writeFile(resolve(output, 'actual-input.json'), JSON.stringify(actual, null, 2) + '\n', { flag: 'wx' });
      if (url.pathname !== '/api/v2/optimizations' || businessRequests !== 1 || !isDeepStrictEqual(actual, job.approvedEnhancementInput)) {
        routeFailed = true; await route.fulfill({ status: 409, json: { error: { message: 'Unexpected UI DTO or extra request blocked before model.' } } }); return;
      }
      try {
        await guard(); inspectRecall(actual, fixture); assert.equal(actual.modelId, modelId);
        await writeFile(resolve(runtime, 'active-job.json'), JSON.stringify(job, null, 2) + '\n');
        const response = await route.fetch({ url: backend + '/api/v2/evaluation/candidate',
          headers: { ...request.headers(), 'X-Evaluation-Job': id }, postData: request.postData(), maxRetries: 0, maxRedirects: 0, timeout: 240000 });
        productResponse = { id, phase: 'GENERATION', version: '50', caseId: 'C01', status: response.status(), body: await response.json() };
        await mkdir(resolve(runtime, 'results'), { recursive: true });
        await writeFile(resolve(runtime, 'results', id + '.json'), JSON.stringify(productResponse, null, 2) + '\n', { flag: 'wx' });
        await writeFile(resolve(output, 'response.json'), JSON.stringify(productResponse, null, 2) + '\n', { flag: 'wx' });
        await route.fulfill({ response });
      } catch {
        routeFailed = true; await route.fulfill({ status: 502, json: { error: { message: 'Single live request failed; no automatic retry.' } } });
      }
      return;
    }
    const match = url.pathname.match(/^\/api\/v2\/optimization-history\/([^/]+)$/);
    if (match && request.method() === 'GET') historyIds.push(match[1]);
    const response = await route.fetch({ url: backend + url.pathname + url.search, maxRetries: 0, maxRedirects: 0, timeout: 30000 });
    if (url.pathname === '/api/v2/optimization-history' && response.ok()) historyRows = (await response.json()).data.records;
    await route.fulfill({ response });
  });
  await page.goto(frontend + '/workbench'); await page.waitForFunction(() => window.__registry50FixtureReady === true);
  const selectedModel = await selectNativePro(page, context, expect);
  await writeFile(resolve(output, 'actual-model-selection.json'), JSON.stringify(selectedModel, null, 2) + '\n', { flag: 'wx' });
  await page.getByRole('button', { name: '添加上下文', exact: true }).click();
  await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await expect(page.getByText('79 个源码文件已建立本地索引', { exact: true })).toBeVisible({ timeout: 30000 });
  const paths = await inspectIndex(page, fixture);
  await writeFile(resolve(output, 'indexed-paths.json'), JSON.stringify(paths, null, 2) + '\n', { flag: 'wx' });
  await page.getByLabel('原始提示词', { exact: true }).fill(job.approvedEnhancementInput.rawPrompt);
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  const consent = page.getByRole('dialog', { name: '确认发送上下文', exact: true }); await expect(consent).toBeVisible();
  await consent.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => Boolean(productResponse) || routeFailed, { timeout: 250000 }).toBe(true);
  assert.equal(routeFailed, false); assert.equal(productResponse.status, 200); assert.equal(businessRequests, 1);
  const product = productResponse.body.data;
  assert.equal(product.chainVersion, '50'); assert.equal(product.provider.mock, false); assert.equal(product.provider.model, modelId);
  assert.ok(product.processing.completedStages.includes('BODY_GENERATION'));
  const normalized = text => text.replace(/\r\n/g, '\n'), displayed = page.getByTestId('compact-prompt');
  await expect(displayed).toHaveText(product.optimizedPrompt);
  assert.equal(normalized(await displayed.textContent()), normalized(product.optimizedPrompt));
  await page.bringToFront(); await page.locator('.copy-main-button').click();
  await expect(page.locator('.copy-main-button')).toContainText('已复制');
  const copied = await page.evaluate(() => navigator.clipboard.readText()); assert.equal(normalized(copied), normalized(product.optimizedPrompt));
  await writeFile(resolve(output, 'copy.txt'), copied, { flag: 'wx' });
  await page.screenshot({ path: resolve(output, 'result.png'), fullPage: true });
  const historyResponse = await context.request.get(backend + '/api/v2/optimization-history/' + product.recordId);
  assert.equal(historyResponse.status(), 200); const history = (await historyResponse.json()).data;
  assert.equal(history.optimizedPrompt, product.optimizedPrompt); assert.equal(history.chainVersion, '50');
  assert.ok(isDeepStrictEqual(history.processing, product.processing));
  await page.goto(frontend + '/history');
  await expect.poll(() => historyRows.findIndex(row => String(row.id) === String(product.recordId))).toBeGreaterThanOrEqual(0);
  const index = historyRows.findIndex(row => String(row.id) === String(product.recordId));
  await page.locator('.history-table .el-table__body tr').nth(index).getByRole('button', { name: '查看', exact: true }).click();
  const detail = page.getByRole('dialog', { name: '历史记录详情', exact: true }).locator('pre').nth(1);
  await expect(detail).toHaveText(product.optimizedPrompt); assert.equal(normalized(await detail.textContent()), normalized(product.optimizedPrompt));
  assert.deepEqual(historyIds, [String(product.recordId)]);
  await page.screenshot({ path: resolve(output, 'history.png'), fullPage: true });
  const check = { recordId: product.recordId, bodyEqual: true, versionEqual: true, processingEqual: true, exactHistoryUiRecord: true };
  await writeFile(resolve(runtime, 'results', id + '-history.json'), JSON.stringify(check, null, 2) + '\n', { flag: 'wx' });
  await writeFile(resolve(output, 'verdict.json'), JSON.stringify({ ...check, actualUiModelSelection: selectedModel,
    sourceManifestSha256: frozen.sourceManifestSha256, sourcePackFilesVerified: frozen.sourcePackFilesVerified,
    realAuthentication: true, full79Indexed: true, inputExactlyFrozen: true, businessRequests,
    bodyGenerated: true, nativeClipboardMatched: true, qualityBenefitProven: false, defaultEntrypointChecked: false }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ event: 'NATIVE_PRO50_C01_UI_COMPLETED', businessRequests, exactHistoryUiRecord: true, qualityBenefitProven: false }));
} catch {
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {});
  await writeFile(resolve(output, 'failure.json'), JSON.stringify({ businessRequests, routeFailed, responseCaptured: Boolean(productResponse),
    retryAllowed: false, rawExceptionOmitted: true }, null, 2) + '\n', { flag: 'wx' });
  throw Error('Native Pro UI verification failed; original attempt retained, no automatic retry.');
} finally { await context?.close(); await browser?.close(); await server?.close(); }
