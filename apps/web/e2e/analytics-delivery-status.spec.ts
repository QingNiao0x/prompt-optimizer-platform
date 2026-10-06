import { expect, test, type Page } from '@playwright/test';
import type { AnalyticsDashboard, AnalyticsDeliveryStatus } from '../src/types/api';
import { mockAuthentication, testUser } from './authFixture';

const healthy: AnalyticsDeliveryStatus = {
  status: 'HEALTHY', scope: 'INSTANCE', healthy: true, initialized: true,
  journalAvailable: true, databaseAvailable: true, pendingEvents: 0,
  oldestPendingAt: null, oldestPendingAgeSeconds: 0, databaseFailures: 0,
  journalFailures: 0, corruptFiles: 0, deliveredEvents: 3,
  lastFailureAt: null, lastDeliveredAt: '2026-10-02T00:00:00Z',
  pendingAlertThreshold: 1000, oldestPendingAlertSeconds: 300, backlogAlert: false,
};

const dashboard: AnalyticsDashboard = {
  period: { fromDate: '2026-10-02', toDateInclusive: '2026-10-02',
    fromInclusive: '2026-10-01T16:00:00Z', toExclusive: '2026-10-02T16:00:00Z', zoneId: 'Asia/Shanghai' },
  registeredAccountCount: 33, newAccountCount: 0, actualUserCount: 0,
  accessCount: 0, uniqueVisitorCount: 0, activeUserCount: 0, averageDailyActiveUsers: 0, directEnhancementCount: 0, planCompletedCount: 0,
  dailyMetrics: [], hourlyUsage: [], monthlyUsage: [], deviceDistribution: [],
  rechargeByDay: [], rechargeStatisticsAvailable: false,
};

/** 这些用例只验证后台提示与查询独立；真实持久化、故障恢复另由真实链路验收覆盖。 */
const mockDeliveryPage = async (page: Page, initial: AnalyticsDeliveryStatus | null) => {
  await mockAuthentication(page, true, true);
  let delivery = initial;
  let dashboardRequests = 0;
  await page.route('**/api/v1/analytics/**', (route) => route.fulfill({ status: 200, json: {
    data: new URL(route.request().url()).pathname.endsWith('/context')
      ? { userId: testUser.userId, loginSessionId: null } : null,
  } }));
  await page.route('**/api/v1/admin/analytics/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/delivery')) {
      return delivery ? route.fulfill({ status: 200, json: { data: delivery } })
        : route.fulfill({ status: 503, json: { error: { message: '投递状态暂不可用。' } } });
    }
    if (path.endsWith('/dashboard')) {
      dashboardRequests += 1;
      return route.fulfill({ status: 200, json: { data: dashboard } });
    }
    if (path.endsWith('/usage-ranking')) return route.fulfill({ status: 200, json: {
      data: { period: dashboard.period, items: [] },
    } });
    return route.fulfill({ status: 200, json: {
      data: { current: 1, size: 10, total: 0, pages: 0, records: [] },
    } });
  });
  return { setDelivery: (status: AnalyticsDeliveryStatus | null) => { delivery = status; },
    dashboardRequests: () => dashboardRequests };
};

test('审计积压明确提示实例范围，补写后重新查询可恢复且统计结果保持可用', async ({ page }) => {
  const fixture = await mockDeliveryPage(page, { ...healthy, status: 'DEGRADED', healthy: false,
    databaseAvailable: false, pendingEvents: 12, oldestPendingAgeSeconds: 20, databaseFailures: 1 });
  await page.goto('/admin/analytics', { waitUntil: 'domcontentloaded' });
  const alert = page.getByTestId('analytics-delivery-alert');
  await expect(alert).toContainText('审计事件正在补写');
  await expect(alert).toContainText('当前实例待补写 12 条');
  await expect(alert).toContainText('数据库投递异常，持久接收可用');
  await expect(alert).toContainText('补写完成后点击查询刷新');
  await expect(page.locator('.metric-strip')).toContainText('33');
  fixture.setDelivery(healthy);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(alert).toHaveCount(0);
  await expect(page.locator('.metric-strip')).toContainText('33');
  expect(fixture.dashboardRequests()).toBe(2);
});

test('状态接口失败和损坏文件告警不会掩盖正常统计，恢复后提示消失', async ({ page }) => {
  const fixture = await mockDeliveryPage(page, null);
  await page.goto('/admin/analytics', { waitUntil: 'domcontentloaded' });
  const alert = page.getByTestId('analytics-delivery-alert');
  await expect(alert).toContainText('无法读取审计投递状态');
  await expect(page.locator('.metric-strip')).toContainText('33');
  fixture.setDelivery({ ...healthy, status: 'UNAVAILABLE', healthy: false,
    journalAvailable: false, databaseAvailable: false, corruptFiles: 1 });
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(alert).toContainText('审计投递暂不可用');
  await expect(alert).toContainText('损坏文件 1 个');
  await expect(page.locator('.metric-strip')).toContainText('33');
  fixture.setDelivery(healthy);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(alert).toHaveCount(0);
});
