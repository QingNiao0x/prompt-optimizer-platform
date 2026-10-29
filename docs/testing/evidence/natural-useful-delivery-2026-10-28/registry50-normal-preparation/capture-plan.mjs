import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { here, repo, webRoot, backend, frontend, json, inspectStage, installProjectPicker, inspectIndex, inspectRecall } from './browser-support.mjs';

if (process.argv.includes('--help')) {
  console.log('node capture-plan.mjs <active-frozen-stage> <capture-id> <raw-prompt-file> [--capture-context]. Default preflight. Explicit capture uses real authentication/context preparation, then blocks Plan before any model request.');
  process.exit(0);
}
const stage = process.argv[2], captureId = process.argv[3], rawFile = process.argv[4];
assert.match(captureId ?? '', /^[A-Za-z0-9_-]+$/); assert.ok(rawFile);
const rawPrompt = await readFile(resolve(rawFile), 'utf8'); assert.ok(rawPrompt.trim());
assert.ok(rawPrompt.includes('@synthetic-survey-workspace/src/views/records'));
const frozen = await inspectStage(stage), { runtime } = frozen;
if (!process.argv.includes('--capture-context')) {
  console.log(JSON.stringify({ event: 'REGISTRY50_PLAN_CAPTURE_PREFLIGHT', stage, captureId, modelCalls: 0,
    requiresCaptureContext: true, sourcePackFilesVerified: frozen.sourcePackFilesVerified })); process.exit(0);
}
const output = resolve(here, 'plan-captures', stage, captureId);
await mkdir(output, { recursive: true });
await writeFile(resolve(output, 'invocation.json'), JSON.stringify({ stage, captureId, startedAt: new Date().toISOString(),
  sourceManifestSha256: frozen.sourceManifestSha256, modelCallsAllowed: 0 }, null, 2) + '\n', { flag: 'wx' });
const require = createRequire(resolve(webRoot, 'package.json'));
const { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
let server, browser, context, page, prepareInput, preparation, planInput, routeFailure;
let prepareRequests = 0, planRequests = 0, unexpectedGenerationRequests = 0;
try {
  server = await createServer({ root: webRoot, logLevel: 'warn', server: { host: '127.0.0.1', port: 5184, strictPort: true } });
  await server.listen();
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
  context = await browser.newContext({ storageState: resolve(runtime, 'auth/browser-storage-state.json'), locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const identity = await context.request.get(`${backend}/api/v1/auth/me`);
  assert.equal(identity.status(), 200);
  const actor = (await identity.json()).data, account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
  for (const field of ['userId', 'tenantId', 'workspaceId']) assert.ok(actor[field] === account[field], 'Unexpected authenticated account');
  page = await context.newPage();
  const fixture = await installProjectPicker(page, true);
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url());
    if (request.method() === 'POST' && url.pathname === '/api/v2/optimizations/plan') {
      planRequests += 1; planInput = request.postDataJSON();
      if (planRequests !== 1) routeFailure = 'Repeated Plan request blocked';
      await writeFile(resolve(output, 'plan-input.json'), JSON.stringify(planInput, null, 2) + '\n', { flag: 'wx' });
      await route.fulfill({ status: 409, json: { error: { message: 'Actual Plan DTO captured; model request intentionally blocked.' } } }); return;
    }
    if (request.method() === 'POST' && url.pathname.startsWith('/api/v2/optimizations')) {
      unexpectedGenerationRequests += 1; routeFailure = 'Unexpected model request blocked';
      await route.fulfill({ status: 409, json: { error: { message: routeFailure } } }); return;
    }
    if (request.method() === 'POST' && url.pathname === '/api/v2/context/planning') {
      prepareRequests += 1;
      if (prepareRequests !== 1) {
        routeFailure = 'Repeated deterministic preparation blocked';
        await route.fulfill({ status: 409, json: { error: { message: routeFailure } } }); return;
      }
      try {
        prepareInput = request.postDataJSON(); assert.equal(prepareInput.rawPrompt, rawPrompt);
        inspectRecall(prepareInput, fixture); await inspectStage(stage);
        await writeFile(resolve(output, 'prepare-input.json'), JSON.stringify(prepareInput, null, 2) + '\n', { flag: 'wx' });
        const response = await route.fetch({ url: `${backend}${url.pathname}`, maxRetries: 0, maxRedirects: 0, timeout: 30000 });
        preparation = { status: response.status(), body: await response.json() };
        await writeFile(resolve(output, 'prepare-response.json'), JSON.stringify(preparation, null, 2) + '\n', { flag: 'wx' });
        await route.fulfill({ response });
      } catch (failure) {
        routeFailure = failure.message;
        await route.fulfill({ status: 502, json: { error: { message: 'Single deterministic preparation failed; no retry.' } } });
      }
      return;
    }
    const response = await route.fetch({ url: `${backend}${url.pathname}${url.search}`, maxRetries: 0, maxRedirects: 0, timeout: 30000 });
    await route.fulfill({ response });
  });
  await page.goto(`${frontend}/workbench`); await page.waitForFunction(() => window.__registry50FixtureReady === true);
  await page.getByRole('button', { name: '添加上下文', exact: true }).click();
  await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await expect(page.getByText('79 个源码文件已建立本地索引', { exact: true })).toBeVisible({ timeout: 30000 });
  const indexedPaths = await inspectIndex(page, fixture);
  await writeFile(resolve(output, 'indexed-paths.json'), JSON.stringify(indexedPaths, null, 2) + '\n', { flag: 'wx' });
  await page.getByLabel('原始提示词', { exact: true }).fill(rawPrompt);
  await page.getByRole('button', { name: '先确认并增强', exact: true }).click();
  const consent = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  await expect(consent).toBeVisible(); await consent.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => Boolean(planInput) || Boolean(routeFailure), { timeout: 45000 }).toBe(true);
  assert.equal(routeFailure, undefined); assert.equal(prepareRequests, 1); assert.equal(planRequests, 1); assert.equal(unexpectedGenerationRequests, 0);
  assert.equal(preparation.status, 200); assert.equal(planInput.rawPrompt, rawPrompt);
  assert.deepEqual(Object.keys(planInput).sort(), ['contextDescription', 'conversationHistory', 'modelId', 'planningContext', 'rawPrompt']);
  assert.equal(planInput.planningContext.contextId, preparation.body.data.contextId);
  assert.equal(planInput.planningContext.version, preparation.body.data.version);
  assert.equal(planInput.modelId, 'deepseek:deepseek-flash');
  assert.ok(Date.parse(preparation.body.data.expiresAt) > Date.now());
  await writeFile(resolve(output, 'verdict.json'), JSON.stringify({ stage, captureId, capturedAt: new Date().toISOString(),
    actualModelCalls: 0, realAuthentication: true, realDeterministicPreparation: true, planBlockedBeforeModel: true,
    indexedFiles: indexedPaths.length, prepareRequests, planRequests, contextReferenceMatchesActualPreparation: true,
    contextExpiresAt: preparation.body.data.expiresAt, sourceManifestSha256: frozen.sourceManifestSha256,
    boundary: 'Real UI through deterministic preparation and intercepted Plan POST; no real Plan, answer or final generation executed.' }, null, 2) + '\n', { flag: 'wx' });
  await page.screenshot({ path: resolve(output, 'capture.png'), fullPage: true });
  console.log(JSON.stringify({ event: 'REGISTRY50_PLAN_CONTEXT_CAPTURED', stage, captureId, actualModelCalls: 0 }));
} catch (failure) {
  await page?.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {});
  await writeFile(resolve(output, 'failure.json'), JSON.stringify({ message: failure.message, routeFailure,
    prepareRequests, planRequests, unexpectedGenerationRequests, actualModelCalls: 0 }, null, 2) + '\n', { flag: 'wx' });
  throw failure;
} finally { await context?.close(); await browser?.close(); await server?.close(); }
