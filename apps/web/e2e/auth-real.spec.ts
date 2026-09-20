import { expect, test } from '@playwright/test';

// 显式启用，使用独立 local-mock 实例，不读取真实账户或调用收费模型。
test.skip(process.env.AUTH_E2E_REAL !== 'true', '需要本地认证测试实例');

test('真实 Session、CSRF Cookie、登录、刷新与退出', async ({ page, context }) => {
  const password = process.env.LOCAL_AUTH_PASSWORD;
  if (!password) { throw new Error('缺少 LOCAL_AUTH_PASSWORD'); }
  await page.goto('/workbench');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByPlaceholder('请输入邮箱').fill(process.env.LOCAL_AUTH_EMAIL ?? 'demo@local');
  await page.getByPlaceholder('请输入密码').fill('incorrect-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByPlaceholder('请输入密码').fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/workbench$/);
  const cookies = await context.cookies();
  expect(cookies.find(cookie => cookie.name === 'JSESSIONID')).toMatchObject({ httpOnly: true, sameSite: 'Lax' });
  const csrf = cookies.find(cookie => cookie.name === 'XSRF-TOKEN');
  expect(csrf).toMatchObject({ httpOnly: false, sameSite: 'Lax' });
  const denied = await page.request.post('/api/v1/optimizations/plan', { data: { rawPrompt: '写一份功能说明' } });
  expect(denied.status()).toBe(403);
  const allowed = await page.request.post('/api/v1/optimizations/plan', {
    headers: { 'X-XSRF-TOKEN': csrf?.value ?? '' }, data: { rawPrompt: '写一份功能说明' },
  });
  expect(allowed.status()).toBe(200);
  expect((await allowed.json()).data.planId).toBeTruthy();
  await page.reload();
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible();
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/$/);
  expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401);
  await page.goto('/workbench');
  await expect(page).toHaveURL(/\/login$/);
});
