# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-prompt.spec.ts >> 自然正文编辑、失败保稿、保存复制和持久化撤销保持同一有效内容
- Location: e2e\natural-prompt.spec.ts:98:1

# Error details

```
Error: expect(locator).toHaveText(expected) failed

Locator: getByTestId('compact-prompt')
Timeout: 5000ms
- Expected  - 2
+ Received  + 0

  请交付客户延期邮件主题与正文，日期待定，保留不赔偿的选择。
-
- 明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。

Call log:
  - Expect "toHaveText" with timeout 5000ms
  - waiting for getByTestId('compact-prompt')
    14 × locator resolved to <article data-v-aa10c3d5="" class="compact-prompt" data-testid="compact-prompt">请交付客户延期邮件主题与正文，日期待定，保留不赔偿的选择。</article>
       - unexpected value "请交付客户延期邮件主题与正文，日期待定，保留不赔偿的选择。"

```

```yaml
- article: 请交付客户延期邮件主题与正文，日期待定，保留不赔偿的选择。
```

# Test source

```ts
  15  |   ambiguities: [], appliedConstraints: [rule],
  16  |   constraintGroups: { platformMandatory: [rule], taskSpecific: [], additionalPermissions: [] },
  17  |   provider: { provider: 'mock', model: 'fixture', mock: true }, latencyMs: 1,
  18  | });
  19  | 
  20  | /** 固定响应验证浏览器闭环；路由白名单阻止测试误发真实模型请求，不创建用户账号。 */
  21  | const setup = async (page: Page, planning: 'off' | 'zero' | 'question') => {
  22  |   await mockAuthentication(page);
  23  |   await page.addInitScript(enabled => localStorage.setItem('prompt-optimizer.plan-mode.v1',
  24  |     JSON.stringify({ enabled, introSeen: true })), planning !== 'off');
  25  |   await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  26  |   await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
  27  |     { id: 'mock', displayName: '受控测试模型', provider: 'mock', defaultModel: true },
  28  |   ] } }));
  29  |   await page.route('**/api/v1/optimizations/plan-events', route => route.fulfill({ status: 204 }));
  30  |   const state = { current: fixture(), requests: [] as OptimizationRequest[], plans: 0, edits: 0, failSave: false };
  31  |   await page.route('**/api/v2/**', async route => {
  32  |     const path = new URL(route.request().url()).pathname;
  33  |     if (path === '/api/v2/optimizations/plan') {
  34  |       state.plans++;
  35  |       return route.fulfill({ json: { data: {
  36  |         summary: '仅确认用户保留的交付日期', templateCode: 'AUTO', provider: fixture().provider,
  37  |         latencyMs: 1, planId: '22222222-2222-4222-8222-222222222222',
  38  |         questions: planning === 'zero' ? [] : [{ id: 'date', question: '如何处理新日期？', hint: '影响邮件承诺',
  39  |           type: 'FREE_TEXT', options: [], examples: [], allowCustomAnswer: true }],
  40  |       } } });
  41  |     }
  42  |     if (path === '/api/v2/optimizations') {
  43  |       const input = route.request().postDataJSON() as OptimizationRequest;
  44  |       state.requests.push(input);
  45  |       const current = fixture();
  46  |       if (input.planConfirmation?.answers.length) current.optimizedPrompt += '\n' + input.planConfirmation.answers[0]!.answer;
  47  |       state.current = current;
  48  |       // 原生TEXT协议省略sections，验证API适配器不会要求模型制造四段。
  49  |       return route.fulfill({ json: { data: { ...current, sections: undefined } } });
  50  |     }
  51  |     if (path.endsWith('/document')) {
  52  |       state.edits++;
  53  |       if (state.failSave) return route.fulfill({ status: 409,
  54  |         json: { error: { code: 'VERSION_CONFLICT', message: '记录版本已变化，请保留修改稿后核对。' } } });
  55  |       const input = route.request().postDataJSON() as { optimizedPrompt: string; expectedRevision: number };
  56  |       expect(input.expectedRevision).toBe(state.current.editRevision);
  57  |       state.current = { ...state.current, optimizedPrompt: input.optimizedPrompt, editRevision: input.expectedRevision + 1 };
  58  |       return route.fulfill({ json: { data: { optimizedPrompt: state.current.optimizedPrompt, editRevision: state.current.editRevision } } });
  59  |     }
  60  |     if (path === '/api/v2/optimization-history') {
  61  |       return route.fulfill({ json: { data: { records: [{ id: state.current.recordId,
  62  |         rawPromptPreview: raw, templateCode: 'GENERAL', providerName: 'mock', modelName: 'fixture', mock: true,
  63  |         latencyMs: 1, createdAt: '2026-10-19T00:00:00Z', resultKind: 'TEXT' }], total: 1, current: 1, size: 10, pages: 1 } } });
  64  |     }
  65  |     if (path.startsWith('/api/v2/optimization-history/')) {
  66  |       return route.fulfill({ json: { data: { ...state.current, id: state.current.recordId, rawPrompt: raw,
  67  |         contextSummary: {}, includePermissionBoundaries: true, conversationHistory: [],
  68  |         permissionPolicy: {}, providerName: 'mock', modelName: 'fixture', mock: true,
  69  |         latencyMs: 1, createdAt: '2026-10-19T00:00:00Z' } } });
  70  |     }
  71  |     return route.fulfill({ status: 400, json: { error: { message: '测试未授权此接口' } } });
  72  |   });
  73  |   return state;
  74  | };
  75  | 
  76  | const generate = async (page: Page, plan = false) => {
  77  |   await page.goto('/workbench');
  78  |   await openWorkbenchPane(page, 'intent');
  79  |   await page.getByLabel('原始提示词', { exact: true }).fill(raw);
  80  |   await page.getByRole('button', { name: plan ? '先确认并增强' : '直接增强提示词', exact: true }).click();
  81  | };
  82  | 
  83  | test('移除示例参考开关，原文中的示例要求随直接增强请求保留', async ({ page }) => {
  84  |   const state = await setup(page, 'off');
  85  |   const request = '解释排序方法，提供输入与预期输出示例，不虚构业务数据。';
  86  |   await page.goto('/workbench');
  87  |   await openWorkbenchPane(page, 'intent');
  88  |   await expect(page.getByRole('switch', { name: /示例/ })).toHaveCount(0);
  89  |   await expect(page.getByText('示例参考', { exact: true })).toHaveCount(0);
  90  |   await expect(page.getByRole('switch', { name: 'Plan 确认', exact: true })).toHaveCount(1);
  91  |   await page.getByLabel('原始提示词', { exact: true }).fill(request);
  92  |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  93  |   await expect.poll(() => state.requests.length).toBe(1);
  94  |   expect(state.requests[0]!.rawPrompt).toBe(request);
  95  |   expect(state.requests[0]!.enhancement).not.toHaveProperty('includeExamples');
  96  | });
  97  | 
  98  | test('自然正文编辑、失败保稿、保存复制和持久化撤销保持同一有效内容', async ({ page }) => {
  99  |   const state = await setup(page, 'off');
  100 |   await generate(page);
  101 |   await openWorkbenchPane(page, 'result');
  102 |   await expect(page.getByTestId('compact-prompt')).toHaveText(state.current.optimizedPrompt);
  103 |   await page.getByRole('button', { name: '编辑', exact: true }).click();
  104 |   const input = page.getByLabel('编辑完整自然提示词', { exact: true });
  105 |   await expect(input).toHaveValue(state.current.optimizedPrompt);
  106 |   await expect(page.locator('#section-TASK')).toHaveCount(0);
  107 |   const edit = '请交付客户延期邮件主题与正文，日期待定，保留不赔偿的选择。';
  108 |   await input.fill(edit);
  109 |   state.failSave = true;
  110 |   await page.getByRole('button', { name: '保存修改', exact: true }).click();
  111 |   await expect(input).toHaveValue(edit);
  112 |   await expect(page.locator('.edit-error')).toHaveText('记录版本已变化，请保留修改稿后核对。');
  113 |   state.failSave = false;
  114 |   await page.getByRole('button', { name: '保存修改', exact: true }).click();
> 115 |   await expect(page.getByTestId('compact-prompt')).toHaveText(edit + '\n\n' + rule);
      |                                                    ^ Error: expect(locator).toHaveText(expected) failed
  116 |   await page.bringToFront();
  117 |   await page.locator('.copy-main-button').click();
  118 |   await expect(page.locator('.copy-main-button')).toContainText('已复制');
  119 |   const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n');
  120 |   expect(copied).toBe(state.current.optimizedPrompt);
  121 |   const detail = await page.evaluate(async () => (await fetch('/api/v2/optimization-history/11111111-1111-4111-8111-111111111111')).json());
  122 |   expect(detail.data.optimizedPrompt).toBe(copied);
  123 |   await page.getByRole('button', { name: '撤销', exact: true }).click();
  124 |   await expect(page.getByTestId('compact-prompt')).toHaveText(fixture().optimizedPrompt);
  125 |   expect(state.edits).toBe(3);
  126 |   await page.goto('/history');
  127 |   await page.getByRole('button', { name: '查看', exact: true }).filter({ visible: true }).click();
  128 |   await expect(page.getByRole('dialog', { name: '历史记录详情' }).locator('pre').nth(1)).toHaveText(fixture().optimizedPrompt);
  129 | });
  130 | 
  131 | for (const mode of ['zero', 'question'] as const) {
  132 |   test('新Plan完整适配自然正文：' + mode, async ({ page }) => {
  133 |     const state = await setup(page, mode);
  134 |     await generate(page, true);
  135 |     if (mode === 'question') {
  136 |       const dialog = page.getByRole('dialog', { name: '确认关键细节' });
  137 |       await dialog.getByLabel('填写回答').fill('交付日期尚未确定，不得编造日期。');
  138 |       await dialog.getByRole('button', { name: '生成最终提示词', exact: true }).click();
  139 |       await expect(dialog).not.toBeVisible();
  140 |     }
  141 |     await openWorkbenchPane(page, 'result');
  142 |     await expect(page.getByTestId('compact-prompt')).toHaveText(state.current.optimizedPrompt);
  143 |     expect(state.plans).toBe(1);
  144 |     expect(state.requests).toHaveLength(1);
  145 |     expect(state.requests[0]!.planConfirmation?.planId).toBe('22222222-2222-4222-8222-222222222222');
  146 |     expect(state.requests[0]!.planConfirmation?.answers).toHaveLength(mode === 'zero' ? 0 : 1);
  147 |     expect(state.requests[0]!.enhancement.promptFormat).toBe('COMPACT');
  148 |   });
  149 | }
  150 | 
```