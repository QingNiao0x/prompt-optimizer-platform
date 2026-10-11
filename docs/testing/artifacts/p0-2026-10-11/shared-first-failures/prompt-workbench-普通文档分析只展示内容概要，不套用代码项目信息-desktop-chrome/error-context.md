# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: prompt-workbench.spec.ts >> 普通文档分析只展示内容概要，不套用代码项目信息
- Location: e2e\prompt-workbench.spec.ts:634:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('个文件已加入上下文')
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByText('个文件已加入上下文')

```

```yaml
- banner:
  - link "返回 PromptOptimizer 首页":
    - /url: /
    - strong: PromptOptimizer
    - text: Prompt context studio
  - navigation "主导航":
    - link "首页":
      - /url: /
    - link "工作台":
      - /url: /workbench
    - link "历史":
      - /url: /history
    - link "设置":
      - /url: /settings
  - text: 测试用户
  - button "退出登录"
  - text: DeepSeek-V4-Pro
- main:
  - paragraph: Context-aware prompt engineering
  - paragraph: 让模型先理解你的工程，再理解你的要求。
  - list "提示词处理流程":
    - listitem: 项目事实
    - listitem: 需求明确化
    - listitem: 结构化输出
  - complementary:
    - text: 01 / Context
    - heading "项目上下文" [level=2]
    - text: 自定义项目描述
    - paragraph: 描述当前项目、架构偏好或团队约束，不是产品公告。
    - textbox "自定义项目描述":
      - /placeholder: 例如：公共卫生研究，数据来自年度死因登记；或 Spring Boot 3 项目，使用 PostgreSQL。
    - text: 0 / 4000 本地资料与项目文件
    - button "清空"
    - button "Choose File"
    - button "Choose File"
    - button "添加上下文":
      - img
      - text: 添加上下文
    - region "拖入文件或文件夹添加上下文":
      - img
      - strong: 也可将文件或文件夹拖到这里
      - text: 拖入后自动识别可用内容，需上传解析的文档会先请你确认
    - paragraph: 可建立项目索引，也可单独上传报告、论文、表格、演示文稿和图片。
    - paragraph: 可添加代码、Word、Excel、PPT、PDF 等文件或整个文件夹；支持的浏览器会在本地索引文件夹中的代码，办公文档上传解析前会先请你确认。
    - paragraph: 确认上传后，文档按 1 MiB 分片发送至本项目后端，解析后只保留最长 2 小时的临时全文索引； 一键增强时仅选取与当前任务相关的片段。
    - note "暂不支持的文件格式": 暂不支持 .zip .rar .7z .tar .gz .exe .dll .so .dylib .jar .class .bin .mp3 .wav .mp4 .avi .mkv .mov .flv .ttf .otf .woff .woff2 .ico .psd
    - text: 1 个文件已选择
    - list:
      - listitem:
        - text: text 季度报告.txt
        - button "移除 季度报告.txt":
          - img
    - group:
      - img
      - text: 粘贴当前打开文件
    - button "分析上下文资料":
      - img
      - text: 分析上下文资料
  - text: 02 / Intent
  - heading "把想法写下来。 工程细节，交给上下文。" [level=1]
  - text: 原始提示词
  - textbox "原始提示词":
    - /placeholder: 请帮我查询全球使用AI最多的职业/行业
  - text: 0 / 8000
  - strong: 直接生成
  - text: 将直接生成最终提示词。若关键事实不足，结果中会列出待确认事项。 模型版本
  - combobox "选择模型版本"
  - text: DeepSeek-V4-Pro
  - img
  - text: Plan 确认
  - switch "Plan 确认"
  - text: 示例参考
  - switch "示例参考"
  - button "直接增强提示词" [disabled]
  - text: 03 / Structured prompt
  - heading "增强结果" [level=2]
  - heading "结果会在这里展开" [level=3]
  - paragraph: 输入一个简短需求并点击“直接增强提示词”， 系统会结合背景与资料生成可直接使用的任务说明。
- button "切换为深色主题"
```

# Test source

```ts
  553 |   await page.goto('/workbench');
  554 |   await openWorkbenchPane(page, 'intent');
  555 |   await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  556 |   await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  557 |   const dialog = page.getByRole('dialog', { name: '确认关键细节' });
  558 |   const answer = dialog.getByLabel('填写回答');
  559 |   await answer.fill('正常和异常登录场景都有自动化测试。');
  560 |   await dialog.getByRole('button', { name: '生成最终提示词' }).click();
  561 | 
  562 |   await expect(dialog.getByRole('alert')).toContainText('模型服务当前请求繁忙，请稍后重试。');
  563 |   await expect(answer).toHaveValue('正常和异常登录场景都有自动化测试。');
  564 | });
  565 | 
  566 | test('计划会话过期后可以重新生成问题并完成增强', async ({ page }) => {
  567 |   await configurePlanMode(page, { enabled: true, introSeen: true });
  568 |   let planCalls = 0;
  569 |   let finalCalls = 0;
  570 |   await page.route('**/api/v1/optimizations/plan', async (route) => {
  571 |     planCalls += 1;
  572 |     await route.fulfill({
  573 |       status: 200,
  574 |       json: {
  575 |         requestId: `expiry-plan-${planCalls}`,
  576 |         data: {
  577 |           ...loginPlanResponse.data,
  578 |           questions: [loginPlanResponse.data.questions[1]!],
  579 |           planId: planCalls === 1
  580 |             ? 'd53d3b67-62b2-4505-89dd-4ca88f837391'
  581 |             : '3a0e2ec5-1898-4b10-85ac-a9be1268ea1a',
  582 |           planningContext: null,
  583 |           expiresAt: '2026-09-20T12:30:00Z',
  584 |         },
  585 |       } satisfies ApiResponse<OptimizationPlan>,
  586 |     });
  587 |   });
  588 |   await page.route('**/api/v1/optimizations', async (route) => {
  589 |     finalCalls += 1;
  590 |     const requestBody = route.request().postDataJSON() as {
  591 |       planConfirmation?: { planId?: string };
  592 |     };
  593 |     if (finalCalls === 1) {
  594 |       expect(requestBody.planConfirmation?.planId).toBe('d53d3b67-62b2-4505-89dd-4ca88f837391');
  595 |       await route.fulfill({
  596 |         status: 400,
  597 |         json: {
  598 |           requestId: 'expired-final-request',
  599 |           error: {
  600 |             code: 'INVALID_ARGUMENT',
  601 |             message: '确认问题已过期，请重新生成。',
  602 |             retryable: false,
  603 |             details: {},
  604 |           },
  605 |         },
  606 |       });
  607 |       return;
  608 |     }
  609 |     expect(requestBody.planConfirmation?.planId).toBe('3a0e2ec5-1898-4b10-85ac-a9be1268ea1a');
  610 |     await route.fulfill({ status: 200, json: optimizationResponse });
  611 |   });
  612 | 
  613 |   await page.goto('/workbench');
  614 |   await openWorkbenchPane(page, 'intent');
  615 |   await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  616 |   await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  617 | 
  618 |   let dialog = page.getByRole('dialog', { name: '确认关键细节' });
  619 |   await dialog.getByLabel('填写回答').fill('覆盖正常和异常登录场景。');
  620 |   await dialog.getByRole('button', { name: '生成最终提示词' }).click();
  621 |   await expect(dialog.getByRole('alert')).toContainText('确认问题已过期，请重新生成。');
  622 |   await dialog.getByRole('button', { name: '返回修改需求' }).click();
  623 | 
  624 |   await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  625 |   dialog = page.getByRole('dialog', { name: '确认关键细节' });
  626 |   await dialog.getByLabel('填写回答').fill('覆盖正常和异常登录场景。');
  627 |   await dialog.getByRole('button', { name: '生成最终提示词' }).click();
  628 | 
  629 |   await expect(page.getByText('最终提示词已生成。')).toBeVisible();
  630 |   expect(planCalls).toBe(2);
  631 |   expect(finalCalls).toBe(2);
  632 | });
  633 | 
  634 | test('普通文档分析只展示内容概要，不套用代码项目信息', async ({ page }) => {
  635 |   await page.route('**/api/v1/context/analyze', async (route) => {
  636 |     await route.fulfill({
  637 |       status: 200,
  638 |       json: {
  639 |         requestId: 'e2e-document-context-request',
  640 |         data: documentContextSnapshot,
  641 |       } satisfies ApiResponse<ContextSnapshot>,
  642 |     });
  643 |   });
  644 | 
  645 |   await page.goto('/workbench');
  646 |   await page.waitForLoadState('networkidle');
  647 |   await openWorkbenchPane(page, 'context');
  648 |   await page.locator('input[type="file"]').nth(1).setInputFiles({
  649 |     name: '季度报告.txt',
  650 |     mimeType: 'text/plain',
  651 |     buffer: Buffer.from('本季度完成核心功能交付，下一季度将重点改善用户体验。'),
  652 |   });
> 653 |   await expect(page.getByText('个文件已加入上下文', { exact: false })).toBeVisible();
      |                                                               ^ Error: expect(locator).toBeVisible() failed
  654 | 
  655 |   await page.getByRole('button', { name: '分析上下文资料' }).click();
  656 |   await confirmContextTransmission(page, '分析上下文资料');
  657 | 
  658 |   await expect(page.getByText('普通文档', { exact: true })).toBeVisible();
  659 |   await expect(page.getByText('文件内容概要', { exact: true })).toBeVisible();
  660 |   await expect(page.getByText(documentContextSnapshot.fileSnippets[0]?.summary ?? '')).toBeVisible();
  661 |   await expect(page.getByText('项目概要', { exact: true })).toHaveCount(0);
  662 |   await expect(page.getByText('功能模块', { exact: true })).toHaveCount(0);
  663 |   await expect(page.getByText('技术栈', { exact: true })).toHaveCount(0);
  664 | });
  665 | 
  666 | test('未单独分析上下文时，一键增强仍返回并展示项目分析结果', async ({ page }) => {
  667 |   await configurePlanMode(page, { enabled: true, introSeen: true });
  668 |   let contextAnalyzeCalls = 0;
  669 |   let planningContextCalls = 0;
  670 |   await page.route('**/api/v1/context/analyze', async (route) => {
  671 |     contextAnalyzeCalls += 1;
  672 |     await route.fulfill({ status: 200, json: contextResponse });
  673 |   });
  674 |   await page.route('**/api/v1/optimizations/plan', async (route) => {
  675 |     const requestBody: unknown = route.request().postDataJSON();
  676 |     expect(requestBody).toMatchObject({ planningContext: planningContextReference });
  677 |     await route.fulfill({
  678 |       status: 200,
  679 |       json: {
  680 |         ...directPlanResponse,
  681 |         data: {
  682 |           ...directPlanResponse.data,
  683 |           planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
  684 |           planningContext: planningContextReference,
  685 |           expiresAt: '2026-09-14T08:30:00Z',
  686 |         },
  687 |       },
  688 |     });
  689 |   });
  690 |   await page.route('**/api/v1/context/planning', async (route) => {
  691 |     planningContextCalls += 1;
  692 |     await route.fulfill({ status: 200, json: planningContextResponse });
  693 |   });
  694 |   await page.route('**/api/v1/optimizations', async (route) => {
  695 |     const requestBody: unknown = route.request().postDataJSON();
  696 |     expect(requestBody).toMatchObject({
  697 |       rawPrompt: '为示例项目补充健康检查接口',
  698 |       context: {
  699 |         files: [expect.objectContaining({ path: 'pom.xml' })],
  700 |       },
  701 |     });
  702 |     await route.fulfill({ status: 200, json: optimizationResponse });
  703 |   });
  704 | 
  705 |   await page.goto('/workbench');
  706 |   await page.waitForLoadState('networkidle');
  707 |   await openWorkbenchPane(page, 'context');
  708 |   await page.getByText('粘贴当前打开文件').click();
  709 |   await page.getByLabel('文件相对路径').fill('pom.xml');
  710 |   await page.getByPlaceholder('粘贴与当前任务相关的代码片段…').fill(
  711 |     '<dependency><artifactId>spring-boot-starter-web</artifactId></dependency>',
  712 |   );
  713 |   await page.getByRole('button', { name: '加入上下文' }).click();
  714 |   await openWorkbenchPane(page, 'intent');
  715 |   await page.getByLabel('原始提示词').fill('为示例项目补充健康检查接口');
  716 |   await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  717 |   await confirmContextTransmission(page, '生成确认问题前分析上下文');
  718 |   await confirmContextTransmission(page, '生成最终提示词');
  719 |   expect(contextAnalyzeCalls).toBe(0);
  720 |   expect(planningContextCalls).toBe(1);
  721 |   await openWorkbenchPane(page, 'context');
  722 |   await expect(page.getByText('代码项目', { exact: true })).toBeVisible();
  723 |   await expect(page.getByText('项目概要', { exact: true })).toBeVisible();
  724 |   await expect(page.getByText('功能模块', { exact: true })).toBeVisible();
  725 | });
  726 | 
  727 | test('项目代码与单独上传的方案文件一起进入 Plan 上下文准备', async ({ page }) => {
  728 |   await configurePlanMode(page, { enabled: true, introSeen: true });
  729 |   await page.route('**/api/v1/optimizations/plan-events', async (route) => {
  730 |     await route.fulfill({ status: 204 });
  731 |   });
  732 |   let mixedPreparationCalls = 0;
  733 |   await page.route('**/api/v1/context/planning', async (route) => {
  734 |     const body = route.request().postDataJSON() as {
  735 |       context?: { files?: Array<{ path: string; content: string }> };
  736 |     };
  737 |     expect(body.context?.files?.map((file) => file.path))
  738 |       .toEqual(expect.arrayContaining(['pom.xml', '审批方案.txt']));
  739 |     expect(body.context?.files).toHaveLength(2);
  740 |     expect(body.context?.files?.find((file) => file.path === '审批方案.txt')?.content)
  741 |       .toContain('订单超过五万元须财务复核');
  742 |     mixedPreparationCalls += 1;
  743 |     await route.fulfill({ status: 200, json: planningContextResponse });
  744 |   });
  745 |   await page.route('**/api/v1/optimizations/plan', async (route) => {
  746 |     await route.fulfill({
  747 |       status: 200,
  748 |       json: { ...directPlanResponse, data: {
  749 |         ...directPlanResponse.data,
  750 |         planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
  751 |         planningContext: planningContextReference,
  752 |       } },
  753 |     });
```