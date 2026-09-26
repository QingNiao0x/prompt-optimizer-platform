import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';

const historyItem = {
  id: 'history-1',
  templateCode: 'GENERAL',
  rawPromptPreview: '查询全球使用 AI 最多的职业',
  providerName: 'mock',
  modelName: 'deterministic-enhancer-v1',
  mock: true,
  latencyMs: 12,
  createdAt: '2026-09-24T10:00:00Z',
};

test('历史列表默认第一页 10 条，翻页和每页条数各只请求一次', async ({ page }) => {
  await mockAuthentication(page);
  const historyRequests: URL[] = [];
  await page.route('**/api/v1/optimization-history**', async route => {
    if (route.request().method() !== 'GET' || route.request().url().includes('/optimization-history/')) {
      await route.continue();
      return;
    }
    const requestUrl = new URL(route.request().url());
    historyRequests.push(requestUrl);
    await route.fulfill({
      status: 200,
      json: {
        requestId: 'history-request',
        data: {
          items: [historyItem],
          page: Number(requestUrl.searchParams.get('page') ?? '0'),
          size: Number(requestUrl.searchParams.get('size') ?? '10'),
          totalItems: 35,
          totalPages: 4,
        },
      },
    });
  });

  await page.goto('/history');
  await expect(page.getByRole('heading', { name: '优化历史' })).toBeVisible();
  await expect.poll(() => historyRequests.length).toBe(1);
  expect(historyRequests[0]?.searchParams.get('page')).toBe('0');
  expect(historyRequests[0]?.searchParams.get('size')).toBe('10');
  expect(historyRequests[0]?.searchParams.has('keyword')).toBe(false);
  expect(historyRequests[0]?.searchParams.has('dateRange')).toBe(false);

  await page.getByLabel('原始提示词搜索').fill('AI 职业');
  await page.getByLabel('原始提示词搜索').press('Enter');
  await expect.poll(() => historyRequests.length).toBe(2);
  expect(historyRequests[1]?.searchParams.get('keyword')).toBe('AI 职业');
  expect(historyRequests[1]?.searchParams.get('page')).toBe('0');
  expect(historyRequests[1]?.searchParams.get('size')).toBe('10');

  const pagination = page.locator('.pagination-row .el-pagination');
  await pagination.locator('.el-pager li', { hasText: /^2$/ }).click();
  await expect.poll(() => historyRequests.length).toBe(3);
  expect(historyRequests[2]?.searchParams.get('page')).toBe('1');
  expect(historyRequests[2]?.searchParams.get('size')).toBe('10');
  expect(historyRequests[2]?.searchParams.get('keyword')).toBe('AI 职业');
  await page.waitForTimeout(300);
  expect(historyRequests.length).toBe(3);

  await pagination.locator('.el-select').click();
  await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '20/page' }).click();
  await expect.poll(() => historyRequests.length).toBe(4);
  expect(historyRequests[3]?.searchParams.get('page')).toBe('0');
  expect(historyRequests[3]?.searchParams.get('size')).toBe('20');
  expect(historyRequests[3]?.searchParams.get('keyword')).toBe('AI 职业');
  await page.waitForTimeout(300);
  expect(historyRequests.length).toBe(4);
});

test('日期范围选择时结束日高亮跟随指针，且选择过程不发起搜索', async ({ page }) => {
  await mockAuthentication(page);
  const historyRequests: URL[] = [];
  await page.route('**/api/v1/optimization-history**', async route => {
    if (route.request().method() !== 'GET') {
      await route.continue();
      return;
    }
    historyRequests.push(new URL(route.request().url()));
    await route.fulfill({
      status: 200,
      json: {
        requestId: 'history-request',
        data: {
          items: [historyItem],
          page: 0,
          size: 10,
          totalItems: 1,
          totalPages: 1,
        },
      },
    });
  });

  await page.goto('/history');
  await expect.poll(() => historyRequests.length).toBe(1);
  await page.getByPlaceholder('开始日期').click();
  const panel = page.locator('.el-picker__popper:visible .el-date-range-picker');
  await expect(panel).toBeVisible();
  const panelBox = await panel.boundingBox();
  const viewport = page.viewportSize();
  expect(panelBox).not.toBeNull();
  expect(panelBox!.x).toBeGreaterThanOrEqual(-1);
  expect(panelBox!.x + panelBox!.width).toBeLessThanOrEqual((viewport?.width ?? panelBox!.width) + 1);

  const leftMonth = panel.locator('.el-date-range-picker__content.is-left');
  const dayCell = (label: string) => leftMonth.locator(
    'td.available:not(.prev-month):not(.next-month)',
    { hasText: new RegExp(`^\\s*${label}\\s*$`) },
  );
  await dayCell('10').click();
  await dayCell('18').hover();
  await expect(dayCell('18')).toHaveClass(/in-range/);
  await expect(dayCell('2')).not.toHaveClass(/in-range/);
  await page.waitForTimeout(300);
  expect(historyRequests.length).toBe(1);
});
