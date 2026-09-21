import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';

test('首页提供与工作台一致的玻璃主题入口', async ({ page }) => {
  await mockAuthentication(page, false);
  await page.goto('/');

  await expect(page.getByRole('heading', { name: /让模型先理解你的工程/ })).toBeVisible();
  await expect(
    page.getByText(
      'AI 时代真正拉开差距的，不是谁用了更贵的模型，是谁更清楚自己要啥、更会把这份清楚说出来。',
      { exact: true },
    ),
  ).toBeVisible();
  await expect(
    page.getByText('提问这件事，正在变成和写作、沟通一样重要的基本功。', { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('link', { name: 'PromptOptimizer 首页' }).locator('svg')).toBeVisible();
  await expect(page.getByText('项目上下文感知', { exact: true })).toBeVisible();
  await expect(page.getByText('多模型适配', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '开始增强提示词' })).toHaveAttribute('href', '/workbench');
  await expect(page.getByText('免费开始', { exact: true })).toHaveCount(0);
  await expect(page.locator('.topbar')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '登录' }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '注册' }).first()).toBeVisible();

  await page.getByRole('button', { name: '登录' }).first().click();
  await expect(page.getByRole('heading', { name: '账号登录' })).toBeVisible();
  await expect(page.getByPlaceholder('请输入邮箱')).toBeVisible();
  await expect(page.getByPlaceholder('请输入密码')).toBeVisible();
  await page.getByRole('button', { name: '关闭', exact: true }).click();

  await page.getByRole('button', { name: '注册' }).first().click();
  await expect(page.getByRole('heading', { name: '创建账号' })).toBeVisible();
  const registrationAccount = page.getByPlaceholder('请输入邮箱地址或手机号');
  await expect(registrationAccount).toBeVisible();
  await expect(page.getByPlaceholder('请输入 6 位验证码')).toBeVisible();
  await expect(page.getByPlaceholder('请再次输入密码')).toBeVisible();
  await expect(page.getByRole('complementary', { name: '微信扫码登录' })).toBeVisible();
  await expect(page.getByRole('button', { name: '创建账号' })).toBeDisabled();
  await expect(page.getByText(/创建账号前：请先填写邮箱地址/)).toBeVisible();
  await registrationAccount.fill('invalid-account');
  await expect(page.getByText('请输入有效的邮箱地址或手机号。', { exact: true })).toBeVisible();
  await registrationAccount.fill('13800138000');
  await expect(page.getByText(/手机号格式正确，但短信验证码注册尚未开通/).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '短信注册待开通' })).toBeDisabled();
  await registrationAccount.fill('new@example.com');
  await page.getByRole('button', { name: '获取验证码' }).click();
  await expect(page.getByRole('status')).toContainText('验证码已发送');
  await expect(page.getByRole('button', { name: /秒后重发/ })).toBeDisabled();
  await page.getByPlaceholder('请输入 6 位验证码').fill('123456');
  const password = page.getByPlaceholder('请输入密码');
  const confirmPassword = page.getByPlaceholder('请再次输入密码');
  await password.fill('1234567');
  await confirmPassword.fill('1234567');
  await page.getByRole('checkbox').check();
  await expect(page.getByText('创建账号前：密码至少需要 8 个字符。')).toBeVisible();
  await expect(page.getByRole('button', { name: '创建账号' })).toBeDisabled();
  await page.getByRole('button', { name: '显示密码' }).click();
  await expect(password).toHaveAttribute('type', 'text');
  await page.getByRole('button', { name: '隐藏密码' }).click();
  await expect(password).toHaveAttribute('type', 'password');
  await page.getByRole('button', { name: '显示确认密码' }).click();
  await expect(confirmPassword).toHaveAttribute('type', 'text');
  await password.fill('test-password-123');
  await confirmPassword.fill('test-password-123');
  await expect(page.getByRole('button', { name: '创建账号' })).toBeEnabled();
  await page.getByRole('button', { name: '关闭', exact: true }).click();

  await page.getByRole('button', { name: '切换为深色主题' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-ui-theme', 'glass-dark');
  await expect(page.locator('html')).toHaveClass(/dark/);
  await expect(page.locator('body')).toHaveClass(/dark/);

  await page.getByRole('link', { name: '开始增强提示词' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '账号登录' })).toBeVisible();
});
