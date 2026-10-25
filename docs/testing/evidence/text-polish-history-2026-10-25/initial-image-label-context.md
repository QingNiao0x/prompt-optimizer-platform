# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: image-prompt-history.spec.ts >> 历史页分类直接展示图像记录，支持模式筛选、分页与刷新定位
- Location: e2e\image-prompt-history.spec.ts:65:1

# Error details

```
Test timeout of 60000ms exceeded.
```

```
Error: locator.click: Test timeout of 60000ms exceeded.
Call log:
  - waiting for getByRole('tab', { name: '提示词增强', exact: true })

```

# Page snapshot

```yaml
- generic [ref=f1e3]:
  - banner [ref=f1e4]:
    - link "返回 PromptOptimizer 首页" [ref=f1e5] [cursor=pointer]:
      - /url: /
      - generic [ref=f1e10]:
        - strong [ref=f1e11]: PromptOptimizer
        - generic [ref=f1e12]: Prompt context studio
    - navigation "主导航" [ref=f1e13]:
      - link "首页" [ref=f1e14] [cursor=pointer]:
        - /url: /
      - link "提示词优化" [ref=f1e18] [cursor=pointer]:
        - /url: /workbench
      - link "图像转提示词" [ref=f1e22] [cursor=pointer]:
        - /url: /image-to-prompt
      - link "文章祛AI味" [ref=f1e27] [cursor=pointer]:
        - /url: /text-polish
      - link "历史" [ref=f1e31] [cursor=pointer]:
        - /url: /history
      - link "设置" [ref=f1e37] [cursor=pointer]:
        - /url: /settings
    - generic [ref=f1e41]:
      - generic [ref=f1e42]:
        - generic "test@example.com" [ref=f1e43]: 测试用户
        - button "退出登录" [ref=f1e44] [cursor=pointer]
      - generic "模型版本：平台模型（由平台管理员维护）" [ref=f1e45]: 平台模型
  - main [ref=f1e48]:
    - generic [ref=f1e49]:
      - generic [ref=f1e51]:
        - text: History
        - heading "优化历史" [level=1] [ref=f1e52]
        - paragraph [ref=f1e53]: 查看提示词优化、图像转提示词与文章祛AI味的使用记录，载入后继续处理。图像历史不长期保存原图。
      - generic "历史记录类型" [ref=f1e54]:
        - tablist [ref=f1e58]:
          - tab "提示词优化" [ref=f1e60]
          - tab "图像转提示词" [selected] [ref=f1e61]
          - tab "文章祛AI味" [ref=f1e62]
        - tabpanel "图像转提示词" [ref=f1e64]:
          - region "图像转提示词历史记录" [ref=f1e65]:
            - generic [ref=f1e66]:
              - generic [ref=f1e68] [cursor=pointer]:
                - generic:
                  - combobox "图像记录模式" [ref=f1e70]
                  - generic [ref=f1e71]: 全部模式
              - button "刷新记录" [ref=f1e76] [cursor=pointer]
              - button "前往图像转提示词" [ref=f1e81] [cursor=pointer]
            - paragraph [ref=f1e83]: 生成成功后自动记录提示词、模式、模型与必要分析。原图不长期保存，载入历史不会重新识图或调用模型。
            - generic [ref=f1e86]:
              - table [ref=f1e88]:
                - rowgroup [ref=f1e96]:
                  - row [ref=f1e97]:
                    - columnheader "生成时间" [ref=f1e98]
                    - columnheader "模式" [ref=f1e100]
                    - columnheader "模型" [ref=f1e102]
                    - columnheader "生成的提示词" [ref=f1e104]
                    - columnheader "参考图" [ref=f1e106]
                    - columnheader "操作" [ref=f1e108]
              - table [ref=f1e114]:
                - rowgroup [ref=f1e122]:
                  - row [ref=f1e123]:
                    - cell "2026/10/25 20:00:00" [ref=f1e124]
                    - cell "参考图还原" [ref=f1e126]
                    - cell "受控视觉模型" [ref=f1e130]
                    - cell "历史当前正文 0：白底蓝圆黄色方形参考图" [ref=f1e132]
                    - cell "1 张" [ref=f1e135]
                    - cell [ref=f1e137]:
                      - generic [ref=f1e138]:
                        - button "查看" [ref=f1e139] [cursor=pointer]
                        - button "载入" [ref=f1e144] [cursor=pointer]
                        - button "删除" [ref=f1e149] [cursor=pointer]
                  - row [ref=f1e154]:
                    - cell "2026/10/25 19:59:59" [ref=f1e155]
                    - cell "Plan 新图创作" [ref=f1e157]
                    - cell "受控视觉模型" [ref=f1e161]
                    - cell "历史当前正文 1：创作全新植物海报" [ref=f1e163]
                    - cell "0 张" [ref=f1e166]
                    - cell [ref=f1e168]:
                      - generic [ref=f1e169]:
                        - button "查看" [ref=f1e170] [cursor=pointer]
                        - button "载入" [ref=f1e175] [cursor=pointer]
                        - button "删除" [ref=f1e180] [cursor=pointer]
                  - row [ref=f1e185]:
                    - cell "2026/10/25 19:59:58" [ref=f1e186]
                    - cell "参考图还原" [ref=f1e188]
                    - cell "受控视觉模型" [ref=f1e192]
                    - cell "历史当前正文 2：白底蓝圆黄色方形参考图" [ref=f1e194]
                    - cell "1 张" [ref=f1e197]
                    - cell [ref=f1e199]:
                      - generic [ref=f1e200]:
                        - button "查看" [ref=f1e201] [cursor=pointer]
                        - button "载入" [ref=f1e206] [cursor=pointer]
                        - button "删除" [ref=f1e211] [cursor=pointer]
                  - row [ref=f1e216]:
                    - cell "2026/10/25 19:59:57" [ref=f1e217]
                    - cell "Plan 新图创作" [ref=f1e219]
                    - cell "受控视觉模型" [ref=f1e223]
                    - cell "历史当前正文 3：创作全新植物海报" [ref=f1e225]
                    - cell "0 张" [ref=f1e228]
                    - cell [ref=f1e230]:
                      - generic [ref=f1e231]:
                        - button "查看" [ref=f1e232] [cursor=pointer]
                        - button "载入" [ref=f1e237] [cursor=pointer]
                        - button "删除" [ref=f1e242] [cursor=pointer]
                  - row [ref=f1e247]:
                    - cell "2026/10/25 19:59:56" [ref=f1e248]
                    - cell "参考图还原" [ref=f1e250]
                    - cell "受控视觉模型" [ref=f1e254]
                    - cell "历史当前正文 4：白底蓝圆黄色方形参考图" [ref=f1e256]
                    - cell "1 张" [ref=f1e259]
                    - cell [ref=f1e261]:
                      - generic [ref=f1e262]:
                        - button "查看" [ref=f1e263] [cursor=pointer]
                        - button "载入" [ref=f1e268] [cursor=pointer]
                        - button "删除" [ref=f1e273] [cursor=pointer]
                  - row [ref=f1e278]:
                    - cell "2026/10/25 19:59:55" [ref=f1e279]
                    - cell "Plan 新图创作" [ref=f1e281]
                    - cell "受控视觉模型" [ref=f1e285]
                    - cell "历史当前正文 5：创作全新植物海报" [ref=f1e287]
                    - cell "0 张" [ref=f1e290]
                    - cell [ref=f1e292]:
                      - generic [ref=f1e293]:
                        - button "查看" [ref=f1e294] [cursor=pointer]
                        - button "载入" [ref=f1e299] [cursor=pointer]
                        - button "删除" [ref=f1e304] [cursor=pointer]
                  - row [ref=f1e309]:
                    - cell "2026/10/25 19:59:54" [ref=f1e310]
                    - cell "参考图还原" [ref=f1e312]
                    - cell "受控视觉模型" [ref=f1e316]
                    - cell "历史当前正文 6：白底蓝圆黄色方形参考图" [ref=f1e318]
                    - cell "1 张" [ref=f1e321]
                    - cell [ref=f1e323]:
                      - generic [ref=f1e324]:
                        - button "查看" [ref=f1e325] [cursor=pointer]
                        - button "载入" [ref=f1e330] [cursor=pointer]
                        - button "删除" [ref=f1e335] [cursor=pointer]
                  - row [ref=f1e340]:
                    - cell "2026/10/25 19:59:53" [ref=f1e341]
                    - cell "Plan 新图创作" [ref=f1e343]
                    - cell "受控视觉模型" [ref=f1e347]
                    - cell "历史当前正文 7：创作全新植物海报" [ref=f1e349]
                    - cell "0 张" [ref=f1e352]
                    - cell [ref=f1e354]:
                      - generic [ref=f1e355]:
                        - button "查看" [ref=f1e356] [cursor=pointer]
                        - button "载入" [ref=f1e361] [cursor=pointer]
                        - button "删除" [ref=f1e366] [cursor=pointer]
                  - row [ref=f1e371]:
                    - cell "2026/10/25 19:59:52" [ref=f1e372]
                    - cell "参考图还原" [ref=f1e374]
                    - cell "受控视觉模型" [ref=f1e378]
                    - cell "历史当前正文 8：白底蓝圆黄色方形参考图" [ref=f1e380]
                    - cell "1 张" [ref=f1e383]
                    - cell [ref=f1e385]:
                      - generic [ref=f1e386]:
                        - button "查看" [ref=f1e387] [cursor=pointer]
                        - button "载入" [ref=f1e392] [cursor=pointer]
                        - button "删除" [ref=f1e397] [cursor=pointer]
                  - row [ref=f1e402]:
                    - cell "2026/10/25 19:59:51" [ref=f1e403]
                    - cell "Plan 新图创作" [ref=f1e405]
                    - cell "受控视觉模型" [ref=f1e409]
                    - cell "历史当前正文 9：创作全新植物海报" [ref=f1e411]
                    - cell "0 张" [ref=f1e414]
                    - cell [ref=f1e416]:
                      - generic [ref=f1e417]:
                        - button "查看" [ref=f1e418] [cursor=pointer]
                        - button "载入" [ref=f1e423] [cursor=pointer]
                        - button "删除" [ref=f1e428] [cursor=pointer]
            - generic [ref=f1e434]:
              - generic [ref=f1e435]: Total 12
              - button "Go to previous page" [disabled] [ref=f1e436]
              - list [ref=f1e437]:
                - listitem "page 1" [ref=f1e438]: "1"
                - listitem "page 2" [ref=f1e439] [cursor=pointer]: "2"
              - button "Go to next page" [ref=f1e440] [cursor=pointer]
              - generic [ref=f1e443] [cursor=pointer]:
                - generic:
                  - combobox [ref=f1e445]
                  - generic [ref=f1e446]: 10/page
  - button "切换为深色主题" [ref=f1e451] [cursor=pointer]
```

# Test source

```ts
  1   | import { expect, test, type Page } from '@playwright/test';
  2   | import { mockAuthentication } from './authFixture';
  3   | import type { ImagePromptDocument } from '../src/types/imagePrompt';
  4   | 
  5   | const model = { id: 'history-fixture', displayName: '受控视觉模型', vision: true, maxImages: 3, evidence: '受控测试', mock: false };
  6   | const makeRecord = (index: number): ImagePromptDocument => {
  7   |   const mode = index % 2 === 0 ? 'REFERENCE_RECONSTRUCTION' : 'CUSTOM_CREATION';
  8   |   return {
  9   |     operationId: `operation-${index}`, recordId: `record-${index}`, mode,
  10  |     optimizedPrompt: `历史当前正文 ${index}：${mode === 'CUSTOM_CREATION' ? '创作全新植物海报' : '白底蓝圆黄色方形参考图'}`,
  11  |     input: { mode, modelId: model.id, images: [], creationIntent: mode === 'CUSTOM_CREATION' ? '新的植物展海报' : '',
  12  |       exactText: '春日·2026', context: '社区植物展资料', language: 'ZH', targetTool: '通用文生图' },
  13  |     sources: mode === 'REFERENCE_RECONSTRUCTION' ? [{ id: 'private-asset', name: '受控参考图.png', hash: 'fixture',
  14  |       role: 'GENERAL', width: 900, height: 600, expiresAt: '2026-10-24T01:00:00Z' }] : [],
  15  |     analysis: mode === 'REFERENCE_RECONSTRUCTION'
  16  |       ? [{ imageId: 'private-asset', category: '布局', description: '蓝色圆形在黄色方形左侧', status: 'OBSERVED' }] : [],
  17  |     questions: mode === 'CUSTOM_CREATION' ? [{ id: 'style', type: 'SINGLE_CHOICE', question: '希望采用哪种风格？',
  18  |       options: [{ id: 'illustration', label: '柔和插画' }], required: true }] : [],
  19  |     answers: mode === 'CUSTOM_CREATION' ? [{ questionId: 'style', optionIds: ['illustration'], customText: '保留纸张纹理' }] : [],
  20  |     warnings: ['请核对画面文字'], modelId: model.id, modelName: model.displayName,
  21  |     mock: false, editRevision: 2, historySaved: true, persistentHistory: true,
  22  |     createdAt: new Date(Date.UTC(2026, 9, 25, 12, 0, -index)).toISOString(),
  23  |   };
  24  | };
  25  | 
  26  | /** 所有业务请求均为有状态夹具；历史查看、复制和载入不得误发视觉调用或创建账号。 */
  27  | async function setup(page: Page) {
  28  |   const state = { records: Array.from({ length: 12 }, (_, index) => makeRecord(index)), requests: [] as URL[], deletes: [] as string[], paidCalls: 0 };
  29  |   await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记的历史测试请求' } } }));
  30  |   await mockAuthentication(page);
  31  |   await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [{ ...model, provider: 'fixture', defaultModel: true }] } }));
  32  |   await page.route('**/api/v2/optimization-history**', route => route.fulfill({ json: { data: { records: [{ id: 'text-record',
  33  |     templateCode: 'GENERAL', rawPromptPreview: '原有文本增强记录', providerName: 'fixture', modelName: 'text-fixture',
  34  |     modelVersion: '受控文字模型', mock: false, latencyMs: 10, createdAt: '2026-10-25T00:00:00Z' }], total: 1, current: 1, size: 10, pages: 1 } } }));
  35  |   await page.route('**/api/v1/image**', route => {
  36  |     const url = new URL(route.request().url());
  37  |     const method = route.request().method();
  38  |     if (url.pathname.endsWith('/capabilities')) return route.fulfill({ json: { data: { models: [model], maxImageBytes: 10485760,
  39  |       maxPixels: 15000000, maxEdge: 8192, maxImages: 3, assetTtlSeconds: 3600, persistentHistory: true } } });
  40  |     if (url.pathname.endsWith('/history') && method === 'GET') {
  41  |       state.requests.push(url);
  42  |       const current = Number(url.searchParams.get('current'));
  43  |       const size = Number(url.searchParams.get('size'));
  44  |       const mode = url.searchParams.get('mode');
  45  |       const records = state.records.filter(record => !mode || record.mode === mode);
  46  |       return route.fulfill({ json: { data: { records: records.slice((current - 1) * size, current * size),
  47  |         current, size, total: records.length, pages: Math.ceil(records.length / size) } } });
  48  |     }
  49  |     const id = url.pathname.match(/\/history\/([^/]+)$/)?.[1];
  50  |     if (id && method === 'DELETE') {
  51  |       state.deletes.push(id); state.records = state.records.filter(record => record.recordId !== id);
  52  |       return route.fulfill({ json: { data: null } });
  53  |     }
  54  |     if (id && method === 'GET') {
  55  |       const record = state.records.find(record => record.recordId === id);
  56  |       return route.fulfill({ status: record ? 200 : 404, json: { data: record } });
  57  |     }
  58  |     if (method === 'POST') state.paidCalls++;
  59  |     return route.fulfill({ status: 501, json: { error: { message: '历史操作不应产生此请求' } } });
  60  |   });
  61  |   return state;
  62  | }
  63  | const visibleRows = (page: Page) => page.locator('.image-history-table:visible .el-table__row, .image-history-cards:visible .image-history-card');
  64  | 
  65  | test('历史页分类直接展示图像记录，支持模式筛选、分页与刷新定位', async ({ page }, testInfo) => {
  66  |   const state = await setup(page);
  67  |   await page.goto('/history');
  68  |   await expect(page.getByText('原有文本增强记录', { exact: true }).filter({ visible: true })).toBeVisible();
  69  |   expect(state.requests).toHaveLength(0);
  70  |   await page.getByRole('tab', { name: '图像转提示词', exact: true }).click();
  71  |   await expect(visibleRows(page)).toHaveCount(10);
  72  |   await expect(page.locator('.mode-filter')).toContainText('全部模式');
  73  |   await expect(visibleRows(page).first()).toContainText('参考图还原');
  74  |   await expect(visibleRows(page).nth(1)).toContainText('Plan 新图创作');
  75  |   await expect(visibleRows(page).first()).toContainText(model.displayName);
  76  |   const pagination = page.locator('.image-history-pagination');
  77  |   await pagination.locator('.el-pager li', { hasText: /^2$/ }).click();
  78  |   await expect(visibleRows(page)).toHaveCount(2);
  79  |   expect(state.requests.at(-1)?.searchParams.get('current')).toBe('2');
  80  |   await pagination.locator('.el-select').click();
  81  |   await page.getByRole('option', { name: '20/page', exact: true }).click();
  82  |   await expect(visibleRows(page)).toHaveCount(12);
  83  |   expect(state.requests.at(-1)?.searchParams.get('current')).toBe('1');
  84  |   expect(state.requests.at(-1)?.searchParams.get('size')).toBe('20');
  85  |   await page.locator('.mode-filter').click();
  86  |   await page.getByRole('option', { name: 'Plan 新图创作', exact: true }).click();
  87  |   await expect(visibleRows(page)).toHaveCount(6);
  88  |   expect(state.requests.at(-1)?.searchParams.get('mode')).toBe('CUSTOM_CREATION');
  89  |   await page.screenshot({ path: testInfo.outputPath('image-history-list.png'), fullPage: true, animations: 'disabled' });
  90  |   expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  91  |   await page.reload();
  92  |   await expect(page.getByRole('tab', { name: '图像转提示词', exact: true })).toHaveAttribute('aria-selected', 'true');
  93  |   await expect(visibleRows(page)).toHaveCount(10);
> 94  |   await page.getByRole('tab', { name: '提示词增强', exact: true }).click();
      |                                                               ^ Error: locator.click: Test timeout of 60000ms exceeded.
  95  |   await expect(page.getByText('原有文本增强记录', { exact: true }).filter({ visible: true })).toBeVisible();
  96  |   expect(state.paidCalls).toBe(0);
  97  | });
  98  | 
  99  | test('图像记录详情保留题答和当前正文，复制降级与载入不重复调用模型', async ({ page }, testInfo) => {
  100 |   const state = await setup(page);
  101 |   await page.goto('/history?type=image-prompt');
  102 |   await visibleRows(page).first().getByRole('button', { name: '查看', exact: true }).click();
  103 |   const detail = page.getByRole('dialog', { name: '图像提示词记录详情' });
  104 |   await expect(detail).toContainText('受控参考图.png');
  105 |   await expect(detail).toContainText('蓝色圆形在黄色方形左侧');
  106 |   await expect(detail.locator('img')).toHaveCount(0);
  107 |   await detail.getByRole('button', { name: 'Close this dialog' }).click();
  108 |   await visibleRows(page).nth(1).getByRole('button', { name: '查看', exact: true }).click();
  109 |   await expect(detail).toContainText('新的植物展海报');
  110 |   await expect(detail).toContainText('春日·2026');
  111 |   await expect(detail).toContainText('希望采用哪种风格？');
  112 |   await expect(detail).toContainText('柔和插画；保留纸张纹理');
  113 |   await page.screenshot({ path: testInfo.outputPath('image-history-detail.png'), fullPage: true, animations: 'disabled' });
  114 |   await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true,
  115 |     value: { writeText: () => Promise.reject(new Error('controlled clipboard denial')) } }));
  116 |   await detail.getByRole('button', { name: '复制提示词', exact: true }).click();
  117 |   await expect(page.getByRole('textbox', { name: '手动复制历史图像提示词' })).toHaveValue(state.records[1]!.optimizedPrompt);
  118 |   await page.getByRole('dialog', { name: '手动复制提示词' }).getByRole('button', { name: 'Close this dialog' }).click();
  119 |   await detail.getByRole('button', { name: 'Close this dialog' }).click();
  120 |   await visibleRows(page).nth(1).getByRole('button', { name: '载入', exact: true }).click();
  121 |   await expect(page).toHaveURL(/\/image-to-prompt\?pane=result/);
  122 |   await expect(page.locator('.prompt-text')).toHaveText(state.records[1]!.optimizedPrompt);
  123 |   expect(state.paidCalls).toBe(0);
  124 | });
  125 | 
  126 | test('删除确认可取消，删空最后一页后回退且刷新不恢复已删除记录', async ({ page }) => {
  127 |   const state = await setup(page);
  128 |   await page.goto('/history?type=image-prompt');
  129 |   await expect(visibleRows(page)).toHaveCount(10);
  130 |   await page.locator('.image-history-pagination .el-pager li', { hasText: /^2$/ }).click();
  131 |   await expect(visibleRows(page)).toHaveCount(2);
  132 |   await visibleRows(page).first().getByRole('button', { name: '删除', exact: true }).click();
  133 |   await page.getByRole('button', { name: '取消', exact: true }).click();
  134 |   expect(state.deletes).toHaveLength(0);
  135 |   for (const count of [1, 10]) {
  136 |     await visibleRows(page).first().getByRole('button', { name: '删除', exact: true }).click();
  137 |     await page.getByRole('button', { name: '删除记录', exact: true }).click();
  138 |     await expect(visibleRows(page)).toHaveCount(count);
  139 |   }
  140 |   expect(state.deletes).toEqual(['record-10', 'record-11']);
  141 |   await expect(page.locator('.image-history-pagination .el-pager li.is-active')).toHaveText('1');
  142 |   await page.getByRole('button', { name: '刷新记录', exact: true }).click();
  143 |   await expect(visibleRows(page)).toHaveCount(10);
  144 |   await expect(visibleRows(page).filter({ hasText: '历史当前正文 10' })).toHaveCount(0);
  145 |   expect(state.paidCalls).toBe(0);
  146 | });
  147 | 
  148 | test('历史分类切换身份后拒绝旧列表的迟到响应', async ({ page }) => {
  149 |   await setup(page);
  150 |   let release!: () => void;
  151 |   let requested = false;
  152 |   let switched = false;
  153 |   const waiting = new Promise<void>(resolve => { release = resolve; });
  154 |   await page.route(/\/api\/v1\/image-prompts\/history\?/, async route => {
  155 |     if (switched) return route.fulfill({ json: { data: { records: [], total: 0, current: 1, size: 10, pages: 0 } } });
  156 |     requested = true;
  157 |     await waiting;
  158 |     return route.fulfill({ json: { data: { records: [{ ...makeRecord(0), optimizedPrompt: '旧身份私有图像记录' }],
  159 |       total: 1, current: 1, size: 10, pages: 1 } } });
  160 |   });
  161 |   await page.goto('/history?type=image-prompt');
  162 |   await expect.poll(() => requested).toBe(true);
  163 |   switched = true;
  164 |   await page.evaluate(() => {
  165 |     type Host = HTMLElement & { __vue_app__?: { config: { globalProperties: { $pinia: { _s: Map<string, { user: unknown }> } } } } };
  166 |     const auth = document.querySelector<Host>('#app')?.__vue_app__?.config.globalProperties.$pinia._s.get('auth');
  167 |     if (!auth) throw new Error('Missing controlled Pinia fixture');
  168 |     auth.user = { userId: 'controlled-other', tenantId: 'controlled-other', workspaceId: 'controlled-other',
  169 |       displayName: '其他夹具', email: 'other@example.test', platformAdmin: false };
  170 |   });
  171 |   await expect(page.getByText('还没有图像转提示词记录', { exact: true })).toBeVisible();
  172 |   const oldResponse = page.waitForResponse(response => response.url().includes('/image-prompts/history?'));
  173 |   release(); await oldResponse;
  174 |   await expect(page.getByText('旧身份私有图像记录')).toHaveCount(0);
  175 | });
  176 | 
```