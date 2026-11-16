import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记的合成测试接口' } } }));
  await mockAuthentication(page);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'fixture', displayName: '测试模型', provider: 'Mock', defaultModel: true },
  ] } }));
});

/** 仅选择器返回值使用 OPFS 合成句柄，后续真实 Worker、规则和 IndexedDB 保持生产实现。 */
test('多文件夹清单支持同名根、去重、移除、取消，确认后完整索引并逐根隔离规则', async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  let uploads = 0;
  page.on('request', request => { if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/documents')) uploads++; });
  await page.addInitScript(async () => {
    const storage = await navigator.storage.getDirectory();
    const first = await (await storage.getDirectoryHandle('parent-a', { create: true })).getDirectoryHandle('source', { create: true });
    const second = await (await storage.getDirectoryHandle('parent-b', { create: true })).getDirectoryHandle('source', { create: true });
    const third = await storage.getDirectoryHandle('removed-root', { create: true });
    const write = async (root: FileSystemDirectoryHandle, name: string, text: string) => {
      const writer = await (await root.getFileHandle(name, { create: true })).createWritable();
      await writer.write(text); await writer.close();
    };
    await write(first, 'index.ts', 'export const first = "甲末尾规则";');
    await write(first, '.gitignore', 'private.ts');
    await write(first, 'private.ts', '甲不能读取');
    await write(first, '.env', 'SYNTHETIC_PRIVATE_VALUE=hidden');
    await write(second, 'index.ts', 'export const second = "乙末尾规则";');
    await write(second, 'private.ts', '乙必须保留');
    await write(second, 'guide.docx', 'PK synthetic document');
    const queue = [first, first, second, third, first, second];
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => queue.shift() });
  });
  await page.goto('/workbench'); await openWorkbenchPane(page, 'context');
  const open = async () => {
    await page.getByRole('button', { name: '添加上下文' }).click();
    await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  };
  await open();
  const dialog = page.getByRole('dialog', { name: '选择文件夹', exact: true });
  await expect(dialog.locator('.folder-selection-item')).toHaveCount(1);
  await dialog.getByRole('button', { name: '继续添加文件夹' }).click();
  await expect(page.getByText('该文件夹已在选择清单中。')).toBeVisible();
  await expect(dialog.locator('.folder-selection-item')).toHaveCount(1);
  await dialog.getByRole('button', { name: '继续添加文件夹' }).click();
  await expect(dialog.getByText('source (2)', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '继续添加文件夹' }).click();
  await dialog.getByRole('button', { name: '移除 removed-root' }).click();
  await expect(dialog.locator('.folder-selection-item')).toHaveCount(2);
  const geometry = await dialog.evaluate(element => {
    const title = element.querySelector('.el-dialog__header')!.getBoundingClientRect();
    const footer = element.querySelector('.el-dialog__footer')!.getBoundingClientRect();
    const rect = element.getBoundingClientRect();
    return { title: title.top >= 0, footer: footer.bottom <= innerHeight, horizontal: rect.left >= 0 && rect.right <= innerWidth };
  });
  expect(geometry).toEqual({ title: true, footer: true, horizontal: true });
  await page.screenshot({ path: testInfo.outputPath('folder-selection.png') });
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(page.locator('.project-index-summary')).toHaveCount(0);
  expect(uploads).toBe(0);
  await open();
  await expect(dialog.locator('.folder-selection-item')).toHaveCount(1);
  await dialog.getByRole('button', { name: '继续添加文件夹' }).click();
  await dialog.getByRole('button', { name: '开始处理 2 个文件夹' }).click();
  await expect(page.getByText('4 个文本文件已完整建立本地索引')).toBeVisible({ timeout: 30_000 });
  const upload = page.getByRole('dialog', { name: '确认上传需要解析的文档' });
  await expect(upload.getByText('source (2)/guide.docx')).toBeVisible();
  expect(uploads).toBe(0);
  await upload.getByRole('button', { name: '暂不上传' }).click();
  const found = await page.evaluate(async () => {
    const storagePath = '/src/features/project-index/projectIndexStorage.ts';
    const { listIsolatedProjects } = await import(storagePath);
    const path = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const { projectIndexRepository: repo } = await import(path);
    const ids = [...await listIsolatedProjects('bucket'), ...await listIsolatedProjects('database')];
    const id = ids[0];
    const source = await repo.findProjectSource(id);
    const chunks = await repo.findChunksByPaths(id, ['source/index.ts', 'source/private.ts', 'source/.env',
      'source (2)/index.ts', 'source (2)/private.ts'], 20);
    return { count: ids.length, roots: source.roots.map((root: { prefix: string }) => root.prefix),
      chunks: chunks.map((chunk: { path: string; content: string }) => ({ path: chunk.path, content: chunk.content })) };
  });
  expect(found.count).toBe(1);
  expect(found.roots).toEqual(['source', 'source (2)']);
  expect(found.chunks).toEqual(expect.arrayContaining([
    { path: 'source/index.ts', content: 'export const first = "甲末尾规则";' },
    { path: 'source (2)/index.ts', content: 'export const second = "乙末尾规则";' },
    { path: 'source (2)/private.ts', content: '乙必须保留' },
  ]));
  expect(found.chunks).toHaveLength(3);
  expect(errors).toEqual([]);
});

test('不支持原生句柄时可累积两个同名文件夹，首次选择和路径识别均正常', async ({ page }, testInfo) => {
  let received: Array<{ path: string; content: string }> = [];
  await page.route('**/api/v1/context/analyze', route => {
    received = route.request().postDataJSON().files;
    return route.fulfill({ json: { data: {
      customDescription: '', technologyStack: [], dependencies: [], directoryTree: [], fileSnippets: [],
      fileCoverage: [], warnings: [], redactions: [], analysisVersion: 'test', analysisStatus: 'COMPLETE',
    } } });
  });
  await page.addInitScript(() => Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: undefined }));
  const first = testInfo.outputPath('first', 'source'), second = testInfo.outputPath('second', 'source');
  await mkdir(first, { recursive: true }); await mkdir(second, { recursive: true });
  await writeFile(join(first, 'same.ts'), 'export const first = "甲正文";');
  await writeFile(join(first, '.gitignore'), 'private.ts');
  await writeFile(join(first, 'private.ts'), '甲不应导入');
  await writeFile(join(second, 'same.ts'), 'export const second = "乙正文";');
  await writeFile(join(second, 'private.ts'), 'export const valid = "乙应导入";');
  await page.goto('/workbench'); await openWorkbenchPane(page, 'context');
  await page.getByRole('button', { name: '添加上下文' }).click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await (await chooser).setFiles(first);
  const dialog = page.getByRole('dialog', { name: '选择文件夹', exact: true });
  await expect(dialog.locator('.folder-selection-item')).toHaveCount(1);
  const next = page.waitForEvent('filechooser');
  await dialog.getByRole('button', { name: '继续添加文件夹' }).click();
  await (await next).setFiles(second);
  await dialog.getByRole('button', { name: '开始处理 2 个文件夹' }).click();
  await expect(page.getByText('source/same.ts', { exact: true })).toBeVisible();
  await expect(page.getByText('source (2)/same.ts', { exact: true })).toBeVisible();
  await expect(page.getByText('source (2)/private.ts', { exact: true })).toBeVisible();
  await expect(page.getByText('source/private.ts', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '分析上下文资料', exact: true }).click();
  const confirmation = page.getByRole('dialog', { name: '确认发送上下文' });
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => received.length).toBe(4);
  expect(received.map(({ path, content }) => ({ path, content }))).toEqual(expect.arrayContaining([
    { path: 'source/.gitignore', content: 'private.ts' },
    { path: 'source/same.ts', content: 'export const first = "甲正文";' },
    { path: 'source (2)/same.ts', content: 'export const second = "乙正文";' },
    { path: 'source (2)/private.ts', content: 'export const valid = "乙应导入";' },
  ]));
});

test('多根真实 Worker 暂停后沿用同一组路径继续，全文与末尾均完整', async ({ page }) => {
  // 只延长文件读取以稳定命中暂停时机；不替换扫描、检查点、存储和恢复实现。
  let loads = 0;
  await page.route(url => url.pathname.endsWith('/projectIndex.worker.ts'), async route => {
    const response = await route.fetch();
    if (++loads !== 1) return route.fulfill({ response });
    const delay = `const originalRead=FileSystemFileHandle.prototype.getFile;
      FileSystemFileHandle.prototype.getFile=async function(){await new Promise(r=>setTimeout(r,20));return originalRead.call(this)};`;
    return route.fulfill({ response, body: delay + '\n' + await response.text() });
  });
  await page.goto('/workbench');
  const result = await page.evaluate(async () => {
    const vuePath = '/node_modules/.vite/deps/vue.js';
    const { effectScope, watch } = await import(vuePath);
    const indexPath = '/src/composables/useProjectIndex.ts';
    const { useProjectIndex } = await import(indexPath);
    const selectionPath = '/src/features/project-index/projectDirectorySelection.ts';
    const { createDirectorySelection } = await import(selectionPath);
    const repositoryPath = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const { projectIndexRepository: repo } = await import(repositoryPath);
    const storage = await navigator.storage.getDirectory();
    const roots: FileSystemDirectoryHandle[] = [];
    const expected: Record<string, string> = {};
    for (const name of ['甲根', '乙根']) {
      const root = await storage.getDirectoryHandle(name, { create: true });
      roots.push(root);
      for (let start = 0; start < 300; start += 10) {
        await Promise.all(Array.from({ length: 10 }, async (_, offset) => {
          const file = `file-${start + offset}.ts`, text = `export const value = '${name}-${start + offset}-末尾规则';`;
          expected[`${name}/${file}`] = text;
          const writer = await (await root.getFileHandle(file, { create: true })).createWritable();
          await writer.write(text); await writer.close();
        }));
      }
    }
    const scope = effectScope(), index = scope.run(useProjectIndex);
    let pauseSent = false;
    const stop = watch(index.progress, (progress: { processedFiles: number } | undefined) => {
      if (!pauseSent && progress && progress.processedFiles >= 220) {
        pauseSent = true; index.pauseIndexing();
      }
    });
    const options = { retention: 'SESSION', autoCleanupDays: 1,
      limits: { maxScanFiles: 2000, maxIndexBytes: 50 * 1024 * 1024, maxIndexableFileBytes: 1024 * 1024 } };
    let id: string | undefined;
    try {
      const paused = await index.indexDirectory(options, createDirectorySelection(roots));
      id = paused.id;
      const resumed = await index.resumeIndexing(options);
      const saved = await repo.findProjectSource(id);
      const chunks = await repo.findChunksByPaths(id, Object.keys(expected), 2000);
      const exact = chunks.filter((chunk: { path: string; content: string }) => expected[chunk.path] === chunk.content).length;
      return { pauseSent, paused: { status: paused.status, files: paused.indexedFiles },
        resumed: { status: resumed.status, files: resumed.indexedFiles, reused: resumed.unchangedFiles, failed: resumed.failedFiles },
        prefixes: saved.roots.map((root: { prefix: string }) => root.prefix), exact, chunks: chunks.length };
    } finally { stop(); scope.stop(); if (id) await repo.deleteProject(id); }
  });
  expect(result.pauseSent).toBe(true);
  expect(result.paused.status).toBe('PAUSED');
  expect(result.paused.files).toBeGreaterThan(0);
  expect(result.paused.files).toBeLessThan(600);
  expect(result.resumed).toEqual({ status: 'READY', files: 600, reused: result.paused.files, failed: 0 });
  expect(result.prefixes).toEqual(['甲根', '乙根']);
  expect(result.exact).toBe(600);
  expect(result.chunks).toBe(600);
});
