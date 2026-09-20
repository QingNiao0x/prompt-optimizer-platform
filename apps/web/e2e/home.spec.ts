import { expect, test } from '@playwright/test';

import { openWorkbenchPane } from './workbenchPanes';

test('首页提供与工作台一致的玻璃主题入口', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('heading', { name: /让模型先理解你的工程/ })).toBeVisible();
  await expect(page.getByRole('link', { name: 'PromptOptimizer 首页' }).locator('svg')).toBeVisible();
  await expect(page.getByText('项目上下文感知', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '开始增强提示词' })).toHaveAttribute('href', '/workbench');
  await expect(page.locator('.topbar')).toHaveCount(0);

  await page.getByRole('button', { name: '切换为深色主题' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-ui-theme', 'glass-dark');

  await page.getByRole('link', { name: '开始增强提示词' }).click();
  await expect(page).toHaveURL(/\/workbench$/);
  await openWorkbenchPane(page, 'intent');
  await expect(page.getByLabel('原始提示词')).toBeVisible();
});
