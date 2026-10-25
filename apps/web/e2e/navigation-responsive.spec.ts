import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';

test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', route => route.fulfill({ status: 503, json: { error: { message: '未模拟接口' } } }));
  await mockAuthentication(page, true, true);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'navigation-model', displayName: '用于验证模型名称不会撑开导航的合成模型', defaultModel: true },
  ] } }));
  await page.route('**/api/v1/optimization-history**', route => route.fulfill({ json: {
    data: { records: [], current: 1, size: 10, total: 0, pages: 0 },
  } }));
});

test('管理员导航在手机、平板和桌面完整显示文字且互不遮挡', async ({ page }, testInfo) => {
  await page.goto('/history');
  await expect(page.getByRole('heading', { name: '优化历史', exact: true })).toBeVisible();
  for (const width of [320, 375, 430, 768, 1024, 1280, 1441, 1536, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    const toggle = page.getByRole('button', { name: '功能菜单', exact: true });
    if (await toggle.isVisible() && await toggle.getAttribute('aria-expanded') === 'false') await toggle.click();
    const links = page.locator('.main-nav .nav-link');
    await expect(links).toHaveCount(8);
    const boxes = [];
    for (const link of await links.all()) {
      await expect(link.locator('span')).toBeVisible();
      const box = await link.boundingBox();
      expect(box).not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(width + 1);
      expect(box!.height).toBeGreaterThanOrEqual(44);
      boxes.push(box!);
    }
    for (let index = 0; index < boxes.length; index += 1) {
      for (const next of boxes.slice(index + 1)) {
        const current = boxes[index]!;
        const overlapX = Math.min(current.x + current.width, next.x + next.width) - Math.max(current.x, next.x);
        const overlapY = Math.min(current.y + current.height, next.y + next.height) - Math.max(current.y, next.y);
        expect(overlapX <= 1 || overlapY <= 1).toBe(true);
      }
    }
    const brand = (await page.locator('.topbar .brand').boundingBox())!;
    const tools = (await page.locator('.topbar-tools').boundingBox())!;
    expect(brand.x + brand.width).toBeLessThanOrEqual(tools.x + 1);
    for (const box of boxes) {
      for (const target of [brand, tools]) {
        const overlapX = Math.min(box.x + box.width, target.x + target.width) - Math.max(box.x, target.x);
        const overlapY = Math.min(box.y + box.height, target.y + target.height) - Math.max(box.y, target.y);
        expect(overlapX <= 1 || overlapY <= 1).toBe(true);
      }
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth))
      .toBeLessThanOrEqual(1);
    if (width === 375 || width === 768) {
      await page.screenshot({ path: testInfo.outputPath(`admin-navigation-${width}.png`) });
    }
  }
});

test('小屏功能菜单支持关闭、键盘与导航后自动收起', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/history');
  const toggle = page.getByRole('button', { name: '功能菜单', exact: true });
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('navigation', { name: '主导航', exact: true })).toBeHidden();
  await toggle.click();
  await page.getByRole('link', { name: '模型管理', exact: true }).focus();
  await page.keyboard.press('Escape');
  await expect(toggle).toBeFocused();
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await toggle.click();
  await page.getByRole('link', { name: '设置', exact: true }).click();
  await expect(page).toHaveURL(/\/settings$/);
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
});

test('普通用户菜单不暴露管理员入口', async ({ page }) => {
  await mockAuthentication(page, true, false);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/history');
  await page.getByRole('button', { name: '功能菜单', exact: true }).click();
  await expect(page.locator('.main-nav .nav-link')).toHaveCount(6);
  await expect(page.getByRole('link', { name: '模型管理', exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: '统计日志', exact: true })).toHaveCount(0);
});
