import { expect, test, type Page } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { ImagePromptDocument, ImagePromptInput, SourcePromptAsset } from '../src/types/imagePrompt';

const expiry = '2099-01-01T00:00:00Z';
const model = { id: 'source-fixture', displayName: '受控文字模型', vision: false, maxImages: 1, evidence: '', mock: false };
const body = '请根据所选代码将页面迁移到 React 与 TypeScript，保留接口、校验和权限行为。来源仅为静态代码，未运行项目。';

/** 浏览器工程验收只使用合成资料及受控响应，不上传用户源码或调用付费模型。 */
async function setup(page: Page) {
  const state = { assets: [] as SourcePromptAsset[], inputs: [] as ImagePromptInput[], consents: [] as ImagePromptInput[], textEntries: [] as { path: string; content: string }[], plans: 0, confirms: 0, removed: 0 };
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记的资料测试请求' } } }));
  await mockAuthentication(page);
  await page.addInitScript(() => { Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (value: string) => { sessionStorage.setItem('source-copied', value); } } }); });
  await page.route('**/api/v1/image**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method();
    if (path === '/api/v1/image-prompts/capabilities') return route.fulfill({ json: { data: { models: [model], maxImageBytes: 10485760, maxPixels: 15000000, maxEdge: 8192, maxImages: 3, assetTtlSeconds: 3600, persistentHistory: true } } });
    if (path === '/api/v1/image-prompt-media/capabilities') return route.fulfill({ json: { data: { maxAssets: 6, maxFileBytes: 52428800, maxSelectedUnits: 12, maxVideoDurationMs: 30000, maxSlides: 100, videoAvailable: true } } });
    if (path === '/api/v1/image-prompt-sources/capabilities') return route.fulfill({ json: { data: { maxAssets: 20, maxFileBytes: 52428800, maxSelectedUnits: 40, maxSelectedCharacters: 120000, maxEntries: 5000, extensions: ['txt', 'md', 'vue', 'ts', 'json', 'html', 'pdf', 'docx'] } } });
    if (path === '/api/v1/image-prompt-sources/consents') {
      state.consents.push(route.request().postDataJSON());
      return route.fulfill({ json: { data: { id: 'consent-fixture', expiresAt: expiry } } });
    }
    if (path === '/api/v1/image-prompt-sources/text' || path === '/api/v1/image-prompt-sources' && method === 'POST') {
      const textRequest = path.endsWith('/text') ? route.request().postDataJSON() as { name: string; entries: { path: string; content: string }[] } : undefined;
      if (textRequest) state.textEntries = textRequest.entries;
      const asset: SourcePromptAsset = { id: `source-${state.assets.length + 1}`, name: textRequest?.name ?? 'OrderPanel.vue', kind: textRequest?.entries.length === 1 ? 'TEXT' : 'COLLECTION', status: 'READY', warnings: ['仅静态资料，不证明运行行为。'], expiresAt: expiry, totalUnits: 2,
        units: [{ id: 'unit-1', path: textRequest?.entries[0]?.path ?? 'src/OrderPanel.vue', label: '字符 1–60', text: textRequest?.entries[0]?.content ?? 'export const endpoint = "/api/orders";' },
          { id: 'unit-2', path: 'src/unselected.ts', label: '字符 61–100', text: '未勾选的内容不得参与本次模型输入。' }] };
      state.assets.push(asset); return route.fulfill({ json: { data: asset } });
    }
    if (path.startsWith('/api/v1/image-prompt-sources/') && method === 'DELETE') { state.removed++; return route.fulfill({ json: { data: null } }); }
    const makeDocument = (input: ImagePromptInput): ImagePromptDocument => ({ operationId: 'source-operation', recordId: 'source-record', mode: input.mode, optimizedPrompt: body, input, analysis: [], sources: [], warnings: [], questions: [], answers: [], modelId: model.id, modelName: model.displayName, mock: false, editRevision: 0, historySaved: true, persistentHistory: true, createdAt: '2026-10-29T00:00:00Z', sourceSources: input.sourceTask?.sources.map(ref => { const asset = state.assets.find(asset => asset.id === ref.assetId)!; return { assetId: asset.id, name: asset.name, kind: asset.kind, track: ref.track, role: ref.role, units: asset.units.filter(unit => ref.unitIds.includes(unit.id)), warnings: asset.warnings }; }) });
    if (path === '/api/v1/image-prompts/plans') {
      state.plans++; const input = route.request().postDataJSON().input as ImagePromptInput; state.inputs.push(input);
      return route.fulfill({ json: { data: { id: 'source-plan', fingerprint: 'source-binding', summary: '保留接口并确认技术迁移范围', questions: [{ id: 'boundary', type: 'TEXT', question: '哪些交互必须保留？', required: true, options: [] }], analysis: [], warnings: [], expiresAt: expiry, mock: false, sourceSources: makeDocument(input).sourceSources } } });
    }
    if (path.endsWith('/confirm')) { state.confirms++; return route.fulfill({ json: { data: makeDocument(state.inputs.at(-1)!) } }); }
    if (path === '/api/v1/image-prompts' && method === 'POST') { const input = route.request().postDataJSON().input as ImagePromptInput; state.inputs.push(input); return route.fulfill({ json: { data: makeDocument(input) } }); }
    if (path.endsWith('/history')) return route.fulfill({ json: { data: { records: [], total: 0, size: 10, current: 1, pages: 0 } } });
    return route.fulfill({ status: 501, json: { error: { message: `未登记的资料测试请求：${path}` } } });
  });
  await page.goto('/image-to-prompt');
  await page.getByTestId('source-task-type').click(); await page.getByRole('option', { name: '代码与页面', exact: true }).click();
  return state;
}

async function pane(page: Page, name: string) {
  const navigation = page.getByRole('navigation', { name: '图像工作台分区' });
  if (await navigation.isVisible()) await navigation.getByRole('button', { name, exact: true }).click();
}

async function pasteAndReview(page: Page) {
  await pane(page, '素材'); await page.getByRole('button', { name: '粘贴内容', exact: true }).click();
  const paste = page.getByRole('dialog', { name: '添加文本资料' });
  await paste.getByRole('textbox', { name: '文本资料名称' }).fill('OrderPanel.vue');
  await paste.getByRole('textbox', { name: '粘贴资料内容' }).fill('const submit = () => fetch("/api/orders");');
  await paste.getByRole('button', { name: '加入资料草稿' }).click();
  await page.getByRole('button', { name: '解析并预览', exact: true }).click();
  await page.getByRole('button', { name: '审阅与选择范围', exact: true }).click();
  const review = page.getByRole('dialog', { name: '审阅资料与选择使用范围' });
  await expect(review.getByRole('button', { name: '保存使用范围' })).toBeDisabled();
  await expect(review.getByRole('checkbox').first()).not.toBeChecked();
  await review.locator('.el-checkbox').first().click();
  await review.getByRole('button', { name: '保存使用范围' }).click();
}

test('通用资料需独立审阅和隐私同意，直接生成只引用所选片段且成功复制', async ({ page }) => {
  const state = await setup(page); await pasteAndReview(page);
  expect(state.inputs).toHaveLength(0); expect(state.consents).toHaveLength(0);
  await pane(page, '设置'); await page.getByRole('textbox', { name: '本次任务目标', exact: true }).fill('迁移到React，保留接口、权限和交互。');
  const generate = page.getByRole('button', { name: '生成优化提示词', exact: true });
  await expect(generate).toBeDisabled(); await page.locator('.consent-panel .el-checkbox').click();
  await generate.click(); await expect(page.locator('.prompt-text')).toHaveText(body);
  expect(state.plans).toBe(0); expect(state.consents).toHaveLength(1); expect(state.inputs).toHaveLength(1);
  expect(state.inputs[0]!.sourceTask).toMatchObject({ taskType: 'CODE', consentId: 'consent-fixture', sources: [{ assetId: 'source-1', unitIds: ['unit-1'] }] });
  await expect(page.getByLabel('资料交接清单')).toContainText('OrderPanel.vue');
  await expect(page.getByLabel('资料交接清单')).not.toContainText('unselected.ts');
  await page.getByRole('button', { name: '复制提示词', exact: true }).click();
  await expect.poll(() => page.evaluate(() => sessionStorage.getItem('source-copied'))).toBe(body);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});

test('通用Plan保留资料范围，必答后确认，切换Plan不重复解析', async ({ page }) => {
  const state = await setup(page); await pasteAndReview(page); await pane(page, '设置');
  await page.getByRole('textbox', { name: '本次任务目标', exact: true }).fill('参考现有源码迁移页面，目标技术栈React。');
  await page.locator('.column-heading .el-switch').click();
  await page.locator('.consent-panel .el-checkbox').click();
  await page.getByRole('button', { name: '梳理关键要求', exact: true }).click();
  const panel = page.getByTestId('image-plan-panel'); await expect(panel).toBeVisible();
  await panel.getByRole('button', { name: '确认并生成代码任务提示词', exact: true }).click(); expect(state.confirms).toBe(0);
  await panel.getByRole('textbox', { name: '哪些交互必须保留？的补充回答' }).fill('保留权限显示、提交前校验和失败重试。');
  await panel.getByRole('button', { name: '确认并生成代码任务提示词', exact: true }).click();
  await expect(page.locator('.prompt-text')).toHaveText(body); expect(state.confirms).toBe(1); expect(state.assets).toHaveLength(1);
});

test('资料审阅在深色窄屏与桌面均完整显示且预览不自动选中', async ({ page }, testInfo) => {
  await setup(page); await page.evaluate(() => document.documentElement.setAttribute('data-ui-theme', 'glass-dark'));
  await pane(page, '素材'); await page.getByRole('button', { name: '粘贴内容', exact: true }).click();
  await page.getByRole('textbox', { name: '粘贴资料内容' }).fill('主题配置：蓝色按钮，保留完整文字和键盘操作。');
  await page.getByRole('button', { name: '加入资料草稿' }).click();
  await page.getByRole('button', { name: '解析并预览', exact: true }).click(); await page.getByRole('button', { name: '审阅与选择范围', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '审阅资料与选择使用范围' });
  await dialog.getByRole('button', { name: '预览内容', exact: true }).first().click();
  await expect(dialog.locator('pre')).toContainText('主题配置');
  await expect(dialog.getByRole('checkbox').first()).not.toBeChecked(); await expect(dialog.getByRole('button', { name: '保存使用范围' })).toBeDisabled();
  const bounds = await dialog.boundingBox(); expect(bounds!.x).toBeGreaterThanOrEqual(0); expect(bounds!.width).toBeLessThanOrEqual((page.viewportSize()?.width ?? 0));
  expect(bounds!.y).toBeGreaterThanOrEqual(0); expect(bounds!.y + bounds!.height).toBeLessThanOrEqual((page.viewportSize()?.height ?? 0) + 1);
  await expect(dialog.getByRole('heading', { name: '审阅资料与选择使用范围', exact: true })).toBeInViewport();
  await expect(dialog.getByRole('button', { name: '保存使用范围', exact: true })).toBeInViewport();
  await page.screenshot({ path: testInfo.outputPath('source-review-dark.png'), fullPage: false });
});

test('代码文件上传后仍需选片段，取消资料会撤销临时资产', async ({ page }) => {
  const state = await setup(page); await pane(page, '素材');
  await page.getByTestId('source-materials').locator('input[type=file]').first().setInputFiles({ name: 'OrderPanel.vue', mimeType: 'text/plain', buffer: Buffer.from('const submit = () => fetch("/api/orders");') });
  expect(state.assets).toHaveLength(0);
  await page.getByRole('button', { name: '解析并预览', exact: true }).click();
  await expect(page.getByRole('button', { name: '审阅与选择范围', exact: true })).toBeVisible();
  await expect(page.getByTestId('source-materials')).toContainText('已选0个');
  await page.getByRole('button', { name: '移除资料', exact: true }).click();
  await expect.poll(() => state.removed).toBe(1); expect(state.inputs).toHaveLength(0);
});

test('文件夹先筛选保护文件，再仅上传主动勾选文件', async ({ page }, testInfo) => {
  const state = await setup(page); await pane(page, '素材');
  const directory = testInfo.outputPath('synthetic-project'); await mkdir(join(directory, 'src'), { recursive: true });
  await writeFile(join(directory, 'src', 'OrderPanel.vue'), 'const save = () => fetch("/api/orders");');
  await writeFile(join(directory, '.env'), 'SYNTHETIC_SECRET=blocked-fixture');
  await page.getByLabel('选择资料文件夹', { exact: true }).setInputFiles(directory);
  const dialog = page.getByRole('dialog', { name: '选择文件夹中的资料范围' });
  await expect(dialog).toContainText('发现 2 个文件，其中 1 个可选');
  await expect(dialog.getByRole('button', { name: '加入所选文件', exact: true })).toBeDisabled();
  await dialog.locator('.el-checkbox').click(); await dialog.getByRole('button', { name: '加入所选文件', exact: true }).click();
  expect(state.textEntries).toHaveLength(0);
  await page.getByRole('button', { name: '解析并预览', exact: true }).click();
  await expect.poll(() => state.textEntries.length).toBe(1);
  expect(state.textEntries[0]!.path).toContain('src/OrderPanel.vue');
  expect(JSON.stringify(state.textEntries)).not.toContain('SYNTHETIC_SECRET'); expect(state.inputs).toHaveLength(0);
});
