import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';
import type { DocumentUploadStatus } from '../src/types/api';

// 浏览器用例验证目录选择、原始字节上传及 documentId 传递；真实 Office/PDF 解析由 Java 集成测试验证。
test('文档文件夹保留嵌套路径并完整上传中等大小 TXT 与办公文件', async ({ page }, testInfo) => {
  await mockAuthentication(page);
  await page.addInitScript(() => {
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: undefined });
  });
  const folder = testInfo.outputPath('资料');
  await mkdir(join(folder, '子目录'), { recursive: true });
  const body = '中文资料正文。'.repeat(12_000) + '尾部唯一规则：退款期限三个工作日。';
  await writeFile(join(folder, '方案.txt'), body, 'utf8');
  await writeFile(join(folder, '子目录', '说明.docx'), Buffer.from('PK synthetic docx'));
  await writeFile(join(folder, '附录.pdf'), Buffer.from('%PDF synthetic pdf'));
  const sessions = new Map<string, DocumentUploadStatus>();
  const uploaded = new Map<string, Buffer[]>();
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/api/v1/context/documents**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/documents')) {
      const input = request.postDataJSON() as { path: string; language: string; sizeBytes: number };
      const documentId = `document-${sessions.size}`;
      const status: DocumentUploadStatus = {
        documentId, path: input.path, language: input.language, fileSizeBytes: input.sizeBytes,
        phase: 'UPLOADING', uploadedBytes: 0, progressPercent: 0, extractedCharacters: 0,
        chunkCount: 0, summary: '', warnings: [], errorMessage: '',
        expiresAt: '2099-01-01T00:00:00Z', chunkSizeBytes: 65_536,
      };
      sessions.set(documentId, status);
      uploaded.set(documentId, []);
      await route.fulfill({ status: 201, json: { data: status } });
      return;
    }
    const documentId = path.split('/documents/')[1]!.split('/')[0]!;
    const status = sessions.get(documentId)!;
    if (path.includes('/chunks/')) {
      const bytes = request.postDataBuffer()!;
      uploaded.get(documentId)!.push(bytes);
      status.uploadedBytes += bytes.length;
    }
    if (path.endsWith('/complete')) {
      status.phase = 'READY';
      status.progressPercent = 100;
      status.extractedCharacters = 100;
      status.chunkCount = 1;
    }
    await route.fulfill({ json: { data: status } });
  });
  let analyzedPaths: string[] = [];
  await page.route('**/api/v1/context/analyze', async (route) => {
    const input = route.request().postDataJSON() as { files: Array<{ path: string; content: string; documentId: string }> };
    analyzedPaths = input.files.map((file) => file.path).sort();
    for (const file of input.files) {
      expect(file.content).toBe('');
      expect(sessions.get(file.documentId)?.path).toBe(file.path);
    }
    await route.fulfill({ json: { data: {
      customDescription: '', technologyStack: [], dependencies: [], directoryTree: [], fileSnippets: [],
      fileCoverage: [], warnings: [], redactions: [], analysisVersion: 'test', analysisStatus: 'COMPLETE',
    } } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await expect(page.getByText(/支持的浏览器会在本地索引文件夹中的代码，办公文档上传解析前会先请你确认/)).toBeVisible();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: '添加上下文' }).click();
  await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  await (await chooser).setFiles(folder);
  const uploadConfirm = page.getByRole('dialog', { name: '确认上传需要解析的文档' });
  await expect(uploadConfirm).toBeVisible();
  expect(sessions.size).toBe(0);
  await uploadConfirm.getByRole('button', { name: '暂不上传' }).click();
  const chooserAgain = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: '添加上下文' }).click();
  await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  await (await chooserAgain).setFiles(folder);
  await expect(uploadConfirm).toBeVisible();
  await uploadConfirm.getByRole('button', { name: '确认上传所选文档' }).click();
  await expect(page.getByText('资料/子目录/说明.docx', { exact: true })).toBeVisible();
  await expect(page.getByText('资料/方案.txt', { exact: true })).toBeVisible();
  await expect(page.getByText('资料/附录.pdf', { exact: true })).toBeVisible();
  const textSession = [...sessions.values()].find((session) => session.path.endsWith('方案.txt'))!;
  expect(uploaded.get(textSession.documentId)!.length).toBeGreaterThan(1);
  expect(Buffer.concat(uploaded.get(textSession.documentId)!).toString('utf8')).toBe(body);
  await page.getByRole('button', { name: '分析上下文资料', exact: true }).click();
  const confirmation = page.getByRole('dialog', { name: '确认发送上下文' });
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => analyzedPaths).toEqual(['资料/子目录/说明.docx', '资料/方案.txt', '资料/附录.pdf'].sort());
  expect(errors).toEqual([]);
});

test('一次选择混合文件夹会索引源码，并在确认后才上传办公文档', async ({ page }) => {
  await mockAuthentication(page);
  await page.addInitScript(async () => {
    const storage = await navigator.storage.getDirectory();
    const root = await storage.getDirectoryHandle('mixed-context-folder', { create: true });
    const source = await root.getFileHandle('Example.java', { create: true });
    const sourceWriter = await source.createWritable();
    await sourceWriter.write('class Example { String rule = "审批需复核"; }');
    await sourceWriter.close();
    const docs = await root.getDirectoryHandle('docs', { create: true });
    const document = await docs.getFileHandle('方案.docx', { create: true });
    const documentWriter = await document.createWritable();
    await documentWriter.write('PK synthetic document');
    await documentWriter.close();
    Object.defineProperty(window, 'showDirectoryPicker', {
      configurable: true,
      value: async () => root,
    });
  });

  let uploads = 0;
  let analyzedPaths: string[] = [];
  const status: DocumentUploadStatus = {
    documentId: 'mixed-doc', path: 'docs/方案.docx', language: 'docx', fileSizeBytes: 21,
    phase: 'UPLOADING', uploadedBytes: 0, progressPercent: 0, extractedCharacters: 0,
    chunkCount: 0, summary: '', warnings: [], errorMessage: '',
    expiresAt: '2099-01-01T00:00:00Z', chunkSizeBytes: 65_536,
  };
  await page.route('**/api/v1/context/documents**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/documents')) {
      uploads += 1;
      await route.fulfill({ status: 201, json: { data: status } });
      return;
    }
    if (path.endsWith('/complete')) {
      status.phase = 'READY';
      status.progressPercent = 100;
      status.chunkCount = 1;
    }
    await route.fulfill({ json: { data: status } });
  });
  await page.route('**/api/v1/context/analyze', async (route) => {
    const input = route.request().postDataJSON() as { files: Array<{ path: string }> };
    analyzedPaths = input.files.map((file) => file.path);
    await route.fulfill({ json: { data: {
      customDescription: '', technologyStack: [], dependencies: [], directoryTree: [],
      fileSnippets: [], fileCoverage: [], warnings: [], redactions: [],
      analysisVersion: 'test', analysisStatus: 'COMPLETE',
    } } });
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.getByRole('button', { name: '添加上下文' }).click();
  await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  await expect(page.getByText('1 个源码文件已建立本地索引')).toBeVisible();
  const dialog = page.getByRole('dialog', { name: '确认上传需要解析的文档' });
  await expect(dialog.getByText('docs/方案.docx')).toBeVisible();
  await expect(dialog.getByText('Example.java')).toHaveCount(0);
  expect(uploads).toBe(0);
  await dialog.getByRole('button', { name: '暂不上传' }).click();
  await expect(page.getByText(/已发现 1 个文档但未上传/)).toBeVisible();
  expect(uploads).toBe(0);
  await page.getByRole('button', { name: '添加上下文' }).click();
  await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  await expect(dialog.getByText('docs/方案.docx')).toBeVisible();
  await dialog.getByRole('button', { name: '确认上传所选文档' }).click();
  await expect(page.getByText('docs/方案.docx', { exact: true })).toBeVisible();
  expect(uploads).toBe(1);

  await page.getByRole('button', { name: '分析上下文资料', exact: true }).click();
  const transmission = page.getByRole('dialog', { name: '确认发送上下文' });
  await transmission.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => analyzedPaths).toContain('docs/方案.docx');
  expect(analyzedPaths.some((path) => path.startsWith('Example.java#chunk-'))).toBe(true);
});
