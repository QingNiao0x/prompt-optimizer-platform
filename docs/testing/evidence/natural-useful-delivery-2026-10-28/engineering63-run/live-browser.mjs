import assert from 'node:assert/strict';
import { selectFlash } from '../registry51-normal-preparation/browser-support.mjs';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

const here = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(here, '..');
const repo = resolve(evidence, '../../../..');
const webRoot = resolve(repo, 'apps/web');
const stage = process.argv[2];
const id = process.argv[3];
if (process.argv.includes('--help')) {
  console.log('Usage: node engineering-run/live-browser.mjs <frozen-stage> E01_V63_GEN [--execute]. Default preflight only. Execute only after LOGIN_READY and exclusive browser authorization.');
  process.exit(0);
}
if (!/^[a-z0-9-]+$/.test(stage ?? '') || id !== 'E01_V63_GEN') {
  throw new Error('Only a frozen stage and E01_V63_GEN are accepted; default is zero-model preflight.');
}
const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', stage);
const stageEvidence = resolve(evidence, stage);
const sealed = resolve(evidence, 'sealed-engineering-holdout');
const backend = 'http://127.0.0.1:9014';
const frontend = 'http://127.0.0.1:5194';
const hash = (value) => createHash('sha256').update(value).digest('hex');
const json = async (file) => JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));
const absent = async (file, message) => {
  try { await stat(file); throw new Error(message); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
};

const protocol = await json(resolve(stageEvidence, 'protocol.json'));
const job = (await json(resolve(stageEvidence, 'jobs.json'))).find((item) => item.id === id);
assert.equal(protocol.status, 'FROZEN');
assert.equal(protocol.qualityRetriesAllowed, false);
assert.ok(protocol.allowedJobs.includes(id));
assert.equal(job?.id, 'E01_V63_GEN');
assert.equal(job?.caseId, 'E01');
assert.equal(job?.phase, 'GENERATION');
assert.equal(job?.version, '63');
assert.equal(protocol.parameters.generationMaxTokens, 8192);
assert.equal(protocol.parameters.executionMaxTokens, 10000);
assert.equal(protocol.parameters.temperature, 0.2);
assert.equal(protocol.parameters.thinking, 'disabled');
assert.equal(protocol.parameters.stream, false);
assert.equal(protocol.frozenBundleSha256, hash(await readFile(resolve(here, 'generation-bundle.json'))));
const sourceManifestBytes = await readFile(resolve(stageEvidence, 'source-manifest.json'));
const sourceManifest = JSON.parse(sourceManifestBytes);
assert.ok(sourceManifest && typeof sourceManifest === 'object' && Object.keys(sourceManifest).length > 0);
assert.ok(Array.isArray(sourceManifest.files) && sourceManifest.files.length > 0);
let sourcePackFilesVerified = 0;
for (const entry of sourceManifest.files) {
  assert.ok(isAbsolute(entry.path), 'The runtime source-pack manifest must name absolute frozen class paths');
  assert.equal(hash(await readFile(entry.path)), entry.sha256,
    'A frozen runtime class/source-pack file drifted; no model request is allowed');
  sourcePackFilesVerified += 1;
}

const release = { candidateVersion: '63', candidateFreeze: 'registry63-engineering-verification.json' };
// This is a known30-file engineering regression, not a new unseen holdout release.
assert.equal(release.candidateVersion, '63');
const candidateFreeze = await json(resolve(evidence, release.candidateFreeze));
assert.equal(candidateFreeze.version, '63');
let candidateFilesVerified = 0;
for (const entry of candidateFreeze.sources) {
  assert.equal(hash(await readFile(resolve(repo, entry.path))), entry.sha256,
    'A candidate source file drifted after freeze; no model request is allowed');
  candidateFilesVerified += 1;
}
const manifestBytes = await readFile(resolve(sealed, 'manifest.json'));
assert.equal(hash(manifestBytes), 'a7f9c19244af011966e6558e2aeb7877e3d8d466b7a6f4f6a2fa3be2be4311af');
const manifest = JSON.parse(manifestBytes);
for (const entry of manifest.files) assert.equal(hash(await readFile(resolve(sealed, entry.path))), entry.sha256);
const executionProject = await json(resolve(here, '../engineering-run/execution-project.json'));
assert.equal(executionProject.files.length, 30);
for (const entry of executionProject.fileHashes) {
  const file = executionProject.files.find((item) => item.path === entry.path);
  assert.equal(hash(file.content), entry.sha256);
  assert.equal(hash(await readFile(resolve(sealed, 'inputs/project', entry.path))), entry.sha256);
}
const rawPrompt = await readFile(resolve(sealed, 'inputs/prompt.md'), 'utf8');
const input = job.approvedEnhancementInput;
assert.equal(input.rawPrompt + '\n', rawPrompt);
assert.ok(isDeepStrictEqual(input, await json(resolve(here, '../engineering-run/capture/r3-E01/input.json'))));
assert.equal(input.context.files.length, 13);
for (const directory of [evidence, stageEvidence, dirname(runtime), runtime]) {
  await absent(resolve(directory, 'closure.json'), 'A closed task or stage cannot execute');
}
const resultPath = resolve(runtime, 'results', `${id}.json`);
await absent(resultPath, 'This job already has a result; no quality retry is allowed');
for (const name of (await readdir(resolve(runtime, 'traces'))).filter((name) => name.endsWith('.json'))) {
  if ((await json(resolve(runtime, 'traces', name))).jobId === id) {
    throw new Error('This job has an upstream attempt or reservation; do not execute it again');
  }
}
if (!process.argv.includes('--execute')) {
  console.log(JSON.stringify({ event: 'ENGINEERING_BROWSER_PREFLIGHT', stage, id, caseId: 'E01', version: '63',
    sourceBundleVerified: true, sourcePackFilesVerified, candidateFilesVerified, engineeringFilesVerified: manifest.files.length,
    projectFiles: 30, enhancementContextFiles: 13, modelCalls: 0, requiresExecute: true,
    requiresLoginReadyAndExclusiveNotification: true }));
  process.exit(0);
}

// --execute is invoked only after infrastructure reports LOGIN_READY and root
// grants exclusive use. This script never switches stage mode or starts CLI jobs.
const require = createRequire(resolve(webRoot, 'package.json'));
const { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
let server;
try {
  const response = await fetch(frontend, { signal: AbortSignal.timeout(1500) });
  if (!response.ok || !(await response.text()).includes('<title>Prompt Optimizer</title>')) {
    throw new Error('The reserved local browser port is occupied by another service');
  }
} catch (error) {
  if (error.message === 'The reserved local browser port is occupied by another service') throw error;
  server = await createServer({ root: webRoot, logLevel: 'warn',
    server: { host: '127.0.0.1', port: 5194, strictPort: true } });
  await server.listen();
}
const output = resolve(here, 'live', stage, id);
await mkdir(output, { recursive: true });
await absent(resolve(output, 'attempt.json'), 'The browser already attempted this job; no automatic retry is allowed');
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
let context, page, responseRecord, routeFailure;
let businessRequests = 0;
const uiHistoryDetailIds = [];
try {
  context = await browser.newContext({ storageState: resolve(runtime, 'auth/browser-storage-state.json'),
    permissions: ['clipboard-read', 'clipboard-write'], locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const identity = await context.request.get(`${backend}/api/v1/auth/me`);
  assert.equal(identity.status(), 200, 'The existing synthetic account session is unavailable; no model request was sent');
  const account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
  const actor = (await identity.json()).data;
  for (const field of ['userId', 'tenantId', 'workspaceId']) {
    assert.ok(actor[field] === account[field], 'The real authenticated actor differs from the fixed synthetic account');
  }
  page = await context.newPage();
  await page.addInitScript(async ({ rootName, files }) => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
    const storage = await navigator.storage.getDirectory();
    const root = await storage.getDirectoryHandle(rootName, { create: true });
    for (const file of files) {
      let directory = root;
      const parts = file.path.split('/');
      for (const part of parts.slice(0, -1)) directory = await directory.getDirectoryHandle(part, { create: true });
      const handle = await directory.getFileHandle(parts.at(-1), { create: true });
      const writer = await handle.createWritable(); await writer.write(file.content); await writer.close();
    }
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
    window.__engineeringDirectoryPrepared = true;
  }, { rootName: 'engineering-holdout', files: executionProject.files });

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v2/optimizations' && request.method() === 'POST') {
      businessRequests += 1;
      if (businessRequests !== 1) {
        routeFailure = 'A second enhancement request was blocked';
        await route.fulfill({ status: 409, json: { error: { message: routeFailure } } });
        return;
      }
      const actual = request.postDataJSON();
      await writeFile(resolve(output, 'actual-input.json'), JSON.stringify(actual, null, 2) + '\n', { flag: 'wx' });
      if (!isDeepStrictEqual(actual, input)) {
        routeFailure = 'The actual workbench DTO differs from the frozen DTO; model request blocked';
        await route.fulfill({ status: 409, json: { error: { message: routeFailure } } });
        return;
      }
      await writeFile(resolve(output, 'attempt.json'), JSON.stringify({ id, stage, caseId: 'E01', version: '63',
        realAuthenticationVerified: true, exactFrozenDto: true, retriesAllowed: false,
        sourceManifestSha256: hash(sourceManifestBytes), sourcePackFilesVerified, candidateFilesVerified,
        engineeringManifestSha256: hash(manifestBytes), projectFiles: 30, enhancementContextFiles: 13 }, null, 2) + '\n', { flag: 'wx' });
      await writeFile(resolve(runtime, 'active-job.json'), JSON.stringify(job, null, 2) + '\n');
      try {
        const response = await route.fetch({ url: `${backend}${job.entrypoint === 'DEFAULT' ? '/api/v2/optimizations' : '/api/v2/evaluation/candidate'}`,
          headers: { ...request.headers(), 'X-Evaluation-Job': id }, postData: request.postData(),
          maxRetries: 0, maxRedirects: 0, timeout: 1140000 });
        responseRecord = { id, phase: 'GENERATION', version: '63', caseId: 'E01',
          status: response.status(), body: await response.json() };
        await mkdir(dirname(resultPath), { recursive: true });
        await writeFile(resultPath, JSON.stringify(responseRecord, null, 2) + '\n', { flag: 'wx' });
        await writeFile(resolve(output, 'response.json'), JSON.stringify(responseRecord, null, 2) + '\n', { flag: 'wx' });
        await route.fulfill({ response });
      } catch (error) {
        routeFailure = String(error.message);
        await route.fulfill({ status: 502, json: { error: { message: 'The single live request failed; no retry is allowed.' } } });
      }
      return;
    }
    const detail = url.pathname.match(/^\/api\/v2\/optimization-history\/([^/]+)$/);
    if (detail && request.method() === 'GET') uiHistoryDetailIds.push(detail[1]);
    // Authentication, models, history and all other page APIs use the real
    // controlled backend. No successful response or identity is mocked here.
    const response = await route.fetch({ url: `${backend}${url.pathname}${url.search}`, maxRetries: 0,
      maxRedirects: 0, timeout: 30000 });
    await route.fulfill({ response });
  });
  await page.goto(`${frontend}/workbench`);
  await page.waitForFunction(() => window.__engineeringDirectoryPrepared === true);
  await writeFile(resolve(output, 'model-selection.json'), JSON.stringify(await selectFlash(page, context, expect), null, 2) + '\n', { flag: 'wx' });
  await page.getByRole('button', { name: '添加上下文', exact: true }).click();
  await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await expect(page.getByText('30 个源码文件已建立本地索引', { exact: true })).toBeVisible({ timeout: 30000 });
  const indexSnapshot = await page.evaluate(async () => {
    const open = indexedDB.open('prompt-optimizer-project-index');
    const database = await new Promise((resolve, reject) => { open.onsuccess = () => resolve(open.result); open.onerror = () => reject(open.error); });
    const snapshot = {};
    for (const name of [...database.objectStoreNames]) {
      const request = database.transaction(name, 'readonly').objectStore(name).getAll();
      snapshot[name] = await new Promise((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
    }
    database.close(); return snapshot;
  });
  const indexedPaths = indexSnapshot.files.map((file) => file.path).sort();
  assert.ok(isDeepStrictEqual(indexedPaths, executionProject.files.map((file) => file.path).sort()));
  await writeFile(resolve(output, 'indexed.json'), JSON.stringify(indexSnapshot, null, 2) + '\n', { flag: 'wx' });
  await page.getByLabel('原始提示词', { exact: true }).fill(rawPrompt);
  assert.equal(await page.getByLabel('原始提示词', { exact: true }).inputValue(), rawPrompt);
  await writeFile(resolve(output, 'ui-prompt.txt'), rawPrompt, { flag: 'wx' });
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  await expect(dialog).toBeVisible();
  await page.screenshot({ path: resolve(output, 'real-consent.png'), fullPage: true });
  await dialog.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => Boolean(responseRecord) || Boolean(routeFailure), { timeout: 1150000 }).toBe(true);
  assert.equal(routeFailure, undefined, 'The single real UI request did not complete; do not retry');
  assert.equal(responseRecord.status, 200);
  assert.equal(businessRequests, 1);
  const generated = responseRecord.body.data;
  assert.equal(generated.provider.mock, false);
  assert.equal(generated.chainVersion, '63');
  assert.ok(generated.processing.completedStages.includes('BODY_GENERATION'));
  const displayed = page.getByTestId('compact-prompt');
  await expect(displayed).toHaveText(generated.optimizedPrompt);
  const displayedText = await displayed.textContent();
  assert.equal(displayedText.replace(/\r\n/g, '\n'), generated.optimizedPrompt.replace(/\r\n/g, '\n'));
  await writeFile(resolve(output, 'display.txt'), displayedText, { flag: 'wx' });
  const retrieval = await page.evaluate(async () => (await import('/src/stores/optimization.ts')).useOptimizationStore().contextRetrieval);
  await writeFile(resolve(output, 'retrieval.json'), JSON.stringify(retrieval, null, 2) + '\n', { flag: 'wx' });
  await page.bringToFront();
  await page.locator('.copy-main-button').click();
  await expect(page.locator('.copy-main-button')).toContainText('已复制');
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  assert.equal(copied.replace(/\r\n/g, '\n'), generated.optimizedPrompt.replace(/\r\n/g, '\n'));
  const historyResponse = await context.request.get(`${backend}/api/v2/optimization-history/${generated.recordId}`);
  assert.equal(historyResponse.status(), 200);
  const history = (await historyResponse.json()).data;
  const historyCheck = { status: 200, recordId: generated.recordId,
    bodyEqual: history.optimizedPrompt === generated.optimizedPrompt,
    versionEqual: history.chainVersion === generated.chainVersion,
    processingEqual: isDeepStrictEqual(history.processing, generated.processing) };
  assert.ok(historyCheck.bodyEqual && historyCheck.versionEqual && historyCheck.processingEqual);
  await writeFile(resolve(output, 'copy.txt'), copied, { flag: 'wx' });
  await writeFile(resolve(output, 'history-check.json'), JSON.stringify(historyCheck, null, 2) + '\n', { flag: 'wx' });
  await writeFile(resolve(runtime, 'results', `${id}-history.json`), JSON.stringify(historyCheck, null, 2) + '\n', { flag: 'wx' });
  await page.screenshot({ path: resolve(output, 'real-generated-result.png'), fullPage: true });
  await page.goto(`${frontend}/history`);
  await page.getByRole('button', { name: '查看', exact: true }).filter({ visible: true }).first().click();
  const historyDisplay = page.getByRole('dialog', { name: '历史记录详情', exact: true }).locator('pre').nth(1);
  await expect(historyDisplay).toHaveText(generated.optimizedPrompt);
  const historyDisplayedText = await historyDisplay.textContent();
  assert.equal(historyDisplayedText.replace(/\r\n/g, '\n'), generated.optimizedPrompt.replace(/\r\n/g, '\n'));
  await writeFile(resolve(output, 'history-display.txt'), historyDisplayedText, { flag: 'wx' });
  assert.ok(uiHistoryDetailIds.includes(String(generated.recordId)), 'The history UI did not open this actual generated record');
  await page.screenshot({ path: resolve(output, 'real-history.png'), fullPage: true });
  const verdict = { id, stage, caseId: 'E01', version: generated.chainVersion, phase: 'GENERATION',
    mockIdentity: false, realAuthenticationVerified: true, controlledResponse: false,
    providerMock: generated.provider.mock, inputEqualsFrozen: true, businessRequests,
    indexedFiles: indexedPaths.length, enhancementContextFiles: input.context.files.length,
    sourceManifestSha256: hash(sourceManifestBytes), sourcePackFilesVerified, candidateFilesVerified,
    sourcePromptSha256: hash(rawPrompt), normalizedDtoPromptSha256: hash(input.rawPrompt),
    bodyGenerationCompleted: true, processing: generated.processing,
    displayEqualsResponse: true, copyEqualsResponseAfterClipboardNewlineNormalization: true,
    actualHistoryBodyVersionProcessingEqual: true, historyUiOpenedExactRecord: true,
    downstreamEngineeringWorkExecuted: false, retriesPerformed: 0 };
  await writeFile(resolve(output, 'verdict.json'), JSON.stringify(verdict, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ event: 'ENGINEERING_REAL_BROWSER_COMPLETE', id, stage,
    status: 200, version: '63', businessRequests, bodyGenerationCompleted: true,
    displayedCopiedActualHistoryEqual: true }));
} catch (error) {
  if (page) await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {});
  await writeFile(resolve(output, 'failure.json'), JSON.stringify({ id, stage, businessRequests,
    responseCaptured: Boolean(responseRecord), name: error.name, message: error.message,
    routeFailure, retryAllowed: false }, null, 2) + '\n', { flag: 'wx' });
  throw error;
} finally {
  await context?.close(); await browser.close(); if (server) await server.close();
}
