import { expect, test, type Page } from '@playwright/test';
import type { ContextFileInput, ContextSnapshot, DocumentUploadStatus, OptimizationHistoryDetail, OptimizationResult } from '../src/types/api';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

const context: ContextSnapshot = { customDescription: '', technologyStack: [], dependencies: [],
  directoryTree: [], fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'p0' };
const result: OptimizationResult = { optimizedPrompt: '原始生成稿', sections: [
  { type: 'BACKGROUND', title: '背景', content: '测试背景' },
  { type: 'TASK', title: '任务', content: '原任务' },
  { type: 'OUTPUT', title: '输出', content: '原输出' },
  { type: 'CONSTRAINTS', title: '约束', content: '保留事实边界' },
], contextReport: context, ambiguities: [], appliedConstraints: [], templateCode: 'GENERAL',
  provider: { provider: 'mock', model: 'p0-browser', mock: true }, latencyMs: 1 };

test.beforeEach(async ({ page }) => {
  await mockAuthentication(page);
  await page.addInitScript(() => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
    localStorage.setItem('prompt-optimizer.project-context-settings.v1', JSON.stringify({
      profile: 'WEB_STANDARD', retention: 'SESSION', autoCleanupDays: 7, confirmBeforeSendingCode: false,
    }));
  });
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'mock:p0', displayName: 'P0 交互测试', provider: 'mock', defaultModel: true },
  ] } }));
  await page.route('**/api/v1/optimizations/plan-events', route => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/optimizations', route => route.fulfill({ json: { requestId: 'p0', data: result } }));
});

const historyB: OptimizationHistoryDetail = { id: 'history-b', rawPrompt: '任务 B：写邮件',
  optimizedPrompt: '', sections: [], contextSummary: {}, ambiguities: [], appliedConstraints: [],
  templateCode: 'GENERAL', providerName: 'mock', modelName: 'p0', mock: true, latencyMs: 1,
  createdAt: '2026-10-11T00:00:00Z', includePermissionBoundaries: true, includeExamples: false,
  conversationHistory: [], permissionPolicy: {} };

const mockHistoryB = async (page: Page): Promise<void> => {
  await page.route('**/api/v1/optimization-history**', route => route.fulfill({ json: {
    data: new URL(route.request().url()).pathname.endsWith('/history-b') ? historyB : {
      records: [{ ...historyB, rawPromptPreview: historyB.rawPrompt }], current: 1, size: 10, total: 1, pages: 1,
    },
  } }));
};

const addMaterial = async (page: Page, path: string, content: string): Promise<void> => {
  await openWorkbenchPane(page, 'context');
  const manual = page.locator('.manual-snippet');
  if (!await manual.evaluate(element => element.hasAttribute('open'))) await manual.locator('summary').click();
  await page.getByLabel('文件相对路径').fill(path);
  await manual.locator('textarea').fill(content);
  await manual.getByRole('button', { name: '加入上下文', exact: true }).click();
};

const loadB = async (page: Page): Promise<void> => {
  // 使用 SPA 路由保留 Pinia，整页 goto 会清空内存并掩盖历史载入缺陷。
  await page.getByRole('link', { name: '历史', exact: true }).click();
  await page.getByRole('button', { name: '载入', exact: true }).filter({ visible: true }).first().click();
  await expect(page).toHaveURL(/\/workbench$/);
};

const generate = async (page: Page): Promise<void> => {
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('请写一封短邮件。');
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  await openWorkbenchPane(page, 'result');
  await expect(page.getByRole('button', { name: '编辑', exact: true })).toBeVisible();
};

test('P0 保存校验失败保留全部草稿，修正后应用并复制', async ({ page }, testInfo) => {
  await generate(page);
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await page.locator('#section-TASK').fill('保留这段未保存的修改');
  await page.locator('#section-OUTPUT').fill('');
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  await expect(page.locator('#section-TASK')).toHaveValue('保留这段未保存的修改');
  await expect(page.locator('#section-OUTPUT')).toHaveValue('');
  await expect(page.locator('#section-OUTPUT')).toBeFocused();
  await expect(page.locator('#section-OUTPUT')).toHaveAttribute('aria-invalid', 'true');
  await page.screenshot({ path: testInfo.outputPath('failed-save-draft.png') });
  await page.locator('#section-OUTPUT').fill('修正后的输出要求');
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  await expect(page.getByRole('button', { name: '编辑', exact: true })).toBeVisible();
  await expect(page.locator('.section-list')).toContainText('保留这段未保存的修改');
  await expect(page.locator('.section-list')).toContainText('修正后的输出要求');
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.getByRole('button', { name: '复制提示词', exact: true }).click();
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  expect(copied).toContain('保留这段未保存的修改');
  expect(copied).toContain('修正后的输出要求');
});

test('P0 历史任务 B 添加资料后，最终请求不含 A 的资料', async ({ page }) => {
  await mockHistoryB(page);
  await page.goto('/workbench');
  await addMaterial(page, 'A.txt', 'A_PRIVATE_MARKER');
  await loadB(page);
  await addMaterial(page, 'B.txt', 'B_ONLY_MARKER');
  await expect(page.locator('.file-list')).not.toContainText('A.txt');
  await openWorkbenchPane(page, 'intent');
  const request = page.waitForRequest(req => req.url().endsWith('/api/v1/optimizations') && req.method() === 'POST');
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  const payload = (await request).postDataJSON() as { context: { files: ContextFileInput[] } };
  expect(payload.context.files.map(file => file.path)).toEqual(['B.txt']);
  expect(JSON.stringify(payload)).not.toContain('A_PRIVATE_MARKER');
});

test('P0 A 的增强响应迟到，不能覆盖 B 的结果', async ({ page }) => {
  await mockHistoryB(page);
  let respondA!: () => void;
  let aStarted = false;
  const waitA = new Promise<void>(resolve => { respondA = resolve; });
  await page.route('**/api/v1/optimizations', async route => {
    const input = route.request().postDataJSON();
    if (input.rawPrompt === '任务 A') {
      aStarted = true;
      await waitA;
      await route.fulfill({ json: { requestId: 'old-a', data: { ...result, optimizedPrompt: 'OLD_A_RESULT',
        sections: result.sections.map(section => ({ ...section, content: 'OLD_A_RESULT' })) } } });
    } else await route.fulfill({ json: { requestId: 'new-b', data: { ...result,
      sections: result.sections.map(section => ({ ...section, content: 'NEW_B_RESULT' })) } } });
  });
  await page.goto('/workbench');
  await page.getByLabel('原始提示词').fill('任务 A');
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  await expect.poll(() => aStarted).toBe(true);
  await loadB(page);
  await openWorkbenchPane(page, 'intent');
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  await openWorkbenchPane(page, 'result');
  await expect(page.locator('.section-list')).toContainText('NEW_B_RESULT');
  const aResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/optimizations')
    && response.request().postDataJSON().rawPrompt === '任务 A');
  respondA();
  await (await aResponse).finished();
  // 让 Axios/Vue 处理迟到响应，之后通过保留 B 的正文与可编辑状态验证。
  await page.waitForTimeout(150);
  await expect(page.locator('.section-list')).not.toContainText('OLD_A_RESULT');
  await expect(page.locator('.section-list')).toContainText('NEW_B_RESULT');
  await expect(page.getByRole('button', { name: '编辑', exact: true })).toBeEnabled();
});

test('P0 未保存修改阻止再次增强和离开，取消离开后仍保留草稿', async ({ page }) => {
  await generate(page);
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await page.locator('#section-TASK').fill('保留导航中的未保存修改');
  await openWorkbenchPane(page, 'intent');
  let extraRequests = 0;
  page.on('request', request => { if (request.url().endsWith('/api/v1/optimizations')) extraRequests++; });
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  await expect(page.getByText('请先保存或取消结果中的修改，再进行增强。')).toBeVisible();
  expect(extraRequests).toBe(0);
  await page.getByRole('link', { name: '历史', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '尚未保存的修改' });
  await dialog.getByRole('button', { name: '继续编辑', exact: true }).click();
  await expect(page).toHaveURL(/\/workbench$/);
  await openWorkbenchPane(page, 'result');
  await expect(page.locator('#section-TASK')).toHaveValue('保留导航中的未保存修改');
});

test('P0 显示完整、部分与未发送正文，并只预览本次安全片段', async ({ page }, testInfo) => {
  await page.route('**/api/v1/optimizations', route => route.fulfill({ json: { requestId: 'p0-receipt', data: {
    ...result, provider: { provider: 'synthetic', model: 'fixture', mock: false }, contextReport: { ...context,
      fileSnippets: [{ path: 'full.txt', language: 'text', content: '完整正文', summary: '', truncated: false },
        { path: 'partial.txt', language: 'text', content: '选取部分', summary: '', truncated: true }],
      warnings: ['文件内容已截断：partial.txt'],
    },
  } } }));
  await page.goto('/workbench');
  await addMaterial(page, 'full.txt', '完整正文');
  await addMaterial(page, 'partial.txt', '选取部分，后续内容未进入模型');
  await addMaterial(page, 'omitted.txt', 'OMITTED_RAW_MARKER');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('整理资料中的事实。');
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  await openWorkbenchPane(page, 'context');
  const report = page.getByRole('region', { name: '本次资料发送范围' });
  await expect(report.locator('[data-path="full.txt"]')).toContainText('完整发送');
  await expect(report.locator('[data-path="partial.txt"]')).toContainText('部分发送');
  await expect(report.locator('[data-path="omitted.txt"]')).toContainText('未发送正文');
  await report.locator('[data-path="partial.txt"] summary').click();
  await expect(report.locator('[data-path="partial.txt"] pre')).toHaveText('选取部分');
  await expect(report).not.toContainText('OMITTED_RAW_MARKER');
  await expect(report).not.toContainText('后续内容未进入模型');
  await page.screenshot({ path: testInfo.outputPath('actual-context-range.png') });
});

test('P0 清空资料会取消进行中的文档上传，迟到响应不恢复引用', async ({ page }) => {
  let releaseComplete!: () => void;
  const waitComplete = new Promise<void>(resolve => { releaseComplete = resolve; });
  let completing = false;
  const deleted: string[] = [];
  const status: DocumentUploadStatus = { documentId: 'old-document-a', path: 'A.pdf', language: 'pdf',
    fileSizeBytes: 14, phase: 'UPLOADING', uploadedBytes: 0, progressPercent: 0, extractedCharacters: 0,
    chunkCount: 0, summary: '', warnings: [], errorMessage: '', expiresAt: '2099', chunkSizeBytes: 65_536 };
  await page.route('**/api/v1/context/documents**', async route => {
    const request = route.request();
    if (request.method() === 'DELETE') {
      deleted.push(status.documentId);
      await route.fulfill({ status: 204 });
    } else if (request.url().endsWith('/complete')) {
      completing = true;
      await waitComplete;
      // 浏览器可能已取消请求，仍释放模拟后端响应以检查引用不会复活。
      await route.fulfill({ json: { data: { ...status, phase: 'READY', extractedCharacters: 100 } } }).catch(() => undefined);
    } else await route.fulfill({ json: { data: status } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.locator('input[type="file"]:not([webkitdirectory])').setInputFiles({ name: 'A.pdf',
    mimeType: 'application/pdf', buffer: Buffer.from('%PDF synthetic') });
  const upload = page.getByRole('dialog', { name: '确认上传需要解析的文档' });
  await upload.getByRole('button', { name: '确认上传所选文档' }).click();
  await expect.poll(() => completing).toBe(true);
  await page.getByRole('button', { name: '清空', exact: true }).click();
  releaseComplete();
  await expect.poll(() => deleted).toContain(status.documentId);
  await addMaterial(page, 'B.txt', 'B_ONLY');
  await expect(page.locator('.file-list')).not.toContainText('A.pdf');
  await expect(page.locator('.file-list')).toContainText('B.txt');
});
