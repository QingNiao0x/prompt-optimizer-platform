import { expect, test } from '@playwright/test';

import { openWorkbenchPane } from './workbenchPanes';

test('首页提供与工作台一致的玻璃主题入口', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('heading', { name: /让模型先理解你的工程/ })).toBeVisible();
  await expect(page.getByRole('link', { name: 'PromptOptimizer 首页' }).locator('svg')).toBeVisible();
  await expect(page.getByText('项目上下文感知', { exact: true })).toBeVisible();
  await expect(page.getByText('多模型适配', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '开始增强提示词' })).toHaveAttribute('href', '/workbench');
  await expect(page.locator('.topbar')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '登录' }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '注册' }).first()).toBeVisible();

  await page.getByRole('button', { name: '登录' }).first().click();
  await expect(page.getByRole('heading', { name: '扫码登录' })).toBeVisible();
  await expect(page.getByText('打开微信扫一扫，扫码登录')).toBeVisible();
  await page.getByRole('button', { name: '使用账号密码登录' }).click();
  await expect(page.getByPlaceholder('请输入邮箱或手机号')).toBeVisible();
  await page.getByRole('button', { name: '关闭', exact: true }).click();

  await page.getByRole('button', { name: '注册' }).first().click();
  await expect(page.getByRole('heading', { name: '创建账号' })).toBeVisible();
  await page.getByRole('button', { name: '关闭', exact: true }).click();

  await page.getByRole('button', { name: '切换为深色主题' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-ui-theme', 'glass-dark');
  await expect(page.locator('html')).toHaveClass(/dark/);
  await expect(page.locator('body')).toHaveClass(/dark/);

  await page.getByRole('link', { name: '开始增强提示词' }).click();
  await expect(page).toHaveURL(/\/workbench$/);
  await openWorkbenchPane(page, 'intent');
  await expect(page.getByLabel('原始提示词')).toBeVisible();
});
