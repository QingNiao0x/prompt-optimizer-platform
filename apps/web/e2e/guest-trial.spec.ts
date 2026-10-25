import { expect, test, type Page } from '@playwright/test';
import type { OptimizationResult } from '../src/types/api';
import type { ImagePromptDocument, ImagePromptInput } from '../src/types/imagePrompt';
import type { PolishDocument, PolishInput } from '../src/types/textPolish';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

type Feature = 'imagePrompt' | 'enhancement' | 'textPolish';
type Quotas = Record<Feature, { limit: number; used: number; remaining: number }>;
const model = { id: 'guest-fixture', displayName: '受控试用模型', provider: 'fixture', defaultModel: true, mock: false };
const exhaustedMessage = '免费体验次数已用完，请注册或登录后继续使用。';
const historyMessage = '历史记录仅向已注册并登录的用户开放，请先注册或登录。';
const tinyPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6o0MAAAAASUVORK5CYII=', 'base64');

const optimization = (): OptimizationResult => ({
  resultKind: 'TEXT', generationPipeline: 'NATURAL', chainVersion: 'fixture', framework: 'ICIO',
  optimizedPrompt: '写一封简短的会议通知邮件，保留时间待确认的状态，不编造会议安排。',
  sections: [], promptFormat: 'COMPACT', templateCode: 'GENERAL',
  contextReport: { customDescription: '', technologyStack: [], dependencies: [], directoryTree: [],
    fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'fixture' },
  ambiguities: [], appliedConstraints: [],
  provider: { provider: 'fixture', model: model.id, mock: false }, latencyMs: 1,
});
const polished = (input: PolishInput): PolishDocument => ({
  id: 'guest-polish-result', input, generatedText: '这是一段经过润色的文章，保留原有事实。',
  polishedText: '这是一段经过润色的文章，保留原有事实。', sourceRecordId: null, sourceMode: 'RAW',
  modelId: model.id, modelName: model.displayName, mock: false, editRevision: 0, checks: [],
  historySaved: false, persistentHistory: false, createdAt: '2026-10-25T00:00:00Z',
});
const imagePrompt = (input: ImagePromptInput): ImagePromptDocument => ({
  operationId: 'guest-image-operation', recordId: null, mode: input.mode,
  optimizedPrompt: '还原参考图中的白色背景和蓝色圆形，保留主体位置与简单构图。',
  analysis: [], warnings: [], input, sources: [], questions: [], answers: [], modelId: model.id,
  modelName: model.displayName, mock: false, editRevision: 0, historySaved: false,
  persistentHistory: false, createdAt: '2026-10-25T00:00:00Z',
});

/** 有状态服务端配额夹具；全部 API 拦截，既不创建账号，也不向真实模型发送正文或图片。 */
async function setup(page: Page, authenticated = false) {
  const quotas: Quotas = {
    imagePrompt: { limit: 1, used: 0, remaining: 1 },
    enhancement: { limit: 3, used: 0, remaining: 3 },
    textPolish: { limit: 3, used: 0, remaining: 3 },
  };
  const state = { quotas, calls: { imagePrompt: 0, enhancement: 0, textPolish: 0 },
    historyRequests: [] as string[], trialRequests: [] as string[], unexpected: [] as string[], forceExhausted: false, statusUnavailable: false };
  page.on('request', request => {
    if (/\/api\/.*(?:optimization-history|\/history)(?:[/?]|$)/.test(request.url())) state.historyRequests.push(request.url());
  });
  await page.route('**/api/**', route => {
    state.unexpected.push(new URL(route.request().url()).pathname);
    return route.fulfill({ status: 501, json: { error: { code: 'UNEXPECTED_TEST_REQUEST', message: '未登记的试用测试请求' } } });
  });
  await mockAuthentication(page, authenticated);
  await page.addInitScript(() => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
    localStorage.setItem('prompt-optimizer.project-context-settings.v1', JSON.stringify({
      profile: 'WEB_STANDARD', retention: 'SESSION', autoCleanupDays: 7, confirmBeforeSendingCode: false,
    }));
  });
  await page.route('**/api/v1/trial/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    state.trialRequests.push(path);
    const ok = (data: unknown) => route.fulfill({ json: { requestId: 'guest-fixture', data } });
    if (path.endsWith('/session') || path.endsWith('/status')) {
      if (state.statusUnavailable) return route.fulfill({ status: 503,
        json: { error: { code: 'TRIAL_UNAVAILABLE', message: '免费体验暂时不可用，请稍后重试。' } } });
      return ok({ authenticated, available: true, quotas });
    }
    if (path.endsWith('/models')) return ok([model]);
    if (path.endsWith('/image-prompts/capabilities')) return ok({
      models: [{ ...model, vision: true, maxImages: 1, evidence: '受控视觉夹具' }],
      maxImageBytes: 10485760, maxPixels: 15000000, maxEdge: 8192, maxImages: 1,
      assetTtlSeconds: 3600, persistentHistory: false,
    });
    if (path.endsWith('/text-polish/capabilities')) return ok({ models: [model],
      maxInputCharacters: 12000, maxOutputCharacters: 24000, persistentHistory: false });
    if (path.endsWith('/image-prompt-assets') && method === 'POST') return ok({
      id: 'guest-asset', name: 'fixture.png', mimeType: 'image/png', width: 1, height: 1, bytes: tinyPng.length,
      hash: 'fixture', expiresAt: '2099-01-01T00:00:00Z', previewUrl: '/private', processing: ['已清除元数据'],
    });
    if (path.includes('/image-prompt-assets/') && method === 'DELETE') return ok(null);
    const feature: Feature | undefined = path.endsWith('/optimizations') ? 'enhancement'
      : path.endsWith('/image-prompts') ? 'imagePrompt' : path.endsWith('/text-polish') ? 'textPolish' : undefined;
    if (feature && method === 'POST') {
      state.calls[feature]++;
      if (state.forceExhausted || quotas[feature].remaining === 0) {
        quotas[feature].used = quotas[feature].limit; quotas[feature].remaining = 0;
        return route.fulfill({ status: 429, json: { error: { code: 'TRIAL_QUOTA_EXHAUSTED', message: exhaustedMessage, details: {} } } });
      }
      quotas[feature].used++; quotas[feature].remaining--;
      if (feature === 'enhancement') {
        expect(route.request().headers()['x-trial-request-id']).toBeTruthy();
        return ok(optimization());
      }
      const body = route.request().postDataJSON() as { input: ImagePromptInput & PolishInput };
      return ok(feature === 'imagePrompt' ? imagePrompt(body.input) : polished(body.input));
    }
    state.unexpected.push(path);
    return route.fulfill({ status: 501, json: { error: { message: '未登记的试用接口' } } });
  });
  return state;
}

/** 移动端只切换当前工具已有分区，不绕过真实按钮和表单状态。 */
async function toolPane(page: Page, tool: '图像' | '文章', name: string): Promise<void> {
  const navigation = page.getByRole('navigation', { name: `${tool}工作台分区` });
  if (await navigation.isVisible()) await navigation.getByRole('button', { name, exact: true }).click();
}

test('首页三项工具有可点击入口，游客进入各工作台看到独立免费额度', async ({ page }, testInfo) => {
  const state = await setup(page);
  for (const [path, name, quota] of [
    ['/workbench', '开始优化提示词', '剩余 3 / 3 次'],
    ['/image-to-prompt', '图像转提示词', '剩余 1 / 1 次'],
    ['/text-polish', '文章祛AI味', '剩余 3 / 3 次'],
  ]) {
    await page.goto('/');
    await page.locator('.hero-section__actions').getByRole('link', { name, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${path}$`));
    await expect(page.getByText(new RegExp(quota))).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  }
  expect(state.calls).toEqual({ imagePrompt: 0, enhancement: 0, textPolish: 0 });
  expect(state.historyRequests).toHaveLength(0);
  expect(state.unexpected).toHaveLength(0);
  await page.screenshot({ path: testInfo.outputPath('guest-tool-entry.png'), fullPage: true });
});

test('图像免费提取一次后不可再调用，刷新不重置额度且结果仍可复制', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/image-to-prompt');
  await page.locator('input[type=file]').setInputFiles({ name: 'fixture.png', mimeType: 'image/png', buffer: tinyPng });
  await toolPane(page, '图像', '设置');
  await page.locator('.upload-consent').click();
  await page.getByRole('button', { name: '提取参考图提示词', exact: true }).click();
  await toolPane(page, '图像', '结果');
  await expect(page.locator('.prompt-text')).toContainText('蓝色圆形');
  await expect(page.getByRole('button', { name: '复制提示词', exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: '重试保存历史', exact: true })).toHaveCount(0);
  await expect(page.getByText(exhaustedMessage, { exact: true }).first()).toBeVisible();
  await toolPane(page, '图像', '设置');
  await expect(page.getByRole('button', { name: '提取参考图提示词', exact: true })).toBeDisabled();
  expect(state.calls.imagePrompt).toBe(1);
  await page.reload();
  await expect(page.getByText(exhaustedMessage, { exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: '立即注册', exact: true }).click();
  await expect(page).toHaveURL(/\/login\?.*mode=register/);
  await expect(page.getByRole('heading', { name: '创建账号', exact: true })).toBeVisible();
  expect(state.calls.imagePrompt).toBe(1);
  expect(state.historyRequests).toHaveLength(0);
});

test('文章仅可体验三次，重复生成占用额度且不会消耗其他工具额度', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/text-polish');
  await toolPane(page, '文章', '原文');
  await page.getByRole('textbox', { name: '原始文章', exact: true }).fill('这是一段用户主动提交的文章，只调整表达，不添加新事实。');
  for (let index = 0; index < 3; index++) {
    await toolPane(page, '文章', '设置');
    await page.getByRole('button', { name: index ? '基于原文重新生成' : '开始润色', exact: true }).click();
    await expect.poll(() => state.calls.textPolish).toBe(index + 1);
    await toolPane(page, '文章', '结果');
    await expect(page.getByLabel('润色正文', { exact: true })).toContainText('保留原有事实');
  }
  await expect(page.getByText(exhaustedMessage, { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '复制正文', exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: '重试保存历史', exact: true })).toHaveCount(0);
  await toolPane(page, '文章', '设置');
  await expect(page.getByRole('button', { name: '基于原文重新生成', exact: true })).toBeDisabled();
  expect(state.quotas.imagePrompt.remaining).toBe(1);
  expect(state.quotas.enhancement.remaining).toBe(3);
  await page.reload();
  await expect(page.getByText(exhaustedMessage, { exact: true }).first()).toBeVisible();
  expect(state.calls.textPolish).toBe(3);
  expect(state.historyRequests).toHaveLength(0);
});

test('提示词优化仅可体验三次，使用试用接口并在耗尽后保留复制正文', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/workbench');
  for (let index = 0; index < 3; index++) {
    await openWorkbenchPane(page, 'intent');
    await page.getByLabel('原始提示词', { exact: true }).fill(`请写第${index + 1}封会议通知邮件，时间待确认。`);
    await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
    await expect.poll(() => state.calls.enhancement).toBe(index + 1);
    await openWorkbenchPane(page, 'result');
    await expect(page.getByTestId('compact-prompt')).toContainText('会议通知邮件');
  }
  await expect(page.getByText(exhaustedMessage, { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '复制提示词', exact: true })).toBeEnabled();
  await openWorkbenchPane(page, 'intent');
  await expect(page.getByRole('button', { name: '直接增强提示词', exact: true })).toBeDisabled();
  await page.reload();
  await expect(page.getByText(exhaustedMessage, { exact: true }).first()).toBeVisible();
  expect(state.calls.enhancement).toBe(3);
  expect(state.historyRequests).toHaveLength(0);
  expect(state.unexpected).toHaveLength(0);
});

test('访客直接访问三类历史及工具历史入口均引导注册，不请求任何历史数据', async ({ page }) => {
  const state = await setup(page);
  for (const path of ['/history', '/history?type=image-prompt', '/history?type=text-polish',
    '/image-to-prompt?history=1', '/text-polish?history=1',
    '/image-to-prompt?record=other-record', '/text-polish?record=other-record']) {
    await page.goto(path);
    await expect(page).toHaveURL(/\/login\?.*reason=history/);
    await expect(page.getByText(historyMessage, { exact: true })).toBeVisible();
  }
  for (const [path, button] of [['/image-to-prompt', '图像历史'], ['/text-polish', '文章历史']]) {
    await page.goto(path);
    await page.getByRole('button', { name: button, exact: true }).click();
    await expect(page).toHaveURL(/\/login\?.*reason=history/);
    await expect(page.getByText(historyMessage, { exact: true })).toBeVisible();
  }
  expect(state.historyRequests).toHaveLength(0);
  expect(state.calls).toEqual({ imagePrompt: 0, enhancement: 0, textPolish: 0 });
});

test('后端返回额度耗尽时前端更新状态并保留原文，不自动重试生成', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/text-polish');
  await expect(page.getByText(/剩余 3 \/ 3 次/)).toBeVisible();
  state.forceExhausted = true;
  await toolPane(page, '文章', '原文');
  const original = '这段原文必须在额度发生变化时仍然保留。';
  await page.getByRole('textbox', { name: '原始文章', exact: true }).fill(original);
  await toolPane(page, '文章', '设置');
  await page.getByRole('button', { name: '开始润色', exact: true }).click();
  await expect(page.getByText(exhaustedMessage, { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '开始润色', exact: true })).toBeDisabled();
  await toolPane(page, '文章', '原文');
  await expect(page.getByRole('textbox', { name: '原始文章', exact: true })).toHaveValue(original);
  expect(state.calls.textPolish).toBe(1);
  await expect(page).toHaveURL(/\/text-polish$/);
});

test('试用状态服务不可用时禁止生成，不以浏览器默认额度放行', async ({ page }) => {
  const state = await setup(page); state.statusUnavailable = true;
  await page.goto('/text-polish');
  await expect(page.getByText('免费体验暂时不可用，请稍后重试。', { exact: true }).first()).toBeVisible();
  await toolPane(page, '文章', '原文');
  await page.getByRole('textbox', { name: '原始文章', exact: true }).fill('待润色的文章。');
  await toolPane(page, '文章', '设置');
  await expect(page.getByRole('button', { name: '开始润色', exact: true })).toBeDisabled();
  expect(state.calls.textPolish).toBe(0);
});

test('正式登录用户沿用正式生成接口，不受游客额度耗尽或试用服务故障影响', async ({ page }) => {
  const state = await setup(page, true);
  for (const quota of Object.values(state.quotas)) { quota.used = quota.limit; quota.remaining = 0; }
  state.statusUnavailable = true;
  let generated = 0;
  await page.route('**/api/v1/text-polish/capabilities', route => route.fulfill({ json: { data: {
    models: [model], maxInputCharacters: 12000, maxOutputCharacters: 24000, persistentHistory: true,
  } } }));
  await page.route('**/api/v1/text-polish', route => {
    generated++;
    const body = route.request().postDataJSON() as { input: PolishInput };
    return route.fulfill({ json: { data: { ...polished(body.input), historySaved: true, persistentHistory: true } } });
  });
  await page.goto('/text-polish');
  await toolPane(page, '文章', '原文');
  await page.getByRole('textbox', { name: '原始文章', exact: true }).fill('正式用户的原始文章，沿用原有认证及保存流程。');
  await toolPane(page, '文章', '设置');
  await page.getByRole('button', { name: '开始润色', exact: true }).click();
  await toolPane(page, '文章', '结果');
  await expect(page.getByLabel('润色正文', { exact: true })).toContainText('保留原有事实');
  await expect(page.getByText(exhaustedMessage, { exact: true })).toHaveCount(0);
  await expect(page.getByText(/免费体验：剩余/)).toHaveCount(0);
  expect(generated).toBe(1);
  expect(state.trialRequests).toHaveLength(0);
  expect(state.unexpected).toHaveLength(0);
});
