import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

test('邮箱验证码注册成功后自动登录并进入工作台', async ({ page }) => {
  await mockAuthentication(page, false);
  await page.goto('/');
  await page.getByRole('button', { name: '注册' }).first().click();
  await page.getByPlaceholder('请输入邮箱地址').fill('new@example.com');
  await page.getByRole('button', { name: '获取邮箱验证码' }).click();
  await page.getByPlaceholder('请输入 6 位验证码').fill('123456');
  await page.getByPlaceholder('请输入密码').fill('test-password-123');
  await page.getByPlaceholder('请再次输入密码').fill('test-password-123');
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: '创建账号' }).click();
  // 当前注册流程保留完成提示，由用户确认进入工作台。
  await expect(page.getByRole('heading', { name: '账号创建成功，已自动登录' })).toBeVisible();
  await page.getByRole('button', { name: '进入工作台', exact: true }).click();
  await expect(page).toHaveURL(/\/workbench$/);
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible();
});

test('未登录不能挂载工作台，错误密码可重试，登录后可刷新和退出', async ({ page }) => {
  await mockAuthentication(page, false);
  await page.goto('/workbench');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByLabel('原始提示词')).toHaveCount(0);
  await page.getByPlaceholder('请输入手机号或邮箱').fill('test@example.com');
  await page.getByPlaceholder('请输入密码').fill('wrong-password');
  await page.getByPlaceholder('请输入图中字符').fill('ABCD');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('邮箱或密码不正确');
  await page.getByPlaceholder('请输入密码').fill('test-password');
  await page.getByPlaceholder('请输入图中字符').fill('ABCD');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/workbench$/);
  await page.reload();
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible();
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto('/workbench');
  await expect(page).toHaveURL(/\/login$/);
});

test('身份服务不可用时不进入受保护页面', async ({ page }) => {
  await page.route('**/api/v1/auth/me', route => route.fulfill({ status: 503, json: {} }));
  await page.goto('/history');
  await expect(page).toHaveURL(/\/login\?unavailable=1$/);
  await expect(page.locator('.topbar')).toHaveCount(0);
});

test('历史记录仅在点击搜索或按回车后加载对应查询结果', async ({ page }) => {
  await mockAuthentication(page);
  const consoleWarnings: string[] = [];
  page.on('console', message => {
    if (message.type() === 'warning') {
      consoleWarnings.push(message.text());
    }
  });
  const historyRequests: URL[] = [];
  await page.route('**/api/v1/optimization-history**', async route => {
    if (route.request().method() !== 'GET') {
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
          records: [{
            id: 'history-1',
            templateCode: 'GENERAL',
            rawPromptPreview: '查询全球使用 AI 最多的职业',
            providerName: 'mock',
            modelName: 'deterministic-enhancer-v1',
            mock: true,
            latencyMs: 12,
            createdAt: '2026-09-24T10:00:00Z',
          }],
          current: 1,
          size: 10,
          total: 1,
          pages: 1,
        },
      },
    });
  });

  await page.goto('/history');
  await expect(page.getByRole('heading', { name: '优化历史' })).toBeVisible();
  await expect.poll(() => historyRequests.length).toBe(1);
  expect(historyRequests[0]?.searchParams.get('current')).toBe('1');
  expect(historyRequests[0]?.searchParams.get('size')).toBe('10');
  expect(consoleWarnings.some(warning => warning.includes('Failed to resolve directive: loading'))).toBe(false);
  await expect(
    page.locator('.preview-cell:visible, .history-mobile-card__preview:visible')
      .filter({ hasText: '查询全球使用 AI 最多的职业' }),
  ).toBeVisible();

  await page.getByLabel('原始提示词搜索').fill('AI 职业');
  const requestCountAfterKeywordInput = historyRequests.length;
  await page.waitForTimeout(400);
  expect(historyRequests.length).toBe(requestCountAfterKeywordInput);
  await page.getByLabel('原始提示词搜索').press('Enter');
  await expect.poll(() => historyRequests.length, { timeout: 5_000 }).toBeGreaterThan(requestCountAfterKeywordInput);
  expect(historyRequests.at(-1)?.searchParams.get('keyword')).toBe('AI 职业');
  await expect(page.getByLabel('创建时间范围').first()).toBeVisible();

  const dateInputs = page.locator('.history-filter-bar__date input.el-range-input');
  await dateInputs.nth(0).fill('2026-09-01');
  await dateInputs.nth(1).fill('2026-09-24');
  await dateInputs.nth(1).press('Enter');
  const requestCountAfterDateInput = historyRequests.length;
  await page.waitForTimeout(400);
  expect(historyRequests.length).toBe(requestCountAfterDateInput);
  await dateInputs.nth(1).press('Enter');
  await expect.poll(() => historyRequests.length, { timeout: 5_000 }).toBeGreaterThan(requestCountAfterDateInput);
  expect(historyRequests.at(-1)?.searchParams.get('dateRange')).toBe('2026-09-01,2026-09-24');
});

test('业务接口返回登录失效时清空工作台并跳转，不重放提交', async ({ page }) => {
  await mockAuthentication(page);
  // 该用例只在提交优化时模拟失效；模型列表不能先请求真实服务并提前触发跳转。
  await page.route('**/api/v1/models', (route) => route.fulfill({
    json: { data: [{ id: 'mock:authentication-test', displayName: '测试模型',
      provider: 'Mock', defaultModel: true }] },
  }));
  await page.addInitScript(() => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
  });
  let calls = 0;
  await page.route('**/api/v1/optimizations', async route => {
    calls += 1;
    await route.fulfill({ status: 401, json: { error: { code: 'AUTHENTICATION_REQUIRED' } } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('编写一份用户说明文档');
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  await expect(page).toHaveURL(/\/login\?expired=1$/);
  await expect(page.getByLabel('原始提示词')).toHaveCount(0);
  expect(calls).toBe(1);
});
