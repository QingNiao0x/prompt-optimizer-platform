import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { writeFile } from 'node:fs/promises';

// 复用当前项目 Playwright 和独立合成浏览器目录，不使用日常用户浏览器或服务。
const { chromium, expect } = createRequire(resolve('apps/web/package.json'))('@playwright/test');
const root = resolve('tmp/natural-prepared-exemplars-20261022/page-artifacts');
const rows = [];
for (const arm of ['RAW', 'V4', 'V11', 'V12']) {
  const profile = resolve(root, arm, 'browser-profile');
  const file = pathToFileURL(resolve(root, arm, 'index.html')).href;
  const checks = [];
  const externalRequests = [];
  const errors = [];
  let context;
  const launch = async () => {
    context = await chromium.launchPersistentContext(profile, { channel: 'chrome', headless: true,
      viewport: { width: 1100, height: 850 }, locale: 'zh-CN' });
    await context.route(/^https?:/, route => { externalRequests.push(route.request().url()); return route.abort(); });
    const page = context.pages()[0] ?? await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(file);
    await expect(page.locator('input[type=text]')).toBeVisible();
    return page;
  };
  const check = async (name, assertion) => {
    try { await assertion(); checks.push({ name, pass: true }); }
    catch (error) { checks.push({ name, pass: false, message: error.message.slice(0, 600) }); }
  };
  try {
    let page = await launch();
    const input = page.locator('input[type=text]');
    await input.fill('准备评审材料'); await input.press('Enter');
    await check('新增任务', () => expect(page.locator('input[type=checkbox]')).toHaveCount(1));
    await input.fill('<img src=x onerror="window.artifactInjected=true">'); await input.press('Enter');
    await check('任务文本不解析为HTML', async () => {
      await expect(page.locator('input[type=checkbox]')).toHaveCount(2);
      expect(await page.evaluate(() => Boolean(window.artifactInjected))).toBe(false);
      await expect(page.locator('img[src=x]')).toHaveCount(0);
    });
    await input.fill('   '); await input.press('Enter');
    await check('空任务不新增', () => expect(page.locator('input[type=checkbox]')).toHaveCount(2));
    await check('勾选完成', async () => {
      await page.locator('input[type=checkbox]').first().check();
      await expect(page.locator('input[type=checkbox]').first()).toBeChecked();
    });
    await page.reload();
    await check('刷新保留任务和完成状态', async () => {
      await expect(page.locator('input[type=checkbox]')).toHaveCount(2);
      await expect(page.locator('input[type=checkbox]').first()).toBeChecked();
    });
    await page.screenshot({ path: resolve(root, arm, 'after-reload.png'), fullPage: true });
    await context.close();
    page = await launch();
    await check('关闭浏览器后重新打开仍保留', async () => {
      await expect(page.locator('input[type=checkbox]')).toHaveCount(2);
      await expect(page.locator('input[type=checkbox]').first()).toBeChecked();
    });
    await check('删除并刷新后不恢复', async () => {
      await page.locator('.delete-btn').first().click();
      await expect(page.locator('input[type=checkbox]')).toHaveCount(1);
      await page.reload();
      await expect(page.locator('input[type=checkbox]')).toHaveCount(1);
    });
    checks.push({ name: '离线无外链与脚本异常', pass: externalRequests.length === 0 && errors.length === 0 });
  } finally { if (context) await context.close(); }
  rows.push({ arm, checks, externalRequests, errors });
}
await writeFile(resolve('docs/testing/evidence/natural-prepared-exemplars-2026-10-22/page-artifact-checks.json'),
  JSON.stringify({ modelCodeModified: false, browser: 'installed Chrome via existing Playwright', rows }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify(rows.map(row => ({ arm: row.arm, passed: row.checks?.filter(c => c.pass).length,
  failed: row.checks?.filter(c => !c.pass).length, reason: row.reason }))));
