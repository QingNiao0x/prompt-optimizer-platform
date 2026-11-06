import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';
import type { ContextAnalysisRequest } from '../src/types/api';

test('材料用途随实际分析请求发送，桌面和移动端可选择并恢复自动判断', async ({ page }, testInfo) => {
  let received: ContextAnalysisRequest | undefined;
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记的受控请求' } } }));
  await mockAuthentication(page);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'fixture', displayName: '受控模型', provider: 'fixture', defaultModel: true },
  ] } }));
  await page.route('**/api/v1/context/analyze', route => {
    received = route.request().postDataJSON() as ContextAnalysisRequest;
    return route.fulfill({ json: { data: {
      customDescription: '', technologyStack: [], dependencies: [], directoryTree: ['notes.txt'],
      fileSnippets: [{ path: 'notes.txt', language: 'text', content: '测试模板', summary: '', truncated: false,
        purpose: received.files[0]?.purpose, purposeGuidance: '用于格式或风格参考。' }],
      warnings: [], redactions: [], analysisStatus: 'COMPLETE', fileCoverage: [], analysisVersion: 'fixture',
    } } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.locator('.manual-snippet summary').click();
  await page.getByRole('textbox', { name: '文件相对路径' }).fill('notes.txt');
  await page.getByPlaceholder('粘贴与当前任务相关的代码片段…').fill('测试模板');
  await page.getByRole('button', { name: '加入上下文', exact: true }).click();
  const purpose = page.locator('.file-purpose');
  await expect(page.getByRole('combobox', { name: 'notes.txt 的材料用途' })).toBeVisible();
  await purpose.locator('.el-select__wrapper').click();
  await page.getByRole('option', { name: '格式或风格参考', exact: true }).click();
  await expect(purpose).toContainText('格式或风格参考');
  await page.getByRole('button', { name: '分析上下文资料', exact: true }).click();
  const confirmation = page.getByRole('dialog', { name: '确认发送上下文' });
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => received?.files[0]?.purpose).toBe('STYLE_REFERENCE');
  await page.screenshot({ path: testInfo.outputPath('material-purpose.png'), fullPage: true, animations: 'disabled' });
  await purpose.locator('.el-select__wrapper').click();
  await page.getByRole('option', { name: '按原始需求自动判断', exact: true }).click();
  await expect(purpose).toContainText('按原始需求自动判断');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});
