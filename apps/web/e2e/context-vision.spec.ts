import { expect, test, type Page } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';
import type { ContextVisionConfirmation, DocumentUploadStatus } from '../src/types/api';

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6o0MAAAAASUVORK5CYII=', 'base64');
async function setup(page: Page, modelFailure = false, delayedRecognition = false) {
  const state = { confirmations: [] as ContextVisionConfirmation[], deleted: 0, analyzed: [] as { documentId: string; content: string }[], createdWithReview: false,
    finishRecognition: !delayedRecognition };
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: 'Unregistered controlled request' } } }));
  await mockAuthentication(page);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [{ id: 'vision', displayName: '受控视觉模型', provider: 'fixture', defaultModel: true }] } }));
  let status: DocumentUploadStatus = { documentId: 'controlled-document', path: '混合资料.pdf', language: 'pdf', phase: 'UPLOADING',
    fileSizeBytes: 20, uploadedBytes: 0, progressPercent: 0, extractedCharacters: 0, chunkCount: 0, summary: '', warnings: [], errorMessage: '',
    expiresAt: '2099-01-01T00:00:00Z', chunkSizeBytes: 1048576 };
  await page.route('**/api/v1/context/documents**', route => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    if (path.endsWith('/vision-models')) return route.fulfill({ status: modelFailure ? 503 : 200, json: { data: [
      { id: 'vision', displayName: '受控视觉模型', vision: true, maxImages: 1, evidence: '受控测试', mock: false },
      { id: 'text', displayName: '受控文字模型', vision: false, maxImages: 0, evidence: '纯文字', mock: false },
    ], error: { message: '模型目录暂不可用' } } });
    if (path.endsWith('/documents') && method === 'POST') {
      state.createdWithReview = route.request().postDataJSON().visionRequested === true;
      return route.fulfill({ status: 201, json: { data: status } });
    }
    if (path.includes('/pages/')) return route.fulfill({ contentType: 'image/png', body: png, headers: { 'cache-control': 'no-store' } });
    if (path.endsWith('/complete')) status = { ...status, phase: 'AWAITING_CONFIRMATION', progressPercent: 45,
      visionReview: { pages: [
        { page: 1, readableText: false, hasImages: true, suggested: true, blocked: false, reason: '扫描页，建议识别' },
        { page: 2, readableText: true, hasImages: false, suggested: false, blocked: false, reason: '文字层可本地提取' },
      ], maxPages: 10, maxCalls: 10, maxOutputTokens: 8000, deadlineSeconds: 180 } };
    if (path.endsWith('/vision')) {
      const input = route.request().postDataJSON() as ContextVisionConfirmation;
      state.confirmations.push(input);
      status = { ...status, phase: input.pages.length ? delayedRecognition ? 'EXTRACTING' : 'READY' : 'PARTIAL', progressPercent: 100, chunkCount: 1, extractedCharacters: 120,
        warnings: input.pages.length ? [] : ['第 1 页未识别；仍保留第 2 页文字层'], visionReview: null };
    }
    if (method === 'GET' && status.phase === 'EXTRACTING' && state.finishRecognition) status = { ...status, phase: 'READY' };
    if (method === 'DELETE') state.deleted++;
    return route.fulfill({ json: { data: method === 'DELETE' ? null : status } });
  });
  await page.route('**/api/v1/context/analyze', route => {
    state.analyzed = route.request().postDataJSON().files;
    return route.fulfill({ json: { data: { customDescription: '', technologyStack: [], dependencies: [], directoryTree: [],
      fileSnippets: [{ path: '混合资料.pdf', language: 'pdf', content: '已识别的阈值为 27，保留原任务交付物。', summary: '资料阈值 27', truncated: false }],
      fileCoverage: [], warnings: [], redactions: [], analysisStatus: 'COMPLETE', analysisVersion: 'fixture' } } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.getByRole('button', { name: '添加上下文', exact: true }).click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('menuitem', { name: '选择文件', exact: true }).click();
  await (await chooser).setFiles({ name: '混合资料.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF controlled') });
  await page.getByRole('dialog', { name: '确认上传需要解析的文档' }).getByRole('button', { name: '确认上传所选文档' }).click();
  await expect(page.getByRole('dialog', { name: '确认图片与 PDF 识别范围' })).toBeVisible();
  return state;
}

test('先审阅并遮挡，明确同意后才识别，最终上下文仅传临时索引引用', async ({ page }, testInfo) => {
  const state = await setup(page);
  const dialog = page.getByRole('dialog', { name: '确认图片与 PDF 识别范围' });
  await expect(dialog.getByRole('button', { name: '确认识别所选页面' })).toBeDisabled();
  await expect(dialog.getByRole('img', { name: '第 1 页私有预览' })).toBeVisible();
  expect(state.confirmations).toHaveLength(0); expect(state.createdWithReview).toBe(true);
  await dialog.locator('summary').click();
  const inputs = dialog.locator('.exact-mask input');
  for (const [index, value] of ['10','10','30','20'].entries()) await inputs.nth(index).fill(value);
  await dialog.getByRole('button', { name: '添加遮挡', exact: true }).click();
  await expect(dialog.getByText('本页遮挡 1 处', { exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('context-vision-review.png'), fullPage: true, animations: 'disabled' });
  await dialog.locator('.vision-consent .el-checkbox').click();
  await dialog.getByRole('button', { name: '确认识别所选页面' }).click();
  await expect(dialog).not.toBeVisible();
  expect(state.confirmations).toEqual([{ modelId: 'vision', consent: true, pages: [{ page: 1, masks: [{ x: 0.1, y: 0.1, width: 0.3, height: 0.2 }] }] }]);
  await expect(page.getByText('混合资料.pdf', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '分析上下文资料', exact: true }).click();
  const transmission = page.getByRole('dialog', { name: '确认发送上下文' });
  if (await transmission.isVisible()) await transmission.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => state.analyzed.length).toBe(1);
  expect(state.analyzed[0]?.documentId).toBe('controlled-document');
  expect(state.analyzed[0]?.content).toBe('');
  expect(state.confirmations).toHaveLength(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});

test('模型目录不可用时仍可仅本地提取，并明确显示未识别页', async ({ page }) => {
  const state = await setup(page, true);
  const dialog = page.getByRole('dialog', { name: '确认图片与 PDF 识别范围' });
  await expect(dialog.getByText('模型目录暂不可用')).toBeVisible();
  await dialog.getByRole('button', { name: '仅本地提取' }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByText(/第 1 页未识别；仍保留第 2 页文字层/)).toBeVisible();
  expect(state.confirmations).toEqual([{ modelId: '', consent: false, pages: [] }]);
});

test('取消待审阅文件清理服务端会话且不发送识别确认', async ({ page }) => {
  const state = await setup(page);
  await page.getByRole('dialog', { name: '确认图片与 PDF 识别范围' }).getByRole('button', { name: '取消此文件' }).click();
  await expect.poll(() => state.deleted).toBe(1);
  expect(state.confirmations).toHaveLength(0);
});

test('后台识别完成前禁用分析和增强，避免空文件集合被提前提交', async ({ page }) => {
  const state = await setup(page, false, true);
  const dialog = page.getByRole('dialog', { name: '确认图片与 PDF 识别范围' });
  await dialog.locator('.vision-consent .el-checkbox').click();
  await dialog.getByRole('button', { name: '确认识别所选页面' }).click();
  await expect(dialog).not.toBeVisible();
  const analyze = page.getByRole('button', { name: '分析上下文资料', exact: true });
  await expect(analyze).toBeDisabled();
  expect(state.analyzed).toHaveLength(0);
  await openWorkbenchPane(page, 'intent');
  await page.locator('.intent-column textarea').fill('根据资料写采购报告');
  await expect(page.getByRole('button', { name: '直接增强提示词', exact: true })).toBeDisabled();
  await openWorkbenchPane(page, 'context');
  state.finishRecognition = true;
  await expect(analyze).toBeEnabled();
  await analyze.click();
  const transmission = page.getByRole('dialog', { name: '确认发送上下文' });
  if (await transmission.isVisible()) await transmission.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => state.analyzed.length).toBe(1);
  expect(state.analyzed[0]?.documentId).toBe('controlled-document');
});
