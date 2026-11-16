import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

/** 使用真实文件输入与读取链路；HTTP 只隔离身份、模型目录及分析回包，不调用付费模型。 */
test('选择文件支持同时读取多个源码文件，受保护文件仍被排除', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记的测试接口' } } }));
  await mockAuthentication(page);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'fixture', displayName: '测试模型', provider: 'Mock', defaultModel: true },
  ] } }));
  let received: Array<{ path: string; content: string }> = [];
  await page.route('**/api/v1/context/analyze', route => {
    received = route.request().postDataJSON().files;
    return route.fulfill({ json: { data: {
      customDescription: '', technologyStack: [], dependencies: [], directoryTree: [], fileSnippets: [],
      fileCoverage: [], warnings: [], redactions: [], analysisVersion: 'test', analysisStatus: 'COMPLETE',
    } } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.getByRole('button', { name: '添加上下文' }).click();
  const chooserEvent = page.waitForEvent('filechooser');
  await page.getByRole('menuitem', { name: '选择文件', exact: true }).click();
  const chooser = await chooserEvent;
  expect(chooser.isMultiple()).toBe(true);
  const sources = [
    { name: 'MultiFirst.java', body: 'class MultiFirst { String rule = "甲规则末尾"; }' },
    { name: 'MultiSecond.ts', body: 'export const rule = "乙规则末尾";' },
  ];
  await chooser.setFiles([
    ...sources.map(file => ({ name: file.name, mimeType: 'text/plain', buffer: Buffer.from(file.body) })),
    { name: '.env', mimeType: 'text/plain', buffer: Buffer.from('SYNTHETIC_PRIVATE_VALUE=do-not-include') },
  ]);
  for (const source of sources) await expect(page.getByText(source.name, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '分析上下文资料', exact: true }).click();
  const confirmation = page.getByRole('dialog', { name: '确认发送上下文' });
  if (await confirmation.isVisible()) await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => received.length).toBe(2);
  for (const source of sources) expect(received.find(file => file.path === source.name)?.content).toBe(source.body);
  expect(received.some(file => file.path === '.env')).toBe(false);
  expect(errors).toEqual([]);
});
