import { expect, test, type Page } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import type { PolishDocument } from '../src/types/textPolish';

const model = { id: 'polish-history-fixture', displayName: '历史文章模型', defaultModel: true, mock: false };
const record = (index: number): PolishDocument => ({
  id: `polish-record-${index}`,
  input: { sourceText: `第${index}篇原始文章。“保持原句。”\n<img src=x onerror="window.__polishHistoryExecuted=true">`,
    style: index % 2 ? 'NATURAL' : 'FORMAL', depth: 'LIGHT', audience: '团队同事', requirements: '保留标题及段落。',
    lockedTexts: ['“保持原句。”'], modelId: model.id },
  generatedText: `首次生成正文 ${index}。“保持原句。”`, polishedText: `历史润色正文 ${index}。“保持原句。”`,
  sourceMode: 'RAW', sourceRecordId: null, modelId: model.id, modelName: model.displayName, mock: false,
  editRevision: 2, checks: [], historySaved: true, persistentHistory: true,
  createdAt: new Date(Date.UTC(2026, 9, 25, 12, 0, -index)).toISOString(),
});
const rows = (page: Page) => page.locator('.polish-history-table:visible .el-table__row, .polish-history-cards:visible .polish-history-card');
async function pane(page: Page, label: '原文' | '设置' | '结果') {
  const nav = page.locator('nav.mobile-panes'); await nav.waitFor({ state: 'attached' });
  if (await nav.isVisible()) await nav.getByRole('button', { name: label, exact: true }).click();
}

/** 有状态模拟记录持久化及分页；未登记请求一律拦截，历史操作不能落到真实模型或认证服务。 */
async function setup(page: Page, count = 12) {
  const state = { records: Array.from({ length: count }, (_, index) => record(index)), requests: [] as URL[],
    deletes: [] as string[], paidCalls: 0, failList: false };
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记的文章历史测试请求' } } }));
  await mockAuthentication(page);
  await page.route('**/api/v2/optimization-history**', route => route.fulfill({ json: { data: { records: [{ id: 'text-record',
    templateCode: 'GENERAL', rawPromptPreview: '原有提示词增强记录', providerName: 'fixture', modelName: 'fixture',
    modelVersion: '原有模型', mock: false, latencyMs: 10, createdAt: '2026-10-25T00:00:00Z' }],
    total: 1, current: 1, size: 10, pages: 1 } } }));
  await page.route('**/api/v1/image-prompts/history**', route => route.fulfill({ json: { data: { records: [], total: 0, current: 1, size: 10, pages: 0 } } }));
  await page.route('**/api/v1/text-polish**', route => {
    const url = new URL(route.request().url()), method = route.request().method();
    const ok = (data: unknown) => route.fulfill({ json: { data } });
    if (url.pathname.endsWith('/capabilities')) return ok({ models: [model], maxInputCharacters: 12000, maxOutputCharacters: 24000, persistentHistory: true });
    if (url.pathname === '/api/v1/text-polish' && method === 'POST') {
      state.paidCalls++;
      const body = route.request().postDataJSON();
      const generated = { ...record(100), id: body.requestId, input: body.input, editRevision: 0,
        generatedText: '本次生成的润色正文。', polishedText: '本次生成的润色正文。' };
      state.records.unshift(generated); return ok(generated);
    }
    if (url.pathname.endsWith('/history') && method === 'GET') {
      state.requests.push(url);
      if (state.failList) return route.fulfill({ status: 503, json: { error: { code: 'HISTORY_UNAVAILABLE', message: '受控历史加载失败' } } });
      const current = Number(url.searchParams.get('current')), size = Number(url.searchParams.get('size'));
      return ok({ records: state.records.slice((current - 1) * size, current * size), current, size,
        total: state.records.length, pages: Math.ceil(state.records.length / size) });
    }
    const id = url.pathname.match(/\/history\/([^/]+)$/)?.[1];
    if (id && method === 'GET') {
      const value = state.records.find(item => item.id === id);
      return value ? ok(value) : route.fulfill({ status: 404, json: { error: { message: '文章记录不存在或已删除。' } } });
    }
    if (id && method === 'DELETE') {
      state.deletes.push(id); state.records = state.records.filter(item => item.id !== id); return ok(null);
    }
    return route.fulfill({ status: 501, json: { error: { message: '历史操作不应产生此请求' } } });
  });
  return state;
}

test('文章润色成功后进入总历史页，直接展示使用记录且刷新保留分类', async ({ page }, testInfo) => {
  const state = await setup(page, 0);
  await page.goto('/text-polish'); await pane(page, '原文');
  await page.getByRole('textbox', { name: '原始文章', exact: true }).fill('本次主动提交的原始文章。');
  await pane(page, '设置'); await page.getByRole('button', { name: '开始润色', exact: true }).click();
  await expect(page.getByLabel('润色正文', { exact: true })).toHaveText('本次生成的润色正文。');
  await page.getByRole('button', { name: '文章历史', exact: true }).click();
  await expect(page).toHaveURL(/\/history\?type=text-polish/);
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page).first()).toContainText('本次主动提交的原始文章。');
  await expect(rows(page).first()).toContainText('本次生成的润色正文。');
  await expect(rows(page).first()).toContainText('自然流畅');
  await expect(rows(page).first()).toContainText(model.displayName);
  await page.screenshot({ path: testInfo.outputPath('text-polish-history-list.png'), fullPage: true, animations: 'disabled' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.reload(); await expect(page.getByRole('tab', { name: '文章祛AI味', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(rows(page)).toHaveCount(1);
  await page.getByRole('tab', { name: '提示词优化', exact: true }).click();
  await expect(page.getByText('原有提示词增强记录', { exact: true }).filter({ visible: true })).toBeVisible();
  await page.getByRole('tab', { name: '图像转提示词', exact: true }).click();
  await expect(page.getByText('还没有图像转提示词记录', { exact: true })).toBeVisible();
  expect(state.paidCalls).toBe(1);
});

test('文章历史分页和页大小传给服务端，删空末页回退，取消删除不改记录', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/history?type=text-polish'); await expect(rows(page)).toHaveCount(10);
  const pagination = page.locator('.polish-history-pagination');
  await pagination.locator('.el-select').click(); await page.getByRole('option', { name: '20/page', exact: true }).click();
  await expect(rows(page)).toHaveCount(12); expect(state.requests.at(-1)?.searchParams.get('size')).toBe('20');
  await pagination.locator('.el-select').click(); await page.getByRole('option', { name: '10/page', exact: true }).click();
  await expect(rows(page)).toHaveCount(10);
  await pagination.locator('.el-pager li', { hasText: /^2$/ }).click(); await expect(rows(page)).toHaveCount(2);
  expect(state.requests.at(-1)?.searchParams.get('current')).toBe('2');
  await rows(page).first().getByRole('button', { name: '删除', exact: true }).click();
  await page.getByRole('button', { name: '取消', exact: true }).click(); expect(state.deletes).toHaveLength(0);
  await page.evaluate(value => {
    type Host = HTMLElement & { __vue_app__?: { config: { globalProperties: { $pinia: { _s: Map<string, { restoreRecord: (record: PolishDocument) => void }> } } } } };
    const store = document.querySelector<Host>('#app')?.__vue_app__?.config.globalProperties.$pinia._s.get('text-polish');
    if (!store) throw new Error('Missing article store'); store.restoreRecord(value);
  }, state.records[10]!);
  for (const expected of [1, 10]) {
    await rows(page).first().getByRole('button', { name: '删除', exact: true }).click();
    await page.getByRole('button', { name: '删除记录', exact: true }).click(); await expect(rows(page)).toHaveCount(expected);
  }
  expect(state.deletes).toEqual(['polish-record-10', 'polish-record-11']);
  expect(await page.evaluate(() => {
    type Host = HTMLElement & { __vue_app__?: { config: { globalProperties: { $pinia: { _s: Map<string, { result?: { id: string } }> } } } } };
    return document.querySelector<Host>('#app')?.__vue_app__?.config.globalProperties.$pinia._s.get('text-polish')?.result?.id ?? null;
  })).toBeNull();
  await expect(pagination.locator('.el-pager li.is-active')).toHaveText('1');
  await page.getByRole('button', { name: '刷新记录', exact: true }).click();
  await expect(rows(page)).toHaveCount(10); expect(state.paidCalls).toBe(0);
});

test('详情对照原文与当前正文，保留生成快照，复制降级和载入不调用模型', async ({ page, context }, testInfo) => {
  const state = await setup(page);
  await page.goto('/history?type=text-polish'); await rows(page).first().getByRole('button', { name: '查看', exact: true }).click();
  const detail = page.getByRole('dialog', { name: '文章润色记录详情', exact: true });
  await expect(detail).toContainText(state.records[0]!.input.sourceText);
  await expect(detail).toContainText(state.records[0]!.polishedText);
  await expect(detail.locator('img')).toHaveCount(0);
  await detail.getByText('首次生成正文', { exact: true }).click(); await expect(detail).toContainText(state.records[0]!.generatedText);
  await detail.getByText('本次润色要求', { exact: true }).click(); await expect(detail).toContainText('团队同事');
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await detail.getByRole('button', { name: '复制正文', exact: true }).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(state.records[0]!.polishedText);
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true,
    value: { writeText: () => Promise.reject(new Error('controlled clipboard denial')) } }));
  await detail.getByRole('button', { name: '复制正文', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '手动复制历史润色正文', exact: true })).toHaveValue(state.records[0]!.polishedText);
  await page.getByRole('dialog', { name: '手动复制文章', exact: true }).getByRole('button', { name: 'Close this dialog' }).click();
  await expect(page.getByRole('dialog', { name: '手动复制文章', exact: true, includeHidden: true })).not.toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('text-polish-history-detail.png'), fullPage: true, animations: 'disabled' });
  await detail.getByRole('button', { name: '载入文章工作台', exact: true }).click();
  await expect(page).toHaveURL(/\/text-polish\?pane=result/);
  await expect(page.getByLabel('润色正文', { exact: true })).toHaveText(state.records[0]!.polishedText);
  await pane(page, '原文'); await expect(page.getByRole('textbox', { name: '原始文章', exact: true })).toHaveValue(state.records[0]!.input.sourceText);
  await pane(page, '设置'); await expect(page.getByRole('radio', { name: /正式清晰/ })).toBeChecked();
  expect(state.paidCalls).toBe(0);
});

test('载入前保护未保存编辑稿，取消后保留原稿，确认后恢复所选历史', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/history?type=text-polish'); await expect(rows(page)).toHaveCount(10);
  await page.evaluate(value => {
    type Host = HTMLElement & { __vue_app__?: { config: { globalProperties: { $pinia: { _s: Map<string, object> } } } } };
    const store = document.querySelector<Host>('#app')?.__vue_app__?.config.globalProperties.$pinia._s.get('text-polish');
    if (!store) throw new Error('Missing article store');
    Object.assign(store, { result: value, editDraft: '尚未保存的个人编辑稿', isEditing: true });
  }, state.records[0]!);
  await rows(page).nth(1).getByRole('button', { name: '载入', exact: true }).click();
  await page.getByRole('button', { name: '保留编辑稿', exact: true }).click();
  await expect(page).toHaveURL(/\/history\?type=text-polish/);
  expect(await page.evaluate(() => {
    type Host = HTMLElement & { __vue_app__?: { config: { globalProperties: { $pinia: { _s: Map<string, { editDraft: string; isEditing: boolean }> } } } } };
    const store = document.querySelector<Host>('#app')?.__vue_app__?.config.globalProperties.$pinia._s.get('text-polish');
    return { draft: store?.editDraft, editing: store?.isEditing };
  })).toEqual({ draft: '尚未保存的个人编辑稿', editing: true });
  await rows(page).nth(1).getByRole('button', { name: '载入', exact: true }).click();
  await page.getByRole('button', { name: '放弃编辑并载入', exact: true }).click();
  await expect(page.getByLabel('润色正文', { exact: true })).toHaveText(state.records[1]!.polishedText);
  expect(state.paidCalls).toBe(0);
});

test('空记录与加载失败分别呈现，刷新可恢复且不会产生模型调用', async ({ page }) => {
  const state = await setup(page, 0);
  await page.goto('/history?type=text-polish'); await expect(page.getByText('还没有文章祛AI味记录', { exact: true })).toBeVisible();
  state.failList = true; await page.getByRole('button', { name: '刷新记录', exact: true }).click();
  await expect(page.getByText('受控历史加载失败', { exact: true })).toBeVisible();
  state.failList = false; state.records.push(record(0)); await page.getByRole('button', { name: '刷新记录', exact: true }).click();
  await expect(rows(page)).toHaveCount(1); await expect(page.getByText('受控历史加载失败', { exact: true })).toHaveCount(0);
  expect(state.paidCalls).toBe(0);
});

test('身份变化清除文章历史并拒绝旧身份的迟到列表', async ({ page }) => {
  await setup(page);
  let release!: () => void, requested = false, switched = false;
  const waiting = new Promise<void>(resolve => { release = resolve; });
  await page.route(/\/api\/v1\/text-polish\/history\?/, async route => {
    if (switched) return route.fulfill({ json: { data: { records: [], total: 0, current: 1, size: 10, pages: 0 } } });
    requested = true; await waiting;
    return route.fulfill({ json: { data: { records: [{ ...record(0), polishedText: '旧身份私有润色记录' }], total: 1, current: 1, size: 10, pages: 1 } } });
  });
  await page.goto('/history?type=text-polish'); await expect.poll(() => requested).toBe(true);
  switched = true;
  await page.evaluate(() => {
    type Host = HTMLElement & { __vue_app__?: { config: { globalProperties: { $pinia: { _s: Map<string, { user: unknown }> } } } } };
    const auth = document.querySelector<Host>('#app')?.__vue_app__?.config.globalProperties.$pinia._s.get('auth');
    if (!auth) throw new Error('Missing controlled auth fixture');
    auth.user = { userId: 'controlled-other', tenantId: 'controlled-other', workspaceId: 'controlled-other',
      displayName: '其他夹具', email: 'other@example.test', platformAdmin: false };
  });
  await expect(page.getByText('还没有文章祛AI味记录', { exact: true })).toBeVisible();
  const response = page.waitForResponse(value => value.url().includes('/text-polish/history?'));
  release(); await response; await expect(page.getByText('旧身份私有润色记录')).toHaveCount(0);
});
