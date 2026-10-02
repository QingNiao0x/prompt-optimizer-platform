/**
 * 由 AnalyticsRealBrowserAcceptanceTest 启动的私有验收 runner。
 * 随机认证材料仅通过 stdin/stdout 命令管道在两个测试进程间传递；日志和报告只写阶段、状态与数量。
 * Vite 使用独立端口和显式代理，不读取 .env，也不拦截正常 API 响应。
 */
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline';
import { writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const requireWeb = createRequire(new URL('../apps/web/package.json', import.meta.url));
const { chromium, expect } = requireWeb('@playwright/test');
// Windows 的 require.resolve 返回盘符绝对路径；ESM 动态导入必须转换为 file URL。
const { createServer } = await import(pathToFileURL(requireWeb.resolve('vite')).href);
const { default: vue } = await import(pathToFileURL(requireWeb.resolve('@vitejs/plugin-vue')).href);
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
const messages = input[Symbol.asyncIterator]();
const receive = async () => JSON.parse((await messages.next()).value);
const fixture = await receive();
const command = async (name) => { process.stdout.write(`${name}\n`); return receive(); };
const base = `http://127.0.0.1:${fixture.webPort}`;
const root = fileURLToPath(new URL('../apps/web', import.meta.url));
let stage = 'initialization';
let server;
let browser;
const watchdog = setTimeout(() => { process.stderr.write(`stage=${stage} failureType=AcceptanceTimeout\n`); process.exit(2); }, 240_000);
const report = { scope: 'real Chrome + Vite /api proxy + real HTTP/Security/Redis/MyBatis/PostgreSQL',
  databaseTransaction: 'test-only shared outer transaction; commit suppressed; final rollback verified by JUnit',
  modelProvider: 'Mock; no external model calls', checks: [] };

const poll = async (read, accepts, description) => {
  for (let attempt = 0; attempt < 150; attempt++) {
    const value = await read();
    if (accepts(value)) return value;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(description);
};
const inspect = () => command('INSPECTION');
const data = async (response) => {
  expect(response.status()).toBe(200);
  const body = await response.json();
  return body.data;
};
const csrf = async (context) => {
  const value = (await context.cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value;
  expect(typeof value).toBe('string');
  return decodeURIComponent(value);
};
const login = async (page, email) => {
  await page.goto(`${base}/login`);
  await expect(page.locator('input[name="account"]')).toBeVisible();
  await expect(page.locator('.login-modal__captcha img')).toBeVisible();
  const answer = await command('CAPTCHA');
  await page.locator('input[name="account"]').fill(email);
  await page.locator('input[name="password"]').fill(fixture.password);
  await page.locator('input[name="captcha"]').fill(answer.captcha);
  const response = page.waitForResponse((candidate) => candidate.url().endsWith('/api/v1/auth/login') && candidate.request().method() === 'POST');
  await page.locator('.login-modal__submit').click();
  expect((await response).status()).toBe(200);
  await page.waitForURL('**/workbench');
};
const sendEvent = async (page, event, headers = {}) => page.request.post(`${base}/api/v1/analytics/events`, {
  headers: { 'X-XSRF-TOKEN': await csrf(page.context()), ...headers }, data: event,
});
const dashboard = async (page, range = 'TODAY') => data(await page.request.get(`${base}/api/v1/admin/analytics/dashboard`, {
  params: { range, userId: fixture.memberId, ...(range === 'CUSTOM' ? { fromDate: today, toDate: today } : {}) },
}));
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());

try {
  server = await createServer({ root, configFile: false, envFile: false, envDir: false,
    plugins: [vue()], resolve: { alias: { '@': `${root}/src` } }, logLevel: 'silent',
    server: { host: '127.0.0.1', port: fixture.webPort, strictPort: true,
      proxy: { '/api': { target: `http://127.0.0.1:${fixture.apiPort}`, changeOrigin: true } } },
  });
  await server.listen();
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome', headless: true });
  const memberContext = await browser.newContext({ locale: 'zh-CN' });
  const member = await memberContext.newPage();
  const adminContext = await browser.newContext({ locale: 'zh-CN' });
  const admin = await adminContext.newPage();
  let browserErrors = 0;
  member.on('pageerror', () => browserErrors++);
  admin.on('pageerror', () => browserErrors++);

  stage = 'anonymous_permissions';
  const adminPaths = [
    '/api/v1/admin/analytics/dashboard?range=TODAY',
    `/api/v1/admin/analytics/usage-ranking?period=DAY&date=${today}`,
    `/api/v1/admin/analytics/operations?fromDate=${today}&toDate=${today}`,
    '/api/v1/admin/analytics/delivery',
  ];
  for (const path of adminPaths) expect((await member.request.get(base + path)).status()).toBe(401);
  report.checks.push({ name: stage, endpoints: 4, expectedStatus: 401 });

  stage = 'real_browser_login';
  await login(member, fixture.memberEmail);
  await poll(inspect, (value) => value.visits >= 1 && value.pending === 0, stage);
  for (const path of adminPaths) expect((await member.request.get(base + path)).status()).toBe(403);
  report.checks.push({ name: stage, status: 200, ordinaryAdminStatus: 403, permissionEndpoints: 4 });

  stage = 'home_identity_restore_and_refresh';
  let count = (await inspect()).visits;
  await member.goto(base);
  await poll(inspect, (value) => value.visits === count + 1 && value.pending === 0, stage);
  count++;
  await member.reload();
  await poll(inspect, (value) => value.visits === count + 1 && value.pending === 0, stage);
  count++;
  report.checks.push({ name: stage, extraVisits: 2, duplicateVisitsPerDocument: 0 });

  stage = 'database_failure_durable_replay';
  await command('OUTAGE_ON');
  await member.reload();
  await poll(inspect, (value) => value.pending >= 1 && value.databaseFailures >= 1 && value.visits === count, stage);
  // 数据库写入失败时主页仍可正常渲染；确认已持久接收的事件在恢复后真正进入 PostgreSQL。
  await expect(member.locator('.home-page')).toBeVisible();
  await command('OUTAGE_OFF');
  await poll(inspect, (value) => value.pending === 0 && value.visits === count + 1, stage);
  count++;
  report.checks.push({ name: stage, businessPageAvailable: true, recoveredVisits: 1, pendingAfterRecovery: 0 });

  stage = 'browser_network_retry';
  let aborted = false;
  await member.route('**/api/v1/analytics/events', async (route) => {
    if (!aborted && route.request().method() === 'POST') { aborted = true; await route.abort('failed'); }
    else await route.continue();
  });
  await member.reload();
  await poll(inspect, (value) => aborted && value.visits === count + 1 && value.pending === 0, stage);
  count++;
  await member.unroute('**/api/v1/analytics/events');
  report.checks.push({ name: stage, injectedNetworkFailures: 1, recoveredVisits: 1 });

  stage = 'client_event_idempotency_and_proxy';
  const identity = await data(await member.request.get(base + '/api/v1/analytics/context'));
  const event = { eventType: 'APP_VISIT', eventId: randomUUID(), occurredAt: new Date().toISOString(),
    expectedUserId: fixture.memberId, expectedLoginSessionId: identity.loginSessionId };
  expect((await sendEvent(member, event, { 'X-Forwarded-For': '203.0.113.71' })).status()).toBe(200);
  expect((await sendEvent(member, event, { 'X-Forwarded-For': '203.0.113.71' })).status()).toBe(200);
  await poll(inspect, (value) => value.visits === count + 1 && value.pending === 0, stage);
  count++;
  report.checks.push({ name: stage, duplicateRequests: 2, persistedVisits: 1 });

  stage = 'existing_business_compatibility';
  const contextInput = { customDescription: 'Java 21 验收用用户服务', files: [] };
  await data(await member.request.post(base + '/api/v1/context/analyze', {
    headers: { 'X-XSRF-TOKEN': await csrf(memberContext) }, data: contextInput,
  }));
  const optimization = await data(await member.request.post(base + '/api/v1/optimizations', {
    headers: { 'X-XSRF-TOKEN': await csrf(memberContext) },
    data: { rawPrompt: '为 Java 用户服务设计登录接口，输出接口契约和正常、异常场景，保留权限校验。', context: contextInput },
  }));
  expect(typeof optimization.optimizedPrompt).toBe('string');
  expect(optimization.optimizedPrompt.length).toBeGreaterThan(0);
  const history = await data(await member.request.get(base + '/api/v1/optimization-history'));
  expect(history.total).toBeGreaterThanOrEqual(1);
  await poll(inspect, (value) => value.pending === 0, stage);
  report.checks.push({ name: stage, contextStatus: 200, optimizationStatus: 200, historyStatus: 200, provider: 'Mock' });

  stage = 'admin_filters_metrics_and_charts';
  await login(admin, fixture.adminEmail);
  await poll(inspect, (value) => value.pending === 0, stage);
  for (const range of ['TODAY', 'YESTERDAY', 'THIS_WEEK', 'THIS_MONTH', 'LAST_MONTH', 'CUSTOM']) {
    stage = `admin_range_${range}`;
    await dashboard(admin, range);
  }
  stage = 'admin_metric_counts';
  const metrics = await dashboard(admin);
  report.metricObservation = { expectedAccessCount: count, accessCount: metrics.accessCount,
    uniqueVisitorCount: metrics.uniqueVisitorCount, activeUserCount: metrics.activeUserCount,
    actualUserCount: metrics.actualUserCount, averageDailyActiveUsers: metrics.averageDailyActiveUsers,
    rechargeStatisticsAvailable: metrics.rechargeStatisticsAvailable };
  expect(metrics.accessCount).toBe(count);
  expect(metrics.uniqueVisitorCount).toBe(1);
  expect(metrics.activeUserCount).toBe(1);
  expect(metrics.actualUserCount).toBe(1);
  expect(metrics.averageDailyActiveUsers).toBe(1);
  expect(metrics.rechargeStatisticsAvailable).toBe(false);
  for (const period of ['DAY', 'WEEK', 'MONTH']) {
    stage = `admin_ranking_${period}`;
    const ranking = await data(await admin.request.get(`${base}/api/v1/admin/analytics/usage-ranking`, {
      params: { period, date: today, userId: fixture.memberId },
    }));
    expect(ranking.items).toHaveLength(1);
    expect(ranking.items[0].userId).toBe(fixture.memberId);
    expect(ranking.items[0].operationCount).toBeGreaterThanOrEqual(2);
    expect(ranking.items[0].activeDays).toBe(1);
  }
  report.rankingObservation = { periods: 3, accountIdMatched: true, activeDays: 1 };
  stage = 'admin_operation_log_geoip';
  const logs = await data(await admin.request.get(`${base}/api/v1/admin/analytics/operations`, {
    params: { fromDate: today, toDate: today, userId: fixture.memberId, size: '100' },
  }));
  // 服务端把客户端 UUID 与账号/租户/事件类型一起生成持久化 ID；按本次专用时间戳定位，不假定两个 ID 相同。
  const forwarded = logs.records.filter((row) => row.eventType === 'APP_VISIT'
    && Date.parse(row.occurredAt) === Date.parse(event.occurredAt));
  report.logObservation = { total: logs.total, records: logs.records.length, matchedEventCount: forwarded.length,
    loopbackIp: forwarded.length === 1 && ['127.0.0.1', '0:0:0:0:0:0:0:1', '::1'].includes(forwarded[0].clientIp),
    countryNull: forwarded.length === 1 && forwarded[0].country === null,
    countryOmitted: forwarded.length === 1 && !Object.hasOwn(forwarded[0], 'country'),
    provinceNull: forwarded.length === 1 && forwarded[0].province === null,
    cityNull: forwarded.length === 1 && forwarded[0].city === null };
  expect(forwarded).toHaveLength(1);
  expect(['127.0.0.1', '0:0:0:0:0:0:0:1', '::1']).toContain(forwarded[0].clientIp);
  expect(forwarded[0].country).toBeNull();
  expect(forwarded[0].province).toBeNull();
  expect(forwarded[0].city).toBeNull();
  stage = 'admin_invalid_range';
  expect((await admin.request.get(`${base}/api/v1/admin/analytics/dashboard?range=ILLEGAL`)).status()).toBe(400);
  stage = 'admin_chart_page';
  await admin.goto(`${base}/admin/analytics`);
  await expect(admin.getByRole('heading', { name: '使用与访问', exact: true })).toBeVisible();
  const chartNames = [
    '每日访问、去重访问、活跃、实际使用与新增账号折线图',
    '按小时汇总的关键操作柱状图',
    '按月份汇总的关键操作柱状图',
    '手机、平板、电脑和未知设备登录分布图',
  ];
  for (const name of chartNames) {
    const chart = admin.getByRole('img', { name, exact: true });
    await expect(chart).toBeVisible();
    await expect(chart.locator('canvas').first()).toBeVisible();
  }
  report.chartObservation = { namedChartsVisible: chartNames.length, canvasRenderedPerNamedChart: true };
  stage = 'admin_email_filter';
  await admin.getByRole('textbox', { name: '按登录邮箱筛选' }).fill(fixture.memberEmail);
  const filteredResponse = admin.waitForResponse((response) => response.url().includes('/admin/analytics/dashboard?') && response.request().method() === 'GET');
  await admin.getByRole('button', { name: '查询', exact: true }).click();
  const filtered = await data(await filteredResponse);
  expect(filtered.uniqueVisitorCount).toBe(1);
  expect(filtered.registeredAccountCount).toBe(1);
  stage = 'admin_delivery_health';
  const health = await data(await admin.request.get(base + '/api/v1/admin/analytics/delivery'));
  expect(health.pendingEvents).toBe(0);
  expect(health.healthy).toBe(true);
  expect(browserErrors).toBe(0);
  report.checks.push({ name: stage, presetAndCustomRanges: 6, visitCount: count, uniqueVisitors: 1,
    activeUsers: 1, charts: chartNames.length, geoIpMissingReturnsNull: true,
    untrustedForwardedIpIgnored: true, invalidRangeStatus: 400, browserErrors });

  stage = 'copy_export_real_workbench';
  await member.evaluate(() => localStorage.setItem('prompt-optimizer.plan-mode.v1',
    JSON.stringify({ enabled: false, introSeen: true })));
  await member.goto(`${base}/workbench`);
  await member.getByLabel('原始提示词', { exact: true }).fill('整理一周学习计划，输出中文多行清单，仅用于复制验收。');
  const generated = member.waitForResponse((response) => response.url().endsWith('/api/v1/optimizations')
    && response.request().method() === 'POST');
  await member.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  const copyResult = await data(await generated);
  expect(copyResult.provider.mock).toBe(true);
  const exportLogs = async () => data(await admin.request.get(`${base}/api/v1/admin/analytics/operations`, {
    params: { fromDate: today, toDate: today, userId: fixture.memberId, eventType: 'RESULT_EXPORTED', size: '100' },
  }));
  const originalExports = (await exportLogs()).total;
  // 保留真实剪贴板，只注入拒绝分支和弹窗拦截；外部平台不会收到验收文本。
  await member.evaluate(() => {
    const nativeWrite = navigator.clipboard.writeText.bind(navigator.clipboard);
    const probe = window.__copyExportAcceptance = { reject: false, writes: 0 };
    navigator.clipboard.writeText = async (text) => {
      if (probe.reject) throw new DOMException('Synthetic permission denial', 'NotAllowedError');
      await nativeWrite(text);
      probe.writes++;
    };
    window.open = () => null;
  });
  const copyButton = member.locator('.copy-main-button');
  await member.bringToFront();
  await copyButton.click();
  await poll(exportLogs, (value) => value.total === originalExports + 1, stage);
  // 用真实粘贴动作检查 OS 剪贴板，不读取复制前可能存在的用户内容。
  await member.evaluate(() => {
    const field = document.createElement('textarea');
    field.setAttribute('aria-label', '验收剪贴板粘贴区');
    document.body.append(field);
  });
  const pasteField = member.getByRole('textbox', { name: '验收剪贴板粘贴区', exact: true });
  await pasteField.focus();
  await member.keyboard.press(process.platform === 'darwin' ? 'Meta+V' : 'Control+V');
  await expect(pasteField).toHaveValue(copyResult.optimizedPrompt);
  await pasteField.evaluate((element) => element.remove());

  stage = 'copy_export_permission_failure';
  await member.evaluate(() => { window.__copyExportAcceptance.reject = true; });
  await copyButton.click();
  const manualDialog = member.getByRole('dialog', { name: '手动复制提示词', exact: true });
  await expect(manualDialog.getByRole('textbox')).toHaveValue(copyResult.optimizedPrompt);
  await manualDialog.getByRole('button', { name: '全选', exact: true }).click();
  expect((await exportLogs()).total).toBe(originalExports + 1);
  await manualDialog.getByRole('button', { name: '关闭', exact: true }).click();

  stage = 'copy_export_blocked_popup';
  await member.evaluate(() => { window.__copyExportAcceptance.reject = false; });
  await member.getByRole('button', { name: '选择 AI 平台', exact: true }).click();
  await member.getByRole('menuitem', { name: '复制并打开 Kimi', exact: true }).click();
  await poll(exportLogs, (value) => value.total === originalExports + 2, stage);
  const manualLink = member.getByRole('link', { name: '打开 Kimi', exact: true });
  await expect(manualLink).toBeVisible();
  await memberContext.route('https://www.kimi.com/**', (route) => route.fulfill({
    contentType: 'text/html', body: '<title>Isolated platform fixture</title>',
  }));
  const opened = memberContext.waitForEvent('page');
  await manualLink.click();
  const target = await opened;
  await expect(target).toHaveURL('https://www.kimi.com/');
  await target.close();
  expect((await exportLogs()).total).toBe(originalExports + 2);

  stage = 'copy_export_lost_response_idempotency';
  const exportAttempts = [];
  await member.route('**/api/v1/analytics/events', async (route) => {
    const event = route.request().postDataJSON();
    if (event.eventType !== 'RESULT_EXPORTED') { await route.continue(); return; }
    exportAttempts.push(event);
    if (exportAttempts.length === 1) {
      // 服务端已真实接收，再丢弃应答，验证不确定网络结果下的重试不会重复落库。
      expect((await route.fetch()).status()).toBe(200);
      await route.abort('failed');
    } else await route.continue();
  });
  await member.bringToFront();
  await copyButton.click();
  await expect(copyButton).toHaveText('已复制');
  await poll(exportLogs, (value) => exportAttempts.length >= 2 && value.total === originalExports + 3, stage);
  expect(new Set(exportAttempts.map((event) => event.eventId)).size).toBe(1);
  expect(exportAttempts.every((event) => JSON.stringify(event) === JSON.stringify(exportAttempts[0]))).toBe(true);
  await expect.poll(() => member.evaluate(() => Object.keys(localStorage)
    .filter((key) => key.startsWith('prompt-optimizer.analytics.pending.v1:')).length)).toBe(0);
  await member.unroute('**/api/v1/analytics/events');
  await poll(inspect, (value) => value.pending === 0, stage);
  expect((await exportLogs()).total).toBe(originalExports + 3);
  expect(await member.evaluate(() => window.__copyExportAcceptance.writes)).toBe(3);
  expect(browserErrors).toBe(0);
  report.checks.push({ name: 'copy_export_real_browser_and_database', clipboard: 'native write and keyboard paste',
    successfulCopies: 3, rejectedCopies: 1, persistedExports: 3, manualSelectionExports: 0, manualLinkExports: 0,
    lostResponseRetries: exportAttempts.length - 1, stableEventId: true, duplicateDatabaseRows: 0,
    externalPlatform: 'intercepted fixture; no prompt sent', browserErrors });
  report.completedAt = new Date().toISOString();
  report.passed = true;
  await writeFile(fixture.report, JSON.stringify(report, null, 2), 'utf8');
  process.stdout.write('DONE\n');
} catch (failure) {
  // Playwright 的原始错误可能包含表单 fill 参数，必须只输出安全的阶段代码和错误类型。
  report.passed = false;
  report.failureStage = stage;
  report.failureType = failure?.name ?? 'UnknownError';
  report.completedAt = new Date().toISOString();
  await writeFile(fixture.report, JSON.stringify(report, null, 2), 'utf8');
  process.stderr.write(`stage=${stage} failureType=${failure?.name ?? 'UnknownError'}\n`);
  process.exitCode = 1;
} finally {
  clearTimeout(watchdog);
  await browser?.close();
  await server?.close();
  input.close();
}
