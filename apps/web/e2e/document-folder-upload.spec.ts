import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';
import type { DocumentUploadStatus } from '../src/types/api';

// 浏览器用例验证目录选择、原始字节上传及 documentId 传递；真实 Office/PDF 解析由 Java 集成测试验证。
test('文档文件夹保留嵌套路径并完整上传中等大小 TXT 与办公文件', async ({ page }, testInfo) => {
  await mockAuthentication(page);
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
  await page.route('**/api/v1/models', (route) => route.fulfill({ json: { data: [] } }));
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
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: '添加文档文件夹', exact: true }).click();
  await (await chooser).setFiles(folder);
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
