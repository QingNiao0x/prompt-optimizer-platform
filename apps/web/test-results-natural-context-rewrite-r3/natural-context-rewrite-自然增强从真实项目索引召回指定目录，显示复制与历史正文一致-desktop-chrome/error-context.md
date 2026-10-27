# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-context-rewrite.spec.ts >> 自然增强从真实项目索引召回指定目录，显示复制与历史正文一致
- Location: e2e\natural-context-rewrite.spec.ts:92:1

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: false
Received: true
```

# Page snapshot

```yaml
- generic [active] [ref=e1]:
  - generic [ref=e3]:
    - banner [ref=e4]:
      - link "返回 PromptOptimizer 首页" [ref=e5] [cursor=pointer]:
        - /url: /
        - generic [ref=e10]:
          - strong [ref=e11]: PromptOptimizer
          - generic [ref=e12]: Prompt context studio
      - navigation "主导航" [ref=e13]:
        - link "首页" [ref=e14] [cursor=pointer]:
          - /url: /
        - link "提示词优化" [ref=e18] [cursor=pointer]:
          - /url: /workbench
        - link "图像转提示词" [ref=e22] [cursor=pointer]:
          - /url: /image-to-prompt
        - link "文章祛AI味" [ref=e27] [cursor=pointer]:
          - /url: /text-polish
        - link "历史" [ref=e31] [cursor=pointer]:
          - /url: /history
        - link "设置" [ref=e37] [cursor=pointer]:
          - /url: /settings
      - generic [ref=e41]:
        - generic [ref=e42]:
          - generic "test@example.com" [ref=e43]: 测试用户
          - button "退出登录" [ref=e44] [cursor=pointer]
        - generic "模型版本：受控链路模型目录（由平台管理员维护）" [ref=e45]: 受控链路模型目录
    - main [ref=e48]:
      - generic [ref=e49]:
        - generic [ref=e50]:
          - generic [ref=e51]:
            - heading "提示词优化工作台" [level=1] [ref=e52]
            - paragraph [ref=e53]: 把需求表达清楚，让 AI 更好执行。
          - list "提示词处理流程" [ref=e54]:
            - listitem [ref=e55]:
              - generic [ref=e56]: 项目事实
            - listitem [ref=e58]:
              - generic [ref=e59]: 需求明确化
            - listitem [ref=e61]:
              - generic [ref=e62]: 优化结果
        - generic [ref=e63]:
          - complementary [ref=e64]:
            - generic [ref=e65]:
              - generic [ref=e66]:
                - text: 01 / Context
                - heading "项目上下文" [level=2] [ref=e67]
              - generic [ref=e68]: 已分析
            - generic [ref=e73]:
              - generic [ref=e74]:
                - generic [ref=e75]: 自定义项目描述
                - paragraph [ref=e76]: 描述当前项目、架构偏好或团队约束，不是产品公告。
                - generic [ref=e77]:
                  - textbox "自定义项目描述" [ref=e78]:
                    - /placeholder: 例如：公共卫生研究，数据来自年度死因登记；或 Spring Boot 3 项目，使用 PostgreSQL。
                  - generic [ref=e79]: 0 / 4000
              - generic [ref=e80]:
                - generic [ref=e81]:
                  - generic [ref=e82]: 本地资料与项目文件
                  - button "清空" [ref=e83] [cursor=pointer]
                - button "Choose File" [ref=e85]
                - button "Choose File" [ref=e86]
                - generic [ref=e87]:
                  - button "添加上下文" [ref=e90] [cursor=pointer]
                  - region "拖入文件或文件夹添加上下文" [ref=e97]:
                    - generic [ref=e101]:
                      - strong [ref=e102]: 也可将文件或文件夹拖到这里
                      - generic [ref=e103]: 拖入后自动识别可用内容，需上传解析的文档会先请你确认
                  - generic [ref=e104]:
                    - paragraph [ref=e105]: 可建立项目索引，也可单独上传报告、论文、表格、演示文稿和图片。
                    - paragraph [ref=e106]: 可添加代码、Word、Excel、PPT、PDF 等文件或整个文件夹；支持的浏览器会在本地索引文件夹中的代码，办公文档上传解析前会先请你确认。
                    - paragraph [ref=e107]: 确认上传后，文档按 1 MiB 分片发送至本项目后端，解析后只保留最长 2 小时的临时全文索引； 一键增强时仅选取与当前任务相关的片段。
                - generic [ref=e108]:
                  - button "增量更新" [ref=e109] [cursor=pointer]
                  - button "删除本地索引" [ref=e111] [cursor=pointer]
                - note "暂不支持的文件格式" [ref=e113]:
                  - generic [ref=e114]: 暂不支持
                  - generic [ref=e115]:
                    - generic [ref=e116]: .zip
                    - generic [ref=e118]: .rar
                    - generic [ref=e120]: .7z
                    - generic [ref=e122]: .tar
                    - generic [ref=e124]: .gz
                    - generic [ref=e126]: .exe
                    - generic [ref=e128]: .dll
                    - generic [ref=e130]: .so
                    - generic [ref=e132]: .dylib
                    - generic [ref=e134]: .jar
                    - generic [ref=e136]: .class
                    - generic [ref=e138]: .bin
                    - generic [ref=e140]: .mp3
                    - generic [ref=e142]: .wav
                    - generic [ref=e144]: .mp4
                    - generic [ref=e146]: .avi
                    - generic [ref=e148]: .mkv
                    - generic [ref=e150]: .mov
                    - generic [ref=e152]: .flv
                    - generic [ref=e154]: .ttf
                    - generic [ref=e156]: .otf
                    - generic [ref=e158]: .woff
                    - generic [ref=e160]: .woff2
                    - generic [ref=e162]: .ico
                    - generic [ref=e164]: .psd
                - generic [ref=e166]:
                  - strong [ref=e167]: synthetic-survey-workspace
                  - generic [ref=e168]: 79 个源码文件已建立本地索引
                  - generic [ref=e169]: 79 个代码块 · 0 个文件已忽略 · 0 个超大文件仅保留元数据
                  - generic [ref=e170]: 本轮新增 79 · 更新 0 · 未变化 0 · 删除 0
                  - group [ref=e171]:
                    - generic "查看本次代码选择依据（6 段， 2,026 字符）" [ref=e172] [cursor=pointer]
              - group [ref=e173]:
                - generic "粘贴当前打开文件" [ref=e174] [cursor=pointer]
              - region "本次资料发送范围" [ref=e178]:
                - generic [ref=e179]:
                  - strong [ref=e180]: 本次增强资料范围
                  - generic [ref=e181]: 已核对
                - paragraph [ref=e183]: 本次为模拟增强，未调用外部模型；这里显示纳入增强输入的正文。
                - generic [ref=e184]: 完整范围指本次已读取或提取的正文。本地项目索引不会全部上传。
                - list [ref=e188]:
                  - listitem [ref=e189]:
                    - generic [ref=e190]:
                      - generic "src/views/records/dateRules.js" [ref=e191]
                      - generic [ref=e192]: 部分选取
                    - generic [ref=e194]: 仅纳入本次选取的安全片段或摘要，无法确认原文件全文覆盖。
                    - group [ref=e195]:
                      - generic "查看本次安全片段（361 字符）" [ref=e196] [cursor=pointer]
                  - listitem [ref=e197]:
                    - generic [ref=e198]:
                      - generic "src/views/records/import.js" [ref=e199]
                      - generic [ref=e200]: 部分选取
                    - generic [ref=e202]: 仅纳入本次选取的安全片段或摘要，无法确认原文件全文覆盖。
                    - group [ref=e203]:
                      - generic "查看本次安全片段（432 字符）" [ref=e204] [cursor=pointer]
                  - listitem [ref=e205]:
                    - generic [ref=e206]:
                      - generic "src/views/records/create.js" [ref=e207]
                      - generic [ref=e208]: 部分选取
                    - generic [ref=e210]: 仅纳入本次选取的安全片段或摘要，无法确认原文件全文覆盖。
                    - group [ref=e211]:
                      - generic "查看本次安全片段（306 字符）" [ref=e212] [cursor=pointer]
                  - listitem [ref=e213]:
                    - generic [ref=e214]:
                      - generic "src/views/records/dateRules.spec.js" [ref=e215]
                      - generic [ref=e216]: 部分选取
                    - generic [ref=e218]: 仅纳入本次选取的安全片段或摘要，无法确认原文件全文覆盖。
                    - group [ref=e219]:
                      - generic "查看本次安全片段（660 字符）" [ref=e220] [cursor=pointer]
                  - listitem [ref=e221]:
                    - generic [ref=e222]:
                      - generic "src/views/records-old/create.js" [ref=e223]
                      - generic [ref=e224]: 部分选取
                    - generic [ref=e226]: 仅纳入本次选取的安全片段或摘要，无法确认原文件全文覆盖。
                    - group [ref=e227]:
                      - generic "查看本次安全片段（109 字符）" [ref=e228] [cursor=pointer]
                  - listitem [ref=e229]:
                    - generic [ref=e230]:
                      - generic "package.json" [ref=e231]
                      - generic [ref=e232]: 部分选取
                    - generic [ref=e234]: 仅纳入本次选取的安全片段或摘要，无法确认原文件全文覆盖。
                    - group [ref=e235]:
                      - generic "查看本次安全片段（158 字符）" [ref=e236] [cursor=pointer]
              - generic [ref=e237]:
                - generic [ref=e238]:
                  - generic [ref=e239]: 识别结果
                  - generic [ref=e243]: 代码项目
                - generic [ref=e245]:
                  - heading "项目概要" [level=3] [ref=e246]
                  - paragraph [ref=e247]: 根据已分析的源码文件，当前识别为软件项目。 可见模块主要包括页面模块。 本次上下文包含 6 个文件摘要、0 项依赖和 0 个目录节点。
                - generic [ref=e248]:
                  - generic [ref=e249]:
                    - generic [ref=e250]: 功能模块
                    - generic [ref=e251]: 1 个
                  - list [ref=e255]:
                    - listitem [ref=e256]:
                      - generic [ref=e257]:
                        - strong [ref=e258]: 页面模块
                        - generic [ref=e259]: 5 个来源文件
                      - code [ref=e260]: src/views
                      - paragraph [ref=e261]: 组织面向用户的页面内容和页面级交互。
                - generic [ref=e264]:
                  - generic [ref=e265]:
                    - generic [ref=e266]: 代码文件摘要
                    - generic [ref=e267]: 6 个文件
                  - generic [ref=e268]:
                    - list [ref=e271]:
                      - listitem [ref=e272]:
                        - generic [ref=e273]:
                          - strong [ref=e274]: src/views/records/dateRules.js#chunk-1
                          - generic [ref=e275]: javascript
                        - paragraph [ref=e277]: 未提取到可概括的文本内容。
                      - listitem [ref=e278]:
                        - generic [ref=e279]:
                          - strong [ref=e280]: src/views/records/import.js#chunk-1
                          - generic [ref=e281]: javascript
                        - paragraph [ref=e283]: 未提取到可概括的文本内容。
                      - listitem [ref=e284]:
                        - generic [ref=e285]:
                          - strong [ref=e286]: src/views/records/create.js#chunk-1
                          - generic [ref=e287]: javascript
                        - paragraph [ref=e289]: 未提取到可概括的文本内容。
                      - listitem [ref=e290]:
                        - generic [ref=e291]:
                          - strong [ref=e292]: src/views/records/dateRules.spec.js#chunk-1
                          - generic [ref=e293]: javascript
                        - paragraph [ref=e295]: 未提取到可概括的文本内容。
                      - listitem [ref=e296]:
                        - generic [ref=e297]:
                          - strong [ref=e298]: src/views/records-old/create.js#chunk-1
                          - generic [ref=e299]: javascript
                        - paragraph [ref=e301]: 未提取到可概括的文本内容。
                      - listitem [ref=e302]:
                        - generic [ref=e303]:
                          - strong [ref=e304]: package.json#chunk-1
                          - generic [ref=e305]: json
                        - paragraph [ref=e307]: 未提取到可概括的文本内容。
                    - generic [ref=e310] [cursor=pointer]
                - paragraph [ref=e311]: 0 个依赖 · 0 个目录节点
            - button "重新分析上下文" [ref=e312] [cursor=pointer]
          - generic [ref=e317]:
            - generic [ref=e318]:
              - text: 02 / Intent
              - heading "把想法写下来。 工程细节，交给上下文。" [level=1] [ref=e319]:
                - generic [ref=e320]: 把想法写下来。
                - generic [ref=e321]: 工程细节，交给上下文。
            - generic [ref=e322]:
              - generic [ref=e323]: 原始提示词
              - generic [ref=e324]:
                - textbox "原始提示词" [ref=e325]:
                  - /placeholder: 请帮我查询全球使用AI最多的职业/行业
                  - text: 请在重点记录模块@synthetic-survey-workspace/src/views/records 的新增和导入功能增加调查时间限制：调查时间不能超过今天。
                - generic [ref=e326]: 82 / 8000
              - generic [ref=e327]:
                - strong [ref=e328]: 直接生成
                - generic [ref=e329]: 将直接生成最终提示词。若关键事实不足，结果中会列出待确认事项。
              - generic [ref=e330]:
                - generic [ref=e331]:
                  - generic [ref=e332]:
                    - generic [ref=e333]: 模型版本
                    - generic [ref=e335] [cursor=pointer]:
                      - generic:
                        - combobox "选择模型版本" [ref=e337]
                        - generic [ref=e338]: 受控链路模型目录
                  - generic [ref=e343] [cursor=pointer]:
                    - generic [ref=e344]: Plan 确认
                    - generic [ref=e345]:
                      - switch "Plan 确认"
                  - generic [ref=e348] [cursor=pointer]:
                    - generic [ref=e349]: 结构化提示词
                    - generic [ref=e350]:
                      - switch "结构化提示词"
                - button "直接增强提示词" [ref=e354] [cursor=pointer]
          - generic [ref=e360]:
            - generic [ref=e361]:
              - generic [ref=e362]: 03 / Optimized prompt
              - generic [ref=e363]:
                - group "复制提示词" [ref=e364]:
                  - button "复制提示词" [ref=e365] [cursor=pointer]
                  - button "选择 AI 平台" [ref=e373] [cursor=pointer]
                - button "编辑" [ref=e377] [cursor=pointer]
            - heading "增强结果" [level=2] [ref=e382]
            - button "直接再次增强" [ref=e383] [cursor=pointer]
            - generic [ref=e388]:
              - paragraph [ref=e389]: 当前结果：自然表达。复制内容与当前结果一致。
              - paragraph [ref=e390]: 点击复制后，请将内容粘贴至您使用的 AI 软件或网页版。
            - generic "增强结果内容，可滚动查看完整提示词" [ref=e392]:
              - generic [ref=e393]:
                - generic [ref=e394]:
                  - term [ref=e395]: 模型版本
                  - definition [ref=e396]: 版本未记录
                  - generic [ref=e397]: fixture
                - generic [ref=e398]:
                  - term [ref=e399]: Template
                  - definition [ref=e400]: GENERAL
                  - generic [ref=e401]: 0 个结构段落
                - generic [ref=e402]:
                  - term [ref=e403]: Latency
                  - definition [ref=e404]: 1 ms
                  - generic [ref=e405]: Mock 结果
              - article [ref=e406]: 请修改重点记录模块 src/views/records 的新增和导入功能，使调查时间只能填写今天及以前的有效日历日期。检查 create.js、import.js 和共用 dateRules.js 中的现有校验，保持两条录入路径规则一致。沿用导入逐行反馈及部分成功行为，保留其他字段，检查昨天、今天、明天和非法日期。
    - button "切换为深色主题" [ref=e407] [cursor=pointer]
  - alert [ref=e410]:
    - paragraph [ref=e414]: 已为 79 个源码文件建立本地索引。
  - alert [ref=e415]:
    - paragraph [ref=e419]: 已生成离线 Mock 示例。
```

# Test source

```ts
  32  |   const result: OptimizationResult = {
  33  |     resultKind: 'TEXT', generationPipeline: 'NATURAL', chainVersion: '45', framework: null,
  34  |     recordId, editRevision: 0, optimizedPrompt: body, sections: [], promptFormat: 'COMPACT',
  35  |     templateCode: 'GENERAL', contextReport: { customDescription: '', technologyStack: [], dependencies: [],
  36  |       directoryTree: [], fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'controlled-browser-fixture' },
  37  |     ambiguities: [], appliedConstraints: [], constraintGroups: { platformMandatory: [], taskSpecific: [], additionalPermissions: [] },
  38  |     provider: { provider: 'fixture', model: 'controlled-browser-response', mock: true }, latencyMs: 1,
  39  |     processing: { completedStages: ['SCOPE_ANALYSIS', 'BODY_GENERATION'], originalPreserved: false },
  40  |   };
  41  |   await page.route('**/api/v2/optimizations', async (route) => {
  42  |     const request = route.request().postDataJSON() as OptimizationRequest;
  43  |     requests.push(request);
  44  |     result.contextReport.fileSnippets = request.context.files;
  45  |     await route.fulfill({ json: { requestId: 'controlled-context-rewrite', data: result } });
  46  |   });
  47  |   await page.route('**/api/v2/optimization-history**', async (route) => {
  48  |     const path = new URL(route.request().url()).pathname;
  49  |     const metadata = { resultKind: result.resultKind, generationPipeline: result.generationPipeline,
  50  |       chainVersion: result.chainVersion, processing: result.processing, framework: result.framework };
  51  |     if (path.endsWith('/optimization-history')) {
  52  |       await route.fulfill({ json: { data: { records: [{ id: recordId, rawPromptPreview: requests[0]?.rawPrompt,
  53  |         templateCode: 'GENERAL', providerName: 'fixture', modelName: 'controlled-browser-response', mock: true,
  54  |         latencyMs: 1, createdAt: '2026-10-27T00:00:00Z', ...metadata }], total: 1, current: 1, size: 10, pages: 1 } } });
  55  |       return;
  56  |     }
  57  |     await route.fulfill({ json: { data: { id: recordId, rawPrompt: requests[0]?.rawPrompt, optimizedPrompt: body,
  58  |       promptFormat: 'COMPACT', sections: [], contextSummary: {}, ambiguities: [], appliedConstraints: [],
  59  |       constraintGroups: result.constraintGroups, templateCode: 'GENERAL', providerName: 'fixture',
  60  |       modelName: 'controlled-browser-response', mock: true, latencyMs: 1, createdAt: '2026-10-27T00:00:00Z',
  61  |       includePermissionBoundaries: false, conversationHistory: [], permissionPolicy: {}, ...metadata } } });
  62  |   });
  63  |   return { requests, unexpected };
  64  | };
  65  | 
  66  | /** 通过文件夹选择器建立真实 Worker/IndexedDB 索引，不直接预填任务上下文。 */
  67  | const selectSyntheticDirectory = async (page: Page) => {
  68  |   const fixture = await loadSyntheticProject();
  69  |   await page.addInitScript(async ({ rootName, files }) => {
  70  |     localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
  71  |     const storage = await navigator.storage.getDirectory();
  72  |     const root = await storage.getDirectoryHandle(rootName, { create: true });
  73  |     for (const file of files) {
  74  |       const parts = file.path.split('/');
  75  |       let directory = root;
  76  |       for (const part of parts.slice(0, -1)) directory = await directory.getDirectoryHandle(part, { create: true });
  77  |       const handle = await directory.getFileHandle(parts.at(-1)!, { create: true });
  78  |       const writer = await handle.createWritable();
  79  |       await writer.write(file.content);
  80  |       await writer.close();
  81  |     }
  82  |     Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
  83  |   }, fixture);
  84  |   await page.goto('/workbench');
  85  |   await openWorkbenchPane(page, 'context');
  86  |   await page.getByRole('button', { name: '添加上下文', exact: true }).click();
  87  |   await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  88  |   await expect(page.getByText(`${fixture.files.length} 个源码文件已建立本地索引`, { exact: true })).toBeVisible();
  89  |   return fixture;
  90  | };
  91  | 
  92  | test('自然增强从真实项目索引召回指定目录，显示复制与历史正文一致', async ({ page }, testInfo) => {
  93  |   test.slow();
  94  |   const state = await setup(page);
  95  |   const fixture = await selectSyntheticDirectory(page);
  96  |   const indexedPaths = await page.evaluate(async () => {
  97  |     const request = indexedDB.open('prompt-optimizer-project-index');
  98  |     const database = await new Promise<IDBDatabase>((resolve, reject) => {
  99  |       request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
  100 |     });
  101 |     const transaction = database.transaction('files', 'readonly');
  102 |     const all = transaction.objectStore('files').getAll();
  103 |     const paths = await new Promise<string[]>((resolve, reject) => {
  104 |       all.onsuccess = () => resolve((all.result as Array<{ path: string }>).map(({ path }) => path));
  105 |       all.onerror = () => reject(all.error);
  106 |     });
  107 |     database.close();
  108 |     return paths.sort();
  109 |   });
  110 |   // 全部噪声仍被索引；目标优先不能靠删减索引范围取得。
  111 |   expect(indexedPaths).toEqual(fixture.files.map(({ path }) => path).sort());
  112 |   await openWorkbenchPane(page, 'intent');
  113 |   await page.getByLabel('原始提示词', { exact: true }).fill(fixture.rawPrompt);
  114 |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  115 |   const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  116 |   await expect(confirmation).toBeVisible();
  117 |   await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  118 |   await expect.poll(() => state.requests.length).toBe(1);
  119 |   const request = state.requests[0]!;
  120 |   const basePaths = request.context.files.map(({ path }) => path.split('#chunk-')[0]);
  121 |   await captureJson(testInfo, 'actual-browser-optimization-request.json', request);
  122 |   const retrieval = await page.evaluate(async () => {
  123 |     const modulePath = '/src/stores/optimization.ts';
  124 |     const { useOptimizationStore } = await import(modulePath) as typeof import('../src/stores/optimization');
  125 |     return useOptimizationStore().contextRetrieval;
  126 |   });
  127 |   await captureJson(testInfo, 'actual-browser-retrieval-report.json', retrieval);
  128 |   expect(request.rawPrompt).toBe(fixture.rawPrompt);
  129 |   expect(request.enhancement.promptFormat).toBe('COMPACT');
  130 |   expect(request.planConfirmation).toBeNull();
  131 |   for (const target of fixture.targetPaths) expect(basePaths).toContain(target);
> 132 |   expect(basePaths.some((path) => path.startsWith('src/views/records-old/'))).toBe(false);
      |                                                                               ^ Error: expect(received).toBe(expected) // Object.is equality
  133 |   expect(basePaths.some((path) => path.startsWith('src/views/records-archive'))).toBe(false);
  134 |   expect(request.context.files.length).toBeLessThan(fixture.files.length);
  135 |   const captureDirectory = new URL('../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/browser-capture/', import.meta.url);
  136 |   await mkdir(captureDirectory, { recursive: true });
  137 |   await writeFile(new URL('input.json', captureDirectory), `${JSON.stringify(request, null, 2)}\n`, 'utf8');
  138 |   await openWorkbenchPane(page, 'result');
  139 |   await expect(page.getByTestId('compact-prompt')).toHaveText(body);
  140 |   await expect(page.locator('.result-meta')).toContainText('已生成优化正文');
  141 |   await page.bringToFront();
  142 |   await page.locator('.copy-main-button').click();
  143 |   await expect(page.locator('.copy-main-button')).toContainText('已复制');
  144 |   const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n');
  145 |   expect(copied).toBe(body);
  146 |   expect(copied).not.toContain('UNRELATED_SOURCE_');
  147 |   expect(copied).not.toContain('提供的材料：');
  148 |   await page.goto('/history');
  149 |   await page.getByRole('button', { name: '查看', exact: true }).filter({ visible: true }).click();
  150 |   await expect(page.getByRole('dialog', { name: '历史记录详情', exact: true }).locator('pre').nth(1)).toHaveText(copied);
  151 |   expect(state.unexpected).toEqual([]);
  152 |   await captureJson(testInfo, 'controlled-browser-verdict.json', {
  153 |     modelCalls: 0, controlledResponse: true, qualityEvidence: false, indexedFiles: indexedPaths.length,
  154 |     selectedPaths: basePaths, displayCopyHistoryEqual: true,
  155 |   });
  156 | });
  157 | 
  158 | test('冻结后的内部新题通过同一真实项目索引定位导入文件及必要依赖', async ({ page }, testInfo) => {
  159 |   test.slow();
  160 |   const state = await setup(page);
  161 |   await selectSyntheticDirectory(page);
  162 |   const newCase = JSON.parse(await readFile(new URL('../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/internal-new-case.json', import.meta.url), 'utf8')) as { rawPrompt: string };
  163 |   await openWorkbenchPane(page, 'intent');
  164 |   await page.getByLabel('原始提示词', { exact: true }).fill(newCase.rawPrompt);
  165 |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  166 |   const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  167 |   await expect(confirmation).toBeVisible();
  168 |   await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  169 |   await expect.poll(() => state.requests.length).toBe(1);
  170 |   const request = state.requests[0]!;
  171 |   const paths = request.context.files.map(({ path }) => path.split('#chunk-')[0]);
  172 |   await captureJson(testInfo, 'actual-browser-new-case-request.json', request);
  173 |   expect(request.rawPrompt).toBe(newCase.rawPrompt);
  174 |   expect(request.modelId).toBe('deepseek:deepseek-flash');
  175 |   expect(paths).toContain('src/views/records/import.js');
  176 |   expect(paths).toContain('src/views/records/dateRules.js');
  177 |   expect(paths.some((path) => path.startsWith('src/views/records-old/'))).toBe(false);
  178 |   expect(paths.some((path) => path.startsWith('src/views/records-archive'))).toBe(false);
  179 |   const captureDirectory = new URL('../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/browser-capture/', import.meta.url);
  180 |   await mkdir(captureDirectory, { recursive: true });
  181 |   await writeFile(new URL('input-new-case.json', captureDirectory), `${JSON.stringify(request, null, 2)}\n`, 'utf8');
  182 |   expect(state.unexpected).toEqual([]);
  183 | });
  184 | 
```