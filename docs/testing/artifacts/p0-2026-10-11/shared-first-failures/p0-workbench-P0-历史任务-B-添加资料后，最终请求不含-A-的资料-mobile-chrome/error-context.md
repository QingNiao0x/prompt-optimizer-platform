# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: p0-workbench.spec.ts >> P0 历史任务 B 添加资料后，最终请求不含 A 的资料
- Location: e2e\p0-workbench.spec.ts:93:1

# Error details

```
Test timeout of 60000ms exceeded.
```

```
Error: locator.click: Test timeout of 60000ms exceeded.
Call log:
  - waiting for getByRole('link', { name: '历史', exact: true })

```

# Page snapshot

```yaml
- generic [ref=e3]:
  - banner [ref=e4]:
    - link "返回 PromptOptimizer 首页" [ref=e5] [cursor=pointer]:
      - /url: /
      - strong [ref=e11]: PromptOptimizer
    - navigation "主导航" [ref=e12]:
      - link [ref=e13] [cursor=pointer]:
        - /url: /
      - link [ref=e16] [cursor=pointer]:
        - /url: /workbench
      - link [ref=e19] [cursor=pointer]:
        - /url: /history
      - link [ref=e24] [cursor=pointer]:
        - /url: /settings
    - button "退出登录" [ref=e29] [cursor=pointer]
  - main [ref=e30]:
    - generic [ref=e31]:
      - tablist "工作台分区" [ref=e33]:
        - tab "项目上下文" [selected] [ref=e34] [cursor=pointer]: 上下文
        - tab "写想法" [ref=e35] [cursor=pointer]
        - tab "增强结果" [ref=e36] [cursor=pointer]: 结果
      - complementary [ref=e38]:
        - generic [ref=e40]:
          - text: 01 / Context
          - heading "项目上下文" [level=2] [ref=e41]
        - generic [ref=e42]:
          - generic [ref=e43]:
            - generic [ref=e44]: 自定义项目描述
            - paragraph [ref=e45]: 描述当前项目、架构偏好或团队约束，不是产品公告。
            - generic [ref=e46]:
              - textbox "自定义项目描述" [ref=e47]:
                - /placeholder: 例如：公共卫生研究，数据来自年度死因登记；或 Spring Boot 3 项目，使用 PostgreSQL。
              - generic [ref=e48]: 0 / 4000
          - generic [ref=e49]:
            - generic [ref=e50]:
              - generic [ref=e51]: 本地资料与项目文件
              - button "清空" [ref=e52] [cursor=pointer]
            - button "Choose File" [ref=e54]
            - button "Choose File" [ref=e55]
            - generic [ref=e56]:
              - button "添加上下文" [ref=e59] [cursor=pointer]
              - region "拖入文件或文件夹添加上下文" [ref=e66]:
                - generic [ref=e70]:
                  - strong [ref=e71]: 也可将文件或文件夹拖到这里
                  - generic [ref=e72]: 拖入后自动识别可用内容，需上传解析的文档会先请你确认
              - generic [ref=e73]:
                - paragraph [ref=e74]: 可建立项目索引，也可单独上传报告、论文、表格、演示文稿和图片。
                - paragraph [ref=e75]: 可添加代码、Word、Excel、PPT、PDF 等文件或整个文件夹；支持的浏览器会在本地索引文件夹中的代码，办公文档上传解析前会先请你确认。
                - paragraph [ref=e76]: 确认上传后，文档按 1 MiB 分片发送至本项目后端，解析后只保留最长 2 小时的临时全文索引； 一键增强时仅选取与当前任务相关的片段。
            - note "暂不支持的文件格式" [ref=e77]:
              - generic [ref=e78]: 暂不支持
              - generic [ref=e79]:
                - generic [ref=e80]: .zip
                - generic [ref=e82]: .rar
                - generic [ref=e84]: .7z
                - generic [ref=e86]: .tar
                - generic [ref=e88]: .gz
                - generic [ref=e90]: .exe
                - generic [ref=e92]: .dll
                - generic [ref=e94]: .so
                - generic [ref=e96]: .dylib
                - generic [ref=e98]: .jar
                - generic [ref=e100]: .class
                - generic [ref=e102]: .bin
                - generic [ref=e104]: .mp3
                - generic [ref=e106]: .wav
                - generic [ref=e108]: .mp4
                - generic [ref=e110]: .avi
                - generic [ref=e112]: .mkv
                - generic [ref=e114]: .mov
                - generic [ref=e116]: .flv
                - generic [ref=e118]: .ttf
                - generic [ref=e120]: .otf
                - generic [ref=e122]: .woff
                - generic [ref=e124]: .woff2
                - generic [ref=e126]: .ico
                - generic [ref=e128]: .psd
            - generic [ref=e130]:
              - generic [ref=e131]:
                - generic [ref=e132]: "1"
                - generic [ref=e133]: 个文件已选择
              - list [ref=e137]:
                - listitem [ref=e138]:
                  - generic [ref=e139]: typescript
                  - generic "A.txt" [ref=e140]
                  - button "移除 A.txt" [ref=e141] [cursor=pointer]
          - group [ref=e144]:
            - generic "粘贴当前打开文件" [ref=e145] [cursor=pointer]
            - generic [ref=e149]:
              - generic [ref=e150]:
                - textbox "文件相对路径" [ref=e153]:
                  - /placeholder: src/example.ts
                  - text: A.txt
                - generic [ref=e155] [cursor=pointer]:
                  - generic:
                    - combobox "代码语言" [ref=e157]
                    - generic [ref=e158]: TypeScript
              - textbox "粘贴与当前任务相关的代码片段…" [ref=e164]
              - button "加入上下文" [active] [ref=e165] [cursor=pointer]
        - button "分析上下文资料" [ref=e170] [cursor=pointer]
  - button "切换为深色主题" [ref=e175] [cursor=pointer]
```

# Test source

```ts
  1   | import { expect, test, type Page } from '@playwright/test';
  2   | import type { ContextFileInput, ContextSnapshot, DocumentUploadStatus, OptimizationHistoryDetail, OptimizationResult } from '../src/types/api';
  3   | import { mockAuthentication } from './authFixture';
  4   | import { openWorkbenchPane } from './workbenchPanes';
  5   | 
  6   | const context: ContextSnapshot = { customDescription: '', technologyStack: [], dependencies: [],
  7   |   directoryTree: [], fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'p0' };
  8   | const result: OptimizationResult = { optimizedPrompt: '原始生成稿', sections: [
  9   |   { type: 'BACKGROUND', title: '背景', content: '测试背景' },
  10  |   { type: 'TASK', title: '任务', content: '原任务' },
  11  |   { type: 'OUTPUT', title: '输出', content: '原输出' },
  12  |   { type: 'CONSTRAINTS', title: '约束', content: '保留事实边界' },
  13  | ], contextReport: context, ambiguities: [], appliedConstraints: [], templateCode: 'GENERAL',
  14  |   provider: { provider: 'mock', model: 'p0-browser', mock: true }, latencyMs: 1 };
  15  | 
  16  | test.beforeEach(async ({ page }) => {
  17  |   await mockAuthentication(page);
  18  |   await page.addInitScript(() => {
  19  |     localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
  20  |     localStorage.setItem('prompt-optimizer.project-context-settings.v1', JSON.stringify({
  21  |       profile: 'WEB_STANDARD', retention: 'SESSION', autoCleanupDays: 7, confirmBeforeSendingCode: false,
  22  |     }));
  23  |   });
  24  |   await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
  25  |     { id: 'mock:p0', displayName: 'P0 交互测试', provider: 'mock', defaultModel: true },
  26  |   ] } }));
  27  |   await page.route('**/api/v1/optimizations/plan-events', route => route.fulfill({ status: 204 }));
  28  |   await page.route('**/api/v1/optimizations', route => route.fulfill({ json: { requestId: 'p0', data: result } }));
  29  | });
  30  | 
  31  | const historyB: OptimizationHistoryDetail = { id: 'history-b', rawPrompt: '任务 B：写邮件',
  32  |   optimizedPrompt: '', sections: [], contextSummary: {}, ambiguities: [], appliedConstraints: [],
  33  |   templateCode: 'GENERAL', providerName: 'mock', modelName: 'p0', mock: true, latencyMs: 1,
  34  |   createdAt: '2026-10-11T00:00:00Z', includePermissionBoundaries: true, includeExamples: false,
  35  |   conversationHistory: [], permissionPolicy: {} };
  36  | 
  37  | const mockHistoryB = async (page: Page): Promise<void> => {
  38  |   await page.route('**/api/v1/optimization-history**', route => route.fulfill({ json: {
  39  |     data: new URL(route.request().url()).pathname.endsWith('/history-b') ? historyB : {
  40  |       records: [{ ...historyB, rawPromptPreview: historyB.rawPrompt }], current: 1, size: 10, total: 1, pages: 1,
  41  |     },
  42  |   } }));
  43  | };
  44  | 
  45  | const addMaterial = async (page: Page, path: string, content: string): Promise<void> => {
  46  |   await openWorkbenchPane(page, 'context');
  47  |   const manual = page.locator('.manual-snippet');
  48  |   if (!await manual.evaluate(element => element.hasAttribute('open'))) await manual.locator('summary').click();
  49  |   await page.getByLabel('文件相对路径').fill(path);
  50  |   await manual.locator('textarea').fill(content);
  51  |   await manual.getByRole('button', { name: '加入上下文', exact: true }).click();
  52  | };
  53  | 
  54  | const loadB = async (page: Page): Promise<void> => {
  55  |   // 使用 SPA 路由保留 Pinia，整页 goto 会清空内存并掩盖历史载入缺陷。
> 56  |   await page.getByRole('link', { name: '历史', exact: true }).click();
      |                                                             ^ Error: locator.click: Test timeout of 60000ms exceeded.
  57  |   await page.getByRole('button', { name: '载入', exact: true }).filter({ visible: true }).first().click();
  58  |   await expect(page).toHaveURL(/\/workbench$/);
  59  | };
  60  | 
  61  | const generate = async (page: Page): Promise<void> => {
  62  |   await page.goto('/workbench');
  63  |   await openWorkbenchPane(page, 'intent');
  64  |   await page.getByLabel('原始提示词').fill('请写一封短邮件。');
  65  |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  66  |   await openWorkbenchPane(page, 'result');
  67  |   await expect(page.getByRole('button', { name: '编辑', exact: true })).toBeVisible();
  68  | };
  69  | 
  70  | test('P0 保存校验失败保留全部草稿，修正后应用并复制', async ({ page }, testInfo) => {
  71  |   await generate(page);
  72  |   await page.getByRole('button', { name: '编辑', exact: true }).click();
  73  |   await page.locator('#section-TASK').fill('保留这段未保存的修改');
  74  |   await page.locator('#section-OUTPUT').fill('');
  75  |   await page.getByRole('button', { name: '保存修改', exact: true }).click();
  76  |   await expect(page.locator('#section-TASK')).toHaveValue('保留这段未保存的修改');
  77  |   await expect(page.locator('#section-OUTPUT')).toHaveValue('');
  78  |   await expect(page.locator('#section-OUTPUT')).toBeFocused();
  79  |   await expect(page.locator('#section-OUTPUT')).toHaveAttribute('aria-invalid', 'true');
  80  |   await page.screenshot({ path: testInfo.outputPath('failed-save-draft.png') });
  81  |   await page.locator('#section-OUTPUT').fill('修正后的输出要求');
  82  |   await page.getByRole('button', { name: '保存修改', exact: true }).click();
  83  |   await expect(page.getByRole('button', { name: '编辑', exact: true })).toBeVisible();
  84  |   await expect(page.locator('.section-list')).toContainText('保留这段未保存的修改');
  85  |   await expect(page.locator('.section-list')).toContainText('修正后的输出要求');
  86  |   await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  87  |   await page.getByRole('button', { name: '复制提示词', exact: true }).click();
  88  |   const copied = await page.evaluate(() => navigator.clipboard.readText());
  89  |   expect(copied).toContain('保留这段未保存的修改');
  90  |   expect(copied).toContain('修正后的输出要求');
  91  | });
  92  | 
  93  | test('P0 历史任务 B 添加资料后，最终请求不含 A 的资料', async ({ page }) => {
  94  |   await mockHistoryB(page);
  95  |   await page.goto('/workbench');
  96  |   await addMaterial(page, 'A.txt', 'A_PRIVATE_MARKER');
  97  |   await loadB(page);
  98  |   await addMaterial(page, 'B.txt', 'B_ONLY_MARKER');
  99  |   await expect(page.locator('.file-list')).not.toContainText('A.txt');
  100 |   await openWorkbenchPane(page, 'intent');
  101 |   const request = page.waitForRequest(req => req.url().endsWith('/api/v1/optimizations') && req.method() === 'POST');
  102 |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  103 |   const payload = (await request).postDataJSON() as { context: { files: ContextFileInput[] } };
  104 |   expect(payload.context.files.map(file => file.path)).toEqual(['B.txt']);
  105 |   expect(JSON.stringify(payload)).not.toContain('A_PRIVATE_MARKER');
  106 | });
  107 | 
  108 | test('P0 A 的增强响应迟到，不能覆盖 B 的结果', async ({ page }) => {
  109 |   await mockHistoryB(page);
  110 |   let respondA!: () => void;
  111 |   let aStarted = false;
  112 |   const waitA = new Promise<void>(resolve => { respondA = resolve; });
  113 |   await page.route('**/api/v1/optimizations', async route => {
  114 |     const input = route.request().postDataJSON();
  115 |     if (input.rawPrompt === '任务 A') {
  116 |       aStarted = true;
  117 |       await waitA;
  118 |       await route.fulfill({ json: { requestId: 'old-a', data: { ...result, optimizedPrompt: 'OLD_A_RESULT',
  119 |         sections: result.sections.map(section => ({ ...section, content: 'OLD_A_RESULT' })) } } });
  120 |     } else await route.fulfill({ json: { requestId: 'new-b', data: { ...result,
  121 |       sections: result.sections.map(section => ({ ...section, content: 'NEW_B_RESULT' })) } } });
  122 |   });
  123 |   await page.goto('/workbench');
  124 |   await page.getByLabel('原始提示词').fill('任务 A');
  125 |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  126 |   await expect.poll(() => aStarted).toBe(true);
  127 |   await loadB(page);
  128 |   await openWorkbenchPane(page, 'intent');
  129 |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  130 |   await openWorkbenchPane(page, 'result');
  131 |   await expect(page.locator('.section-list')).toContainText('NEW_B_RESULT');
  132 |   const aResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/optimizations')
  133 |     && response.request().postDataJSON().rawPrompt === '任务 A');
  134 |   respondA();
  135 |   await (await aResponse).finished();
  136 |   // 让 Axios/Vue 处理迟到响应，之后通过保留 B 的正文与可编辑状态验证。
  137 |   await page.waitForTimeout(150);
  138 |   await expect(page.locator('.section-list')).not.toContainText('OLD_A_RESULT');
  139 |   await expect(page.locator('.section-list')).toContainText('NEW_B_RESULT');
  140 |   await expect(page.getByRole('button', { name: '编辑', exact: true })).toBeEnabled();
  141 | });
  142 | 
  143 | test('P0 未保存修改阻止再次增强和离开，取消离开后仍保留草稿', async ({ page }) => {
  144 |   await generate(page);
  145 |   await page.getByRole('button', { name: '编辑', exact: true }).click();
  146 |   await page.locator('#section-TASK').fill('保留导航中的未保存修改');
  147 |   await openWorkbenchPane(page, 'intent');
  148 |   let extraRequests = 0;
  149 |   page.on('request', request => { if (request.url().endsWith('/api/v1/optimizations')) extraRequests++; });
  150 |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  151 |   await expect(page.getByText('请先保存或取消结果中的修改，再进行增强。')).toBeVisible();
  152 |   expect(extraRequests).toBe(0);
  153 |   await page.getByRole('link', { name: '历史', exact: true }).click();
  154 |   const dialog = page.getByRole('dialog', { name: '尚未保存的修改' });
  155 |   await dialog.getByRole('button', { name: '继续编辑', exact: true }).click();
  156 |   await expect(page).toHaveURL(/\/workbench$/);
```