# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: history.spec.ts >> 历史列表默认第一页 10 条，翻页和每页条数各只请求一次
- Location: e2e\history.spec.ts:16:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByRole('heading', { name: '优化历史' })
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByRole('heading', { name: '优化历史' })

```

```yaml
- text: "[plugin:vite:import-analysis] Failed to resolve import \"./trial\" from \"src/stores/imagePrompt.ts\". Does the file exist? E:/MyProject/prompt-optimizer-platform/apps/web/src/stores/imagePrompt.ts:6:30 4 | import { getApiErrorMessage } from \"@/services/http\"; 5 | import { useAuthStore } from \"./auth\"; 6 | import { useTrialStore } from \"./trial\"; | ^ 7 | export const useImagePromptStore = defineStore(\"imagePrompt\", () => { 8 | const mode = ref(\"REFERENCE_RECONSTRUCTION\"); at TransformPluginContext._formatLog (file:///E:/MyProject/prompt-optimizer-platform/apps/web/node_modules/vite/dist/node/chunks/dep-Dm0c1Wj2.js:42658:41) at TransformPluginContext.error (file:///E:/MyProject/prompt-optimizer-platform/apps/web/node_modules/vite/dist/node/chunks/dep-Dm0c1Wj2.js:42655:16) at normalizeUrl (file:///E:/MyProject/prompt-optimizer-platform/apps/web/node_modules/vite/dist/node/chunks/dep-Dm0c1Wj2.js:40634:23) at processTicksAndRejections (node:internal/process/task_queues:103:5) at file:///E:/MyProject/prompt-optimizer-platform/apps/web/node_modules/vite/dist/node/chunks/dep-Dm0c1Wj2.js:40753:37 at async Promise.all (index 5) at TransformPluginContext.transform (file:///E:/MyProject/prompt-optimizer-platform/apps/web/node_modules/vite/dist/node/chunks/dep-Dm0c1Wj2.js:40680:7) at EnvironmentPluginContainer.transform (file:///E:/MyProject/prompt-optimizer-platform/apps/web/node_modules/vite/dist/node/chunks/dep-Dm0c1Wj2.js:42453:18) at loadAndTransform (file:///E:/MyProject/prompt-optimizer-platform/apps/web/node_modules/vite/dist/node/chunks/dep-Dm0c1Wj2.js:35845:27 Click outside, press Esc key, or fix the code to dismiss. You can also disable this overlay by setting"
- code: server.hmr.overlay
- text: to
- code: "false"
- text: in
- code: vite.config.js
- text: .
```

# Test source

```ts
  1   | import { expect, test } from '@playwright/test';
  2   | import { mockAuthentication } from './authFixture';
  3   | 
  4   | const historyItem = {
  5   |   id: 'history-1',
  6   |   templateCode: 'GENERAL',
  7   |   rawPromptPreview: '查询全球使用 AI 最多的职业',
  8   |   providerName: 'mock',
  9   |   modelName: 'deterministic-enhancer-v1',
  10  |   modelVersion: '历史版本-20261001',
  11  |   mock: true,
  12  |   latencyMs: 12,
  13  |   createdAt: '2026-09-24T10:00:00Z',
  14  | };
  15  | 
  16  | test('历史列表默认第一页 10 条，翻页和每页条数各只请求一次', async ({ page }) => {
  17  |   await mockAuthentication(page);
  18  |   const historyRequests: URL[] = [];
  19  |   await page.route('**/api/v2/optimization-history**', async route => {
  20  |     if (route.request().method() !== 'GET' || route.request().url().includes('/optimization-history/')) {
  21  |       await route.continue();
  22  |       return;
  23  |     }
  24  |     const requestUrl = new URL(route.request().url());
  25  |     historyRequests.push(requestUrl);
  26  |     await route.fulfill({
  27  |       status: 200,
  28  |       json: {
  29  |         requestId: 'history-request',
  30  |         data: {
  31  |           records: [historyItem],
  32  |           current: Number(requestUrl.searchParams.get('current') ?? '1'),
  33  |           size: Number(requestUrl.searchParams.get('size') ?? '10'),
  34  |           total: 35,
  35  |           pages: 4,
  36  |         },
  37  |       },
  38  |     });
  39  |   });
  40  | 
  41  |   await page.goto('/history');
> 42  |   await expect(page.getByRole('heading', { name: '优化历史' })).toBeVisible();
      |                                                             ^ Error: expect(locator).toBeVisible() failed
  43  |   await expect(page.locator('.model-cell:visible, .history-mobile-card__model:visible').first())
  44  |     .toHaveText('历史版本-20261001');
  45  |   await expect.poll(() => historyRequests.length).toBe(1);
  46  |   expect(historyRequests[0]?.searchParams.get('current')).toBe('1');
  47  |   expect(historyRequests[0]?.searchParams.get('size')).toBe('10');
  48  |   expect(historyRequests[0]?.searchParams.has('keyword')).toBe(false);
  49  |   expect(historyRequests[0]?.searchParams.has('dateRange')).toBe(false);
  50  | 
  51  |   await page.getByLabel('原始提示词搜索').fill('AI 职业');
  52  |   await page.getByLabel('原始提示词搜索').press('Enter');
  53  |   await expect.poll(() => historyRequests.length).toBe(2);
  54  |   expect(historyRequests[1]?.searchParams.get('keyword')).toBe('AI 职业');
  55  |   expect(historyRequests[1]?.searchParams.get('current')).toBe('1');
  56  |   expect(historyRequests[1]?.searchParams.get('size')).toBe('10');
  57  | 
  58  |   const pagination = page.locator('.pagination-row .el-pagination');
  59  |   await pagination.locator('.el-pager li', { hasText: /^2$/ }).click();
  60  |   await expect.poll(() => historyRequests.length).toBe(3);
  61  |   expect(historyRequests[2]?.searchParams.get('current')).toBe('2');
  62  |   expect(historyRequests[2]?.searchParams.get('size')).toBe('10');
  63  |   expect(historyRequests[2]?.searchParams.get('keyword')).toBe('AI 职业');
  64  |   await page.waitForTimeout(300);
  65  |   expect(historyRequests.length).toBe(3);
  66  | 
  67  |   await pagination.locator('.el-select').click();
  68  |   await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '20/page' }).click();
  69  |   await expect.poll(() => historyRequests.length).toBe(4);
  70  |   expect(historyRequests[3]?.searchParams.get('current')).toBe('1');
  71  |   expect(historyRequests[3]?.searchParams.get('size')).toBe('20');
  72  |   expect(historyRequests[3]?.searchParams.get('keyword')).toBe('AI 职业');
  73  |   await page.waitForTimeout(300);
  74  |   expect(historyRequests.length).toBe(4);
  75  | });
  76  | 
  77  | test('历史模型显示短名且保留其他模型名称中的数字', async ({ page }) => {
  78  |   await mockAuthentication(page);
  79  |   await page.route('**/api/v2/optimization-history**', route => route.fulfill({
  80  |     status: 200,
  81  |     json: { requestId: 'history-model-version', data: {
  82  |       records: [
  83  |         { ...historyItem, modelVersion: 'DeepSeek-V4-Pro-0813' },
  84  |         { ...historyItem, id: 'history-2', modelVersion: '其他模型-20261001' },
  85  |       ], current: 1, size: 10, total: 2, pages: 1,
  86  |     } },
  87  |   }));
  88  |   await page.goto('/history');
  89  |   const modelCells = page.locator('.model-cell:visible, .history-mobile-card__model:visible');
  90  |   await expect(modelCells).toHaveText(['DeepSeek-V4-Pro', '其他模型-20261001']);
  91  | });
  92  | 
  93  | test('日期范围选择时结束日高亮跟随指针，且选择过程不发起搜索', async ({ page }) => {
  94  |   await mockAuthentication(page);
  95  |   const historyRequests: URL[] = [];
  96  |   await page.route('**/api/v2/optimization-history**', async route => {
  97  |     if (route.request().method() !== 'GET') {
  98  |       await route.continue();
  99  |       return;
  100 |     }
  101 |     historyRequests.push(new URL(route.request().url()));
  102 |     await route.fulfill({
  103 |       status: 200,
  104 |       json: {
  105 |         requestId: 'history-request',
  106 |         data: {
  107 |           records: [historyItem],
  108 |           current: 1,
  109 |           size: 10,
  110 |           total: 1,
  111 |           pages: 1,
  112 |         },
  113 |       },
  114 |     });
  115 |   });
  116 | 
  117 |   await page.goto('/history');
  118 |   await expect.poll(() => historyRequests.length).toBe(1);
  119 |   await page.getByPlaceholder('开始日期').click();
  120 |   const popper = page.locator('.history-date-popper:visible');
  121 |   const panel = popper.locator('.el-date-range-picker');
  122 |   await expect(panel).toBeVisible();
  123 |   const panelBox = await panel.boundingBox();
  124 |   const viewport = page.viewportSize();
  125 |   expect(panelBox).not.toBeNull();
  126 |   expect(panelBox!.x).toBeGreaterThanOrEqual(-1);
  127 |   expect(panelBox!.x + panelBox!.width).toBeLessThanOrEqual((viewport?.width ?? panelBox!.width) + 1);
  128 | 
  129 |   const leftMonth = panel.locator('.el-date-range-picker__content.is-left');
  130 |   const dayCell = (label: string) => leftMonth.locator(
  131 |     'td.available:not(.prev-month):not(.next-month)',
  132 |     { hasText: new RegExp(`^\\s*${label}\\s*$`) },
  133 |   );
  134 |   await dayCell('10').click();
  135 |   await expect(popper).toHaveClass(/is-selecting-end/);
  136 |   await dayCell('18').hover();
  137 |   await expect(dayCell('18')).toHaveClass(/in-range/);
  138 |   await expect(dayCell('2')).not.toHaveClass(/in-range/);
  139 | 
  140 |   const overflowDay = leftMonth.locator('td.next-month', { hasText: /^\s*1\s*$/ }).first();
  141 |   const otherPanelDay = panel.locator(
  142 |     '.el-date-range-picker__content.is-right td:not(.prev-month):not(.next-month)',
```