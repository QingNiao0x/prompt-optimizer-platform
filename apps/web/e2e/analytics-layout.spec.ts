import { expect, test, type Page } from '@playwright/test';

import type {
  AnalyticsDashboard,
  AnalyticsOperationLog,
  AnalyticsPeriodView,
  AnalyticsRanking,
} from '../src/types/api';
import { mockAuthentication } from './authFixture';
import { healthyAnalyticsDelivery } from './analyticsDeliveryFixture';

const period: AnalyticsPeriodView = {
  fromDate: '2026-09-01', toDateInclusive: '2026-09-27',
  fromInclusive: '2026-08-31T16:00:00Z', toExclusive: '2026-09-27T16:00:00Z',
  zoneId: 'Asia/Shanghai',
};
const longName = '用于验证超长显示名称不会撑开统计表格的测试账号'.repeat(6);
const longLocation = '用于验证地区字段溢出展示的测试行政区'.repeat(6);

/** 只模拟展示契约；统计口径与身份隔离不由这些浏览器布局用例验证。 */
const mockAnalytics = async (page: Page, empty = false) => {
  await mockAuthentication(page, true, true);
  await page.route('**/api/v1/analytics/events', (route) => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/analytics/context', (route) => route.fulfill({ json: { data: {
    userId: '00000000-0000-0000-0000-000000000102', loginSessionId: null,
  } } }));
  const dashboard: AnalyticsDashboard = {
    period, registeredAccountCount: empty ? 0 : 15846, newAccountCount: empty ? 0 : 327,
    actualUserCount: empty ? 0 : 7304, accessCount: empty ? 0 : 128540,
    uniqueVisitorCount: empty ? 0 : 8600, activeUserCount: empty ? 0 : 9172,
    averageDailyActiveUsers: empty ? 0 : 2587.33, directEnhancementCount: empty ? 0 : 54, planCompletedCount: empty ? 0 : 27,
    dailyMetrics: Array.from({ length: 27 }, (_, index) => ({
      date: `2026-09-${String(index + 1).padStart(2, '0')}`,
      accessCount: empty ? 0 : 3000 + index * 52, uniqueVisitors: empty ? 0 : 1000 + index * 24,
      activeUsers: empty ? 0 : 800 + index * 18, actualUsers: empty ? 0 : 700 + index * 10, newAccounts: empty ? 0 : 12,
      directEnhancementCount: empty ? 0 : 2, planCompletedCount: empty ? 0 : 1,
    })),
    hourlyUsage: Array.from({ length: 24 }, (_, hour) => ({ hour, operationCount: empty ? 0 : 40 + hour * 9 })),
    monthlyUsage: Array.from({ length: 12 }, (_, index) => ({
      month: `2026-${String(index + 1).padStart(2, '0')}`, operationCount: empty ? 0 : 600 + index * 130,
    })),
    deviceDistribution: empty ? [] : [
      { deviceType: 'DESKTOP', loginCount: 3600, uniqueUsers: 2200 },
      { deviceType: 'MOBILE', loginCount: 1200, uniqueUsers: 700 },
      { deviceType: 'TABLET', loginCount: 300, uniqueUsers: 120 },
    ],
    rechargeByDay: empty ? [] : [{
      date: '2026-09-27', planCode: 'TEST', planName: '测试套餐', paidCount: 3, amountMinor: 9900, currency: 'CNY',
    }],
    rechargeStatisticsAvailable: !empty,
  };
  const ranking: AnalyticsRanking = {
    period,
    items: empty ? [] : Array.from({ length: 20 }, (_, index) => ({
      userId: `00000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
      displayName: index === 0 ? longName : `测试账号 ${index + 1}`,
      operationCount: 1000000 - index, loginCount: 200 - index, activeDays: 27,
    })),
  };
  const records: AnalyticsOperationLog[] = empty ? [] : Array.from({ length: 73 }, (_, index) => ({
    eventId: `event-${index + 1}`, userId: `00000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
    displayName: `测试账号 ${index + 1}`,
    eventType: index === 0 ? 'DIRECT_OPTIMIZATION_SUBMITTED' : index === 1 ? 'PLAN_COMPLETED'
      : index % 2 === 0 ? 'LOGIN' : 'OPTIMIZATION_SUBMITTED',
    occurredAt: '2026-09-27T09:32:10Z',
    clientIp: '2001:db8:1234:5678:abcd:ef01:2345:6789',
    country: '测试国家', province: '测试省份', city: index === 0 ? longLocation : '测试城市',
    loginCountry: null, loginProvince: null, loginCity: null, deviceType: 'DESKTOP',
  }));
  const requests = { dashboard: [] as URL[], ranking: [] as URL[], operations: [] as URL[] };
  await page.route('**/api/v1/admin/analytics/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/delivery')) {
      await route.fulfill({ json: { data: healthyAnalyticsDelivery } });
    } else if (url.pathname.endsWith('/dashboard')) {
      requests.dashboard.push(url);
      await route.fulfill({ json: { data: dashboard } });
    } else if (url.pathname.endsWith('/usage-ranking')) {
      requests.ranking.push(url);
      await route.fulfill({ json: { data: ranking } });
    } else if (url.pathname.endsWith('/operations')) {
      requests.operations.push(url);
      const current = Number(url.searchParams.get('current') ?? 1);
      const size = Number(url.searchParams.get('size') ?? 10);
      const filteredRecords = records.filter((record) => !url.searchParams.get('eventType') || record.eventType === url.searchParams.get('eventType'));
      await route.fulfill({ json: { data: {
        records: filteredRecords.slice((current - 1) * size, current * size),
        current, size, total: filteredRecords.length, pages: Math.ceil(filteredRecords.length / size),
      } } });
    } else {
      await route.abort();
    }
  });
  return { dashboard, requests, records };
};

/** 横向滚动应留在表格内，页面本身不得被控件、数字或长文本撑宽。 */
const expectContainedLayout = async (page: Page): Promise<void> => {
  await expect.poll(() => page.evaluate(() => (
    document.documentElement.scrollWidth - document.documentElement.clientWidth
  ))).toBeLessThanOrEqual(1);
  for (const selector of ['.filters', '.metric-strip', '.feature-usage-strip', '.charts-grid', '.ranking-panel', '.operations-panel']) {
    const bounds = await page.locator(selector).boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.x).toBeGreaterThanOrEqual(10);
    expect(bounds!.x).toBeLessThanOrEqual(28);
    const viewportWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(viewportWidth - bounds!.x - bounds!.width).toBeGreaterThanOrEqual(10);
    expect(viewportWidth - bounds!.x - bounds!.width).toBeLessThanOrEqual(29);
  }
};

test('邮箱和名称提交后用于所有查询，草稿编辑不影响翻页与排行，清空后移除条件', async ({ page }) => {
  const { requests } = await mockAnalytics(page);
  await page.goto('/admin/analytics');
  await expect.poll(() => requests.operations.length).toBe(1);
  await expect.poll(() => requests.ranking.length).toBe(1);
  const email = page.getByRole('textbox', { name: '按登录邮箱筛选' });
  const name = page.getByRole('textbox', { name: '按显示名称筛选' });
  await email.fill('  DEMO+member@EXAMPLE.test  ');
  await name.fill('  名称_100%  ');
  expect(requests.dashboard).toHaveLength(1);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect.poll(() => requests.operations.length).toBe(2);
  await expect.poll(() => requests.ranking.length).toBe(2);
  for (const url of [requests.dashboard.at(-1), requests.operations.at(-1), requests.ranking.at(-1)]) {
    expect(url?.searchParams.get('email')).toBe('DEMO+member@EXAMPLE.test');
    expect(url?.searchParams.get('displayName')).toBe('名称_100%');
  }
  await expect(page.getByLabel('已生效的统计条件')).toContainText('名称包含：名称_100%');
  await email.fill('changed@example.test');
  await name.fill('未提交名称');
  await page.locator('.pagination-row .el-pager').getByText('2', { exact: true }).click();
  await expect.poll(() => requests.operations.length).toBe(3);
  expect(requests.operations.at(-1)?.searchParams.get('email')).toBe('DEMO+member@EXAMPLE.test');
  expect(requests.operations.at(-1)?.searchParams.get('displayName')).toBe('名称_100%');
  await page.getByRole('button', { name: '更新排行', exact: true }).click();
  await expect.poll(() => requests.ranking.length).toBe(3);
  expect(requests.ranking.at(-1)?.searchParams.get('email')).toBe('DEMO+member@EXAMPLE.test');
  await email.fill('');
  await name.fill('');
  await name.press('Enter');
  await expect.poll(() => requests.dashboard.length).toBe(3);
  await expect.poll(() => requests.operations.length).toBe(4);
  await expect.poll(() => requests.ranking.length).toBe(4);
  for (const url of [requests.dashboard.at(-1), requests.operations.at(-1), requests.ranking.at(-1)]) {
    expect(url?.searchParams.get('email')).toBeNull();
    expect(url?.searchParams.get('displayName')).toBeNull();
  }
});

test('日期范围、排行日期弹层和分页选项使用中文', async ({ page }) => {
  await mockAnalytics(page);
  await page.goto('/admin/analytics');
  await expect(page.locator('.pagination-row')).toContainText('10条/页');
  await expect(page.locator('.rank-date input')).toHaveAttribute('placeholder', '选择排行日期');
  await page.locator('.range-select .el-select__wrapper').click();
  await page.getByRole('option', { name: '自定义', exact: true }).click();
  await page.getByPlaceholder('开始日期', { exact: true }).click();
  const rangePicker = page.locator('.analytics-date-range-popper:visible');
  await expect(rangePicker).toContainText('年');
  await expect(rangePicker).toContainText('月');
  await expect(rangePicker.locator('.el-date-table th').first()).toHaveText('日');
  await page.getByRole('heading', { name: '使用与访问', exact: true }).click();
  await page.locator('.rank-date input').click();
  // 日期范围面板离场动画期间仍可见；排行只定位单日选择器，避免短暂的两个面板命中。
  const rankPicker = page.locator('.el-picker-panel.el-date-picker:visible');
  await expect(rankPicker).toContainText('年');
  await expect(rankPicker).toContainText('月');
  await expect(rankPicker.locator('.el-date-table th').first()).toHaveText('日');
});

test('新增细分操作以中文显示，筛选保持后端事件代码', async ({ page }) => {
  const { requests } = await mockAnalytics(page);
  await page.goto('/admin/analytics');
  await expect(page.locator('.operations-panel .el-table__body .el-table__row').first()).toContainText('直接增强提交');
  await expect(page.locator('.operations-panel .el-table__body .el-table__row').nth(1)).toContainText('Plan 完成');
  await page.locator('.event-select .el-select__wrapper').click();
  await page.getByRole('option', { name: 'Plan 完成', exact: true }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect.poll(() => requests.operations.at(-1)?.searchParams.get('eventType')).toBe('PLAN_COMPLETED');
  await expect(page.locator('.operations-panel .el-table__body .el-table__row')).toHaveCount(1);
  await expect(page.locator('.operations-panel .el-table__body .el-table__row')).toContainText('Plan 完成');
});

test('宽屏充分利用宽度，平板与手机不溢出；主题切换保留图表与查询结果', async ({ page }, testInfo) => {
  const { requests } = await mockAnalytics(page);
  await page.goto('/admin/analytics');
  await expect(page.locator('.operations-panel .el-table__body .el-table__row')).toHaveCount(10);
  await expect(page.locator('.chart canvas')).toHaveCount(6);
  await expect(page.getByTestId('direct-enhancement-count').locator('strong')).toHaveText('54');
  await expect(page.getByTestId('plan-completed-count').locator('strong')).toHaveText('27');
  await expect(page.getByText('细分次数自本功能启用后记录；旧通用优化记录无法区分这两种使用路径。')).toBeVisible();
  for (const width of [1920, 1440, 1024, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    await expectContainedLayout(page);
    const ranking = await page.locator('.ranking-panel').boundingBox();
    const operations = await page.locator('.operations-panel').boundingBox();
    expect(operations!.y).toBeGreaterThanOrEqual(ranking!.y + ranking!.height);
    expect(Math.abs(ranking!.width - operations!.width)).toBeLessThanOrEqual(1);
    const brand = await page.locator('.topbar .brand').boundingBox();
    const tools = await page.locator('.topbar-tools').boundingBox();
    const navigation = await page.locator('.main-nav').boundingBox();
    if (width <= 1100) {
      expect(navigation!.y).toBeGreaterThanOrEqual(Math.max(brand!.y + brand!.height, tools!.y + tools!.height));
      expect(tools!.x).toBeGreaterThanOrEqual(brand!.x + brand!.width);
    }
    const links = await page.locator('.main-nav .nav-link').all();
    for (let index = 1; index < links.length; index += 1) {
      const previous = await links[index - 1]!.boundingBox();
      const current = await links[index]!.boundingBox();
      expect(current!.x).toBeGreaterThanOrEqual(previous!.x + previous!.width - 1);
    }
    // 验证 ECharts 已随容器缩放，而不是保留上一个屏宽的画布。
    await expect.poll(() => page.locator('.chart').first().evaluate((element) => {
      const canvas = element.querySelector('canvas');
      return Math.abs((canvas?.getBoundingClientRect().width ?? 0) - element.clientWidth);
    })).toBeLessThanOrEqual(1);
    if (width === 1920 || width === 390) {
      await page.screenshot({ path: testInfo.outputPath(`analytics-light-${width}.png`), fullPage: true });
      if (width === 390) {
        await page.screenshot({ path: testInfo.outputPath('analytics-mobile-viewport.png') });
      }
    }
  }
  await page.getByRole('button', { name: '切换为深色主题' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-ui-theme', 'glass-dark');
  await expect(page.locator('.chart canvas')).toHaveCount(6);
  await expectContainedLayout(page);
  expect(requests.dashboard).toHaveLength(1);
  expect(requests.operations).toHaveLength(1);
  await page.screenshot({ path: testInfo.outputPath('analytics-dark-mobile.png'), fullPage: true });
});

test('长文本与多行记录限制在表格内，切页及每页 50 条保持原查询契约', async ({ page, isMobile }) => {
  const { dashboard, requests } = await mockAnalytics(page);
  dashboard.accessCount = 9999999999999;
  await page.goto('/admin/analytics');
  await expect(page.locator('.ranking-panel .el-table__body .el-table__row')).toHaveCount(20);
  await expect(page.locator('.operations-panel .el-table__body .el-table__row')).toHaveCount(10);
  const pagination = page.locator('.pagination-row .el-pagination');
  await pagination.locator('.btn-next').click();
  await expect.poll(() => requests.operations.at(-1)?.searchParams.get('current')).toBe('2');
  await pagination.locator('.el-select').click();
  await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '50条/页' }).click();
  await expect(page.locator('.operations-panel .el-table__body .el-table__row')).toHaveCount(50);
  const request = requests.operations.at(-1)!;
  expect(request.searchParams.get('current')).toBe('1');
  expect(request.searchParams.get('size')).toBe('50');
  expect(request.searchParams.get('fromDate')).toBe(period.fromDate);
  expect(request.searchParams.get('toDate')).toBe(period.toDateInclusive);
  expect(requests.operations).toHaveLength(3);
  const table = await page.locator('.operations-panel .el-table').boundingBox();
  expect(table!.height).toBeLessThanOrEqual(560);
  await expectContainedLayout(page);

  if (!isMobile) {
    const name = page.locator('.ranking-panel .el-table__body .el-table__row').first().locator('td').nth(2);
    await name.hover();
    await expect(page.getByRole('tooltip').filter({ hasText: longName })).toBeVisible();
  }
  await page.locator('.operations-panel .el-table__body-wrapper .el-scrollbar__wrap').evaluate((element) => {
    element.scrollLeft = element.scrollWidth;
    element.scrollTop = element.scrollHeight;
  });
  await expect(page.locator('.operations-panel .el-table__body .el-table__row').last().locator('td').last()).toContainText('电脑');
});

test('空数据保留筛选和明确空状态，后续查询可恢复图表', async ({ page }) => {
  const { dashboard } = await mockAnalytics(page, true);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/admin/analytics');
  await expect(page.getByText('所选范围内暂无每日访问数据。')).toBeVisible();
  await expect(page.getByText('所选周期内没有关键使用操作。')).toBeVisible();
  await expect(page.getByText('所选范围内没有关键操作日志。')).toBeVisible();
  await expect(page.getByText('充值图表尚无数据源')).toBeVisible();
  await expect(page.locator('.metric strong')).toHaveText(['0', '0', '0', '0', '0', '0', '0', '0', '0']);
  await expect(page.getByText('所选范围内暂无增强功能细分记录。')).toBeVisible();
  await expect(page.locator('.chart canvas')).toHaveCount(0);
  await expect(page.getByText(/^峰值 /)).toHaveCount(0);
  await expect(page.locator('.pagination-row')).toHaveCount(0);
  await expectContainedLayout(page);
  const emptyText = await page.getByText('所选范围内没有关键操作日志。').boundingBox();
  expect(emptyText!.x).toBeGreaterThanOrEqual(12);
  expect(emptyText!.x + emptyText!.width).toBeLessThanOrEqual(378);
  dashboard.dailyMetrics = [{ date: period.fromDate, accessCount: 1, uniqueVisitors: 1, activeUsers: 1, actualUsers: 1, newAccounts: 0, directEnhancementCount: 0, planCompletedCount: 0 }];
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.locator('.chart canvas')).toHaveCount(1);
  dashboard.dailyMetrics[0] = { date: period.fromDate, accessCount: 0, uniqueVisitors: 0, activeUsers: 0, actualUsers: 0, newAccounts: 0, directEnhancementCount: 0, planCompletedCount: 0 };
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.locator('.chart canvas')).toHaveCount(0);
});

test('手机自定义日期弹层可操作，账号和操作筛选仍由查询按钮提交', async ({ page }) => {
  const { requests } = await mockAnalytics(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/admin/analytics');
  await expect.poll(() => requests.operations.length).toBe(1);
  await page.locator('.range-select .el-select__wrapper').click();
  await page.getByRole('option', { name: '自定义', exact: true }).click();
  await page.getByPlaceholder('开始日期').click();
  const picker = page.locator('.analytics-date-range-popper:visible .el-date-range-picker');
  await expect(picker).toBeVisible();
  const bounds = await picker.boundingBox();
  expect(bounds!.width).toBeLessThanOrEqual(366);
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
  await page.getByRole('heading', { name: '使用与访问' }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('请选择完整的自定义开始和结束日期。')).toBeVisible();
  expect(requests.dashboard).toHaveLength(1);

  await page.getByPlaceholder('开始日期').fill('2026-09-02');
  await page.getByPlaceholder('开始日期').press('Tab');
  await page.getByPlaceholder('结束日期').fill('2026-09-08');
  await page.getByPlaceholder('结束日期').press('Tab');
  await page.getByRole('heading', { name: '使用与访问' }).click();
  await page.getByRole('textbox', { name: '按登录账号 ID 筛选' }).fill('00000000-0000-0000-0000-000000000001');
  await page.locator('.event-select .el-select__wrapper').click();
  await page.getByRole('option', { name: '提交优化', exact: true }).click();
  expect(requests.dashboard).toHaveLength(1);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect.poll(() => requests.dashboard.length).toBe(2);
  const query = requests.dashboard.at(-1)!;
  expect(query.searchParams.get('range')).toBe('CUSTOM');
  expect(query.searchParams.get('fromDate')).toBe('2026-09-02');
  expect(query.searchParams.get('toDate')).toBe('2026-09-08');
  expect(query.searchParams.get('userId')).toBe('00000000-0000-0000-0000-000000000001');
  await expect.poll(() => requests.operations.at(-1)?.searchParams.get('eventType')).toBe('OPTIMIZATION_SUBMITTED');
  await page.locator('.rank-period-select .el-select__wrapper').click();
  await page.getByRole('option', { name: '按周', exact: true }).click();
  const previousRankingRequests = requests.ranking.length;
  await page.getByRole('button', { name: '更新排行' }).click();
  await expect.poll(() => requests.ranking.length).toBe(previousRankingRequests + 1);
  expect(requests.ranking.at(-1)?.searchParams.get('period')).toBe('WEEK');
  expect(requests.ranking.at(-1)?.searchParams.get('limit')).toBe('20');
  await expectContainedLayout(page);
});

test('加载和接口失败状态可见，重试恢复且其他页面容器不被放宽', async ({ page }) => {
  await mockAnalytics(page);
  let release: () => void = () => undefined;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/api/v1/admin/analytics/dashboard?**', async (route) => {
    await pending;
    await route.fulfill({ status: 500, json: { error: { message: '统计服务暂时不可用，请稍后重试。' } } });
  }, { times: 1 });
  await page.goto('/admin/analytics');
  await expect(page.getByRole('status', { name: '' }).filter({ hasText: '正在汇总账号事件' })).toBeVisible();
  release();
  await expect(page.getByText('统计服务暂时不可用，请稍后重试。')).toBeVisible();
  await expect(page.getByText('选择时间范围并查询统计数据。')).toBeVisible();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.locator('.metric-strip')).toBeVisible();
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.goto('/settings');
  await expect(page.locator('.app-main--analytics')).toHaveCount(0);
  await expect(page.locator('.topbar--analytics')).toHaveCount(0);
  const main = await page.locator('.app-main').boundingBox();
  expect(main!.width).toBe(1180);
});

test('清空自定义日期后查询给出提示，不抛出页面异常或发送无效请求', async ({ page }) => {
  const { requests } = await mockAnalytics(page);
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/admin/analytics');
  await expect.poll(() => requests.operations.length).toBe(1);
  await page.locator('.range-select .el-select__wrapper').click();
  await page.getByRole('option', { name: '自定义', exact: true }).click();
  await page.getByPlaceholder('开始日期').fill('2026-09-02');
  await page.getByPlaceholder('开始日期').press('Tab');
  await page.getByPlaceholder('结束日期').fill('2026-09-08');
  await page.getByPlaceholder('结束日期').press('Tab');
  await page.getByRole('heading', { name: '使用与访问' }).click();
  await page.locator('.custom-dates').hover();
  await page.locator('.custom-dates .el-range__close-icon').click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('请选择完整的自定义开始和结束日期。')).toBeVisible();
  expect(errors).toEqual([]);
  expect(requests.dashboard).toHaveLength(1);
});

test('单日图表显示数据点，日志使用统计时区而非浏览器时区', async ({ browser }) => {
  const context = await browser.newContext({ timezoneId: 'America/Los_Angeles', locale: 'zh-CN' });
  try {
    const page = await context.newPage();
    const { dashboard, records } = await mockAnalytics(page);
    dashboard.period = { fromDate: '2026-10-01', toDateInclusive: '2026-10-01',
      fromInclusive: '2026-10-01T00:00:00+08:00', toExclusive: '2026-10-02T00:00:00+08:00', zoneId: 'Asia/Shanghai' };
    dashboard.dailyMetrics = [{ date: '2026-10-01', accessCount: 5, uniqueVisitors: 3, activeUsers: 4, actualUsers: 2, newAccounts: 1 , directEnhancementCount: 0, planCompletedCount: 0}];
    records[0]!.occurredAt = '2026-09-30T16:30:00Z';
    await page.goto('/admin/analytics');
    await expect(page.locator('.operations-panel .el-table__body .el-table__row').first())
      .toContainText('2026-10-01 00:30:00');
    await expect(page.getByText('统计时区：Asia/Shanghai', { exact: true })).toBeVisible();
    // 从页面使用的同一 ECharts 实例检查单点标记，避免只断言存在一个空白画布。
    const series = await page.evaluate(async () => {
      const modulePath = '/node_modules/.vite/deps/echarts_core.js';
      const echarts: typeof import('echarts/core') = await import(/* @vite-ignore */ modulePath);
      const element = document.querySelector<HTMLElement>('.chart-wide .chart');
      if (!element) throw new Error('每日图表容器缺失');
      const option = echarts.getInstanceByDom(element)?.getOption();
      return option?.series as Array<{ showSymbol: boolean; data: number[]; name: string }> | undefined;
    });
    expect(series).toHaveLength(5);
    expect(series?.every((item) => item.showSymbol && item.data.length === 1)).toBe(true);
    expect(series?.map((item) => item.name)).toContain('实际使用账号');
    expect(series?.map((item) => item.name)).toContain('新增账号');
  } finally {
    await context.close();
  }
});

test('未提交或查询失败的筛选草稿不改变翻页和排行的已生效条件', async ({ page }) => {
  const { requests } = await mockAnalytics(page);
  await page.goto('/admin/analytics');
  await expect.poll(() => requests.operations.length).toBe(1);
  const appliedUser = '00000000-0000-0000-0000-000000000001';
  const draftUser = '00000000-0000-0000-0000-000000000002';
  await page.getByRole('textbox', { name: '按登录账号 ID 筛选' }).fill(appliedUser);
  await page.locator('.event-select .el-select__wrapper').click();
  await page.getByRole('option', { name: '登录', exact: true }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect.poll(() => requests.operations.length).toBe(2);

  await page.getByRole('textbox', { name: '按登录账号 ID 筛选' }).fill(draftUser);
  await page.locator('.event-select .el-select__wrapper').click();
  await page.getByRole('option', { name: '生成方案', exact: true }).click();
  await page.locator('.pagination-row .btn-next').click();
  await expect.poll(() => requests.operations.length).toBe(3);
  expect(requests.operations.at(-1)?.searchParams.get('userId')).toBe(appliedUser);
  expect(requests.operations.at(-1)?.searchParams.get('eventType')).toBe('LOGIN');
  const rankingCount = requests.ranking.length;
  await page.getByRole('button', { name: '更新排行' }).click();
  await expect.poll(() => requests.ranking.length).toBe(rankingCount + 1);
  expect(requests.ranking.at(-1)?.searchParams.get('userId')).toBe(appliedUser);

  await page.route('**/api/v1/admin/analytics/dashboard?**', (route) => route.fulfill({
    status: 500, json: { error: { message: '测试查询失败，保留原统计。' } },
  }), { times: 1 });
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('测试查询失败，保留原统计。')).toBeVisible();
  await page.locator('.pagination-row .btn-next').click();
  await expect.poll(() => requests.operations.length).toBe(4);
  expect(requests.operations.at(-1)?.searchParams.get('userId')).toBe(appliedUser);
  expect(requests.operations.at(-1)?.searchParams.get('eventType')).toBe('LOGIN');
});

test('旧翻页响应不能覆盖新查询，明细失败显示错误并支持恢复', async ({ page }) => {
  const { requests } = await mockAnalytics(page);
  await page.goto('/admin/analytics');
  const rows = page.locator('.operations-panel .el-table__body .el-table__row');
  await expect(rows).toHaveCount(10);
  let release: () => void = () => undefined;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  let intercepted = false;
  await page.route('**/api/v1/admin/analytics/operations?**', async (route) => {
    intercepted = true;
    await pending;
    await route.fulfill({ json: { data: { records: [], current: 2, size: 10, total: 0, pages: 0 } } });
  }, { times: 1 });
  await page.locator('.pagination-row .btn-next').click();
  await expect.poll(() => intercepted).toBe(true);
  await page.getByRole('textbox', { name: '按登录账号 ID 筛选' })
    .fill('00000000-0000-0000-0000-000000000002');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect.poll(() => requests.operations.length).toBe(2);
  await expect(rows).toHaveCount(10);
  const staleResponse = page.waitForResponse((response) => response.url().includes('/operations?')
    && new URL(response.url()).searchParams.get('current') === '2');
  release();
  await (await staleResponse).finished();
  // 让旧网络响应的处理回调和 Vue 更新完成，再检查新查询结果仍可见。
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(rows).toHaveCount(10);
  await expect(page.locator('.pagination-row .number.is-active')).toHaveText('1');

  for (const endpoint of ['operations', 'usage-ranking']) {
    await page.route(`**/api/v1/admin/analytics/${endpoint}?**`, (route) => route.fulfill({
      status: 500, json: { error: { message: `测试明细查询失败：${endpoint}` } },
    }), { times: 1 });
  }
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.locator('.operations-panel .el-alert')).toContainText('测试明细查询失败：operations');
  await expect(page.locator('.ranking-panel .el-alert')).toContainText('测试明细查询失败：usage-ranking');
  await expect(page.locator('.metric-strip')).toBeVisible();
  await expect(page.locator('.operations-panel .el-table')).toHaveCount(0);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(rows).toHaveCount(10);
  await expect(page.locator('.ranking-panel .el-table__body .el-table__row')).toHaveCount(20);
  await expect(page.locator('.operations-panel .el-alert, .ranking-panel .el-alert')).toHaveCount(0);
});

test('账号校验及回车提交有效，日志就近筛选沿用已生效账号并从第一页查询', async ({ page }) => {
  const { requests } = await mockAnalytics(page);
  await page.goto('/admin/analytics');
  await expect.poll(() => requests.operations.length).toBe(1);
  const account = page.getByRole('textbox', { name: '按登录账号 ID 筛选' });
  await account.fill('bad-id');
  await account.press('Enter');
  await expect(page.getByText('账号 ID 必须是完整的 UUID。')).toBeVisible();
  expect(requests.dashboard).toHaveLength(1);
  const appliedUser = '00000000-0000-0000-0000-000000000002';
  await account.fill(appliedUser);
  await account.press('Enter');
  await expect.poll(() => requests.operations.length).toBe(2);
  await expect(page.getByLabel('已生效的统计条件')).toContainText(appliedUser);
  await account.fill('00000000-0000-0000-0000-000000000003');
  await page.locator('.event-select .el-select__wrapper').click();
  await page.getByRole('option', { name: '登录', exact: true }).click();
  await page.getByRole('button', { name: '筛选日志', exact: true }).click();
  await expect.poll(() => requests.operations.length).toBe(3);
  expect(requests.dashboard).toHaveLength(2);
  expect(requests.operations.at(-1)?.searchParams.get('userId')).toBe(appliedUser);
  expect(requests.operations.at(-1)?.searchParams.get('eventType')).toBe('LOGIN');
  expect(requests.operations.at(-1)?.searchParams.get('current')).toBe('1');
  await expect(page.locator('.operations-panel .panel-caption')).toContainText('登录');
});

test('充值图表分币种显示，设备和日志补齐返回的账号信息', async ({ page }) => {
  const { dashboard } = await mockAnalytics(page);
  dashboard.rechargeByDay.push({ date: '2026-09-27', planCode: 'TEST', planName: '测试套餐', paidCount: 2, amountMinor: 1000, currency: 'USD' });
  await page.goto('/admin/analytics');
  await expect(page.getByLabel('各设备登录次数与去重账号')).toContainText('3,600 次 · 2,200 个账号');
  await expect(page.locator('.operations-panel .el-table__body .el-table__row').first()).toContainText('测试账号 1');
  await page.locator('.currency-select .el-select__wrapper').click();
  await page.getByRole('option', { name: 'USD', exact: true }).click();
  await expect(page.getByText('USD · 最小货币单位 · 成功支付 2 笔')).toBeVisible();
  const series = await page.evaluate(async () => {
    const modulePath = '/node_modules/.vite/deps/echarts_core.js';
    const echarts: typeof import('echarts/core') = await import(/* @vite-ignore */ modulePath);
    const element = document.querySelector<HTMLElement>('[aria-label="每日充值套餐金额堆叠柱状图"]');
    if (!element) throw new Error('充值图表容器缺失');
    return echarts.getInstanceByDom(element)?.getOption().series as Array<{ data: number[]; stack: string }> | undefined;
  });
  expect(series).toHaveLength(1);
  expect(series?.[0]?.data.at(-1)).toBe(1000);
  expect(series?.[0]?.stack).toBe('paid-USD');
});
