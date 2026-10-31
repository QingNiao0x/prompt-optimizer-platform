import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

/** 延长真实仓库准备入口以稳定检查界面，不伪造目录计数或最终成功状态。 */
test('旧库准备期间显示真实阶段，取消后不复活任务，再次选择能完成真实 Worker 索引', async ({ page }, testInfo) => {
  await mockAuthentication(page);
  await page.route('**/api/v1/me/settings', route => route.fulfill({ status: 503, json: { error: { message: '受控偏好不可用' } } }));
  await page.route('**/api/v1/analytics/events', route => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'mock:default', displayName: '测试模型', provider: 'Mock', defaultModel: true },
  ] } }));
  await page.route(url => url.pathname.endsWith('/indexedDbProjectIndexRepository.ts'), async route => {
    const response = await route.fetch(), source = await response.text();
    const marker = 'async prepareProject(projectId, expiresAt, retention) {';
    expect(source).toContain(marker);
    const gate = `if (!globalThis.__preparationReleased) await new Promise(resolve => { globalThis.__releasePreparation = () => { globalThis.__preparationReleased = true; resolve(); }; });`;
    await route.fulfill({ response, body: source.replace(marker, marker + gate) });
  });
  let workerLoads = 0;
  page.on('request', request => { if (new URL(request.url()).pathname.endsWith('/projectIndex.worker.ts')) workerLoads++; });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.evaluate(async () => {
    const root = await (await navigator.storage.getDirectory()).getDirectoryHandle('preparation', { create: true });
    for (let index = 0; index < 20; index++) {
      const writer = await (await root.getFileHandle(`source-${index}.ts`, { create: true })).createWritable();
      await writer.write(`export const value = ${index};`); await writer.close();
    }
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
  });
  const chooseFolder = async () => {
    await page.getByRole('button', { name: '添加上下文' }).click();
    await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  };
  await chooseFolder();
  await expect(page.locator('.reading-meta').filter({ hasText: '正在准备本项目的独立索引' })).toBeVisible();
  await expect(page.getByText(/正在统计文件范围/)).toHaveCount(0);
  await expect(page.locator('.reading-meta').filter({ hasText: /已用 [2-9] 秒/ })).toBeVisible({ timeout: 12_000 });
  expect(workerLoads).toBe(0);
  await page.locator('.reading-meta').first().scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('preparing.png') });
  await page.getByRole('button', { name: '取消并删除索引' }).click();
  await expect(page.getByRole('button', { name: '添加上下文' })).toBeEnabled();
  await page.evaluate(() => {
    (globalThis as typeof globalThis & { __releasePreparation: () => void }).__releasePreparation();
  });
  await expect(page.getByText(/正在准备本项目的独立索引/)).toHaveCount(0);
  expect(workerLoads).toBe(0);
  await chooseFolder();
  await expect(page.getByText('20 个文本文件已完整建立本地索引')).toBeVisible({ timeout: 30_000 });
  expect(workerLoads).toBeGreaterThan(0);
  await page.screenshot({ path: testInfo.outputPath('resumed.png') });
});
