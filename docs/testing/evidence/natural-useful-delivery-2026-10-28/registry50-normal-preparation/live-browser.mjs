import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { isDeepStrictEqual } from 'node:util';
import { here, repo, webRoot, backend, frontend, json, absent, hash, inspectStage,
  installProjectPicker, inspectIndex, inspectRecall } from './browser-support.mjs';

if (process.argv.includes('--help')) {
  console.log('node registry50-normal-preparation/live-browser.mjs <frozen-stage> C01_V50_GEN [--execute]. Default read-only preflight; execute only after LOGIN_READY and exclusive authorization.');
  process.exit(0);
}
const stage = process.argv[2], id = process.argv[3];
assert.equal(id, 'C01_V50_GEN');
const frozen = await inspectStage(stage, id), { runtime, protocol, job } = frozen;
assert.equal(job.phase, 'GENERATION'); assert.equal(job.caseId, 'C01');
assert.notEqual(job.entrypoint, 'DEFAULT', 'This tool only verifies the explicitly frozen candidate entrypoint');
const input = job.approvedEnhancementInput;
const sample = (await json(resolve(here, '../browser/cases.json'))).cases.find(item => item.id === 'C01');
assert.equal(input.rawPrompt, sample.rawPrompt);
assert.equal(input.modelId, protocol.modelId);
assert.equal(input.enhancement.promptFormat, 'COMPACT');
assert.equal(input.planConfirmation, null);
assert.equal(protocol.frozenBundleSha256, hash(await readFile(resolve(here, 'generation-bundle.json'))));
if (!process.argv.includes('--execute')) {
  console.log(JSON.stringify({ event: 'REGISTRY50_BROWSER_PREFLIGHT', stage, id, modelCalls: 0,
    sourcePackFilesVerified: frozen.sourcePackFilesVerified, requiresExecute: true }));
  process.exit(0);
}

const output = resolve(here, 'live', stage, id);
await mkdir(output, { recursive: true });
await writeFile(resolve(output, 'invocation.json'), JSON.stringify({ stage, id, version: '50',
  sourceManifestSha256: frozen.sourceManifestSha256, sourcePackFilesVerified: frozen.sourcePackFilesVerified,
  requestedAt: new Date().toISOString(), automaticRetryAllowed: false }, null, 2) + '\n', { flag: 'wx' });
const require = createRequire(resolve(webRoot, 'package.json'));
const { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
let server, browser, context, page, responseRecord, routeFailure;
let businessRequests = 0, historyRows = [];
const historyDetailIds = [];
try {
  // Own this port: fail if occupied rather than reusing an unknown frontend build.
  server = await createServer({ root: webRoot, logLevel: 'warn',
    server: { host: '127.0.0.1', port: 5184, strictPort: true } });
  await server.listen();
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
  context = await browser.newContext({ storageState: resolve(runtime, 'auth/browser-storage-state.json'),
    permissions: ['clipboard-read', 'clipboard-write'], locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const identity = await context.request.get(`${backend}/api/v1/auth/me`);
  assert.equal(identity.status(), 200, 'Fixed synthetic account is not logged in');
  const actor = (await identity.json()).data, account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
  for (const field of ['userId', 'tenantId', 'workspaceId']) assert.ok(actor[field] === account[field], 'Authenticated actor differs from fixed synthetic account');
  page = await context.newPage();
  const fixture = await installProjectPicker(page, false);
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url());
    if (request.method() === 'POST' && url.pathname.startsWith('/api/v2/optimizations')) {
      businessRequests += 1;
      if (url.pathname !== '/api/v2/optimizations' || businessRequests !== 1) {
        routeFailure = 'Unexpected or repeated model-bearing request blocked';
        await route.fulfill({ status: 409, json: { error: { message: routeFailure } } }); return;
      }
      const actual = request.postDataJSON();
      await writeFile(resolve(output, 'actual-input.json'), JSON.stringify(actual, null, 2) + '\n', { flag: 'wx' });
      if (!isDeepStrictEqual(actual, input)) {
        routeFailure = 'Actual UI DTO differs from frozen DTO; model request blocked';
        await route.fulfill({ status: 409, json: { error: { message: routeFailure } } }); return;
      }
      try {
        // Recheck hashes/closure immediately before the only business POST.
        await inspectStage(stage, id);
        inspectRecall(actual, fixture);
        await writeFile(resolve(output, 'attempt.json'), JSON.stringify({ exactFrozenDto: true, businessRequests,
          sourceManifestSha256: frozen.sourceManifestSha256, sourcePackFilesVerified: frozen.sourcePackFilesVerified }, null, 2) + '\n', { flag: 'wx' });
        await writeFile(resolve(runtime, 'active-job.json'), JSON.stringify(job, null, 2) + '\n');
        const response = await route.fetch({ url: `${backend}/api/v2/evaluation/candidate`,
          headers: { ...request.headers(), 'X-Evaluation-Job': id }, postData: request.postData(),
          maxRetries: 0, maxRedirects: 0, timeout: 240000 });
        responseRecord = { id, phase: job.phase, version: job.version, caseId: job.caseId,
          status: response.status(), body: await response.json() };
        await mkdir(resolve(runtime, 'results'), { recursive: true });
        await writeFile(resolve(runtime, 'results', `${id}.json`), JSON.stringify(responseRecord, null, 2) + '\n', { flag: 'wx' });
        await writeFile(resolve(output, 'response.json'), JSON.stringify(responseRecord, null, 2) + '\n', { flag: 'wx' });
        await route.fulfill({ response });
      } catch (failure) {
        routeFailure = failure.message;
        await route.fulfill({ status: 502, json: { error: { message: 'Single real request failed; no automatic retry.' } } });
      }
      return;
    }
    const detail = url.pathname.match(/^\/api\/v2\/optimization-history\/([^/]+)$/);
    if (detail && request.method() === 'GET') historyDetailIds.push(detail[1]);
    const response = await route.fetch({ url: `${backend}${url.pathname}${url.search}`,
      maxRetries: 0, maxRedirects: 0, timeout: 30000 });
    if (url.pathname === '/api/v2/optimization-history' && request.method() === 'GET' && response.ok()) {
      historyRows = (await response.json()).data.records;
    }
    await route.fulfill({ response });
  });
  await page.goto(`${frontend}/workbench`);
  await page.waitForFunction(() => window.__registry50FixtureReady === true);
  await page.getByRole('button', { name: '添加上下文', exact: true }).click();
  await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await expect(page.getByText('79 个源码文件已建立本地索引', { exact: true })).toBeVisible({ timeout: 30000 });
  const indexedPaths = await inspectIndex(page, fixture);
  await writeFile(resolve(output, 'indexed-paths.json'), JSON.stringify(indexedPaths, null, 2) + '\n', { flag: 'wx' });
  await page.getByLabel('原始提示词', { exact: true }).fill(input.rawPrompt);
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  const consent = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  await expect(consent).toBeVisible();
  await page.screenshot({ path: resolve(output, 'consent.png'), fullPage: true });
  await consent.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => Boolean(responseRecord) || Boolean(routeFailure), { timeout: 250000 }).toBe(true);
  assert.equal(routeFailure, undefined); assert.equal(responseRecord.status, 200); assert.equal(businessRequests, 1);
  const generated = responseRecord.body.data;
  assert.equal(generated.chainVersion, '50'); assert.equal(generated.provider.mock, false);
  assert.ok(generated.processing.completedStages.includes('BODY_GENERATION'));
  const visible = page.getByTestId('compact-prompt');
  await expect(visible).toHaveText(generated.optimizedPrompt);
  const normalize = text => text.replace(/\r\n/g, '\n');
  const display = await visible.textContent(); assert.equal(normalize(display), normalize(generated.optimizedPrompt));
  await page.bringToFront(); await page.locator('.copy-main-button').click();
  await expect(page.locator('.copy-main-button')).toContainText('已复制');
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  assert.equal(normalize(copied), normalize(generated.optimizedPrompt));
  const historyResponse = await context.request.get(`${backend}/api/v2/optimization-history/${generated.recordId}`);
  assert.equal(historyResponse.status(), 200);
  const history = (await historyResponse.json()).data;
  assert.equal(history.optimizedPrompt, generated.optimizedPrompt);
  assert.equal(history.chainVersion, '50'); assert.ok(isDeepStrictEqual(history.processing, generated.processing));
  await writeFile(resolve(output, 'copy.txt'), copied, { flag: 'wx' });
  await writeFile(resolve(output, 'display.txt'), display, { flag: 'wx' });
  const retrieval = await page.evaluate(async () => (await import('/src/stores/optimization.ts')).useOptimizationStore().contextRetrieval);
  await writeFile(resolve(output, 'retrieval.json'), JSON.stringify(retrieval, null, 2) + '\n', { flag: 'wx' });
  await page.screenshot({ path: resolve(output, 'result.png'), fullPage: true });
  await page.goto(`${frontend}/history`);
  await expect.poll(() => historyRows.findIndex(row => String(row.id) === String(generated.recordId)), { timeout: 30000 }).toBeGreaterThanOrEqual(0);
  const rowIndex = historyRows.findIndex(row => String(row.id) === String(generated.recordId));
  // Locate the row by the actual list API recordId mapping, never by "first view".
  await page.locator('.history-table .el-table__body tr').nth(rowIndex).getByRole('button', { name: '查看', exact: true }).click();
  const historical = page.getByRole('dialog', { name: '历史记录详情', exact: true }).locator('pre').nth(1);
  await expect(historical).toHaveText(generated.optimizedPrompt);
  assert.equal(normalize(await historical.textContent()), normalize(generated.optimizedPrompt));
  assert.deepEqual(historyDetailIds, [String(generated.recordId)]);
  await page.screenshot({ path: resolve(output, 'history.png'), fullPage: true });
  const historyCheck = { recordId: generated.recordId, bodyEqual: true, versionEqual: true, processingEqual: true, exactHistoryUiRecord: true };
  await writeFile(resolve(runtime, 'results', `${id}-history.json`), JSON.stringify(historyCheck, null, 2) + '\n', { flag: 'wx' });
  await writeFile(resolve(output, 'verdict.json'), JSON.stringify({ stage, id, version: '50', businessRequests,
    providerMock: false, bodyGenerationCompleted: true, inputEqualsFrozen: true, realAuthenticationVerified: true,
    indexedFiles: indexedPaths.length, targetRecallVerified: true, nativeClipboardVerified: true,
    ...historyCheck, sourceManifestSha256: frozen.sourceManifestSha256,
    sourcePackFilesVerified: frozen.sourcePackFilesVerified, candidateEntrypointOnly: true,
    downstreamWorkExecuted: false, qualityBenefitProven: false }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ event: 'REGISTRY50_REAL_BROWSER_COMPLETE', stage, id, businessRequests,
    version: '50', exactHistoryUiRecord: true, qualityBenefitProven: false }));
} catch (failure) {
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {});
  await writeFile(resolve(output, 'failure.json'), JSON.stringify({ stage, id, businessRequests,
    responseCaptured: Boolean(responseRecord), message: failure.message, routeFailure, retryAllowed: false }, null, 2) + '\n', { flag: 'wx' });
  throw failure;
} finally {
  await context?.close(); await browser?.close(); await server?.close();
}
