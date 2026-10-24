import { expect, test, type Page } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import type { ImagePromptDocument, ImagePromptInput, ImagePromptPlan } from '../src/types/imagePrompt';

const expiry = '2099-01-01T00:00:00Z';
const model = { id: 'fixture', displayName: '受控视觉模型', vision: true, maxImages: 3, evidence: '受控协议测试', mock: false };
const textModel = { id: 'fixture-text', displayName: '受控文字模型', vision: false, maxImages: 0, evidence: '未登记视觉能力', mock: false };
const input = (): ImagePromptInput => ({ mode: 'CUSTOM_CREATION', modelId: 'fixture', images: [], creationIntent: '植物海报', exactText: '', context: '', language: 'ZH', targetTool: '通用文生图' });
const fixture = (task = input()): ImagePromptDocument => ({ operationId: 'operation', recordId: 'record', mode: task.mode,
  optimizedPrompt: task.mode === 'CUSTOM_CREATION' ? `创作一张全新的植物插画海报。${task.exactText}` : '还原参考图：白底、蓝色圆形位于黄色方形左侧。',
  analysis: [], warnings: [], input: task, sources: [], questions: [], answers: [], modelId: 'fixture', modelName: model.displayName,
  mock: false, editRevision: 0, historySaved: true, persistentHistory: true, createdAt: '2026-10-24T00:00:00Z' });

/** 网络白名单全部使用受控响应；测试不得误发真实模型请求或创建账号。 */
async function setup(page: Page, zeroQuestions = false) {
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记的测试请求' } } }));
  await mockAuthentication(page);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [model, textModel].map(item => ({ id: item.id, displayName: item.displayName, provider: 'fixture', defaultModel: item.id === model.id })) } }));
  const state = { uploads: 0, plans: 0, extracts: 0, confirmations: 0, edits: 0, deletes: 0,
    task: input(), current: fixture(), answers: [] as { questionId: string; optionIds: string[]; customText: string }[] };
  await page.route('**/api/v1/image**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method();
    if (path.endsWith('/capabilities')) return route.fulfill({ json: { data: { models: [model, textModel], maxImageBytes: 10485760,
      maxPixels: 15000000, maxEdge: 8192, maxImages: 3, assetTtlSeconds: 3600, persistentHistory: true } } });
    if (path.endsWith('/image-prompt-assets') && method === 'POST') {
      state.uploads++; return route.fulfill({ json: { data: { id: 'asset', name: 'fixture.png', mimeType: 'image/png',
        width: 2, height: 2, bytes: 100, hash: 'fixture', expiresAt: expiry, previewUrl: '/private', processing: ['已清除元数据'] } } });
    }
    if (path.endsWith('/plans')) {
      state.plans++; state.task = route.request().postDataJSON().input as ImagePromptInput;
      const plan: ImagePromptPlan = { id: 'plan', fingerprint: 'binding', summary: '设计新的植物海报', analysis: [], warnings: [],
        expiresAt: expiry, mock: false, questions: zeroQuestions ? [] : [{ id: 'style', type: 'SINGLE_CHOICE', question: '新图希望采用哪种风格？',
          options: [{ id: 'a', label: '柔和插画' }, { id: 'b', label: '自然摄影' }], required: true }] };
      return route.fulfill({ json: { data: plan } });
    }
    if (path.endsWith('/confirm')) {
      state.confirmations++; state.answers = route.request().postDataJSON().answers;
      state.current = fixture(state.task); return route.fulfill({ json: { data: state.current } });
    }
    if (path.endsWith('/image-prompts') && method === 'POST') {
      state.extracts++; state.task = route.request().postDataJSON().input as ImagePromptInput;
      state.current = fixture(state.task); return route.fulfill({ json: { data: state.current } });
    }
    if (path.endsWith('/history')) return route.fulfill({ json: { data: { records: state.deletes ? [] : [state.current], total: state.deletes ? 0 : 1, size: 10, current: 1, pages: 1 } } });
    if (path.endsWith('/history/record') && method === 'PATCH') {
      state.edits++; state.current = { ...state.current, optimizedPrompt: route.request().postDataJSON().optimizedPrompt, editRevision: state.current.editRevision + 1 };
      return route.fulfill({ json: { data: state.current } });
    }
    if (path.endsWith('/history/record') && method === 'DELETE') { state.deletes++; return route.fulfill({ json: { data: null } }); }
    if (path.endsWith('/history/record')) return route.fulfill({ json: { data: state.current } });
    if (path.includes('/image-prompt-assets/') && method === 'DELETE') return route.fulfill({ json: { data: null } });
    return route.fulfill({ status: 501, json: { error: { message: '未登记的图像测试请求' } } });
  });
  await page.goto('/image-to-prompt', { waitUntil: 'domcontentloaded' }); await expect(page.getByRole('heading', { name: '图像转提示词', exact: true })).toBeVisible();
  return state;
}
async function pane(page: Page, name: string) {
  const nav = page.getByRole('navigation', { name: '图像工作台分区' });
  if (await nav.isVisible()) await nav.getByRole('button', { name, exact: true }).click();
}

test('图像模型列表与文本目录一致，文字模型只能用于无图 Plan', async ({ page }) => {
  const state = await setup(page); await pane(page, '设置');
  const directory = await page.evaluate(async () => (await (await fetch('/api/v1/models')).json()).data) as { id: string; displayName: string }[];
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '图像任务模型' }) }).click();
  for (const item of directory) await expect(page.getByRole('option', { name: new RegExp(item.displayName) })).toBeVisible();
  await page.getByRole('option', { name: '受控文字模型（纯文字）', exact: true }).click();
  await expect(page.getByText('当前模型未开放图片输入，请选择视觉模型；无图创作可开启 Plan 并移除参考图。', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '提取参考图提示词' })).toBeDisabled();
  await page.locator('.column-heading .el-switch').click();
  await page.getByRole('textbox', { name: '新图创作构想' }).fill('文字构想：社区植物展海报。');
  await page.getByRole('button', { name: '梳理创作意图' }).click();
  await expect(page.getByRole('heading', { name: '确认新图创作方向' })).toBeVisible();
  expect(state.task.modelId).toBe(textModel.id); expect(state.uploads).toBe(0); expect(state.plans).toBe(1);
});

test('直接模式：选择不上传，确认隐私后单图提取，编辑复制与历史删除闭环', async ({ page }, testInfo) => {
  const state = await setup(page);
  await page.locator('input[type=file]').setInputFiles({ name: 'fixture.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6o0MAAAAASUVORK5CYII=', 'base64') });
  expect(state.uploads).toBe(0); expect(state.extracts).toBe(0);
  await pane(page, '设置');
  await expect(page.getByRole('button', { name: '提取参考图提示词' })).toBeDisabled();
  await expect(page.getByText('请先勾选“我已检查图片隐私”确认框，再生成提示词。', { exact: true })).toBeVisible();
  await expect(page.locator('[data-column="settings"] .consent-panel')).toContainText('待确认');
  await page.screenshot({ path: testInfo.outputPath('image-consent-pending.png'), fullPage: true, animations: 'disabled' });
  await page.locator('.upload-consent').click();
  await expect(page.getByRole('button', { name: '提取参考图提示词' })).toBeEnabled();
  await expect(page.locator('.consent-panel')).toContainText('已确认');
  await expect(page.getByText('请先勾选“我已检查图片隐私”确认框，再生成提示词。', { exact: true })).not.toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('image-consent-confirmed.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: '提取参考图提示词' }).click();
  await expect(page.locator('.prompt-text')).toContainText('蓝色圆形');
  expect(state.plans).toBe(0); expect(state.uploads).toBe(1); expect(state.task.creationIntent).toBe('');
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByRole('textbox', { name: '编辑图像提示词', exact: true }).fill('用户核对后的参考图还原正文。');
  await page.getByRole('button', { name: '保存正文', exact: true }).click();
  await expect(page.locator('.prompt-text')).toHaveText('用户核对后的参考图还原正文。');
  expect(state.extracts).toBe(1); expect(state.edits).toBe(1);
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => Promise.reject(new Error('denied')) } }));
  await page.getByRole('button', { name: '复制提示词', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '手动复制图像提示词' })).toHaveValue(state.current.optimizedPrompt);
  await page.getByRole('dialog', { name: '手动复制提示词' }).getByRole('button', { name: 'Close this dialog' }).click();
  await page.getByRole('button', { name: '图像历史', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '图像提示词历史' })).toContainText('用户核对后的');
  await page.getByRole('dialog', { name: '图像提示词历史' }).getByRole('button', { name: '删除', exact: true }).click();
  await page.getByRole('button', { name: '删除记录', exact: true }).click();
  await expect(page.getByText('还没有图像提示词记录。', { exact: true })).toBeVisible(); expect(state.deletes).toBe(1);
  await page.screenshot({ path: testInfo.outputPath('image-history.png'), fullPage: true });
});

test('Plan：无图新创作，选项与补充共同提交，指定文案可复制', async ({ page }, testInfo) => {
  const state = await setup(page); await pane(page, '设置');
  await page.locator('.column-heading .el-switch').click();
  await page.getByRole('textbox', { name: '新图创作构想' }).fill('创作温暖的植物展海报，风格我想先确认。');
  await page.getByRole('textbox', { name: '画面指定文案' }).fill('春日·2026');
  expect(state.plans).toBe(0); await page.getByRole('button', { name: '梳理创作意图' }).click();
  await page.locator('.plan-question .el-radio').filter({ hasText: '柔和插画' }).click();
  await page.getByRole('textbox', { name: '新图希望采用哪种风格？的补充回答' }).fill('保留纸张纹理');
  await page.getByRole('button', { name: '确认并生成新图提示词' }).click();
  await expect(page.locator('.prompt-text')).toContainText('春日·2026');
  expect(state.uploads).toBe(0); expect(state.extracts).toBe(0); expect(state.confirmations).toBe(1);
  expect(state.answers).toEqual([{ questionId: 'style', optionIds: ['a'], customText: '保留纸张纹理' }]);
  await page.screenshot({ path: testInfo.outputPath('image-plan-result.png'), fullPage: true });
});

test('充分创作要求允许零问题，确认后生成；窄屏无横向溢出', async ({ page }) => {
  const state = await setup(page, true); await pane(page, '设置');
  await page.locator('.column-heading .el-switch').click();
  await page.getByRole('textbox', { name: '新图创作构想' }).fill('完整植物海报设计：3:4、绿色插画、无文字，布局和细节委托设计。');
  await page.getByRole('button', { name: '梳理创作意图' }).click();
  await expect(page.getByText('创作要求已充分，无需补充问题；确认后即可生成新图提示词。')).toBeVisible();
  expect(state.confirmations).toBe(0); await page.getByRole('button', { name: '确认并生成新图提示词' }).click();
  await expect(page.locator('.prompt-text')).toContainText('全新');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});

test('Plan 开关和创作字段可以通过键盘操作', async ({ page }) => {
  await setup(page); await pane(page, '设置');
  // Tab 焦点属于组件的原生输入，外层 div 仅负责布局，不能作为键盘操作目标。
  const toggle = page.getByRole('switch', { name: 'Plan 创作模式' });
  await toggle.focus(); await page.keyboard.press('Space');
  await expect(page.getByRole('textbox', { name: '新图创作构想' })).toBeVisible();
  await page.getByRole('textbox', { name: '新图创作构想' }).focus();
  await page.keyboard.type('keyboard concept'); await page.keyboard.press('Tab');
  await expect(page.getByRole('textbox', { name: '画面指定文案' })).toBeFocused();
});

test('身份切换清空局部历史并拒绝旧身份的迟到历史响应', async ({ page }) => {
  await setup(page);
  let release!: () => void, requested = false;
  const waiting = new Promise<void>(resolve => { release = resolve; });
  await page.route(/\/api\/v1\/image-prompts\/history\?/, async route => {
    requested = true; await waiting;
    await route.fulfill({ json: { data: { records: [{ ...fixture(), optimizedPrompt: '旧身份的私有结果标记' }], total: 1, size: 10, current: 1, pages: 1 } } });
  });
  await page.getByRole('button', { name: '图像历史', exact: true }).click();
  await expect.poll(() => requested).toBe(true);
  // 只在受控浏览器夹具中切换内存身份，复现组件不卸载的场景；不创建后台账户。
  await page.evaluate(() => {
    type Host = HTMLElement & { __vue_app__?: { config: { globalProperties: { $pinia: { _s: Map<string, { user: unknown }> } } } } };
    const auth = document.querySelector<Host>('#app')?.__vue_app__?.config.globalProperties.$pinia._s.get('auth');
    if (!auth) throw new Error('Missing controlled Pinia fixture');
    auth.user = { userId: 'fixture-other', tenantId: 'fixture-other', workspaceId: 'fixture-other', displayName: '其他夹具', email: 'other@example.test', platformAdmin: false };
  });
  const response = page.waitForResponse(r => r.url().includes('/image-prompts/history?'));
  release(); await response;
  await expect(page.getByRole('dialog', { name: '图像提示词历史' })).not.toBeVisible();
  await expect(page.getByText('旧身份的私有结果标记')).not.toBeVisible();
});
