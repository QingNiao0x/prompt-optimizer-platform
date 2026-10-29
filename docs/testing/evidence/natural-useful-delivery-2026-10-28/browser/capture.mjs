import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cases, loadSyntheticProject } from '../fixtures/project.mjs';

const here = dirname(fileURLToPath(import.meta.url)), repo = resolve(here, '../../../../..');
const require = createRequire(resolve(repo, 'apps/web/package.json'));
const { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
const frontend = 'http://127.0.0.1:5184';
const fixture = await loadSyntheticProject();
const samples = process.argv[2] ? JSON.parse(await readFile(resolve(process.argv[2]), 'utf8')).cases : cases;
const server = await createServer({ root: resolve(repo, 'apps/web'), logLevel: 'warn',
  server: { host: '127.0.0.1', port: 5184, strictPort: true } });
await server.listen();
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
try {
  for (const sample of samples) {
    const useProject = sample.useProject ?? sample.id.startsWith('C');
    const outputGroup = process.env.CAPTURE_GROUP ?? 'captures';
    assert.match(outputGroup, /^captures(?:-r\d+)?$/);
    const directory = process.argv.includes('--canonical') ? resolve(here, outputGroup, sample.id)
      : resolve(here, 'capture', `${process.env.CAPTURE_ATTEMPT ?? 'r2'}-${sample.id}`);
    await mkdir(directory, { recursive: true });
    const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    const user = { userId: '00000000-0000-0000-0000-000000000102', tenantId: '00000000-0000-0000-0000-000000000101',
      workspaceId: '00000000-0000-0000-0000-000000000103', email: 'test@example.com', displayName: '受控请求捕获', platformAdmin: false };
    let actual;
    const unexpected = [];
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      let data;
      if (path === '/api/v1/auth/csrf') data = { token: 'controlled-capture-only' };
      else if (path === '/api/v1/auth/capabilities') data = { phoneRegistration: false, smsLogin: false, phoneBinding: false };
      else if (path === '/api/v1/auth/me') data = user;
      else if (path === '/api/v1/me/settings') data = { displayName: user.displayName, email: user.email, username: null,
        locale: 'zh-CN', emailBindingAvailable: false, accountDeletionAvailable: false };
      else if (path === '/api/v1/models') data = [{ id: 'deepseek:deepseek-flash', displayName: '冻结载荷捕获目录', provider: 'DeepSeek', defaultModel: true }];
      else if (path === '/api/v1/analytics/context') data = { userId: user.userId, loginSessionId: null };
      else if (path === '/api/v1/analytics/events') data = null;
      else if (path === '/api/v1/optimizations/plan-events') { await route.fulfill({ status: 204 }); return; }
      else if (path === '/api/v2/optimizations' && route.request().method() === 'POST') {
        assert.equal(actual, undefined, '一次捕获只允许一个业务请求'); actual = route.request().postDataJSON();
        await route.fulfill({ status: 409, json: { error: { message: '本次仅捕获真实UI请求；未调用模型。' } } }); return;
      } else { unexpected.push(path); await route.fulfill({ status: 400, json: { error: { message: '捕获入口未模拟此API' } } }); return; }
      await route.fulfill({ json: { data } });
    });
    await page.addInitScript(async ({ rootName, files }) => {
      localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
      const storage = await navigator.storage.getDirectory();
      const root = await storage.getDirectoryHandle(rootName, { create: true });
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
      let indexedPaths = [];
      if (useProject) {
        await page.getByRole('button', { name: '添加上下文', exact: true }).click();
        await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
        await expect(page.getByText(`${fixture.files.length} 个源码文件已建立本地索引`, { exact: true })).toBeVisible({ timeout: 30000 });
        indexedPaths = await page.evaluate(async () => {
        const request = indexedDB.open('prompt-optimizer-project-index');
        const database = await new Promise((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
        const query = database.transaction('files', 'readonly').objectStore('files').getAll();
        const entries = await new Promise((resolve, reject) => { query.onsuccess = () => resolve(query.result); query.onerror = () => reject(query.error); });
        database.close(); return entries.map(file => file.path).sort();
        });
        assert.deepEqual(indexedPaths, fixture.files.map(file => file.path).sort());
      }
      await page.getByLabel('原始提示词', { exact: true }).fill(sample.rawPrompt);
      await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
      if (useProject) {
        const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
        await expect(confirmation).toBeVisible();
        await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
      }
      await expect.poll(() => Boolean(actual), { timeout: 10000 }).toBe(true);
      await writeFile(resolve(directory, 'input.json'), JSON.stringify(actual, null, 2) + '\n', { flag: 'wx' });
      const retrieval = await page.evaluate(async () => (await import('/src/stores/optimization.ts')).useOptimizationStore().contextRetrieval);
      await writeFile(resolve(directory, 'retrieval.json'), JSON.stringify(retrieval, null, 2) + '\n', { flag: 'wx' });
      assert.equal(actual.rawPrompt, sample.rawPrompt); assert.equal(actual.enhancement.promptFormat, 'COMPACT');
      assert.equal(actual.modelId, 'deepseek:deepseek-flash'); assert.equal(actual.planConfirmation, null);
      const paths = actual.context.files.map(file => file.path.split('#chunk-')[0]);
      if (sample.id.startsWith('C')) for (const path of fixture.targetPaths) assert.ok(paths.includes(path), `${path}未进入发送上下文`);
      assert.ok(!paths.some(path => /records-(old|archive)/.test(path)), '误取旧模块或同词噪声');
      assert.deepEqual(unexpected, []);
      const verdict = { id: sample.id, controlledAuthentication: true, modelCalls: 0, bodyGenerated: false, qualityEvidence: false,
        indexedFiles: indexedPaths.length, selectedPaths: paths, actualContextCharacters: actual.context.files.reduce((sum, file) => sum + file.content.length, 0),
        contractInRawPrompt: sample.contract ? actual.rawPrompt.includes(sample.contract) : null, unexpected };
      await writeFile(resolve(directory, 'verdict.json'), JSON.stringify(verdict, null, 2) + '\n', { flag: 'wx' });
      await page.screenshot({ path: resolve(directory, 'capture.png'), fullPage: true });
      console.log(JSON.stringify(verdict));
    } catch (error) {
      await page.screenshot({ path: resolve(directory, 'failure.png'), fullPage: true }); throw error;
    } finally { await context.close(); }
  }
} finally { await browser.close(); await server.close(); }
