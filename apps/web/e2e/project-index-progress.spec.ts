import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

test('真实 Worker 显示百分比和互斥计数，项目忽略规则可以关闭', async ({ page }, testInfo) => {
  test.setTimeout(90_000);
  await mockAuthentication(page);
  // 索引界面测试不访问真实账号偏好；新增设置加载不能触发真实服务的 401 跳转。
  await page.route('**/api/v1/me/settings', route => route.fulfill({
    status: 503, json: { error: { message: '受控偏好加载不可用' } },
  }));
  await page.route('**/api/v1/analytics/events', route => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'mock:default', displayName: '测试模型', provider: 'Mock', defaultModel: true },
  ] } }));
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.evaluate(async () => {
    const root = await (await navigator.storage.getDirectory()).getDirectoryHandle('percent-project', { create: true });
    const write = async (directory: FileSystemDirectoryHandle, name: string, content: string) => {
      const writer = await (await directory.getFileHandle(name, { create: true })).createWritable();
      await writer.write(content); await writer.close();
    };
    await write(root, '.gitignore', '/tmp/');
    const excluded = await root.getDirectoryHandle('tmp', { create: true });
    await write(excluded, 'intentional.txt', '用户主动纳入的资料');
    await write(root, 'empty.txt', '');
    await write(root, '.env', 'protected');
    const text = Array.from({ length: 900 }, (_, i) => `term${i}`).join(' ') + '\n尾部完整规则。';
    for (let offset = 0; offset < 240; offset += 6) {
      await Promise.all(Array.from({ length: 6 }, (_, i) => write(root, `file-${offset + i}.txt`, text)));
    }
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
    const samples: number[] = [];
    Object.assign(window, { indexProgressSamples: samples });
    new MutationObserver(() => {
      const node = document.querySelector('.read-progress[role="progressbar"]');
      const value = node?.getAttribute('aria-valuenow');
      if (value && node?.textContent?.includes('%')) samples.push(Number(value));
    }).observe(document.body, { subtree: true, childList: true, attributes: true, characterData: true });
  });
  await page.getByRole('button', { name: '添加上下文' }).click();
  await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  await expect(page.locator('.read-progress .el-progress__text')).toContainText('%');
  await page.locator('.folder-dropzone').screenshot({ path: testInfo.outputPath('index-progress.png') });
  // 等待真实存储提交；5 秒通用断言预算不是目录导入性能验收线，保留明确的完成上限。
  await expect(page.getByText('241 个文本文件已完整建立本地索引')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/范围内处理 243 个文件：完整 241 · 跳过 1 · 未完整索引 1 · 失败 0/)).toBeVisible();
  await expect(page.getByText(/范围规则另排除 1 个目录/)).toBeVisible();
  const samples = await page.evaluate(() => (window as unknown as { indexProgressSamples: number[] }).indexProgressSamples);
  expect(samples.some(value => value > 0 && value < 100)).toBe(true);
  const box = await page.locator('.read-progress').count();
  expect(box).toBe(0);
  await page.getByText('按根目录 .gitignore 排除文件', { exact: true }).click();
  await expect(page.getByRole('checkbox', { name: '按根目录 .gitignore 排除文件' })).not.toBeChecked();
  await page.getByRole('button', { name: '增量更新' }).click();
  await expect(page.getByText('242 个文本文件已完整建立本地索引')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/范围规则另排除 0 个目录/)).toBeVisible();
  await expect(page.getByText(/本轮新增 1 · 更新 0 · 未变化 242 · 删除 0/)).toBeVisible();
});
