import { expect, test, type Page } from '@playwright/test';

import type { AnalyticsClientEvent, OptimizationResult } from '../src/types/api';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

const platforms = [
  { name: 'DeepSeek', url: 'https://chat.deepseek.com/' },
  { name: 'Kimi', url: 'https://www.kimi.com/' },
  { name: '智谱', url: 'https://chatglm.cn/' },
  { name: '元宝', url: 'https://yuanbao.tencent.com/' },
  { name: '豆包', url: 'https://www.doubao.com/chat/' },
] as const;
const taskText = '整理一周学习计划，保留中文、换行和代码示例。';
const prompt = [
  '## 背景\n复制验收专用的合成材料。',
  `## 任务\n${taskText}`,
  '## 输出\n```json\n{"说明":"中文与 emoji 🐦", "完成":false}\n```\n'
    + Array.from({ length: 80 }, (_, index) => `${index + 1}. 保留这条完整任务说明，不截断正文。`).join('\n'),
  '## 约束\n仅供用户粘贴后审阅，不自动发送。',
].join('\n\n');
const result: OptimizationResult = {
  optimizedPrompt: prompt,
  sections: [
    { type: 'BACKGROUND', title: '背景', content: '复制验收专用的合成材料。' },
    { type: 'TASK', title: '任务', content: taskText },
    { type: 'OUTPUT', title: '输出', content: prompt.split('## 输出\n')[1]!.split('\n\n## 约束')[0]! },
    { type: 'CONSTRAINTS', title: '约束', content: '仅供用户粘贴后审阅，不自动发送。' },
  ],
  contextReport: { customDescription: '', technologyStack: [], dependencies: [], directoryTree: [],
    fileSnippets: [], warnings: [], redactions: [], analysisVersion: '1.0' },
  ambiguities: [], appliedConstraints: [], templateCode: 'GENERAL',
  provider: { provider: 'Mock', model: 'mock', mock: true }, latencyMs: 1,
};

type ClipboardMode = 'native' | 'reject' | 'throw' | 'pending';
type PopupMode = 'native' | 'blocked' | 'throw';
interface ClipboardProbe {
  mode: ClipboardMode;
  popupMode: PopupMode;
  writes: string[];
  openCalls: number;
  shareCalls: number;
  resolve?: () => void;
}
declare global {
  interface Window { __promptCopyProbe: ClipboardProbe }
}

/** 正常路径调用真实剪贴板；只在故障用例中替换权限、等待和弹窗行为。 */
const installClipboardProbe = async (page: Page, dark: boolean): Promise<void> => {
  await page.addInitScript(({ darkTheme }) => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
    localStorage.setItem('po-theme', darkTheme ? 'dark' : 'light');
    const probe: ClipboardProbe = { mode: 'native', popupMode: 'native', writes: [], openCalls: 0, shareCalls: 0 };
    window.__promptCopyProbe = probe;
    const nativeWrite = navigator.clipboard.writeText.bind(navigator.clipboard);
    navigator.clipboard.writeText = (content: string): Promise<void> => {
      if (probe.mode === 'throw') throw new DOMException('Test permission denied', 'NotAllowedError');
      if (probe.mode === 'reject') return Promise.reject(new DOMException('Test permission denied', 'NotAllowedError'));
      if (probe.mode === 'pending') return new Promise<void>((resolve) => {
        probe.resolve = () => { probe.writes.push(content); resolve(); };
      });
      return nativeWrite(content).then(() => { probe.writes.push(content); });
    };
    const nativeOpen = window.open.bind(window);
    window.open = (url, target, features) => {
      probe.openCalls++;
      if (probe.popupMode === 'blocked') return null;
      if (probe.popupMode === 'throw') throw new DOMException('Test popup denied', 'NotAllowedError');
      return nativeOpen(url, target, features);
    };
    // 即使系统提供分享能力，结果面板也不应再调用它。
    Object.defineProperty(navigator, 'share', { configurable: true, value: async () => { probe.shareCalls++; } });
    Object.defineProperty(navigator, 'canShare', { configurable: true, value: () => true });
  }, { darkTheme: dark });
};

/** 业务接口与目标 AI 页面使用夹具；不会调用真实模型或把文本发送到外部平台。 */
const openCopyWorkbench = async (page: Page, dark = false) => {
  const exports: AnalyticsClientEvent[] = [];
  const navigations: { url: string; referer?: string }[] = [];
  let exportStatus = 200;
  await installClipboardProbe(page, dark);
  await mockAuthentication(page);
  await page.route('**/api/v1/models', (route) => route.fulfill({ json: { data: [
    { id: 'mock', displayName: 'Mock', provider: 'Mock', defaultModel: true },
  ] } }));
  await page.route('**/api/v1/optimizations', (route) => route.fulfill({ json: { data: result } }));
  await page.route('**/api/v1/analytics/events', (route) => {
    const event = route.request().postDataJSON() as AnalyticsClientEvent;
    if (event.eventType === 'RESULT_EXPORTED') exports.push(event);
    return route.fulfill({ status: event.eventType === 'RESULT_EXPORTED' ? exportStatus : 200,
      json: { data: null } });
  });
  await page.context().route(/^https:\/\/(chat\.deepseek\.com|www\.kimi\.com|chatglm\.cn|yuanbao\.tencent\.com|www\.doubao\.com)\//, (route) => {
    navigations.push({ url: route.request().url(), referer: route.request().headers().referer });
    return route.fulfill({ contentType: 'text/html; charset=utf-8',
      body: '<meta charset="utf-8"><title>平台测试页</title><textarea aria-label="平台输入框"></textarea>' });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词', { exact: true }).fill('整理一周学习计划');
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  await expect(page.locator('.copy-main-button')).toBeAttached();
  await openWorkbenchPane(page, 'result');
  await expect(page.locator('.copy-main-button')).toBeEnabled();
  return { exports, navigations, setExportStatus: (status: number) => { exportStatus = status; } };
};

const choosePlatform = async (page: Page, name: string): Promise<void> => {
  await page.bringToFront();
  await page.getByRole('button', { name: '选择 AI 平台', exact: true }).click();
  await page.getByRole('menuitem', { name: `复制并打开 ${name}`, exact: true }).click();
};

const pendingCopy = async (page: Page): Promise<Page> => {
  await page.evaluate(() => { window.__promptCopyProbe.mode = 'pending'; });
  const popup = page.context().waitForEvent('page');
  await choosePlatform(page, 'DeepSeek');
  const tab = await popup;
  await expect(tab).toHaveURL('about:blank');
  return tab;
};

const resolveCopy = async (page: Page): Promise<void> => {
  await page.evaluate(() => { window.__promptCopyProbe.resolve?.(); });
};

test('主按钮与五个平台使用真实剪贴板，粘贴内容完整且导出事件不含正文', async ({ page, context }) => {
  const state = await openCopyWorkbench(page);
  await expect(page.getByRole('button', { name: '系统分享', exact: true })).toHaveCount(0);
  await expect(page.locator('.open-platform-link')).toHaveCount(0);
  await page.locator('.copy-main-button').click();
  await expect.poll(() => state.exports.length).toBe(1);
  await expect(page.locator('.copy-main-button')).toHaveText('已复制');
  expect(context.pages()).toHaveLength(1);
  expect(await page.evaluate(() => window.__promptCopyProbe.openCalls)).toBe(0);
  for (const [index, platform] of platforms.entries()) {
    const popup = context.waitForEvent('page');
    await choosePlatform(page, platform.name);
    const tab = await popup;
    await expect(tab).toHaveURL(platform.url);
    await expect.poll(() => state.exports.length).toBe(index + 2);
    const input = tab.getByRole('textbox', { name: '平台输入框' });
    await expect(input).toHaveValue('');
    expect(await tab.evaluate(() => window.opener)).toBeNull();
    // 模拟用户粘贴到隔离页面，核对系统剪贴板的实际内容，不只检查 writeText 的入参。
    await input.focus();
    await tab.keyboard.press(process.platform === 'darwin' ? 'Meta+V' : 'Control+V');
    await expect(input).toHaveValue(prompt);
    await expect(page.locator('.open-platform-link')).toHaveCount(0);
    await tab.close();
  }
  expect(state.navigations.map((item) => item.url)).toEqual(platforms.map((item) => item.url));
  expect(state.navigations.every((item) => !item.referer)).toBe(true);
  expect(new Set(state.exports.map((event) => event.eventId)).size).toBe(6);
  expect(JSON.stringify(state.exports)).not.toContain('复制验收专用');
  for (const event of state.exports) {
    expect(Object.keys(event).sort()).toEqual(['eventId', 'eventType', 'expectedLoginSessionId', 'expectedUserId', 'occurredAt']);
  }
  expect(await page.evaluate(() => window.__promptCopyProbe.shareCalls)).toBe(0);
});

for (const mode of ['reject', 'throw', 'missing'] as const) {
  test(`剪贴板 ${mode} 时保留完整手动复制文本，不打开平台或记录导出`, async ({ page, context }) => {
    const state = await openCopyWorkbench(page);
    await page.evaluate((failure) => {
      if (failure === 'missing') Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
      else window.__promptCopyProbe.mode = failure;
    }, mode);
    await choosePlatform(page, 'Kimi');
    const dialog = page.getByRole('dialog', { name: '手动复制提示词', exact: true });
    await expect(dialog).toBeVisible();
    const input = dialog.getByRole('textbox', { name: '完整提示词，供手动复制', exact: true });
    await expect(input).toHaveValue(prompt);
    await expect(input).toHaveAttribute('readonly', '');
    await dialog.getByRole('button', { name: '全选', exact: true }).click();
    expect(await input.evaluate((element: HTMLTextAreaElement) => [element.selectionStart, element.selectionEnd])).toEqual([0, prompt.length]);
    await expect.poll(() => context.pages().length).toBe(1);
    expect(state.navigations).toHaveLength(0);
    expect(state.exports).toHaveLength(0);
    await dialog.getByRole('button', { name: '关闭', exact: true }).click();
    await expect(page.locator('.copy-main-button')).toBeEnabled();
  });
}

test('弹窗被拦截或抛错时复制仍成功，手动打开链接不重复计数', async ({ page, context }) => {
  const state = await openCopyWorkbench(page);
  for (const [index, popupMode] of (['blocked', 'throw'] as const).entries()) {
    await page.evaluate((mode) => { window.__promptCopyProbe.popupMode = mode; }, popupMode);
    const platform = platforms[index]!;
    await choosePlatform(page, platform.name);
    await expect.poll(() => state.exports.length).toBe(index + 1);
    const link = page.getByRole('link', { name: `打开 ${platform.name}`, exact: true });
    await expect(link).toHaveAttribute('href', platform.url);
    await expect(page.locator('.export-followup')).toContainText('已复制，但未能打开平台');
    const popup = context.waitForEvent('page');
    await link.click();
    const tab = await popup;
    await expect(tab).toHaveURL(platform.url);
    expect(await tab.evaluate(() => window.opener)).toBeNull();
    expect(state.navigations.at(-1)?.referer).toBeUndefined();
    expect(state.exports).toHaveLength(index + 1);
    await tab.close();
  }
});

test('权限等待期间仅预留空白页并禁用重复复制，成功后才导航', async ({ page }) => {
  const state = await openCopyWorkbench(page);
  const tab = await pendingCopy(page);
  await expect(page.locator('.copy-main-button')).toBeDisabled();
  await expect(page.getByRole('button', { name: '选择 AI 平台', exact: true })).toBeDisabled();
  expect(state.exports).toHaveLength(0);
  expect(state.navigations).toHaveLength(0);
  await expect(tab.locator('body')).not.toContainText('复制验收专用');
  await resolveCopy(page);
  await expect(tab).toHaveURL(platforms[0].url);
  await expect.poll(() => state.exports.length).toBe(1);
  await tab.close();
});

test('用户关闭预留页后不自动重开，复制成功提供手动链接', async ({ page, context }) => {
  const state = await openCopyWorkbench(page);
  const tab = await pendingCopy(page);
  await tab.close();
  await resolveCopy(page);
  await expect.poll(() => state.exports.length).toBe(1);
  await expect(page.locator('.open-platform-link')).toHaveAttribute('href', platforms[0].url);
  expect(context.pages()).toHaveLength(1);
  expect(state.navigations).toHaveLength(0);
});

test('编辑保存关闭旧预留页，之后复制的是最新完整内容', async ({ page }) => {
  const state = await openCopyWorkbench(page);
  const tab = await pendingCopy(page);
  await page.bringToFront();
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await expect.poll(() => tab.isClosed()).toBe(true);
  const editedTask = '修改后的任务：整理一个月学习计划。';
  await page.locator('#section-TASK').fill(editedTask);
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  await resolveCopy(page);
  await expect.poll(() => state.exports.length).toBe(1);
  await expect(page.locator('.copy-main-button')).toHaveText('复制提示词');
  await expect(page.locator('.open-platform-link')).toHaveCount(0);
  expect(state.navigations).toHaveLength(0);
  await page.evaluate(() => { window.__promptCopyProbe.mode = 'native'; });
  await page.locator('.copy-main-button').click();
  await expect.poll(() => state.exports.length).toBe(2);
  expect(await page.evaluate(() => window.__promptCopyProbe.writes.at(-1))).toBe(prompt.replace(taskText, editedTask));
});

test('重新生成期间关闭旧预留页且不把旧复制状态标到新结果', async ({ page }) => {
  const state = await openCopyWorkbench(page);
  const tab = await pendingCopy(page);
  let release = (): void => undefined;
  const generation = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/api/v1/optimizations', async (route) => {
    await generation;
    await route.fulfill({ json: { data: { ...result, optimizedPrompt: prompt + '\n本轮重新生成。' } } });
  });
  await page.bringToFront();
  await page.getByRole('button', { name: '直接再次增强', exact: true }).click();
  await expect.poll(() => tab.isClosed()).toBe(true);
  await resolveCopy(page);
  await expect.poll(() => state.exports.length).toBe(1);
  await expect(page.locator('.copy-main-button')).toBeDisabled();
  release();
  await openWorkbenchPane(page, 'result');
  await expect(page.locator('.copy-main-button')).toBeEnabled();
  await expect(page.locator('.copy-main-button')).toHaveText('复制提示词');
  await expect(page.locator('.open-platform-link')).toHaveCount(0);
  expect(state.navigations).toHaveLength(0);
});

test('离开工作台关闭预留页，迟到的复制回调不打开平台', async ({ page }) => {
  const state = await openCopyWorkbench(page);
  const tab = await pendingCopy(page);
  await page.bringToFront();
  await page.locator('a[href="/"]').first().click();
  await expect.poll(() => tab.isClosed()).toBe(true);
  await resolveCopy(page);
  await expect.poll(() => state.exports.length).toBe(1);
  expect(state.navigations).toHaveLength(0);
  await expect(page.locator('.open-platform-link')).toHaveCount(0);
});

test('上报503不阻断复制，恢复后沿用同一事件ID重试', async ({ page }) => {
  const state = await openCopyWorkbench(page);
  state.setExportStatus(503);
  await page.locator('.copy-main-button').click();
  await expect.poll(() => state.exports.length).toBeGreaterThan(0);
  await expect(page.locator('.copy-main-button')).toHaveText('已复制');
  const originalEvent = state.exports[0]!;
  state.setExportStatus(200);
  await expect.poll(() => state.exports.length).toBeGreaterThanOrEqual(2);
  expect(state.exports.every((event) => JSON.stringify(event) === JSON.stringify(originalEvent))).toBe(true);
  await expect.poll(() => page.evaluate(() => Object.keys(localStorage)
    .filter((key) => key.startsWith('prompt-optimizer.analytics.pending.v1:')).length)).toBe(0);
  expect(await page.evaluate(() => window.__promptCopyProbe.writes.length)).toBe(1);
});

test('320px深色界面的菜单、触控目标与手动复制弹窗可用', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  const state = await openCopyWorkbench(page, true);
  await page.getByRole('button', { name: '选择 AI 平台', exact: true }).click();
  const items = page.getByRole('menuitem');
  await expect(items).toHaveCount(5);
  await expect.poll(() => items.evaluateAll((elements) => elements.every((element) => {
    const box = element.getBoundingClientRect();
    return box.height >= 44 && box.x >= 0 && box.right <= innerWidth && box.y >= 0 && box.bottom <= innerHeight;
  }))).toBe(true);
  expect((await page.locator('.copy-main-button').boundingBox())!.height).toBeGreaterThanOrEqual(44);
  expect((await page.locator('.copy-platform-trigger').boundingBox())!.width).toBeGreaterThanOrEqual(44);
  await page.evaluate(() => { window.__promptCopyProbe.mode = 'reject'; });
  await page.getByRole('menuitem', { name: '复制并打开 豆包', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '完整提示词，供手动复制', exact: true })).toHaveValue(prompt);
  await expect.poll(() => page.locator('.manual-copy-dialog').evaluate((element) => {
    const box = element.getBoundingClientRect();
    return box.x >= 0 && box.right <= innerWidth && box.y >= 0 && box.bottom <= innerHeight;
  })).toBe(true);
  expect(state.exports).toHaveLength(0);
});
