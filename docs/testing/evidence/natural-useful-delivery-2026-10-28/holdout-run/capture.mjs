import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(here, '../../../../..');
const seal = resolve(evidence, 'sealed-holdout');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const release = await json(resolve(evidence, 'holdout-release.json'));
assert.equal(release.candidateVersion, '47');
const manifestBytes = await readFile(resolve(seal, 'manifest.json'));
assert.equal(createHash('sha256').update(manifestBytes).digest('hex'), release.seals.find(item => item.directory === 'sealed-holdout').sha256);
const manifest = JSON.parse(manifestBytes);
for (const file of manifest.files) {
  const bytes = await readFile(resolve(seal, file.path));
  assert.equal(bytes.length, file.bytes); assert.equal(createHash('sha256').update(bytes).digest('hex'), file.sha256);
}
const samples = (await json(resolve(seal, 'case-index.json'))).cases;
const require = createRequire(resolve(repo, 'apps/web/package.json'));
const { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
const server = await createServer({ root: resolve(repo, 'apps/web'), logLevel: 'warn',
  server: { host: '127.0.0.1', port: 5184, strictPort: true } });
await server.listen();
const frontend = 'http://127.0.0.1:5184';
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
try {
  for (const sample of samples.filter(item => !process.env.HOLDOUT_ONLY || item.id === process.env.HOLDOUT_ONLY)) {
    const captureGroup = process.env.HOLDOUT_CAPTURE_GROUP ?? 'captures'; assert.match(captureGroup, /^captures(?:-r\d+)?$/);
    const useProject = sample.id === 'H04' && process.env.HOLDOUT_USE_PROJECT === 'true';
    const output = resolve(here, captureGroup, sample.id); await mkdir(output, { recursive: true });
    const raw = await readFile(resolve(seal, sample.prompt), 'utf8');
    const attachments = await Promise.all(sample.attachments.map(async path => ({ path, absolute: resolve(seal, path),
      originalBytesSha256: createHash('sha256').update(await readFile(resolve(seal, path))).digest('hex') })));
    const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    const user = { userId: '00000000-0000-0000-0000-000000000102', tenantId: '00000000-0000-0000-0000-000000000101',
      workspaceId: '00000000-0000-0000-0000-000000000103', email: 'test@example.com', displayName: '解封题请求捕获', platformAdmin: false };
    let input, businessRequests = 0;
    const unexpected = [];
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      let data;
      if (path === '/api/v1/auth/csrf') data = { token: 'controlled-holdout-capture' };
      else if (path === '/api/v1/auth/capabilities') data = { phoneRegistration: false, smsLogin: false, phoneBinding: false };
      else if (path === '/api/v1/auth/me') data = user;
      else if (path === '/api/v1/me/settings') data = { displayName: user.displayName, email: user.email, username: null,
        locale: 'zh-CN', emailBindingAvailable: false, accountDeletionAvailable: false };
      else if (path === '/api/v1/models') data = [{ id: 'deepseek:deepseek-flash', displayName: '冻结载荷捕获目录', provider: 'DeepSeek', defaultModel: true }];
      else if (path === '/api/v1/analytics/context') data = { userId: user.userId, loginSessionId: null };
      else if (path === '/api/v1/analytics/events') data = null;
      else if (path === '/api/v1/optimizations/plan-events') { await route.fulfill({ status: 204 }); return; }
      else if (path === '/api/v2/optimizations' && route.request().method() === 'POST') {
        businessRequests += 1; assert.equal(businessRequests, 1); input = route.request().postDataJSON();
        await writeFile(resolve(output, 'input.json'), JSON.stringify(input, null, 2) + '\n', { flag: 'wx' });
        await route.fulfill({ status: 409, json: { error: { message: '解封题仅捕获实际UI请求，不发送模型，不返回成功正文。' } } }); return;
      } else { unexpected.push(path); await route.fulfill({ status: 400, json: { error: { message: '零模型捕获不模拟此接口成功' } } }); return; }
      await route.fulfill({ json: { data } });
    });
    await page.addInitScript(() => {
      localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
    });
    if (useProject) {
      const files = await Promise.all(sample.attachments.map(async path => ({ path: path.replace(/^inputs\/H04\//, ''), content: await readFile(resolve(seal, path), 'utf8') })));
      const rootName = JSON.parse(files.find(file => file.path === 'package.json').content).name;
      await page.addInitScript(async ({ rootName, files }) => {
        const storage = await navigator.storage.getDirectory(), root = await storage.getDirectoryHandle(rootName, { create: true });
        for (const file of files) {
          const parts = file.path.split('/'); let directory = root;
          for (const part of parts.slice(0, -1)) directory = await directory.getDirectoryHandle(part, { create: true });
          const handle = await directory.getFileHandle(parts.at(-1), { create: true });
          const writer = await handle.createWritable(); await writer.write(file.content); await writer.close();
        }
        Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
      }, { rootName, files });
    }
    try {
      await page.goto(`${frontend}/workbench`);
      if (useProject) {
        await page.getByRole('button', { name: '添加上下文', exact: true }).click();
        await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
        await expect(page.getByText('2 个源码文件已建立本地索引', { exact: true })).toBeVisible({ timeout: 30000 });
      } else if (attachments.length > 0) {
        await page.getByRole('button', { name: '添加上下文', exact: true }).click();
        const chooserEvent = page.waitForEvent('filechooser');
        await page.getByRole('menuitem', { name: '选择文件', exact: true }).click();
        const chooser = await chooserEvent; await chooser.setFiles(attachments.map(file => file.absolute));
        await expect.poll(async () => page.evaluate(async () => (await import('/src/stores/optimization.ts')).useOptimizationStore().files.length),
          { timeout: 30000 }).toBe(attachments.length);
      }
      await page.getByLabel('原始提示词', { exact: true }).fill(raw);
      await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
      if (attachments.length > 0) {
        const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
        await expect(confirmation).toBeVisible();
        await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
      }
      await expect.poll(() => Boolean(input), { timeout: 10000 }).toBe(true);
      assert.equal(input.rawPrompt, raw.trim()); assert.equal(input.modelId, 'deepseek:deepseek-flash');
      assert.equal(input.enhancement.promptFormat, 'COMPACT'); assert.equal(input.planConfirmation, null);
      assert.equal(input.context.files.length, sample.attachments.length, '所有封存附件都必须进入增强，不能隐式当执行时材料');
      for (const path of sample.attachments) assert.ok(input.context.files.some(file => file.path.split('#')[0].endsWith(basename(path))), `${path}未进入实际请求`);
      assert.deepEqual(unexpected, []);
      const state = await page.evaluate(async () => {
        const store = (await import('/src/stores/optimization.ts')).useOptimizationStore();
        return { retrieval: store.contextRetrieval, files: store.files, index: store.projectIndex };
      });
      await writeFile(resolve(output, 'context-state.json'), JSON.stringify(state, null, 2) + '\n', { flag: 'wx' });
      const verdict = { id: sample.id, realWorkbenchInteraction: true, controlledAuthentication: true, modelCalls: 0,
        successResponseMocked: false, bodyGenerated: false, qualityEvidence: false, businessRequests,
        rawSourceSha256: createHash('sha256').update(raw).digest('hex'), rawTrimOnly: true,
        attachmentVisibility: 'RUN_CONTRACT指定附件均在增强与执行时可见，无execution-only材料',
        attachmentUploadMethod: useProject ? '真实目录选择/Worker/IndexedDB，仅两份指定代码附件' : attachments.length ? '真实文件选择器/本地读取Worker' : '无附件，无项目',
        originalAttachments: attachments.map(({ path, originalBytesSha256 }) => ({ path, sha256: originalBytesSha256 })),
        actualFiles: input.context.files.map(file => ({ path: file.path, characters: file.content.length, sha256: createHash('sha256').update(file.content).digest('hex') })), unexpected };
      await writeFile(resolve(output, 'verdict.json'), JSON.stringify(verdict, null, 2) + '\n', { flag: 'wx' });
      await page.screenshot({ path: resolve(output, 'capture.png'), fullPage: true });
      console.log(JSON.stringify(verdict));
    } catch (error) {
      await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }); throw error;
    } finally { await context.close(); }
  }
} finally { await browser.close(); await server.close(); }
