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
        - generic [ref=e10]:
          - strong [ref=e11]: PromptOptimizer
          - generic [ref=e12]: Prompt context studio
      - navigation "主导航" [ref=e13]:
        - link "首页" [ref=e14] [cursor=pointer]:
          - /url: /
        - link "工作台" [ref=e18] [cursor=pointer]:
          - /url: /workbench
        - link "历史" [ref=e22] [cursor=pointer]:
          - /url: /history
        - link "设置" [ref=e28] [cursor=pointer]:
          - /url: /settings
      - generic [ref=e32]:
        - generic [ref=e33]:
          - generic "test@example.com" [ref=e34]: 测试用户
          - button "退出登录" [ref=e35] [cursor=pointer]
        - generic "模型版本：DeepSeek-V4-Pro（由平台管理员维护）" [ref=e36]: DeepSeek-V4-Pro
    - main [ref=e39]:
      - generic [ref=e40]:
        - generic [ref=e41]:
          - generic [ref=e42]:
            - paragraph [ref=e43]: Context-aware prompt engineering
            - paragraph [ref=e44]: 让模型先理解你的工程，再理解你的要求。
          - list "提示词处理流程" [ref=e45]:
            - listitem [ref=e46]:
              - generic [ref=e47]: 项目事实
            - listitem [ref=e49]:
              - generic [ref=e50]: 需求明确化
            - listitem [ref=e52]:
              - generic [ref=e53]: 结构化输出
        - generic [ref=e54]:
          - complementary [ref=e55]:
            - generic [ref=e56]:
              - generic [ref=e57]:
                - text: 01 / Context
                - heading "项目上下文" [level=2] [ref=e58]
              - generic [ref=e59]: 已分析
            - generic [ref=e64]:
              - generic [ref=e65]:
                - generic [ref=e66]: 自定义项目描述
                - paragraph [ref=e67]: 描述当前项目、架构偏好或团队约束，不是产品公告。
                - generic [ref=e68]:
                  - textbox "自定义项目描述" [ref=e69]:
                    - /placeholder: 例如：公共卫生研究，数据来自年度死因登记；或 Spring Boot 3 项目，使用 PostgreSQL。
                    - text: Spring Boot 3 模块化单体，使用 PostgreSQL。
                  - generic [ref=e70]: 34 / 4000
              - generic [ref=e71]:
                - generic [ref=e72]:
                  - generic [ref=e73]: 本地资料与项目文件
                  - button "清空" [ref=e74] [cursor=pointer]
                - button "Choose File" [ref=e76]
                - button "Choose File" [ref=e77]
                - generic [ref=e78]:
                  - button "添加上下文" [ref=e81] [cursor=pointer]
                  - region "拖入文件或文件夹添加上下文" [ref=e88]:
                    - generic [ref=e92]:
                      - strong [ref=e93]: 也可将文件或文件夹拖到这里
                      - generic [ref=e94]: 拖入后自动识别可用内容，需上传解析的文档会先请你确认
                  - generic [ref=e95]:
                    - paragraph [ref=e96]: 可建立项目索引，也可单独上传报告、论文、表格、演示文稿和图片。
                    - paragraph [ref=e97]: 可添加代码、Word、Excel、PPT、PDF 等文件或整个文件夹；支持的浏览器会在本地索引文件夹中的代码，办公文档上传解析前会先请你确认。
                    - paragraph [ref=e98]: 确认上传后，文档按 1 MiB 分片发送至本项目后端，解析后只保留最长 2 小时的临时全文索引； 一键增强时仅选取与当前任务相关的片段。
                - note "暂不支持的文件格式" [ref=e99]:
                  - generic [ref=e100]: 暂不支持
                  - generic [ref=e101]:
                    - generic [ref=e102]: .zip
                    - generic [ref=e104]: .rar
                    - generic [ref=e106]: .7z
                    - generic [ref=e108]: .tar
                    - generic [ref=e110]: .gz
                    - generic [ref=e112]: .exe
                    - generic [ref=e114]: .dll
                    - generic [ref=e116]: .so
                    - generic [ref=e118]: .dylib
                    - generic [ref=e120]: .jar
                    - generic [ref=e122]: .class
                    - generic [ref=e124]: .bin
                    - generic [ref=e126]: .mp3
                    - generic [ref=e128]: .wav
                    - generic [ref=e130]: .mp4
                    - generic [ref=e132]: .avi
                    - generic [ref=e134]: .mkv
                    - generic [ref=e136]: .mov
                    - generic [ref=e138]: .flv
                    - generic [ref=e140]: .ttf
                    - generic [ref=e142]: .otf
                    - generic [ref=e144]: .woff
                    - generic [ref=e146]: .woff2
                    - generic [ref=e148]: .ico
                    - generic [ref=e150]: .psd
                - generic [ref=e152]:
                  - generic [ref=e153]:
                    - generic [ref=e154]: "1"
                    - generic [ref=e155]: 个文件已选择
                  - list [ref=e159]:
                    - listitem [ref=e160]:
                      - generic [ref=e161]: java
                      - generic "pom.xml" [ref=e162]
                      - button "移除 pom.xml" [ref=e163] [cursor=pointer]
              - group [ref=e166]:
                - generic "粘贴当前打开文件" [ref=e167] [cursor=pointer]
                - generic [ref=e171]:
                  - generic [ref=e172]:
                    - textbox "文件相对路径" [ref=e175]:
                      - /placeholder: src/example.ts
                      - text: pom.xml
                    - generic [ref=e177] [cursor=pointer]:
                      - generic:
                        - combobox "代码语言" [ref=e179]
                        - generic [ref=e180]: Java
                  - textbox "粘贴与当前任务相关的代码片段…" [ref=e186]
                  - button "加入上下文" [ref=e187] [cursor=pointer]
              - region "本次资料发送范围" [ref=e192]:
                - generic [ref=e193]:
                  - strong [ref=e194]: 上下文分析资料范围
                  - generic [ref=e195]: 已核对
                - paragraph [ref=e197]: 这里显示本次后端分析范围，尚未发送给增强模型。
                - generic [ref=e198]: 完整范围指本次已读取或提取的正文。本地项目索引不会全部上传。
                - list [ref=e202]:
                  - listitem [ref=e203]:
                    - generic [ref=e204]:
                      - generic "pom.xml" [ref=e205]
                      - generic [ref=e206]: 部分选取
                    - generic [ref=e208]: 仅纳入本次选取的安全片段或摘要，无法确认原文件全文覆盖。
                    - group [ref=e209]:
                      - generic "查看本次安全片段（48 字符）" [ref=e210] [cursor=pointer]
              - generic [ref=e211]:
                - generic [ref=e212]:
                  - generic [ref=e213]: 识别结果
                  - generic [ref=e217]: 代码项目
                - generic [ref=e219]:
                  - heading "项目概要" [level=3] [ref=e220]
                  - paragraph [ref=e221]: 根据文件路径、构建清单和源码证据，当前识别为软件项目，主要技术栈包括Java 21、Spring Boot 3。 可见模块主要包括后端服务。 本次上下文包含 1 个文件摘要、1 项依赖和 3 个目录节点。
                - generic [ref=e222]:
                  - generic [ref=e223]:
                    - generic [ref=e224]: 技术栈
                    - generic [ref=e225]: 2 项
                  - generic [ref=e226]:
                    - article [ref=e227]:
                      - generic [ref=e228]:
                        - generic [ref=e229]: Java 21
                        - generic [ref=e231]: 100%
                      - paragraph [ref=e232]: 来源：pom.xml
                    - article [ref=e233]:
                      - generic [ref=e234]:
                        - generic [ref=e235]: Spring Boot 3
                        - generic [ref=e237]: 100%
                      - paragraph [ref=e238]: 来源：pom.xml
                - generic [ref=e239]:
                  - generic [ref=e240]:
                    - generic [ref=e241]: 功能模块
                    - generic [ref=e242]: 1 个
                  - list [ref=e246]:
                    - listitem [ref=e247]:
                      - generic [ref=e248]:
                        - strong [ref=e249]: 后端服务
                        - generic [ref=e250]: 1 个来源文件
                      - code [ref=e251]: 项目根目录
                      - paragraph [ref=e252]: 根据构建文件和源码识别出的服务端实现。
                - group [ref=e255]:
                  - generic "依赖信息 1 项" [ref=e256] [cursor=pointer]:
                    - generic [ref=e257]: 依赖信息
                    - generic [ref=e258]: 1 项
                - group [ref=e259]:
                  - generic "目录结构 3 个节点" [ref=e260] [cursor=pointer]:
                    - generic [ref=e261]: 目录结构
                    - generic [ref=e262]: 3 个节点
                - generic [ref=e263]:
                  - generic [ref=e264]:
                    - generic [ref=e265]: 代码文件摘要
                    - generic [ref=e266]: 1 个文件
                  - list [ref=e270]:
                    - listitem [ref=e271]:
                      - generic [ref=e272]:
                        - strong [ref=e273]: pom.xml
                        - generic [ref=e274]: xml
                      - paragraph [ref=e276]: Maven 项目配置，使用 Spring Boot Web。
                - paragraph [ref=e279]: 1 个依赖 · 3 个目录节点
            - button "重新分析上下文" [ref=e280] [cursor=pointer]
          - generic [ref=e285]:
            - generic [ref=e286]:
              - text: 02 / Intent
              - heading "把想法写下来。 工程细节，交给上下文。" [level=1] [ref=e287]:
                - generic [ref=e288]: 把想法写下来。
                - generic [ref=e289]: 工程细节，交给上下文。
            - generic [ref=e290]:
              - generic [ref=e291]: 原始提示词
              - generic [ref=e292]:
                - textbox "原始提示词" [ref=e293]:
                  - /placeholder: 请帮我查询全球使用AI最多的职业/行业
                - generic [ref=e294]: 0 / 8000
              - generic [ref=e295]:
                - strong [ref=e296]: 下一步：方案确认
                - generic [ref=e297]: 系统只询问会明显影响结果的细节，再生成最终提示词。
              - generic [ref=e298]:
                - generic [ref=e299]:
                  - generic [ref=e300]:
                    - generic [ref=e301]: 模型版本
                    - generic [ref=e303] [cursor=pointer]:
                      - generic:
                        - combobox "选择模型版本" [ref=e305]
                        - generic [ref=e306]: DeepSeek-V4-Pro
                  - generic [ref=e311] [cursor=pointer]:
                    - generic [ref=e312]: Plan 确认
                    - emphasis [ref=e313]: Plan
                    - generic [ref=e314]:
                      - switch "Plan 确认" [checked]
                  - generic [ref=e317] [cursor=pointer]:
                    - generic [ref=e318]: 示例参考
                    - generic [ref=e319]:
                      - switch "示例参考"
                - button "先确认并增强" [disabled] [ref=e323]
          - generic [ref=e329]:
            - generic [ref=e330]: 03 / Structured prompt
            - heading "增强结果" [level=2] [ref=e332]
            - generic [ref=e333]:
              - generic [ref=e334]:
                - generic [ref=e335]:
                  - generic [ref=e336]: 意图
                  - generic [ref=e337]: 上下文
                  - generic [ref=e338]: 约束
                - generic [ref=e341]: 结构化提示词
              - heading "结果会在这里展开" [level=3] [ref=e345]
              - paragraph [ref=e346]: 输入一个简短需求并点击“先确认并增强”， 系统会结合背景与资料生成可直接使用的任务说明。
    - button "切换为深色主题" [ref=e347] [cursor=pointer]
  - alert [ref=e350]:
    - paragraph [ref=e354]: 代码片段已加入本次上下文。
  - alert [ref=e355]:
    - paragraph [ref=e359]: 上下文资料分析完成。
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
> 362 |   await expect(page.getByText('Maven 项目配置，使用 Spring Boot Web。')).toBeVisible();
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