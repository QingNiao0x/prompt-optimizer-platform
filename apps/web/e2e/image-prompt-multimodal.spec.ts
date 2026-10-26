import { expect, test, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { mockAuthentication } from './authFixture';
import type { ImagePromptAnswer, ImagePromptDocument, ImagePromptInput, ImagePromptMask, ImagePromptMediaAsset, ImagePromptPlan, ImagePromptQuestion } from '../src/types/imagePrompt';

const expiry = '2099-01-01T00:00:00Z';
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6o0MAAAAASUVORK5CYII=', 'base64');
const model = { id: 'multimodal-fixture', displayName: '受控多模态模型', vision: true, maxImages: 1, evidence: '受控测试', mock: false };
const initial = (): ImagePromptInput => ({ mode: 'CUSTOM_CREATION', modelId: model.id, images: [], creationIntent: '', exactText: '', context: '', language: 'ZH', targetTool: '通用文生图', outputType: 'IMAGE', materials: [], relations: [] });
const makeDocument = (input: ImagePromptInput): ImagePromptDocument => ({ operationId: 'op', recordId: 'record', mode: input.mode,
  input, optimizedPrompt: '按用户确认关系创作全新内容，保留参考配色，使用新主体。', analysis: [], sources: [], questions: [], answers: [], warnings: [],
  modelId: model.id, modelName: model.displayName, mock: false, editRevision: 0, historySaved: true, persistentHistory: true, createdAt: '2026-10-26T00:00:00Z' });

/** 所有请求在浏览器边界拦截，既不创建账号，也不外发真实图片或付费调用。 */
async function setup(page: Page, questions: ImagePromptQuestion[]) {
  const state = { plans: 0, confirmations: 0, uploads: 0, metadata: 0, input: initial(), answers: [] as ImagePromptAnswer[], assetNames: new Map<string, string>(), exportedMasks: [] as ImagePromptMask[] };
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记的多模态测试请求' } } }));
  await mockAuthentication(page);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [{ ...model, provider: 'fixture', defaultModel: true }] } }));
  await page.route('**/api/v1/image**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method();
    if (path === '/api/v1/image-prompt-media/capabilities') return route.fulfill({ json: { data: { maxAssets: 6, maxFileBytes: 52428800, maxSelectedUnits: 12, maxVideoDurationMs: 30000, maxSlides: 100, videoAvailable: true } } });
    if (path.endsWith('/capabilities')) return route.fulfill({ json: { data: { models: [model], maxImageBytes: 10485760, maxPixels: 15000000, maxEdge: 8192, maxImages: 3, assetTtlSeconds: 3600, persistentHistory: true } } });
    if (path === '/api/v1/image-prompt-media' && method === 'POST') {
      state.uploads++;
      const id = `media-${state.uploads}`;
      state.assetNames.set(id, state.uploads === 1 ? 'brand.pptx' : 'subject.png');
      return route.fulfill({ json: { data: media(id, 'PROCESSING') } });
    }
    if (/^\/api\/v1\/image-prompt-media\/[^/]+\/units\/[^/]+\/export$/.test(path)) {
      state.exportedMasks = route.request().postDataJSON().masks;
      return route.fulfill({ contentType: 'image/png', headers: { 'Content-Disposition': 'attachment; filename=reviewed.png' }, body: png });
    }
    const unitPath = path.match(/^\/api\/v1\/image-prompt-media\/([^/]+)\/units\/([^/]+)$/);
    if (unitPath) return route.fulfill({ contentType: 'image/png', body: png });
    const assetPath = path.match(/^\/api\/v1\/image-prompt-media\/([^/]+)$/);
    if (assetPath) {
      if (method === 'DELETE') return route.fulfill({ json: { data: null } });
      state.metadata++;
      return route.fulfill({ json: { data: media(assetPath[1]!, 'READY') } });
    }
    if (path.endsWith('/plans')) {
      state.plans++; state.input = route.request().postDataJSON().input;
      const plan: ImagePromptPlan = { id: `plan-${state.plans}`, fingerprint: 'binding', summary: '依照用户构想与明确关系创作', questions,
        analysis: [], warnings: [], expiresAt: expiry, mock: false };
      return route.fulfill({ json: { data: plan } });
    }
    if (path.endsWith('/confirm')) {
      state.confirmations++; state.answers = route.request().postDataJSON().answers;
      return route.fulfill({ json: { data: makeDocument(state.input) } });
    }
    if (path.endsWith('/history')) return route.fulfill({ json: { data: { records: [], total: 0, current: 1, size: 10, pages: 0 } } });
    return route.fulfill({ status: 501, json: { error: { message: '未登记的图像请求' } } });
  });
  function media(id: string, status: ImagePromptMediaAsset['status']): ImagePromptMediaAsset {
    const presentation = id === 'media-1';
    return { id, name: state.assetNames.get(id) ?? 'fixture', kind: presentation ? 'PPTX' : 'IMAGE', status, warnings: [], expiresAt: expiry,
      totalUnits: presentation ? 2 : 1, durationMs: null, units: status === 'PROCESSING' ? [] : (presentation ? [1, 2] : [1]).map(index => ({
        id: `${id}-unit-${index}`, page: presentation ? index : null, timestampMs: null, width: 900, height: 600,
        text: presentation ? `第${index}页品牌文案` : '', previewUrl: `/api/v1/image-prompt-media/${id}/units/${id}-unit-${index}`,
      })) };
  }
  await page.goto('/image-to-prompt');
  await expect(page.getByRole('heading', { name: '图像转提示词', exact: true })).toBeVisible();
  await pane(page, '设置');
  await page.locator('.column-heading .el-switch').click();
  return state;
}

async function pane(page: Page, name: '图片' | '设置' | '结果') {
  const nav = page.getByRole('navigation', { name: '图像工作台分区' });
  if (await nav.isVisible()) await nav.getByRole('button', { name: new RegExp(`^${name}`) }).click();
}
async function select(page: Page, label: string, option: string) {
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: label, exact: true }) }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}

test('Plan顶部定位与必答进度保留单选自填、多选和选答规则', async ({ page }, testInfo) => {
  // 1280px会触发当前顶栏双行排版，不能只验证固定90px恰好够用的宽桌面。
  if (!testInfo.project.name.startsWith('mobile')) await page.setViewportSize({ width: 1280, height: 720 });
  const belowNavigation = async (target: Locator): Promise<void> => {
    await expect.poll(() => target.evaluate(element => element.getBoundingClientRect().top
      - (document.querySelector('header.topbar')?.getBoundingClientRect().bottom ?? 0))).toBeGreaterThanOrEqual(0);
  };
  const questions: ImagePromptQuestion[] = [
    { id: 'style', type: 'SINGLE_CHOICE', question: '画面采用什么风格？', options: [{ id: 'a', label: '插画' }, { id: 'b', label: '摄影' }], required: true },
    { id: 'elements', type: 'MULTIPLE_CHOICE', question: '保留哪些元素？', options: [{ id: 'color', label: '配色' }, { id: 'layout', label: '构图' }], required: true },
    { id: 'optional', type: 'TEXT', question: '其他偏好？', options: [], required: false },
  ];
  const state = await setup(page, questions);
  await expect(page.getByTestId('creative-supplement-card')).toBeVisible();
  await page.getByRole('textbox', { name: '新图创作构想' }).fill('为读书会创作海报，背景与主体细节由 AI 设计。');
  await page.getByRole('textbox', { name: '画面指定文案' }).fill('秋日读书会');
  await page.getByRole('textbox', { name: '图像项目上下文' }).fill('品牌背景：社区公益活动。');
  expect(state.plans).toBe(0);
  await page.getByRole('button', { name: '梳理创作意图', exact: true }).click();
  const panel = page.getByTestId('image-plan-panel');
  await expect(panel.getByRole('heading', { name: '请确认创作细节' })).toBeFocused();
  await belowNavigation(panel.getByRole('heading', { name: '请确认创作细节' }));
  await expect(panel).toContainText('还有 2 项必答');
  await expect(panel.locator('input:checked')).toHaveCount(0);
  const panelBox = await panel.boundingBox(), formBox = await page.getByTestId('creative-supplement-card').boundingBox();
  expect(panelBox!.y).toBeLessThan(formBox!.y);
  await panel.getByRole('button', { name: '确认并生成新图提示词' }).click();
  expect(state.confirmations).toBe(0);
  await expect(panel.locator('fieldset').first()).toBeFocused();
  await belowNavigation(panel.locator('fieldset').first());
  await panel.getByRole('button', { name: '返回修改创作要求' }).click();
  await expect(page.getByRole('heading', { name: '自主补充创作要求' })).toBeFocused();
  await belowNavigation(page.getByRole('heading', { name: '自主补充创作要求' }));
  await panel.getByRole('button', { name: '定位未答问题' }).click();
  await expect(panel.locator('fieldset').first()).toBeFocused();
  await belowNavigation(panel.locator('fieldset').first());
  // 选择题也可只填写自定义答案，不能因UI改版被强制选择一个选项。
  const custom = panel.getByRole('textbox', { name: '画面采用什么风格？的补充回答' });
  await custom.fill('保留手绘纸张质感');
  await expect(custom).toBeFocused();
  await expect(panel).toContainText('还有 1 项必答');
  await panel.locator('.el-checkbox').filter({ hasText: /^配色$/ }).click();
  await panel.locator('.el-checkbox').filter({ hasText: /^构图$/ }).click();
  await expect(panel).toContainText('必答项已完成');
  await expect(page.locator('.image-workbench > .el-alert--error')).not.toBeVisible();
  if (process.env.PROMPT_OPTIMIZER_CAPTURE_PLAN_VIEWPORT === 'true') {
    // 视口证据保留固定导航的真实位置，不以整页拼接长图代替手机可读性。
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.evaluate(() => document.fonts.ready.then(() => undefined));
    // 通过真实产品导航回到Plan，截图不另行计算定位或覆盖产品滚动行为。
    await panel.getByRole('button', { name: '回到问题顶部' }).click();
    await expect(panel.getByRole('heading', { name: '请确认创作细节' })).toBeFocused();
    await belowNavigation(panel.getByRole('heading', { name: '请确认创作细节' }));
    await expect(panel).toContainText('待确认');
    await expect.poll(() => panel.evaluate(element => Math.abs(element.getBoundingClientRect().top
      - (document.querySelector('header.topbar')?.getBoundingClientRect().bottom ?? 0) - 16))).toBeLessThan(2);
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    const directory = new URL('../../../docs/testing/evidence/image-prompt-multimodal-2026-10-26/', import.meta.url);
    await mkdir(directory, { recursive: true });
    const filename = testInfo.project.name.startsWith('mobile') ? 'plan-mobile-viewport.png' : 'plan-desktop-viewport.png';
    await page.screenshot({ path: fileURLToPath(new URL(filename, directory)), fullPage: false, animations: 'disabled' });
  }
  await page.screenshot({ path: testInfo.outputPath('plan-highlight-progress.png'), fullPage: true, animations: 'disabled' });
  await panel.getByRole('button', { name: '确认并生成新图提示词' }).click();
  await expect(page.locator('.prompt-text')).toContainText('全新内容');
  expect(state.confirmations).toBe(1);
  expect(state.answers.find(answer => answer.questionId === 'style')).toEqual({ questionId: 'style', optionIds: [], customText: '保留手绘纸张质感' });
  expect(state.answers.find(answer => answer.questionId === 'elements')?.optionIds).toEqual(['color', 'layout']);
  expect(state.input.exactText).toBe('秋日读书会');
  expect(state.input.context).toBe('品牌背景：社区公益活动。');
  await expect(panel).not.toBeVisible();
});

test('全选答Plan直接确认且修改自主要求后旧计划失效', async ({ page }) => {
  const state = await setup(page, [{ id: 'optional', type: 'TEXT', question: '还有其他偏好吗？', options: [], required: false }]);
  const concept = page.getByRole('textbox', { name: '新图创作构想' });
  await concept.fill('创作海报，所有设计参数委托 AI。');
  await page.getByRole('button', { name: '梳理创作意图', exact: true }).click();
  await expect(page.getByTestId('image-plan-panel')).toContainText('可补充偏好，也可直接确认生成');
  await expect(page.getByTestId('image-plan-panel')).not.toContainText('还有 1 项必答');
  await concept.fill('创作海报，所有设计参数委托 AI，使用蓝色。');
  await expect(page.getByTestId('image-plan-panel')).not.toBeVisible();
  expect(state.plans).toBe(1); expect(state.confirmations).toBe(0);
  await page.getByRole('button', { name: '梳理创作意图', exact: true }).click();
  await page.getByRole('button', { name: '确认并生成新图提示词' }).click();
  expect(state.confirmations).toBe(1);
});

test('暗色与减少动画下八题长选项保持可见且没有横向溢出', async ({ page }, testInfo) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const state = await setup(page, Array.from({ length: 8 }, (_, index) => ({ id: `q${index}`, type: 'SINGLE_CHOICE',
    question: `第${index + 1}项创作偏好`, required: false, options: [{ id: 'a', label: '保留参考素材中经过明确确认的配色与构图，同时使用用户提供的新主体，其他细节由下游合理设计。'.repeat(3) }, { id: 'b', label: '由 AI 设计' }] })));
  await page.evaluate(() => document.documentElement.setAttribute('data-ui-theme', 'glass-dark'));
  await page.getByRole('textbox', { name: '新图创作构想' }).fill('完整创作要求，全部偏好委托设计。');
  await page.getByRole('button', { name: '梳理创作意图', exact: true }).click();
  const panel = page.getByTestId('image-plan-panel');
  await expect(panel.locator('fieldset')).toHaveCount(8);
  expect(await panel.evaluate(element => getComputedStyle(element).borderTopWidth)).toBe('2px');
  expect(await panel.evaluate(element => getComputedStyle(element).animationName)).toBe('none');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await panel.screenshot({ path: testInfo.outputPath('plan-dark-long-options.png'), animations: 'disabled' });
  expect(state.confirmations).toBe(0);
});

test('双轨素材：先解析审阅再外发，PPT页与遮挡及替换关系进入视频创作输入', async ({ page }, testInfo) => {
  const state = await setup(page, []);
  await page.getByRole('textbox', { name: '新图创作构想' }).fill('创作产品视频，参考品牌幻灯片的配色，主体替换为新产品。');
  await select(page, '目标成品类型', '视频');
  await pane(page, '图片');
  const reference = page.getByTestId('reference-media'), target = page.getByTestId('replacement-media');
  await reference.locator('input[type=file]').setInputFiles({ name: 'brand.pptx', mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', buffer: Buffer.from('controlled presentation upload') });
  await target.locator('input[type=file]').setInputFiles({ name: 'subject.png', mimeType: 'image/png', buffer: png });
  expect(state.uploads).toBe(0); expect(state.plans).toBe(0);
  for (const section of [reference, target]) {
    await section.getByRole('button', { name: '上传到平台解析预览' }).click();
    await expect(section.getByRole('button', { name: '选择页／帧与遮挡' })).toBeVisible();
    expect(state.plans).toBe(0);
    await section.getByRole('button', { name: '选择页／帧与遮挡' }).click();
    const dialog = page.getByRole('dialog', { name: '审阅素材与选择识别范围' });
    await dialog.locator('.el-checkbox').filter({ hasText: section === reference ? /^第 2 页$/ : /^图片$/ }).click();
    if (section === reference) await select(page, '预览素材单位', '第 2 页');
    await expect(dialog.locator('.mask-surface img')).toBeVisible();
    await dialog.getByRole('button', { name: '遮挡整页／帧' }).click();
    if (section === reference) {
      const download = page.waitForEvent('download');
      await dialog.getByRole('button', { name: /下载供下游使用的.*PNG/ }).click();
      await download;
      expect(state.exportedMasks).toEqual([{ x: 0, y: 0, width: 1, height: 1 }]);
      expect(state.plans).toBe(0);
    }
    await dialog.getByRole('button', { name: '保存选择与遮挡' }).click();
  }
  await pane(page, '设置');
  await page.getByRole('button', { name: '添加保留／替换关系' }).click();
  await select(page, '关系1参考素材', 'R1 · brand.pptx');
  await select(page, '关系1参考页帧', '第 2 页');
  await page.getByRole('textbox', { name: '关系1参考对象', exact: true }).fill('中央产品');
  await select(page, '关系1替换素材', 'T1 · subject.png');
  await page.getByRole('textbox', { name: '关系1新内容', exact: true }).fill('新产品的外形与颜色');
  await page.getByRole('textbox', { name: '关系1作用范围', exact: true }).fill('第一个镜头');
  await page.getByRole('textbox', { name: '关系1具体要求', exact: true }).fill('保留配色与构图，将原产品换成新产品。');
  await expect(page.getByRole('button', { name: '梳理创作意图', exact: true })).toBeDisabled();
  await page.locator('.upload-consent').click();
  await page.getByRole('button', { name: '梳理创作意图', exact: true }).click();
  await expect(page.getByTestId('image-plan-panel')).toBeVisible();
  expect(state.uploads).toBe(2); expect(state.input.outputType).toBe('VIDEO');
  expect(state.input.targetTool).toBe('通用视频创作工具');
  expect(state.input.materials?.map(item => item.track)).toEqual(['REFERENCE', 'REPLACEMENT']);
  expect(state.input.materials?.[0]?.units).toEqual([{ unitId: 'media-1-unit-2', masks: [{ x: 0, y: 0, width: 1, height: 1 }] }]);
  expect(state.input.relations?.[0]).toMatchObject({ operation: 'REPLACE', referenceAssetId: 'media-1', referenceUnitId: 'media-1-unit-2', replacementAssetId: 'media-2', scope: '第一个镜头' });
  await page.getByRole('button', { name: '确认并生成视频提示词' }).click();
  await expect(page.locator('.prompt-text')).toContainText('全新内容');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('multimodal-video-result.png'), fullPage: true, animations: 'disabled' });
});

test('素材审阅不被三栏裁切，长图横图完整缩放且鼠标或触摸遮挡坐标一致', async ({ page }, testInfo) => {
  if (testInfo.project.name.startsWith('mobile')) await page.setViewportSize({ width: 375, height: 667 });
  const state = await setup(page, []);
  // 真实像素尺寸能暴露1×1占位图漏掉的高度、裁切和SVG坐标问题，不访问用户素材。
  const previews = await page.evaluate(() => [
    { width: 1200, height: 1800 }, { width: 1800, height: 900 },
  ].map(({ width, height }) => {
    const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height;
    const drawing = canvas.getContext('2d')!; drawing.fillStyle = '#dce8f5'; drawing.fillRect(0, 0, width, height);
    drawing.fillStyle = '#24466d'; drawing.fillRect(0, 0, 100, 100); drawing.fillRect(width - 100, height - 100, 100, 100);
    return canvas.toDataURL('image/png').split(',')[1]!;
  }));
  await page.route('**/api/v1/image-prompt-media/*/units/*', async route => {
    if (route.request().url().endsWith('/export')) return route.fallback();
    const second = route.request().url().endsWith('unit-2');
    return route.fulfill({ contentType: 'image/png', body: Buffer.from(previews[second ? 1 : 0]!, 'base64') });
  });
  await page.getByRole('textbox', { name: '新图创作构想' }).fill('参考选定页面构图，创作全新内容。');
  await pane(page, '图片');
  const reference = page.getByTestId('reference-media');
  await reference.locator('input[type=file]').setInputFiles({ name: 'brand.pptx', mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', buffer: Buffer.from('controlled upload') });
  await reference.getByRole('button', { name: '上传到平台解析预览' }).click();
  await reference.getByRole('button', { name: '选择页／帧与遮挡' }).click();
  const dialog = page.getByRole('dialog', { name: '审阅素材与选择识别范围' });
  const image = dialog.locator('.mask-surface img'), surface = dialog.getByLabel('拖动遮挡素材敏感区域');
  for (const [index, expectedRatio] of [2 / 3, 2].entries()) {
    if (index) await select(page, '预览素材单位', '第 2 页');
    await expect.poll(() => image.evaluate(element => element instanceof HTMLImageElement && element.complete && element.naturalWidth > 0)).toBe(true);
    await image.scrollIntoViewIfNeeded();
    await expect.poll(() => image.evaluate(element => {
      const bounds = element.getBoundingClientRect(); return bounds.width / bounds.height;
    })).toBeCloseTo(expectedRatio, 2);
    // 仅isVisible不会检查玻璃栏裁切或被相邻栏覆盖；四角命中测试检查真正可见且可操作的画面。
    await expect.poll(() => surface.evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return [[.02, .02], [.98, .02], [.02, .98], [.98, .98]].every(([x, y]) => {
        const target = document.elementFromPoint(bounds.left + bounds.width * x!, bounds.top + bounds.height * y!);
        return target !== null && element.contains(target);
      });
    })).toBe(true);
    const bounds = await surface.boundingBox(); expect(bounds).not.toBeNull();
    const imageBounds = await image.boundingBox(); expect(imageBounds).not.toBeNull();
    const footer = await dialog.getByRole('button', { name: '保存选择与遮挡' }).boundingBox();
    expect(footer!.y).toBeGreaterThanOrEqual(0);
    expect(footer!.y + footer!.height).toBeLessThanOrEqual(page.viewportSize()!.height);
    expect(Math.abs(bounds!.width - imageBounds!.width)).toBeLessThan(1);
    expect(Math.abs(bounds!.height - imageBounds!.height)).toBeLessThan(1);
    const start = { x: bounds!.x + bounds!.width * .1, y: bounds!.y + bounds!.height * .2 };
    const end = { x: bounds!.x + bounds!.width * .4, y: bounds!.y + bounds!.height * .5 };
    if (testInfo.project.name.startsWith('mobile')) {
      const session = await page.context().newCDPSession(page);
      await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [start] });
      await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [end] });
      await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await session.detach();
    } else { await page.mouse.move(start.x, start.y); await page.mouse.down(); await page.mouse.move(end.x, end.y, { steps: 4 }); await page.mouse.up(); }
    const mask = surface.locator('rect').first(); await expect(mask).toBeVisible();
    for (const [name, expected] of Object.entries({ x: .1, y: .2, width: .3, height: .3 })) expect(Number(await mask.getAttribute(name))).toBeCloseTo(expected, 2);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`media-review-${index ? 'landscape' : 'portrait'}.png`), animations: 'disabled' });
  }
  await dialog.getByRole('button', { name: '取消修改' }).click();
  expect(state.plans).toBe(0); expect(state.confirmations).toBe(0);
});

test('视频空结果可刷新为PARTIAL，PPT就绪后明确保存范围才解除Plan禁用', async ({ page }) => {
  const state = await setup(page, []);
  let uploads = 0, videoQueries = 0;
  const material = (id: string, status: ImagePromptMediaAsset['status'], empty = false): ImagePromptMediaAsset => {
    const video = id === 'range-1';
    return { id, name: video ? 'clip.mp4' : 'changes.pptx', kind: video ? 'VIDEO' : 'PPTX', status, expiresAt: expiry,
      warnings: status === 'PARTIAL' ? ['仅覆盖两个采样帧，其他范围未识别。'] : [], totalUnits: video ? 4 : 2, durationMs: video ? 5000 : null,
      units: empty ? [] : [1, 2].map(index => ({ id: `${id}-unit-${index}`, page: video ? null : index,
        timestampMs: video ? index * 1000 : null, width: 900, height: 600, text: '', previewUrl: `/api/v1/image-prompt-media/${id}/units/${id}-unit-${index}` })) };
  };
  await page.route('**/api/v1/image-prompt-media', route => {
    uploads++;
    return route.fulfill({ json: { data: material(`range-${uploads}`, 'PROCESSING', true) } });
  });
  await page.route('**/api/v1/image-prompt-media/range-*', route => {
    const id = new URL(route.request().url()).pathname.split('/').pop()!;
    const video = id === 'range-1';
    if (video) videoQueries++;
    return route.fulfill({ json: { data: material(id, video && videoQueries > 1 ? 'PARTIAL' : 'READY', video && videoQueries === 1) } });
  });
  await page.getByRole('textbox', { name: '新图创作构想' }).fill('保留视频配色，使用幻灯片中的新内容。');
  const start = page.getByRole('button', { name: '梳理创作意图', exact: true });
  for (const [track, name, mime, unitLabel] of [
    ['reference-media', 'clip.mp4', 'video/mp4', '1.00 秒'],
    ['replacement-media', 'changes.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', '第 2 页'],
  ] as const) {
    await pane(page, '图片');
    const section = page.getByTestId(track);
    await section.locator('input[type=file]').setInputFiles({ name, mimeType: mime, buffer: Buffer.from('controlled media') });
    await section.getByRole('button', { name: '上传到平台解析预览' }).click();
    if (track === 'reference-media') {
      await expect(section).toContainText('未解析出可选画面');
      await expect(section.getByRole('button', { name: '选择页／帧与遮挡' })).toHaveCount(0);
      await section.getByRole('button', { name: '刷新解析状态' }).click();
      expect(videoQueries).toBe(2);
    }
    await expect(section).toContainText('可选 2 个图片／页／帧，已选 0 个');
    await pane(page, '设置');
    await expect(start).toBeDisabled();
    await expect(page.locator('.start-disabled-reason')).toContainText(`素材“${name}”已解析出 2 个`);
    await pane(page, '图片');
    await section.getByRole('button', { name: '选择页／帧与遮挡' }).click();
    const dialog = page.getByRole('dialog', { name: '审阅素材与选择识别范围' });
    await dialog.locator('.el-checkbox').filter({ hasText: unitLabel }).click();
    await dialog.getByRole('button', { name: '保存选择与遮挡' }).click();
    await expect(section).toContainText('可选 2 个图片／页／帧，已选 1 个');
  }
  await pane(page, '设置');
  await expect(start).toBeDisabled();
  await expect(page.locator('.start-disabled-reason')).toContainText('请先勾选');
  await page.locator('.upload-consent').click();
  await expect(start).toBeEnabled();
  await start.click();
  await expect(page.getByTestId('image-plan-panel')).toBeVisible();
  expect(state.input.materials?.map(item => ({ track: item.track, units: item.units.map(unit => unit.unitId) }))).toEqual([
    { track: 'REFERENCE', units: ['range-1-unit-1'] }, { track: 'REPLACEMENT', units: ['range-2-unit-2'] },
  ]);
  expect(uploads).toBe(2); expect(state.plans).toBe(1);
});

test('预览409显示中文业务原因，同一页再次点击预览可恢复且不调用模型', async ({ page }) => {
  const state = await setup(page, []);
  let requests = 0;
  await page.route('**/api/v1/image-prompt-media/*/units/*', route => {
    requests++;
    if (requests === 1) return route.fulfill({ status: 409, contentType: 'application/json', json: {
      requestId: 'synthetic-preview-conflict', error: { code: 'IMAGE_PROMPT_CONFLICT', message: '素材仍在解析，请等待完成后继续。' },
    } });
    return route.fulfill({ contentType: 'image/png', body: png });
  });
  await pane(page, '图片');
  const reference = page.getByTestId('reference-media');
  await reference.locator('input[type=file]').setInputFiles({ name: 'brand.pptx', mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', buffer: Buffer.from('controlled upload') });
  await reference.getByRole('button', { name: '上传到平台解析预览' }).click();
  await reference.getByRole('button', { name: '选择页／帧与遮挡' }).click();
  const dialog = page.getByRole('dialog', { name: '审阅素材与选择识别范围' });
  await expect(dialog.getByText('素材仍在解析，请等待完成后继续。', { exact: true })).toBeVisible();
  await expect(dialog).not.toContainText('Request failed with status code');
  await dialog.locator('.el-checkbox').filter({ hasText: '第 1 页' }).click();
  await expect(dialog.getByRole('button', { name: '保存选择与遮挡' })).toBeDisabled();
  expect(requests).toBe(1);
  await dialog.getByRole('button', { name: '预览第 1 页', exact: true }).click();
  await expect(dialog.locator('.mask-surface img')).toBeVisible();
  await expect(dialog.getByText('素材仍在解析，请等待完成后继续。', { exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: '保存选择与遮挡' })).toBeEnabled();
  expect(requests).toBe(2);
  await dialog.getByRole('button', { name: '保存选择与遮挡' }).click();
  expect(state.plans).toBe(0); expect(state.confirmations).toBe(0);
});
