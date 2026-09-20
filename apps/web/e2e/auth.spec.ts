import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

test('未登录不能挂载工作台，错误密码可重试，登录后可刷新和退出', async ({ page }) => {
  await mockAuthentication(page, false);
  await page.goto('/workbench');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByLabel('原始提示词')).toHaveCount(0);
  await page.getByPlaceholder('请输入邮箱').fill('test@example.com');
  await page.getByPlaceholder('请输入密码').fill('wrong-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('邮箱或密码不正确');
  await page.getByPlaceholder('请输入密码').fill('test-password');
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

test('业务接口返回登录失效时清空工作台并跳转，不重放提交', async ({ page }) => {
  await mockAuthentication(page);
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
