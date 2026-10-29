import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(here, '..');
const repo = resolve(here, '../../../../..');
const sealed = resolve(evidence, 'sealed-engineering-holdout');
const digest = (value) => createHash('sha256').update(value).digest('hex');
const manifestBytes = await readFile(resolve(sealed, 'manifest.json'));
assert.equal(digest(manifestBytes), 'a7f9c19244af011966e6558e2aeb7877e3d8d466b7a6f4f6a2fa3be2be4311af');
const manifest = JSON.parse(manifestBytes);
for (const item of manifest.files) {
  const bytes = await readFile(resolve(sealed, item.path));
  assert.equal(bytes.length, item.bytes);
  assert.equal(digest(bytes), item.sha256);
}
const promptBytes = await readFile(resolve(sealed, 'inputs/prompt.md'));
const rawPrompt = promptBytes.toString('utf8');
const projectEntries = manifest.files.filter((item) => item.path.startsWith('inputs/project/'));
const fixture = { rootName: 'engineering-holdout', files: await Promise.all(projectEntries.map(async (item) => ({
  path: item.path.slice('inputs/project/'.length), content: (await readFile(resolve(sealed, item.path))).toString('utf8'),
}))) };
assert.equal(fixture.files.length, 30);
const attempt = process.env.ENGINEERING_CAPTURE_ATTEMPT ?? '';
assert.match(attempt, /^(?:r\d+)?$/);
const output = resolve(here, 'capture', attempt ? `${attempt}-E01` : 'E01');
await mkdir(output, { recursive: true });
await writeFile(resolve(output, 'manifest-verification.json'), JSON.stringify({
  caseId: 'E01', verifiedFiles: manifest.files.length, manifestSha256: digest(manifestBytes),
  projectFiles: fixture.files.length, rawPromptSha256: digest(promptBytes), rawPromptUnmodified: true,
  containsAtReference: false, atSyntaxValidation: false,
}, null, 2) + '\n', { flag: 'wx' });

const require = createRequire(resolve(repo, 'apps/web/package.json'));
const { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
const frontend = 'http://127.0.0.1:5194';
const server = await createServer({ root: resolve(repo, 'apps/web'), logLevel: 'warn',
  server: { host: '127.0.0.1', port: 5194, strictPort: true } });
await server.listen();
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
let context;
try {
  context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const mockUser = { userId: '00000000-0000-0000-0000-000000000102', tenantId: '00000000-0000-0000-0000-000000000101',
    workspaceId: '00000000-0000-0000-0000-000000000103', email: 'test@example.com',
    displayName: '工程留出零模型捕获（mock身份）', platformAdmin: false };
  const unexpected = [], errors = [], apiRequests = [];
  let actual;
  page.on('pageerror', (error) => errors.push(String(error.message)));
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    apiRequests.push({ path, method: request.method() });
    let data;
    if (path === '/api/v1/auth/csrf') data = { token: 'controlled-capture-only' };
    else if (path === '/api/v1/auth/capabilities') data = { phoneRegistration: false, smsLogin: false, phoneBinding: false };
    else if (path === '/api/v1/auth/me') data = mockUser;
    else if (path === '/api/v1/me/settings') data = { displayName: mockUser.displayName, email: mockUser.email,
      username: null, locale: 'zh-CN', emailBindingAvailable: false, accountDeletionAvailable: false };
    else if (path === '/api/v1/models') data = [{ id: 'deepseek:deepseek-flash', displayName: '零模型DTO捕获目录', provider: 'DeepSeek', defaultModel: true }];
    else if (path === '/api/v1/analytics/context') data = { userId: mockUser.userId, loginSessionId: null };
    else if (path === '/api/v1/analytics/events') data = null;
    else if (path === '/api/v1/optimizations/plan-events') { await route.fulfill({ status: 204 }); return; }
    else if (path === '/api/v2/optimizations' && request.method() === 'POST') {
      assert.equal(actual, undefined, 'Exactly one DTO capture is allowed');
      actual = request.postDataJSON();
      await route.fulfill({ status: 409, json: { error: { message: 'mock身份只捕获真实工作台DTO；已阻断增强，不调用模型。' } } });
      return;
    } else {
      unexpected.push(path);
      await route.fulfill({ status: 400, json: { error: { message: '零模型捕获未模拟该接口，禁止透传。' } } });
      return;
    }
    await route.fulfill({ json: { data } });
  });
  await page.addInitScript(async ({ rootName, files }) => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
    const storage = await navigator.storage.getDirectory();
    const root = await storage.getDirectoryHandle(rootName, { create: true });
    for (const file of files) {
      const parts = file.path.split('/');
      let directory = root;
      for (const part of parts.slice(0, -1)) directory = await directory.getDirectoryHandle(part, { create: true });
      const handle = await directory.getFileHandle(parts.at(-1), { create: true });
      const writer = await handle.createWritable();
      await writer.write(file.content); await writer.close();
    }
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
    window.__holdoutDirectoryPrepared = true;
  }, fixture);

  try {
    await page.goto(`${frontend}/workbench`);
    await page.waitForFunction(() => window.__holdoutDirectoryPrepared === true);
    await page.getByRole('button', { name: '添加上下文', exact: true }).click();
    await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
    await expect(page.getByText(/\d+ 个源码文件已建立本地索引/)).toBeVisible({ timeout: 30000 });
    const indexed = await page.evaluate(async () => {
      const open = indexedDB.open('prompt-optimizer-project-index');
      const database = await new Promise((resolve, reject) => { open.onsuccess = () => resolve(open.result); open.onerror = () => reject(open.error); });
      const result = {};
      for (const name of [...database.objectStoreNames]) {
        const request = database.transaction(name, 'readonly').objectStore(name).getAll();
        result[name] = await new Promise((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
      }
      database.close();
      return result;
    });
    await writeFile(resolve(output, 'indexed.json'), JSON.stringify(indexed, null, 2) + '\n', { flag: 'wx' });
    await page.getByLabel('原始提示词', { exact: true }).fill(rawPrompt);
    const uiPrompt = await page.getByLabel('原始提示词', { exact: true }).inputValue();
    assert.equal(uiPrompt, rawPrompt);
    await writeFile(resolve(output, 'ui-prompt.txt'), uiPrompt, { flag: 'wx' });
    await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
    const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
    await expect(confirmation).toBeVisible();
    await page.screenshot({ path: resolve(output, 'consent.png'), fullPage: true });
    await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
    await expect.poll(() => Boolean(actual), { timeout: 10000 }).toBe(true);
    await writeFile(resolve(output, 'input.json'), JSON.stringify(actual, null, 2) + '\n', { flag: 'wx' });
    assert.equal(actual.rawPrompt, rawPrompt.replace(/\n$/, ''));
    assert.equal(actual.enhancement.promptFormat, 'COMPACT');
    assert.equal(actual.planConfirmation, null);
    const retrieval = await page.evaluate(async () => (await import('/src/stores/optimization.ts')).useOptimizationStore().contextRetrieval);
    await writeFile(resolve(output, 'retrieval.json'), JSON.stringify(retrieval, null, 2) + '\n', { flag: 'wx' });
    await writeFile(resolve(output, 'requests.json'), JSON.stringify(apiRequests, null, 2) + '\n', { flag: 'wx' });
    const selected = [...new Set(actual.context.files.map((file) => file.path.split('#chunk-')[0]))];
    const indexedPaths = (indexed.files ?? []).map((file) => file.path).sort();
    const verdict = { caseId: 'E01', mockIdentity: true, realAuthenticationVerified: false,
      zeroModelCaptureOnly: true, modelCalls: 0, enhancementBlocked: true, bodyGenerated: false,
      qualityEvidence: false, atSyntaxValidation: false, rawPromptUiUnmodified: true,
      rawPromptDtoEqualsSealed: actual.rawPrompt === rawPrompt,
      platformRemovedSingleTerminalLf: actual.rawPrompt + '\n' === rawPrompt,
      rawPromptBusinessTextUnmodified: actual.rawPrompt === rawPrompt.replace(/\n$/, ''),
      rawPromptSourceSha256: digest(promptBytes), rawPromptUiSha256: digest(uiPrompt),
      rawPromptDtoSha256: digest(actual.rawPrompt),
      selectedDirectoryFiles: fixture.files.length, indexedFiles: indexedPaths.length,
      selectedFiles: selected.length, selectedPaths: selected,
      actualContextCharacters: actual.context.files.reduce((sum, file) => sum + file.content.length, 0),
      indexedMiss: fixture.files.map((file) => file.path).filter((path) => !indexedPaths.includes(path)),
      unexpectedApiRequests: unexpected, pageErrors: errors,
    };
    await writeFile(resolve(output, 'verdict.json'), JSON.stringify(verdict, null, 2) + '\n', { flag: 'wx' });
    await page.screenshot({ path: resolve(output, 'capture.png'), fullPage: true });
    console.log(JSON.stringify({ caseId: verdict.caseId, selectedDirectoryFiles: verdict.selectedDirectoryFiles,
      indexedFiles: verdict.indexedFiles, selectedFiles: verdict.selectedFiles,
      actualContextCharacters: verdict.actualContextCharacters, indexedMiss: verdict.indexedMiss,
      unexpectedApiRequests: unexpected, pageErrors: errors, modelCalls: 0 }));
  } catch (error) {
    await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true });
    await writeFile(resolve(output, 'failure.json'), JSON.stringify({ error: String(error.message),
      actualDtoCaptured: Boolean(actual), unexpected, pageErrors: errors, modelCalls: 0 }, null, 2) + '\n', { flag: 'wx' });
    throw error;
  }
} finally {
  await context?.close(); await browser.close(); await server.close();
}
