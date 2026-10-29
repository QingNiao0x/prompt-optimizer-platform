import assert from 'node:assert/strict';
import { selectFlash } from '../../natural-useful-delivery-2026-10-28/registry51-normal-preparation/browser-support.mjs';
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir, readdir, stat } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { loadSyntheticProject } from '../../natural-useful-delivery-2026-10-28/fixtures/project.mjs';

const evidence = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(evidence, '../../../..');
const webRoot = resolve(repo, 'apps/web');
const stage = process.argv[2];
const id = process.argv[3];
if (!/^[a-z0-9-]+$/.test(stage ?? '') || !/^[A-Za-z0-9_]+$/.test(id ?? '')) {
  throw new Error('用法：node live-browser.mjs <冻结阶段> <GEN作业ID> [--execute]；默认仅预检，不调用模型。');
}
const root = resolve(repo, 'tmp/natural-writer-input70-2026-10-29', stage);
const stageEvidence = resolve(evidence, stage);
const backend = 'http://127.0.0.1:9014';
const frontend = 'http://127.0.0.1:5194';
const json = async (path) => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const protocol = await json(resolve(stageEvidence, 'protocol.json'));
const job = (await json(resolve(stageEvidence, 'jobs.json'))).find((item) => item.id === id);
assert.equal(protocol.status, 'FROZEN');
assert.ok(protocol.allowedJobs.includes(id));
assert.equal(job?.phase, 'GENERATION');
const input = job.approvedEnhancementInput;
const sample = (await json(resolve(evidence, 'browser/cases.json'))).cases.find(item => item.id === job.caseId);
assert.ok(sample, 'Real browser job must name a captured case');
const useProject = sample.useProject ?? sample.id.startsWith('C');
assert.equal(typeof input?.rawPrompt, 'string');
for (const directory of [evidence, stageEvidence, dirname(root), root]) {
  try { await stat(resolve(directory, 'closure.json')); throw new Error('已关闭批次不能执行'); }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
}
const resultPath = resolve(root, 'results', `${id}.json`);
try { await stat(resultPath); throw new Error('作业已有结果，不允许质量重试'); }
catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
for (const name of (await readdir(resolve(root, 'traces'))).filter((file) => file.endsWith('.json'))) {
  if ((await json(resolve(root, 'traces', name))).jobId === id) throw new Error('作业已经尝试，不允许重复执行');
}
if (!process.argv.includes('--execute')) {
  console.log(JSON.stringify({ event: 'BROWSER_JOB_PREFLIGHT', stage, id, caseId: job.caseId,
    version: job.version, frontend, backend, requiresExecute: true, modelCalls: 0 }));
  process.exit(0);
}

const require = createRequire(resolve(webRoot, 'package.json'));
const { chromium } = require('@playwright/test');
const { expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
let server;
try {
  const response = await fetch(frontend, { signal: AbortSignal.timeout(1500) });
  if (!response.ok || !(await response.text()).includes('<title>Prompt Optimizer</title>')) throw new Error('5194端口被其他服务占用');
} catch (failure) {
  if (failure.message === '5194端口被其他服务占用') throw failure;
  server = await createServer({ root: webRoot, logLevel: 'warn', server: { host: '127.0.0.1', port: 5194, strictPort: true } });
  await server.listen();
}
const outputDirectory = resolve(root, 'browser', id);
await mkdir(outputDirectory, { recursive: true });
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
let responseRecord;
let businessRequests = 0;
try {
  const context = await browser.newContext({ storageState: resolve(root, 'auth/browser-storage-state.json'),
    permissions: ['clipboard-read', 'clipboard-write'], locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const identity = await context.request.get(`${backend}/api/v1/auth/me`);
  assert.equal(identity.status(), 200, '固定合成账号会话不可用，未发模型请求');
  const account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
  const actor = (await identity.json()).data;
  for (const field of ['userId', 'tenantId', 'workspaceId']) assert.equal(actor[field], account[field]);
  const page = await context.newPage();
  const fixture = await loadSyntheticProject();
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
  }, fixture);
  // 只改本机评测目的地；POST正文由工作台真实构造，并与已冻结DTO逐项相等。
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === '/api/v2/optimizations' && request.method() === 'POST') {
      businessRequests += 1;
      assert.equal(businessRequests, 1, '同一作业不得重复请求增强');
      const actual = request.postDataJSON();
      await writeFile(resolve(outputDirectory, 'actual-input.json'), `${JSON.stringify(actual, null, 2)}\n`, { flag: 'wx' });
      if (!isDeepStrictEqual(actual, input)) {
        await route.fulfill({ status: 409, json: { error: { message: '真实UI请求与冻结载荷不同，未调用模型。' } } });
        throw new Error('真实UI请求与冻结DTO不同');
      }
      await writeFile(resolve(root, 'active-job.json'), `${JSON.stringify(job, null, 2)}\n`);
      const response = await route.fetch({ url: `${backend}${job.entrypoint === 'DEFAULT' ? '/api/v2/optimizations' : '/api/v2/evaluation/candidate'}`,
        headers: { ...request.headers(), 'X-Evaluation-Job': id }, postData: request.postData(),
        maxRetries: 0, maxRedirects: 0, timeout: 1140000 });
      responseRecord = { id, phase: job.phase, version: job.version, caseId: job.caseId,
        status: response.status(), body: await response.json() };
      await mkdir(dirname(resultPath), { recursive: true });
      await writeFile(resultPath, `${JSON.stringify(responseRecord, null, 2)}\n`, { flag: 'wx' });
      await route.fulfill({ response });
      return;
    }
    // 登录、目录、历史等继续使用实际后端；不拦截出一个假的成功结果。
    const response = await route.fetch({ url: `${backend}${url.pathname}${url.search}`, maxRetries: 0, timeout: 30000 });
    await route.fulfill({ response });
  });
  await page.goto(`${frontend}/workbench`);
  await selectFlash(page, context, expect);
  if (useProject) {
  await page.getByRole('button', { name: '添加上下文', exact: true }).click();
  await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await expect(page.getByText(`${fixture.files.length} 个源码文件已建立本地索引`, { exact: true })).toBeVisible({ timeout: 30000 });
  const indexedPaths = await page.evaluate(async () => {
    const open = indexedDB.open('prompt-optimizer-project-index');
    const database = await new Promise((resolve, reject) => { open.onsuccess = () => resolve(open.result); open.onerror = () => reject(open.error); });
    const request = database.transaction('files', 'readonly').objectStore('files').getAll();
    const files = await new Promise((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
    database.close(); return files.map(file => file.path).sort();
  });
  assert.deepEqual(indexedPaths, fixture.files.map(file => file.path).sort());
  await writeFile(resolve(outputDirectory, 'actual-indexed-paths.json'), JSON.stringify(indexedPaths, null, 2) + '\n', { flag: 'wx' });
  }
  await page.getByLabel('原始提示词', { exact: true }).fill(input.rawPrompt);
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  if (useProject) {
  const dialog = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: '确认发送', exact: true }).click();
  }
  await expect.poll(() => responseRecord?.status, { timeout: 1140000 }).toBe(200);
  const generated = responseRecord.body.data;
  assert.equal(generated.provider.mock, false, '真实模型验收不能使用Mock结果');
  assert.equal(generated.chainVersion, job.version);
  if (['45', '46', '70'].includes(job.version)) assert.ok(generated.processing.completedStages.includes('BODY_GENERATION'));
  const visible = page.getByTestId('compact-prompt');
  await expect(visible).toHaveText(generated.optimizedPrompt);
  await page.bringToFront();
  await page.locator('.copy-main-button').click();
  await expect(page.locator('.copy-main-button')).toContainText('已复制');
  assert.ok(!generated.optimizedPrompt.includes('UNRELATED_SOURCE_'));
  const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n');
  assert.equal(copied, generated.optimizedPrompt.replace(/\r\n/g, '\n'));
  const historyResponse = await context.request.get(`${backend}/api/v2/optimization-history/${generated.recordId}`);
  assert.equal(historyResponse.status(), 200);
  const history = (await historyResponse.json()).data;
  const historyCheck = { status: 200, bodyEqual: history.optimizedPrompt === generated.optimizedPrompt,
    versionEqual: history.chainVersion === generated.chainVersion,
    processingEqual: isDeepStrictEqual(history.processing, generated.processing) };
  assert.ok(historyCheck.bodyEqual && historyCheck.versionEqual && historyCheck.processingEqual);
  await writeFile(resolve(root, 'results', `${id}-history.json`), `${JSON.stringify(historyCheck, null, 2)}\n`, { flag: 'wx' });
  await writeFile(resolve(outputDirectory, 'copy.txt'), copied, { flag: 'wx' });
  await page.screenshot({ path: resolve(outputDirectory, 'real-generated-result.png'), fullPage: true });
  await page.goto(`${frontend}/history`);
  await page.getByRole('button', { name: '查看', exact: true }).filter({ visible: true }).first().click();
  await expect(page.getByRole('dialog', { name: '历史记录详情', exact: true }).locator('pre').nth(1)).toHaveText(generated.optimizedPrompt);
  await page.screenshot({ path: resolve(outputDirectory, 'real-history-detail.png'), fullPage: true });
  const verdict = { id, stage, controlledResponse: false, inputEqualsFrozen: true, businessRequests,
    indexedFiles: useProject ? fixture.files.length : 0,
    selectedPaths: input.context.files.map(file => file.path), displayedCopiedAndHistoryEqual: true, providerMock: generated.provider.mock,
    version: generated.chainVersion, processing: generated.processing };
  await writeFile(resolve(outputDirectory, 'verdict.json'), `${JSON.stringify(verdict, null, 2)}\n`, { flag: 'wx' });
  console.log(JSON.stringify({ event: 'REAL_BROWSER_JOB_COMPLETE', id, status: responseRecord.status, businessRequests,
    version: generated.chainVersion, historyEqual: true }));
} catch (failure) {
  await writeFile(resolve(outputDirectory, 'failure.json'), `${JSON.stringify({ id, businessRequests,
    responseCaptured: Boolean(responseRecord), name: failure.name, message: failure.message }, null, 2)}\n`, { flag: 'wx' });
  throw failure;
} finally {
  await browser.close();
  if (server) await server.close();
}
