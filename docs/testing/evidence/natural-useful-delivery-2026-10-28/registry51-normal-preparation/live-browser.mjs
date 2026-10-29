import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { isDeepStrictEqual } from 'node:util';
import { here, repo, webRoot, backend, frontend, json, inspectStage, installProjectPicker, inspectIndex, inspectRecall, selectFlash } from './browser-support.mjs';

if (process.argv.includes('--help')) {
  console.log('node live-browser.mjs <open-frozen-Flash51-stage> C01_V51_GEN|P01_CAPTURE|P02_CAPTURE [--execute|--capture-context]. Default preflight only. C01 full UI; capture modes stop Plan with409 and make zero model calls.'); process.exit(0);
}
const stage = process.argv[2], id = process.argv[3], captureMode = ['P01_CAPTURE', 'P02_CAPTURE'].includes(id);
assert.ok(captureMode || id === 'C01_V51_GEN');
const recoveryIndex = process.argv.indexOf('--attempt');
const recovery = recoveryIndex < 0 ? null : process.argv[recoveryIndex + 1];
if (recovery) assert.ok(!captureMode && /^r[2-9][0-9]*$/.test(recovery));
const frozen = await inspectStage(stage, captureMode ? undefined : id), { runtime, job } = frozen;
const caseId = captureMode ? id.slice(0, 3) : 'C01';
const sample = captureMode ? (await json(resolve(here, 'plan-cases.json'))).cases.find(item => item.id === caseId)
  : (await json(resolve(here, 'generation-bundle.json'))).jobs.find(item => item.id === id).approvedEnhancementInput;
const rawPrompt = sample.rawPrompt;
if (!process.argv.includes(captureMode ? '--capture-context' : '--execute')) {
  console.log(JSON.stringify({ stage, id, preflightOnly: true, actualModelCalls: 0, browserStarted: false })); process.exit(0);
}
const firstOutput = resolve(here, captureMode ? 'plan-captures' : 'live', stage, captureMode ? caseId : id);
if (recovery) {
  const originalFailure = await json(resolve(firstOutput, 'failure.json'));
  assert.equal(originalFailure.posts, 0); assert.equal(originalFailure.preparations, 0); assert.equal(originalFailure.actualProductCaptured, false);
  // inspectStage above still refuses any paid attempt/result for this job.
}
const output = recovery ? resolve(firstOutput, recovery) : firstOutput;
await mkdir(output, { recursive: true });
const save = (name, value) => writeFile(resolve(output, name), typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
await save('invocation.json', { stage, id, captureMode, startedAt: new Date().toISOString(), automaticRetryAllowed: false });
const require = createRequire(resolve(webRoot, 'package.json')), { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
let server, browser, context, page, product, prepareInput, preparation, planInput, routeFailed = false, posts = 0, preparations = 0, historyRows = [];
const historyIds = [];
try {
  server = await createServer({ root: webRoot, logLevel: 'warn', server: { host: '127.0.0.1', port: 5184, strictPort: true } }); await server.listen();
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
  context = await browser.newContext({ storageState: resolve(runtime, 'auth/browser-storage-state.json'), permissions: ['clipboard-read', 'clipboard-write'], locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const auth = await context.request.get(backend + '/api/v1/auth/me'); assert.equal(auth.status(), 200);
  const actor = (await auth.json()).data, account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
  assert.ok(['userId', 'tenantId', 'workspaceId'].every(key => actor[key] === account[key] && account[key]));
  page = await context.newPage(); const fixture = await installProjectPicker(page, captureMode);
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url());
    if (request.method() === 'POST' && url.pathname.startsWith('/api/v2/optimizations')) {
      posts += 1; const actual = request.postDataJSON();
      if (captureMode) {
        if (posts !== 1 || url.pathname !== '/api/v2/optimizations/plan') routeFailed = true;
        else { planInput = actual; await save('plan-input.json', actual); }
        await route.fulfill({ status: 409, json: { error: { message: 'Actual Plan input captured only; no model request forwarded.' } } }); return;
      }
      await save('actual-input.json', actual);
      if (posts !== 1 || url.pathname !== '/api/v2/optimizations' || !isDeepStrictEqual(actual, job.approvedEnhancementInput)) {
        routeFailed = true; await route.fulfill({ status: 409, json: { error: { message: 'Unexpected or unfrozen UI request blocked.' } } }); return;
      }
      try {
        await inspectStage(stage, id); inspectRecall(actual, fixture);
        await writeFile(resolve(runtime, 'active-job.json'), JSON.stringify(job, null, 2) + '\n');
        const response = await route.fetch({ url: backend + '/api/v2/evaluation/candidate', headers: { ...request.headers(), 'X-Evaluation-Job': id }, postData: request.postData(), maxRetries: 0, maxRedirects: 0, timeout: 240000 });
        product = { id, caseId, phase: 'GENERATION', version: '51', status: response.status(), body: await response.json() };
        await mkdir(resolve(runtime, 'results'), { recursive: true });
        await writeFile(resolve(runtime, 'results', id + '.json'), JSON.stringify(product, null, 2) + '\n', { flag: 'wx' });
        await save('response.json', product); await route.fulfill({ response });
      } catch { routeFailed = true; await route.fulfill({ status: 502, json: { error: { message: 'Single request failed; no automatic retry.' } } }); }
      return;
    }
    if (captureMode && request.method() === 'POST' && url.pathname === '/api/v2/context/planning') {
      if (++preparations !== 1) { routeFailed = true; await route.fulfill({ status: 409, json: { error: { message: 'Extra preparation blocked.' } } }); return; }
      prepareInput = request.postDataJSON(); inspectRecall(prepareInput, fixture); await save('prepare-input.json', prepareInput);
      const response = await route.fetch({ url: backend + url.pathname, maxRetries: 0, maxRedirects: 0, timeout: 30000 });
      preparation = { status: response.status(), body: await response.json() }; await save('prepare-response.json', preparation); await route.fulfill({ response }); return;
    }
    const detail = url.pathname.match(/^\/api\/v2\/optimization-history\/([^/]+)$/); if (detail && request.method() === 'GET') historyIds.push(detail[1]);
    const response = await route.fetch({ url: backend + url.pathname + url.search, maxRetries: 0, maxRedirects: 0, timeout: 30000 });
    if (url.pathname === '/api/v2/optimization-history' && response.ok()) historyRows = (await response.json()).data.records;
    await route.fulfill({ response });
  });
  await page.goto(frontend + '/workbench'); await page.waitForFunction(() => window.__registry50FixtureReady === true);
  const actualUiModelSelection = await selectFlash(page, context, expect); await save('model-selection.json', actualUiModelSelection);
  await page.getByRole('button', { name: '添加上下文', exact: true }).click(); await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await expect(page.getByText('79 个源码文件已建立本地索引', { exact: true })).toBeVisible({ timeout: 30000 });
  await save('indexed-paths.json', await inspectIndex(page, fixture));
  await page.getByLabel('原始提示词', { exact: true }).fill(rawPrompt);
  await page.getByRole('button', { name: captureMode ? '先确认并增强' : '直接增强提示词', exact: true }).click();
  const consent = page.getByRole('dialog', { name: '确认发送上下文', exact: true }); await expect(consent).toBeVisible();
  await consent.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => Boolean(captureMode ? planInput : product) || routeFailed, { timeout: captureMode ? 45000 : 250000 }).toBe(true);
  assert.equal(routeFailed, false); assert.equal(posts, 1);
  if (captureMode) {
    assert.equal(preparations, 1); assert.equal(preparation.status, 200); assert.equal(planInput.rawPrompt, rawPrompt);
    assert.equal(planInput.modelId, 'deepseek:deepseek-flash');
    assert.deepEqual(planInput.planningContext, { contextId: preparation.body.data.contextId, version: preparation.body.data.version });
    assert.ok(Date.parse(preparation.body.data.expiresAt) > Date.now());
    await save('verdict.json', { stage, caseId, capturedAt: new Date().toISOString(), actualModelCalls: 0,
      realAuthentication: true, realDeterministicPreparation: true, planBlockedBeforeModel: true, indexedFiles: 79,
      contextExpiresAt: preparation.body.data.expiresAt, actualUiModelSelection, fullPlanAnswerFlowChecked: false });
  } else {
    assert.equal(product.status, 200); const result = product.body.data;
    assert.equal(result.chainVersion, '51'); assert.equal(result.provider.mock, false); assert.equal(result.provider.model, 'deepseek:deepseek-flash');
    assert.ok(result.processing.completedStages.includes('BODY_GENERATION'));
    const normalized = text => text.replace(/\r\n/g, '\n'), display = page.getByTestId('compact-prompt');
    await expect(display).toHaveText(result.optimizedPrompt); assert.equal(normalized(await display.textContent()), normalized(result.optimizedPrompt));
    await page.bringToFront(); await page.locator('.copy-main-button').click(); await expect(page.locator('.copy-main-button')).toContainText('已复制');
    const copied = await page.evaluate(() => navigator.clipboard.readText()); assert.equal(normalized(copied), normalized(result.optimizedPrompt)); await save('copy.txt', copied);
    const historical = await context.request.get(backend + '/api/v2/optimization-history/' + result.recordId); assert.equal(historical.status(), 200);
    const history = (await historical.json()).data; assert.equal(history.optimizedPrompt, result.optimizedPrompt); assert.equal(history.chainVersion, '51'); assert.deepEqual(history.processing, result.processing);
    await page.screenshot({ path: resolve(output, 'result.png'), fullPage: true }); await page.goto(frontend + '/history');
    await expect.poll(() => historyRows.findIndex(row => String(row.id) === String(result.recordId))).toBeGreaterThanOrEqual(0);
    await page.locator('.history-table .el-table__body tr').nth(historyRows.findIndex(row => String(row.id) === String(result.recordId))).getByRole('button', { name: '查看', exact: true }).click();
    const detail = page.getByRole('dialog', { name: '历史记录详情', exact: true }).locator('pre').nth(1); await expect(detail).toHaveText(result.optimizedPrompt);
    assert.equal(normalized(await detail.textContent()), normalized(result.optimizedPrompt)); assert.deepEqual(historyIds, [String(result.recordId)]);
    const check = { recordId: result.recordId, bodyEqual: true, versionEqual: true, processingEqual: true, exactHistoryUiRecord: true };
    await writeFile(resolve(runtime, 'results', id + '-history.json'), JSON.stringify(check, null, 2) + '\n', { flag: 'wx' });
    await save('verdict.json', { ...check, actualUiModelSelection, realAuthentication: true, full79Indexed: true, businessPosts: posts,
      frozenDtoMatched: true, bodyGenerated: true, nativeClipboardMatched: true, sourceManifestSha256: frozen.sourceManifestSha256,
      defaultEntrypointChecked: false, qualityBenefitProven: false });
  }
  await page.screenshot({ path: resolve(output, captureMode ? 'capture.png' : 'history.png'), fullPage: true });
  console.log(JSON.stringify({ stage, id, captureMode, verified: true, fullUiPlanAnswerFlowChecked: false, qualityBenefitProven: false }));
} catch {
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {});
  await save('failure.json', { posts, preparations, routeFailed, actualProductCaptured: Boolean(product), retryAllowed: false, rawExceptionOmitted: true });
  throw Error('Future51 UI check failed; original attempt retained, no automatic retry.');
} finally { await context?.close(); await browser?.close(); await server?.close(); }
