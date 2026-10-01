import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';

const period = {
  fromDate: '2026-09-27',
  toDateInclusive: '2026-09-27',
  fromInclusive: '2026-09-26T16:00:00Z',
  toExclusive: '2026-09-27T16:00:00Z',
  zoneId: 'Asia/Shanghai',
};

const dashboard = {
  period,
  registeredAccountCount: 1,
  newAccountCount: 0,
  actualUserCount: 1,
  accessCount: 1,
  uniqueVisitorCount: 1,
  activeUserCount: 1,
  averageDailyActiveUsers: 1,
  dailyMetrics: [],
  hourlyUsage: [],
  monthlyUsage: [],
  deviceDistribution: [],
  rechargeByDay: [],
  rechargeStatisticsAvailable: false,
};

test('操作日志默认每页 10 条，并可改为 20 条', async ({ page }) => {
  await mockAuthentication(page, true, true);
  await page.route('**/api/v1/analytics/events', (route) => route.fulfill({ status: 204 }));
  const operationRequests: URL[] = [];
  await page.route('**/api/v1/admin/analytics/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/dashboard')) {
      await route.fulfill({ status: 200, json: { requestId: 'dashboard', data: dashboard } });
      return;
    }
    if (url.pathname.endsWith('/usage-ranking')) {
      await route.fulfill({
        status: 200,
        json: { requestId: 'ranking', data: { period, items: [] } },
      });
      return;
    }
    if (url.pathname.endsWith('/operations')) {
      operationRequests.push(url);
      await route.fulfill({
        status: 200,
        json: {
          requestId: 'operations',
          data: {
            records: [],
            total: 25,
            size: Number(url.searchParams.get('size') ?? '10'),
            current: Number(url.searchParams.get('current') ?? '1'),
            pages: 3,
          },
        },
      });
      return;
    }
    await route.fulfill({ status: 200, json: { data: null } });
  });

  await page.goto('/admin/analytics');
  await expect(page.getByRole('heading', { name: '使用与访问' })).toBeVisible();
  await expect.poll(() => operationRequests.length).toBe(1);
  expect(operationRequests[0]?.searchParams.get('current')).toBe('1');
  expect(operationRequests[0]?.searchParams.get('size')).toBe('10');

  const pagination = page.locator('.pagination-row .el-pagination');
  await pagination.locator('.el-select').click();
  await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '20条/页' }).click();
  await expect.poll(() => operationRequests.length).toBe(2);
  expect(operationRequests[1]?.searchParams.get('current')).toBe('1');
  expect(operationRequests[1]?.searchParams.get('size')).toBe('20');
});
