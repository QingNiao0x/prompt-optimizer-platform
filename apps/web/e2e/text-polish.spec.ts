import { expect, test, type Page } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import type { PolishDocument, PolishInput } from '../src/types/textPolish';

const input = (): PolishInput => ({ sourceText: '这是一段需要润色的文章。', style: 'NATURAL', depth: 'LIGHT', audience: '', requirements: '', lockedTexts: [], modelId: 'fixture' });
const document = (task = input()): PolishDocument => ({ id: 'record', input: task, generatedText: '这段文章表达清楚，细节完整。', polishedText: '这段文章表达清楚，细节完整。', sourceRecordId: null, sourceMode: 'RAW', modelId: 'fixture', modelName: '受控文章模型', mock: false, editRevision: 0, checks: [], historySaved: true, persistentHistory: true, createdAt: '2026-10-25T00:00:00Z' });
async function pane(page: Page, name: '原文' | '设置' | '结果') { const nav = page.locator('nav.mobile-panes'); await nav.waitFor({state:'attached'}); if (await nav.isVisible()) await nav.getByRole('button', { name, exact: true }).click(); }
async function setup(page: Page) {
  await mockAuthentication(page); let saved = document(), calls = 0, fail = false;
  await page.route('**/api/v1/text-polish**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method();
    const ok = (data: unknown) => route.fulfill({ json: { requestId: 'fixture', data } });
    if (path.endsWith('/capabilities')) return ok({ models: [{ id: 'fixture', displayName: '受控文章模型', defaultModel: true, mock: false }], maxInputCharacters: 12000, maxOutputCharacters: 24000, persistentHistory: true });
    if (path === '/api/v1/text-polish') {
      calls++;
      if (fail) return route.fulfill({ status: 502, json: { error: { code: 'PROVIDER_ERROR', message: '受控模型故障' } } });
      saved = document(route.request().postDataJSON().input); return ok(saved);
    }
    if (path.endsWith('/history')) return ok({ records: [saved], current: 1, size: 10, total: 1, pages: 1 });
    if (method === 'PATCH') { saved = { ...saved, polishedText: route.request().postDataJSON().polishedText, editRevision: saved.editRevision + 1 }; return ok(saved); }
    if (method === 'DELETE') return ok(null);
    return ok(saved);
  });
  await page.goto('/text-polish'); return { calls: () => calls, fail: () => { fail = true; }, saved: () => saved };
}
test('三档说明常显，默认自然流畅，切档不生成，沿用主题且无横向溢出', async ({ page }, testInfo) => {
  const state = await setup(page); await pane(page,'设置');
  await expect(page.getByRole('radio', { name: /自然流畅/ })).toBeChecked();
  for (const text of ['适合报告、商务邮件和正式说明。','默认，适合一般文章和日常沟通。','适合社交内容和面向大众的介绍。']) await expect(page.getByText(text,{exact:true})).toBeVisible();
  await page.getByText('轻松交流',{exact:true}).click(); expect(state.calls()).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('text-polish-settings.png'), fullPage: true });
  await pane(page,'原文');
  const oversized = '原'.repeat(12001);
  await page.getByRole('textbox',{name:'原始文章',exact:true}).fill(oversized);
  await expect(page.getByRole('textbox',{name:'原始文章',exact:true})).toHaveValue(oversized);
  await expect(page.getByText('原文超过 12000 字符，请精简后再润色。已粘贴的内容完整保留。',{exact:true})).toBeVisible();
  await pane(page,'设置'); await expect(page.getByRole('button',{name:'开始润色',exact:true})).toBeDisabled();
  expect(state.calls()).toBe(0);
});
test('生成、编辑、真实剪贴板、历史恢复和再次润色来源闭环', async ({ page, context }) => {
  const state = await setup(page); await pane(page,'原文'); await page.getByRole('textbox',{name:'原始文章',exact:true}).fill(input().sourceText);
  await pane(page,'设置'); await page.getByRole('button',{name:'开始润色',exact:true}).click(); await pane(page,'结果');
  await expect(page.getByLabel('润色正文',{exact:true})).toHaveText(state.saved().polishedText);
  await page.getByRole('button',{name:'编辑正文',exact:true}).click(); await page.getByRole('textbox',{name:'润色编辑稿',exact:true}).fill('用户手动修改的文章正文。');
  await page.getByRole('button',{name:'保存编辑',exact:true}).click(); await expect(page.getByLabel('润色正文',{exact:true})).toHaveText('用户手动修改的文章正文。');
  await context.grantPermissions(['clipboard-read','clipboard-write']); await page.getByRole('button',{name:'复制正文',exact:true}).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('用户手动修改的文章正文。');
  await page.getByRole('button',{name:'文章历史',exact:true}).click(); await page.getByRole('button',{name:'打开文章',exact:true}).click();
  const request = page.waitForRequest(r => r.url().endsWith('/api/v1/text-polish') && r.method()==='POST');
  await page.getByRole('button',{name:'以当前稿继续润色',exact:true}).click(); expect((await request).postDataJSON()).toMatchObject({sourceMode:'CURRENT',sourceRecordId:'record',expectedRevision:1});
});
test('模型失败保留输入与上次正文', async ({page}) => {
  const state = await setup(page); await pane(page,'原文'); await page.getByRole('textbox',{name:'原始文章',exact:true}).fill(input().sourceText);
  await pane(page,'设置'); await page.getByRole('button',{name:'开始润色',exact:true}).click(); await pane(page,'结果'); await expect(page.getByLabel('润色正文',{exact:true})).toBeVisible();
  state.fail(); await pane(page,'设置'); await page.getByRole('button',{name:'基于原文重新生成',exact:true}).click();
  await expect(page.getByText('受控模型故障',{exact:true})).toBeVisible(); await pane(page,'结果'); await expect(page.getByLabel('润色正文',{exact:true})).toHaveText(state.saved().polishedText);
  await pane(page,'原文'); await expect(page.getByRole('textbox',{name:'原始文章',exact:true})).toHaveValue(input().sourceText);
});
test('模型管理统一保存文章模块可用模型和独立默认项', async ({page}) => {
  await mockAuthentication(page,true,true);
  await page.route('**/api/v1/admin/models/text-polish', async route => route.fulfill({json:{data:{enabledModelIds:['fixture'],defaultModelId:'fixture'}}}));
  await page.route('**/api/v1/admin/models', async route => route.fulfill({json:{data:[{id:'fixture-id',publicId:'fixture',routeKey:'mock',upstreamModel:'fixture',displayName:'受控文章模型',enabled:true,defaultModel:true,sortOrder:0}]}}));
  await page.route('**/api/v1/admin/models/routes', async route => route.fulfill({json:{data:[{key:'mock',providerName:'受控路由'}]}}));
  await page.goto('/admin/models'); await page.getByRole('button',{name:'文章润色模型',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'文章祛AI味 · 模型设置'})).toBeVisible();
  const request = page.waitForRequest(r=>r.method()==='PUT'&&r.url().endsWith('/admin/models/text-polish'));
  await page.getByRole('button',{name:'保存模型设置',exact:true}).click(); expect((await request).postDataJSON()).toEqual({enabledModelIds:['fixture'],defaultModelId:'fixture'});
});
