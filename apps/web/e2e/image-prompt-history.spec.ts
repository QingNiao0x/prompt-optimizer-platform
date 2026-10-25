import { expect, test, type Page } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import type { ImagePromptDocument } from '../src/types/imagePrompt';

const model = { id: 'history-fixture', displayName: '受控视觉模型', vision: true, maxImages: 3, evidence: '受控测试', mock: false };
const makeRecord = (index: number): ImagePromptDocument => {
  const mode = index % 2 === 0 ? 'REFERENCE_RECONSTRUCTION' : 'CUSTOM_CREATION';
  return {
    operationId: `operation-${index}`, recordId: `record-${index}`, mode,
    optimizedPrompt: `历史当前正文 ${index}：${mode === 'CUSTOM_CREATION' ? '创作全新植物海报' : '白底蓝圆黄色方形参考图'}`,
    input: { mode, modelId: model.id, images: [], creationIntent: mode === 'CUSTOM_CREATION' ? '新的植物展海报' : '',
      exactText: '春日·2026', context: '社区植物展资料', language: 'ZH', targetTool: '通用文生图' },
    sources: mode === 'REFERENCE_RECONSTRUCTION' ? [{ id: 'private-asset', name: '受控参考图.png', hash: 'fixture',
      role: 'GENERAL', width: 900, height: 600, expiresAt: '2026-10-24T01:00:00Z' }] : [],
    analysis: mode === 'REFERENCE_RECONSTRUCTION'
      ? [{ imageId: 'private-asset', category: '布局', description: '蓝色圆形在黄色方形左侧', status: 'OBSERVED' }] : [],
    questions: mode === 'CUSTOM_CREATION' ? [{ id: 'style', type: 'SINGLE_CHOICE', question: '希望采用哪种风格？',
      options: [{ id: 'illustration', label: '柔和插画' }], required: true }] : [],
    answers: mode === 'CUSTOM_CREATION' ? [{ questionId: 'style', optionIds: ['illustration'], customText: '保留纸张纹理' }] : [],
    warnings: ['请核对画面文字'], modelId: model.id, modelName: model.displayName,
    mock: false, editRevision: 2, historySaved: true, persistentHistory: true,
    createdAt: new Date(Date.UTC(2026, 9, 25, 12, 0, -index)).toISOString(),
  };
};

/** 所有业务请求均为有状态夹具；历史查看、复制和载入不得误发视觉调用或创建账号。 */
async function setup(page: Page) {
  const state = { records: Array.from({ length: 12 }, (_, index) => makeRecord(index)), requests: [] as URL[], deletes: [] as string[], paidCalls: 0 };
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记的历史测试请求' } } }));
  await mockAuthentication(page);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [{ ...model, provider: 'fixture', defaultModel: true }] } }));
  await page.route('**/api/v2/optimization-history**', route => route.fulfill({ json: { data: { records: [{ id: 'text-record',
    templateCode: 'GENERAL', rawPromptPreview: '原有文本增强记录', providerName: 'fixture', modelName: 'text-fixture',
    modelVersion: '受控文字模型', mock: false, latencyMs: 10, createdAt: '2026-10-25T00:00:00Z' }], total: 1, current: 1, size: 10, pages: 1 } } }));
  await page.route('**/api/v1/image**', route => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    if (url.pathname.endsWith('/capabilities')) return route.fulfill({ json: { data: { models: [model], maxImageBytes: 10485760,
      maxPixels: 15000000, maxEdge: 8192, maxImages: 3, assetTtlSeconds: 3600, persistentHistory: true } } });
    if (url.pathname.endsWith('/history') && method === 'GET') {
      state.requests.push(url);
      const current = Number(url.searchParams.get('current'));
      const size = Number(url.searchParams.get('size'));
      const mode = url.searchParams.get('mode');
      const records = state.records.filter(record => !mode || record.mode === mode);
      return route.fulfill({ json: { data: { records: records.slice((current - 1) * size, current * size),
        current, size, total: records.length, pages: Math.ceil(records.length / size) } } });
    }
    const id = url.pathname.match(/\/history\/([^/]+)$/)?.[1];
    if (id && method === 'DELETE') {
      state.deletes.push(id); state.records = state.records.filter(record => record.recordId !== id);
      return route.fulfill({ json: { data: null } });
    }
    if (id && method === 'GET') {
      const record = state.records.find(record => record.recordId === id);
      return route.fulfill({ status: record ? 200 : 404, json: { data: record } });
    }
    if (method === 'POST') state.paidCalls++;
    return route.fulfill({ status: 501, json: { error: { message: '历史操作不应产生此请求' } } });
  });
  return state;
}
const visibleRows = (page: Page) => page.locator('.image-history-table:visible .el-table__row, .image-history-cards:visible .image-history-card');

test('历史页分类直接展示图像记录，支持模式筛选、分页与刷新定位', async ({ page }, testInfo) => {
  const state = await setup(page);
  await page.goto('/history');
  await expect(page.getByText('原有文本增强记录', { exact: true }).filter({ visible: true })).toBeVisible();
  expect(state.requests).toHaveLength(0);
  await page.getByRole('tab', { name: '图像转提示词', exact: true }).click();
  await expect(visibleRows(page)).toHaveCount(10);
  await expect(page.locator('.mode-filter')).toContainText('全部模式');
  await expect(visibleRows(page).first()).toContainText('参考图还原');
  await expect(visibleRows(page).nth(1)).toContainText('Plan 新图创作');
  await expect(visibleRows(page).first()).toContainText(model.displayName);
  const pagination = page.locator('.image-history-pagination');
  await pagination.locator('.el-pager li', { hasText: /^2$/ }).click();
  await expect(visibleRows(page)).toHaveCount(2);
  expect(state.requests.at(-1)?.searchParams.get('current')).toBe('2');
  await pagination.locator('.el-select').click();
  await page.getByRole('option', { name: '20/page', exact: true }).click();
  await expect(visibleRows(page)).toHaveCount(12);
  expect(state.requests.at(-1)?.searchParams.get('current')).toBe('1');
  expect(state.requests.at(-1)?.searchParams.get('size')).toBe('20');
  await page.locator('.mode-filter').click();
  await page.getByRole('option', { name: 'Plan 新图创作', exact: true }).click();
  await expect(visibleRows(page)).toHaveCount(6);
  expect(state.requests.at(-1)?.searchParams.get('mode')).toBe('CUSTOM_CREATION');
  await page.screenshot({ path: testInfo.outputPath('image-history-list.png'), fullPage: true, animations: 'disabled' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.reload();
  await expect(page.getByRole('tab', { name: '图像转提示词', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(visibleRows(page)).toHaveCount(10);
  await page.getByRole('tab', { name: '提示词优化', exact: true }).click();
  await expect(page.getByText('原有文本增强记录', { exact: true }).filter({ visible: true })).toBeVisible();
  expect(state.paidCalls).toBe(0);
});

test('图像记录详情保留题答和当前正文，复制降级与载入不重复调用模型', async ({ page }, testInfo) => {
  const state = await setup(page);
  await page.goto('/history?type=image-prompt');
  await visibleRows(page).first().getByRole('button', { name: '查看', exact: true }).click();
  const detail = page.getByRole('dialog', { name: '图像提示词记录详情' });
  await expect(detail).toContainText('受控参考图.png');
  await expect(detail).toContainText('蓝色圆形在黄色方形左侧');
  await expect(detail.locator('img')).toHaveCount(0);
  await detail.getByRole('button', { name: 'Close this dialog' }).click();
  await visibleRows(page).nth(1).getByRole('button', { name: '查看', exact: true }).click();
  await expect(detail).toContainText('新的植物展海报');
  await expect(detail).toContainText('春日·2026');
  await expect(detail).toContainText('希望采用哪种风格？');
  await expect(detail).toContainText('柔和插画；保留纸张纹理');
  await page.screenshot({ path: testInfo.outputPath('image-history-detail.png'), fullPage: true, animations: 'disabled' });
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true,
    value: { writeText: () => Promise.reject(new Error('controlled clipboard denial')) } }));
  await detail.getByRole('button', { name: '复制提示词', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '手动复制历史图像提示词' })).toHaveValue(state.records[1]!.optimizedPrompt);
  await page.getByRole('dialog', { name: '手动复制提示词' }).getByRole('button', { name: 'Close this dialog' }).click();
  await detail.getByRole('button', { name: 'Close this dialog' }).click();
  await visibleRows(page).nth(1).getByRole('button', { name: '载入', exact: true }).click();
  await expect(page).toHaveURL(/\/image-to-prompt\?pane=result/);
  await expect(page.locator('.prompt-text')).toHaveText(state.records[1]!.optimizedPrompt);
  expect(state.paidCalls).toBe(0);
});

test('删除确认可取消，删空最后一页后回退且刷新不恢复已删除记录', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/history?type=image-prompt');
  await expect(visibleRows(page)).toHaveCount(10);
  await page.locator('.image-history-pagination .el-pager li', { hasText: /^2$/ }).click();
  await expect(visibleRows(page)).toHaveCount(2);
  await visibleRows(page).first().getByRole('button', { name: '删除', exact: true }).click();
  await page.getByRole('button', { name: '取消', exact: true }).click();
  expect(state.deletes).toHaveLength(0);
  for (const count of [1, 10]) {
    await visibleRows(page).first().getByRole('button', { name: '删除', exact: true }).click();
    await page.getByRole('button', { name: '删除记录', exact: true }).click();
    await expect(visibleRows(page)).toHaveCount(count);
  }
  expect(state.deletes).toEqual(['record-10', 'record-11']);
  await expect(page.locator('.image-history-pagination .el-pager li.is-active')).toHaveText('1');
  await page.getByRole('button', { name: '刷新记录', exact: true }).click();
  await expect(visibleRows(page)).toHaveCount(10);
  await expect(visibleRows(page).filter({ hasText: '历史当前正文 10' })).toHaveCount(0);
  expect(state.paidCalls).toBe(0);
});

test('历史分类切换身份后拒绝旧列表的迟到响应', async ({ page }) => {
  await setup(page);
  let release!: () => void;
  let requested = false;
  let switched = false;
  const waiting = new Promise<void>(resolve => { release = resolve; });
  await page.route(/\/api\/v1\/image-prompts\/history\?/, async route => {
    if (switched) return route.fulfill({ json: { data: { records: [], total: 0, current: 1, size: 10, pages: 0 } } });
    requested = true;
    await waiting;
    return route.fulfill({ json: { data: { records: [{ ...makeRecord(0), optimizedPrompt: '旧身份私有图像记录' }],
      total: 1, current: 1, size: 10, pages: 1 } } });
  });
  await page.goto('/history?type=image-prompt');
  await expect.poll(() => requested).toBe(true);
  switched = true;
  await page.evaluate(() => {
    type Host = HTMLElement & { __vue_app__?: { config: { globalProperties: { $pinia: { _s: Map<string, { user: unknown }> } } } } };
    const auth = document.querySelector<Host>('#app')?.__vue_app__?.config.globalProperties.$pinia._s.get('auth');
    if (!auth) throw new Error('Missing controlled Pinia fixture');
    auth.user = { userId: 'controlled-other', tenantId: 'controlled-other', workspaceId: 'controlled-other',
      displayName: '其他夹具', email: 'other@example.test', platformAdmin: false };
  });
  await expect(page.getByText('还没有图像转提示词记录', { exact: true })).toBeVisible();
  const oldResponse = page.waitForResponse(response => response.url().includes('/image-prompts/history?'));
  release(); await oldResponse;
  await expect(page.getByText('旧身份私有图像记录')).toHaveCount(0);
});
