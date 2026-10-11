# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: prompt-workbench.spec.ts >> 用户可以分析项目上下文并生成结构化提示词
- Location: e2e\prompt-workbench.spec.ts:252:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('Maven 项目配置，使用 Spring Boot Web。')
Expected: visible
Error: strict mode violation: getByText('Maven 项目配置，使用 Spring Boot Web。') resolved to 2 elements:
    1) <pre data-v-9be6cce9="">Maven 项目配置，使用 Spring Boot Web。↵<artifactId>spring…</pre> aka getByText('Maven 项目配置，使用 Spring Boot Web。 <artifactId>spring-boot-starter-web</artifactId>')
    2) <p data-v-19645adc="">Maven 项目配置，使用 Spring Boot Web。</p> aka getByText('Maven 项目配置，使用 Spring Boot Web。', { exact: true })

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByText('Maven 项目配置，使用 Spring Boot Web。')

```

# Page snapshot

```yaml
- generic [active] [ref=e1]:
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
          - generic [ref=e39]:
            - generic [ref=e40]:
              - text: 01 / Context
              - heading "项目上下文" [level=2] [ref=e41]
            - generic [ref=e42]: 已分析
          - generic [ref=e47]:
            - generic [ref=e48]:
              - generic [ref=e49]: 自定义项目描述
              - paragraph [ref=e50]: 描述当前项目、架构偏好或团队约束，不是产品公告。
              - generic [ref=e51]:
                - textbox "自定义项目描述" [ref=e52]:
                  - /placeholder: 例如：公共卫生研究，数据来自年度死因登记；或 Spring Boot 3 项目，使用 PostgreSQL。
                  - text: Spring Boot 3 模块化单体，使用 PostgreSQL。
                - generic [ref=e53]: 34 / 4000
            - generic [ref=e54]:
              - generic [ref=e55]:
                - generic [ref=e56]: 本地资料与项目文件
                - button "清空" [ref=e57] [cursor=pointer]
              - button "Choose File" [ref=e59]
              - button "Choose File" [ref=e60]
              - generic [ref=e61]:
                - button "添加上下文" [ref=e64] [cursor=pointer]
                - region "拖入文件或文件夹添加上下文" [ref=e71]:
                  - generic [ref=e75]:
                    - strong [ref=e76]: 也可将文件或文件夹拖到这里
                    - generic [ref=e77]: 拖入后自动识别可用内容，需上传解析的文档会先请你确认
                - generic [ref=e78]:
                  - paragraph [ref=e79]: 可建立项目索引，也可单独上传报告、论文、表格、演示文稿和图片。
                  - paragraph [ref=e80]: 可添加代码、Word、Excel、PPT、PDF 等文件或整个文件夹；支持的浏览器会在本地索引文件夹中的代码，办公文档上传解析前会先请你确认。
                  - paragraph [ref=e81]: 确认上传后，文档按 1 MiB 分片发送至本项目后端，解析后只保留最长 2 小时的临时全文索引； 一键增强时仅选取与当前任务相关的片段。
              - note "暂不支持的文件格式" [ref=e82]:
                - generic [ref=e83]: 暂不支持
                - generic [ref=e84]:
                  - generic [ref=e85]: .zip
                  - generic [ref=e87]: .rar
                  - generic [ref=e89]: .7z
                  - generic [ref=e91]: .tar
                  - generic [ref=e93]: .gz
                  - generic [ref=e95]: .exe
                  - generic [ref=e97]: .dll
                  - generic [ref=e99]: .so
                  - generic [ref=e101]: .dylib
                  - generic [ref=e103]: .jar
                  - generic [ref=e105]: .class
                  - generic [ref=e107]: .bin
                  - generic [ref=e109]: .mp3
                  - generic [ref=e111]: .wav
                  - generic [ref=e113]: .mp4
                  - generic [ref=e115]: .avi
                  - generic [ref=e117]: .mkv
                  - generic [ref=e119]: .mov
                  - generic [ref=e121]: .flv
                  - generic [ref=e123]: .ttf
                  - generic [ref=e125]: .otf
                  - generic [ref=e127]: .woff
                  - generic [ref=e129]: .woff2
                  - generic [ref=e131]: .ico
                  - generic [ref=e133]: .psd
              - generic [ref=e135]:
                - generic [ref=e136]:
                  - generic [ref=e137]: "1"
                  - generic [ref=e138]: 个文件已选择
                - list [ref=e142]:
                  - listitem [ref=e143]:
                    - generic [ref=e144]: java
                    - generic "pom.xml" [ref=e145]
                    - button "移除 pom.xml" [ref=e146] [cursor=pointer]
            - group [ref=e149]:
              - generic "粘贴当前打开文件" [ref=e150] [cursor=pointer]
              - generic [ref=e154]:
                - generic [ref=e155]:
                  - textbox "文件相对路径" [ref=e158]:
                    - /placeholder: src/example.ts
                    - text: pom.xml
                  - generic [ref=e160] [cursor=pointer]:
                    - generic:
                      - combobox "代码语言" [ref=e162]
                      - generic [ref=e163]: Java
                - textbox "粘贴与当前任务相关的代码片段…" [ref=e169]
                - button "加入上下文" [ref=e170] [cursor=pointer]
            - region "本次资料发送范围" [ref=e175]:
              - generic [ref=e176]:
                - strong [ref=e177]: 上下文分析资料范围
                - generic [ref=e178]: 已核对
              - paragraph [ref=e180]: 这里显示本次后端分析范围，尚未发送给增强模型。
              - generic [ref=e181]: 完整范围指本次已读取或提取的正文。本地项目索引不会全部上传。
              - list [ref=e185]:
                - listitem [ref=e186]:
                  - generic [ref=e187]:
                    - generic "pom.xml" [ref=e188]
                    - generic [ref=e189]: 部分选取
                  - generic [ref=e191]: 仅纳入本次选取的安全片段或摘要，无法确认原文件全文覆盖。
                  - group [ref=e192]:
                    - generic "查看本次安全片段（48 字符）" [ref=e193] [cursor=pointer]
            - generic [ref=e194]:
              - generic [ref=e195]:
                - generic [ref=e196]: 识别结果
                - generic [ref=e200]: 代码项目
              - generic [ref=e202]:
                - heading "项目概要" [level=3] [ref=e203]
                - paragraph [ref=e204]: 根据文件路径、构建清单和源码证据，当前识别为软件项目，主要技术栈包括Java 21、Spring Boot 3。 可见模块主要包括后端服务。 本次上下文包含 1 个文件摘要、1 项依赖和 3 个目录节点。
              - generic [ref=e205]:
                - generic [ref=e206]:
                  - generic [ref=e207]: 技术栈
                  - generic [ref=e208]: 2 项
                - generic [ref=e209]:
                  - article [ref=e210]:
                    - generic [ref=e211]:
                      - generic [ref=e212]: Java 21
                      - generic [ref=e214]: 100%
                    - paragraph [ref=e215]: 来源：pom.xml
                  - article [ref=e216]:
                    - generic [ref=e217]:
                      - generic [ref=e218]: Spring Boot 3
                      - generic [ref=e220]: 100%
                    - paragraph [ref=e221]: 来源：pom.xml
              - generic [ref=e222]:
                - generic [ref=e223]:
                  - generic [ref=e224]: 功能模块
                  - generic [ref=e225]: 1 个
                - list [ref=e229]:
                  - listitem [ref=e230]:
                    - generic [ref=e231]:
                      - strong [ref=e232]: 后端服务
                      - generic [ref=e233]: 1 个来源文件
                    - code [ref=e234]: 项目根目录
                    - paragraph [ref=e235]: 根据构建文件和源码识别出的服务端实现。
              - group [ref=e238]:
                - generic "依赖信息 1 项" [ref=e239] [cursor=pointer]:
                  - generic [ref=e240]: 依赖信息
                  - generic [ref=e241]: 1 项
              - group [ref=e242]:
                - generic "目录结构 3 个节点" [ref=e243] [cursor=pointer]:
                  - generic [ref=e244]: 目录结构
                  - generic [ref=e245]: 3 个节点
              - generic [ref=e246]:
                - generic [ref=e247]:
                  - generic [ref=e248]: 代码文件摘要
                  - generic [ref=e249]: 1 个文件
                - list [ref=e253]:
                  - listitem [ref=e254]:
                    - generic [ref=e255]:
                      - strong [ref=e256]: pom.xml
                      - generic [ref=e257]: xml
                    - paragraph [ref=e259]: Maven 项目配置，使用 Spring Boot Web。
              - paragraph [ref=e262]: 1 个依赖 · 3 个目录节点
          - button "重新分析上下文" [ref=e263] [cursor=pointer]
    - button "切换为深色主题" [ref=e268] [cursor=pointer]
  - alert [ref=e271]:
    - paragraph [ref=e275]: 代码片段已加入本次上下文。
  - alert [ref=e276]:
    - paragraph [ref=e280]: 上下文资料分析完成。
```

# Test source

```ts
  262 |         {
  263 |           path: 'pom.xml',
  264 |           language: 'java',
  265 |         },
  266 |       ],
  267 |     });
  268 |     await route.fulfill({ status: 200, json: contextResponse });
  269 |   });
  270 | 
  271 |   await page.route('**/api/v1/optimizations/plan', async (route) => {
  272 |     optimizationRequestOrder.push('plan');
  273 |     const requestBody: unknown = route.request().postDataJSON();
  274 |     expect(requestBody).toMatchObject({
  275 |       rawPrompt: '给用户模块增加登录功能',
  276 |       modelId: 'tokenhub:kimi-k3',
  277 |       contextDescription: 'Spring Boot 3 模块化单体，使用 PostgreSQL。',
  278 |       planningContext: planningContextReference,
  279 |     });
  280 |     expect(requestBody).not.toHaveProperty('files');
  281 |     await route.fulfill({
  282 |       status: 200,
  283 |       json: {
  284 |         ...loginPlanResponse,
  285 |         data: {
  286 |           ...loginPlanResponse.data,
  287 |           planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
  288 |           planningContext: planningContextReference,
  289 |           expiresAt: '2026-09-14T08:30:00Z',
  290 |         },
  291 |       },
  292 |     });
  293 |   });
  294 | 
  295 |   await page.route('**/api/v1/context/planning', async (route) => {
  296 |     optimizationRequestOrder.push('context');
  297 |     const requestBody: unknown = route.request().postDataJSON();
  298 |     expect(requestBody).toMatchObject({
  299 |       rawPrompt: '给用户模块增加登录功能',
  300 |       context: {
  301 |         customDescription: 'Spring Boot 3 模块化单体，使用 PostgreSQL。',
  302 |         files: [expect.objectContaining({ path: 'pom.xml' })],
  303 |       },
  304 |     });
  305 |     await route.fulfill({ status: 200, json: planningContextResponse });
  306 |   });
  307 | 
  308 |   await page.route('**/api/v1/optimizations', async (route) => {
  309 |     optimizationRequestOrder.push('final');
  310 |     const requestBody: unknown = route.request().postDataJSON();
  311 |     expect(requestBody).toMatchObject({
  312 |       rawPrompt: '给用户模块增加登录功能',
  313 |       modelId: 'tokenhub:kimi-k3',
  314 |       enhancement: {
  315 |         includePermissionBoundaries: true,
  316 |       },
  317 |       planConfirmation: {
  318 |         planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
  319 |         planningContext: planningContextReference,
  320 |         answers: [
  321 |           expect.objectContaining({ questionId: 'software-login-mode' }),
  322 |           expect.objectContaining({ questionId: 'software-done' }),
  323 |         ],
  324 |       },
  325 |     });
  326 |     await route.fulfill({ status: 200, json: optimizationResponse });
  327 |   });
  328 | 
  329 |   await page.goto('/workbench');
  330 |   await page.waitForLoadState('networkidle');
  331 |   await openWorkbenchPane(page, 'intent');
  332 |   await expect(page.getByText('把想法写下来。', { exact: true })).toBeVisible();
  333 |   await expect(page.getByText('工程细节，交给上下文。', { exact: true })).toBeVisible();
  334 |   await expect(page.getByLabel('原始提示词')).toHaveAttribute(
  335 |     'placeholder',
  336 |     '请帮我查询全球使用AI最多的职业/行业',
  337 |   );
  338 | 
  339 |   await openWorkbenchPane(page, 'context');
  340 |   await page.getByLabel('自定义项目描述').fill('Spring Boot 3 模块化单体，使用 PostgreSQL。');
  341 |   await page.getByText('粘贴当前打开文件').click();
  342 |   await page.getByLabel('文件相对路径').fill('pom.xml');
  343 |   const languageSelect = page.getByRole('combobox', { name: '代码语言' });
  344 |   await languageSelect.focus();
  345 |   await languageSelect.press('ArrowDown');
  346 |   await page.getByRole('option', { name: 'Java', exact: true }).click();
  347 |   await page.getByPlaceholder('粘贴与当前任务相关的代码片段…').fill([
  348 |     '<properties><java.version>21</java.version></properties>',
  349 |     '<dependency><artifactId>spring-boot-starter-web</artifactId></dependency>',
  350 |   ].join('\n'));
  351 |   await page.getByRole('button', { name: '加入上下文' }).click();
  352 |   await expect(page.getByText('pom.xml', { exact: true })).toBeVisible();
  353 | 
  354 |   await page.getByRole('button', { name: '分析上下文资料' }).click();
  355 |   await confirmContextTransmission(page, '分析上下文资料');
  356 |   await expect(page.getByText('代码项目', { exact: true })).toBeVisible();
  357 |   await expect(page.getByText('项目概要', { exact: true })).toBeVisible();
  358 |   await expect(page.getByText('功能模块', { exact: true })).toBeVisible();
  359 |   await expect(page.getByText('依赖信息', { exact: true })).toBeVisible();
  360 |   await expect(page.getByText('目录结构', { exact: true })).toBeVisible();
  361 |   await expect(page.getByText('Spring Boot 3', { exact: true })).toBeVisible();
> 362 |   await expect(page.locator('.analysis-result').getByText('Maven 项目配置，使用 Spring Boot Web。', { exact: true })).toBeVisible();
      |                                                                  ^ Error: expect(locator).toBeVisible() failed
  363 |   await expect(page.getByText('1 个依赖 · 3 个目录节点')).toBeVisible();
  364 | 
  365 |   await openWorkbenchPane(page, 'intent');
  366 |   await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  367 |   await page.locator('.model-select').click();
  368 |   await page.getByRole('option', { name: 'Kimi K3' }).click();
  369 |   await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  370 |   await confirmContextTransmission(page, '生成确认问题前分析上下文');
  371 |   const planDialog = page.getByRole('dialog', { name: '确认关键细节' });
  372 |   await expect(planDialog.getByRole('heading', { name: '把关键细节确认清楚' })).toBeVisible();
  373 |   await expect(planDialog.getByText('登录成功后采用哪种身份保持方式？')).toBeVisible();
  374 |   await expect(planDialog.getByText('选择任务模板')).toHaveCount(0);
  375 |   await expect(planDialog.getByText('确认缺失维度')).toHaveCount(0);
  376 |   await planDialog.getByRole('button', { name: /JWT/ }).click();
  377 |   await planDialog.getByRole('button', { name: '下一题' }).click();
  378 |   await planDialog.getByLabel('填写回答').fill('登录成功、失败和令牌刷新场景都有自动化测试。');
  379 |   await planDialog.getByRole('button', { name: '生成最终提示词' }).click();
  380 |   await confirmContextTransmission(page, '生成最终提示词');
  381 | 
  382 |   expect(optimizationRequestOrder).toEqual(['context', 'plan', 'final']);
  383 | 
  384 |   await openWorkbenchPane(page, 'result');
  385 |   const resultContent = page.getByLabel('增强结果内容，可滚动查看完整提示词');
  386 |   await expect(resultContent.getByText('deepseek', { exact: true })).toBeVisible();
  387 |   await expect(resultContent.getByText('模型版本', { exact: true })).toBeVisible();
  388 |   await expect(resultContent.getByText('DeepSeek-V4-Pro', { exact: true })).toBeVisible();
  389 |   await expect(resultContent.locator('.result-meta')).not.toContainText('deepseek:deepseek-v4-pro');
  390 |   await expect(resultContent.getByRole('heading', { name: '任务目标' })).toBeVisible();
  391 |   await expect(resultContent.getByRole('heading', { name: '输入输出' })).toBeVisible();
  392 |   await expect(resultContent.getByRole('heading', { name: '约束条件' })).toBeVisible();
  393 |   await expect(page.getByRole('status').filter({ hasText: '待确认事项' })).toHaveCount(0);
  394 | });
  395 | 
  396 | test('科研需求会逐项询问业务细节并在全部回答后生成结果', async ({ page }) => {
  397 |   await configurePlanMode(page, { enabled: true, introSeen: true });
  398 |   const researchPrompt = '分析2015-2025年某地区心脑血管疾病死亡率，比较不同人群并进行YLL和Arriaga分解';
  399 |   const researchPlan: ApiResponse<OptimizationPlan> = {
  400 |     requestId: 'research-plan',
  401 |     data: {
  402 |       summary: '我已理解你的研究目标。还需要确认研究范围、数据口径和交付方式。',
  403 |       questions: [
  404 |         {
  405 |           id: 'research-region',
  406 |           question: '这项研究具体覆盖哪个地区？',
  407 |           hint: '请填写明确的省、市、国家或区域名称。',
  408 |           type: 'FREE_TEXT',
  409 |           options: [],
  410 |           examples: ['广东省', '北京市'],
  411 |           allowCustomAnswer: true,
  412 |         },
  413 |         {
  414 |           id: 'research-tool',
  415 |           question: '你希望使用哪种分析工具？',
  416 |           hint: '系统会据此调整代码和图表实现。',
  417 |           type: 'SINGLE_CHOICE',
  418 |           options: [
  419 |             { id: 'r', label: 'R', description: '适合流行病学统计', answer: '使用 R 完成全部分析。', recommended: true },
  420 |             { id: 'python', label: 'Python', description: '适合自动化分析', answer: '使用 Python 完成全部分析。', recommended: false },
  421 |           ],
  422 |           examples: [],
  423 |           allowCustomAnswer: true,
  424 |         },
  425 |       ],
  426 |       templateCode: 'RESEARCH_ANALYSIS',
  427 |       provider: { provider: 'mock', model: 'deterministic-planner-v2', mock: true },
  428 |       latencyMs: 10,
  429 |     },
  430 |   };
  431 |   const researchResult: OptimizationResult = {
  432 |     ...optimizationResult,
  433 |     optimizedPrompt: '## 背景\n广东省心脑血管疾病死亡率研究\n\n## 任务\n完成趋势、YLL和Arriaga分解',
  434 |     sections: [
  435 |       { type: 'BACKGROUND', title: '背景', content: '广东省心脑血管疾病死亡率研究' },
  436 |       { type: 'TASK', title: '任务', content: '完成趋势、YLL和Arriaga分解' },
  437 |       { type: 'OUTPUT', title: '输出', content: '输出表格、图表和 R 代码' },
  438 |       { type: 'CONSTRAINTS', title: '约束', content: '不得编造数据' },
  439 |     ],
  440 |     templateCode: 'RESEARCH_ANALYSIS',
  441 |   };
  442 | 
  443 |   await page.route('**/api/v1/optimizations/plan', async (route) => {
  444 |     await route.fulfill({ status: 200, json: researchPlan });
  445 |   });
  446 |   await page.route('**/api/v1/optimizations', async (route) => {
  447 |     const requestBody = route.request().postDataJSON() as {
  448 |       planConfirmation?: { answers?: Array<{ questionId: string; answer: string }> };
  449 |     };
  450 |     expect(requestBody.planConfirmation?.answers).toEqual([
  451 |       expect.objectContaining({ questionId: 'research-region', answer: '广东省' }),
  452 |       expect.objectContaining({ questionId: 'research-tool', answer: '使用 R 完成全部分析。' }),
  453 |     ]);
  454 |     await route.fulfill({ status: 200, json: { requestId: 'research-final', data: researchResult } });
  455 |   });
  456 | 
  457 |   await page.goto('/workbench');
  458 |   await openWorkbenchPane(page, 'intent');
  459 |   await page.getByLabel('原始提示词').fill(researchPrompt);
  460 |   await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  461 |   const dialog = page.getByRole('dialog', { name: '确认关键细节' });
  462 |   await dialog.getByLabel('填写回答').fill('广东省');
```