import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cases, loadSyntheticProject } from '../fixtures/project.mjs';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(here, '../../../../..');
const stage = process.argv[2], attempt = process.argv[3] ?? 'r1';
assert.match(stage ?? '', /^[a-z0-9-]+$/); assert.match(attempt, /^r\d+$/);
const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', stage);
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const fixture = await loadSyntheticProject(), c01 = cases.find(item => item.id === 'C01');
const samples = [ { id: 'P01', rawPrompt: c01.rawPrompt },
  { id: 'P02', rawPrompt: `请在重点记录模块@synthetic-survey-workspace/src/views/records 的新增和导入功能增加调查时间限制：只能填写当日之前的日期，同时又不能超过今日。今天是否允许填写由我确认，不要代选。\n\n${c01.contract}` } ];
const require = createRequire(resolve(repo, 'apps/web/package.json'));
const { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
const frontend = 'http://127.0.0.1:5184', backend = 'http://127.0.0.1:9014';
const server = await createServer({ root: resolve(repo, 'apps/web'), logLevel: 'warn',
  server: { host: '127.0.0.1', port: 5184, strictPort: true } });
await server.listen();
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
try {
  for (const sample of samples) {
    const output = resolve(here, 'plan-captures', attempt, sample.id); await mkdir(output, { recursive: true });
    const context = await browser.newContext({ storageState: resolve(runtime, 'auth/browser-storage-state.json'),
      locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
    const identity = await context.request.get(`${backend}/api/v1/auth/me`);
    assert.equal(identity.status(), 200, '固定合成账号状态失效；不调用Plan');
    const actor = (await identity.json()).data, account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
    for (const field of ['userId', 'tenantId', 'workspaceId']) assert.equal(actor[field], account[field]);
    const page = await context.newPage();
    let prepareInput, preparation, planInput, prepareRequests = 0, planRequests = 0;
    await page.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.pathname === '/api/v2/optimizations/plan' && request.method() === 'POST') {
        planRequests += 1; assert.equal(planRequests, 1);
        planInput = request.postDataJSON();
        await writeFile(resolve(output, 'plan-input.json'), JSON.stringify(planInput, null, 2) + '\n', { flag: 'wx' });
        await route.fulfill({ status: 409, json: { error: { message: '本次捕获真实Plan协议与材料引用，不发送模型。' } } }); return;
      }
      if (url.pathname === '/api/v2/optimizations' && request.method() === 'POST') throw Error('Plan捕获不应开始最终增强');
      if (url.pathname === '/api/v2/context/planning' && request.method() === 'POST') {
        prepareRequests += 1; assert.equal(prepareRequests, 1); prepareInput = request.postDataJSON();
        await writeFile(resolve(output, 'prepare-input.json'), JSON.stringify(prepareInput, null, 2) + '\n', { flag: 'wx' });
        const response = await route.fetch({ url: `${backend}${url.pathname}`, maxRetries: 0, timeout: 30000 });
        preparation = { status: response.status(), body: await response.json() };
        await writeFile(resolve(output, 'prepare-response.json'), JSON.stringify(preparation, null, 2) + '\n', { flag: 'wx' });
        assert.equal(preparation.status, 200); await route.fulfill({ response }); return;
      }
      const response = await route.fetch({ url: `${backend}${url.pathname}${url.search}`, maxRetries: 0, timeout: 30000 });
      await route.fulfill({ response });
    });
    await page.addInitScript(async ({ rootName, files }) => {
      localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: true, introSeen: true }));
      const storage = await navigator.storage.getDirectory(), root = await storage.getDirectoryHandle(rootName, { create: true });
      for (const file of files) {
        const parts = file.path.split('/'); let directory = root;
        for (const part of parts.slice(0, -1)) directory = await directory.getDirectoryHandle(part, { create: true });
        const handle = await directory.getFileHandle(parts.at(-1), { create: true });
        const writer = await handle.createWritable(); await writer.write(file.content); await writer.close();
      }
      Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
    }, fixture);
    try {
      await page.goto(`${frontend}/workbench`);
      await expect(page.getByRole('switch', { name: 'Plan 确认', exact: true })).toBeChecked();
      await page.getByRole('button', { name: '添加上下文', exact: true }).click();
      await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
      await expect(page.getByText(`${fixture.files.length} 个源码文件已建立本地索引`, { exact: true })).toBeVisible({ timeout: 30000 });
      await page.getByLabel('原始提示词', { exact: true }).fill(sample.rawPrompt);
      await page.getByRole('button', { name: '先确认并增强', exact: true }).click();
      const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
      await expect(confirmation).toBeVisible();
      await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
      await expect.poll(() => Boolean(planInput), { timeout: 45000 }).toBe(true);
      assert.equal(prepareInput.rawPrompt, sample.rawPrompt); assert.equal(planInput.rawPrompt, sample.rawPrompt);
      assert.deepEqual(Object.keys(planInput).sort(), ['contextDescription', 'conversationHistory', 'modelId', 'planningContext', 'rawPrompt']);
      assert.equal(planInput.modelId, 'deepseek:deepseek-flash');
      assert.equal(planInput.planningContext.contextId, preparation.body.data.contextId);
      assert.equal(planInput.planningContext.version, preparation.body.data.version);
      const paths = prepareInput.context.files.map(file => file.path.split('#chunk-')[0]);
      for (const path of fixture.targetPaths) assert.ok(paths.includes(path));
      assert.ok(!paths.some(path => /records-(old|archive)/.test(path)));
      assert.ok(preparation.body.data.contextReport.fileSnippets.length > 0);
      assert.ok(preparation.body.data.digest.analyzedFileCount > 0);
      const retrieval = await page.evaluate(async () => (await import('/src/stores/optimization.ts')).useOptimizationStore().contextRetrieval);
      await writeFile(resolve(output, 'retrieval.json'), JSON.stringify(retrieval, null, 2) + '\n', { flag: 'wx' });
      const verdict = { id: sample.id, actualModelCalls: 0, realAuthentication: true, realDeterministicPreparation: true,
        planBlockedBeforeModel: true, indexedFiles: fixture.files.length, actualSelectedPaths: paths,
        actualSourceCharacters: prepareInput.context.files.reduce((sum, file) => sum + file.content.length, 0),
        analyzedFileCount: preparation.body.data.digest.analyzedFileCount, contextExpiresAt: preparation.body.data.expiresAt,
        prepareRequests, planRequests, contextReferenceMatchesActualPreparation: true };
      await writeFile(resolve(output, 'verdict.json'), JSON.stringify(verdict, null, 2) + '\n', { flag: 'wx' });
      await page.screenshot({ path: resolve(output, 'capture.png'), fullPage: true }); console.log(JSON.stringify(verdict));
    } catch (failure) {
      await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }); throw failure;
    } finally { await context.close(); }
  }
} finally { await browser.close(); await server.close(); }
