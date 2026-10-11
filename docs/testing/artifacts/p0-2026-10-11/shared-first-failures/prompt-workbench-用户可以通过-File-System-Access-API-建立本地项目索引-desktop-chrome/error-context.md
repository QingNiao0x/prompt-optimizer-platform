# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: prompt-workbench.spec.ts >> 用户可以通过 File System Access API 建立本地项目索引
- Location: e2e\prompt-workbench.spec.ts:787:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('src/main.ts', { exact: true })
Expected: visible
Error: strict mode violation: getByText('src/main.ts', { exact: true }) resolved to 2 elements:
    1) <span data-v-2e2fc0d3="" title="src/main.ts">src/main.ts</span> aka getByText('src/main.ts').first()
    2) <span data-v-9be6cce9="" title="src/main.ts">src/main.ts</span> aka getByLabel('本次资料发送范围').getByText('src/main.ts')

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByText('src/main.ts', { exact: true })

```

# Page snapshot

```yaml
- generic [ref=e1]:
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
                  - generic [ref=e70]: 0 / 4000
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
                - generic [ref=e99]:
                  - button "增量更新" [ref=e100] [cursor=pointer]
                  - button "删除本地索引" [ref=e102] [cursor=pointer]
                - note "暂不支持的文件格式" [ref=e104]:
                  - generic [ref=e105]: 暂不支持
                  - generic [ref=e106]:
                    - generic [ref=e107]: .zip
                    - generic [ref=e109]: .rar
                    - generic [ref=e111]: .7z
                    - generic [ref=e113]: .tar
                    - generic [ref=e115]: .gz
                    - generic [ref=e117]: .exe
                    - generic [ref=e119]: .dll
                    - generic [ref=e121]: .so
                    - generic [ref=e123]: .dylib
                    - generic [ref=e125]: .jar
                    - generic [ref=e127]: .class
                    - generic [ref=e129]: .bin
                    - generic [ref=e131]: .mp3
                    - generic [ref=e133]: .wav
                    - generic [ref=e135]: .mp4
                    - generic [ref=e137]: .avi
                    - generic [ref=e139]: .mkv
                    - generic [ref=e141]: .mov
                    - generic [ref=e143]: .flv
                    - generic [ref=e145]: .ttf
                    - generic [ref=e147]: .otf
                    - generic [ref=e149]: .woff
                    - generic [ref=e151]: .woff2
                    - generic [ref=e153]: .ico
                    - generic [ref=e155]: .psd
                - generic [ref=e157]:
                  - strong [ref=e158]: project-index-e2e
                  - generic [ref=e159]: 2 个源码文件已建立本地索引
                  - generic [ref=e160]: 2 个代码块 · 0 个文件已忽略 · 0 个超大文件仅保留元数据
                  - generic [ref=e161]: 本轮新增 2 · 更新 0 · 未变化 0 · 删除 0
                  - group [ref=e162]:
                    - generic "查看本次代码选择依据（2 段， 78 字符）" [active] [ref=e163] [cursor=pointer]
                    - list [ref=e164]:
                      - listitem [ref=e165]:
                        - generic "src/main.ts" [ref=e166]
                        - generic [ref=e167]: 任务中的符号、内容与任务匹配
                      - listitem [ref=e168]:
                        - generic "package.json" [ref=e169]
                        - generic [ref=e170]: 关键工程配置
              - group [ref=e171]:
                - generic "粘贴当前打开文件" [ref=e172] [cursor=pointer]
              - region "本次资料发送范围" [ref=e176]:
                - generic [ref=e177]:
                  - strong [ref=e178]: 本次增强资料范围
                  - generic [ref=e179]: 已核对
                - paragraph [ref=e181]: 本次为模拟增强，未调用外部模型；这里显示纳入增强输入的正文。
                - generic [ref=e182]: 完整范围指本次已读取或提取的正文。本地项目索引不会全部上传。
                - list [ref=e186]:
                  - listitem [ref=e187]:
                    - generic [ref=e188]:
                      - generic "src/main.ts" [ref=e189]
                      - generic [ref=e190]: 未选取正文
                    - generic [ref=e192]: 未纳入本次安全片段：可能受解析、检索、权限或后端预算限制。
                  - listitem [ref=e193]:
                    - generic [ref=e194]:
                      - generic "package.json" [ref=e195]
                      - generic [ref=e196]: 未选取正文
                    - generic [ref=e198]: 未纳入本次安全片段：可能受解析、检索、权限或后端预算限制。
              - generic [ref=e199]:
                - generic [ref=e200]:
                  - generic [ref=e201]: 识别结果
                  - generic [ref=e205]: 代码项目
                - generic [ref=e207]:
                  - heading "项目概要" [level=3] [ref=e208]
                  - paragraph [ref=e209]: 根据文件路径、构建清单和源码证据，当前识别为软件项目，主要技术栈包括Java 21、Spring Boot 3。 可见模块主要包括后端服务。 本次上下文包含 1 个文件摘要、1 项依赖和 3 个目录节点。
                - generic [ref=e210]:
                  - generic [ref=e211]:
                    - generic [ref=e212]: 技术栈
                    - generic [ref=e213]: 2 项
                  - generic [ref=e214]:
                    - article [ref=e215]:
                      - generic [ref=e216]:
                        - generic [ref=e217]: Java 21
                        - generic [ref=e219]: 100%
                      - paragraph [ref=e220]: 来源：pom.xml
                    - article [ref=e221]:
                      - generic [ref=e222]:
                        - generic [ref=e223]: Spring Boot 3
                        - generic [ref=e225]: 100%
                      - paragraph [ref=e226]: 来源：pom.xml
                - generic [ref=e227]:
                  - generic [ref=e228]:
                    - generic [ref=e229]: 功能模块
                    - generic [ref=e230]: 1 个
                  - list [ref=e234]:
                    - listitem [ref=e235]:
                      - generic [ref=e236]:
                        - strong [ref=e237]: 后端服务
                        - generic [ref=e238]: 1 个来源文件
                      - code [ref=e239]: 项目根目录
                      - paragraph [ref=e240]: 根据构建文件和源码识别出的服务端实现。
                - group [ref=e243]:
                  - generic "依赖信息 1 项" [ref=e244] [cursor=pointer]:
                    - generic [ref=e245]: 依赖信息
                    - generic [ref=e246]: 1 项
                - group [ref=e247]:
                  - generic "目录结构 3 个节点" [ref=e248] [cursor=pointer]:
                    - generic [ref=e249]: 目录结构
                    - generic [ref=e250]: 3 个节点
                - generic [ref=e251]:
                  - generic [ref=e252]:
                    - generic [ref=e253]: 代码文件摘要
                    - generic [ref=e254]: 1 个文件
                  - list [ref=e258]:
                    - listitem [ref=e259]:
                      - generic [ref=e260]:
                        - strong [ref=e261]: pom.xml
                        - generic [ref=e262]: xml
                      - paragraph [ref=e264]: Maven 项目配置，使用 Spring Boot Web。
                - paragraph [ref=e267]: 1 个依赖 · 3 个目录节点
            - button "重新分析上下文" [ref=e268] [cursor=pointer]
          - generic [ref=e273]:
            - generic [ref=e274]:
              - text: 02 / Intent
              - heading "把想法写下来。 工程细节，交给上下文。" [level=1] [ref=e275]:
                - generic [ref=e276]: 把想法写下来。
                - generic [ref=e277]: 工程细节，交给上下文。
            - generic [ref=e278]:
              - generic [ref=e279]: 原始提示词
              - generic [ref=e280]:
                - textbox "原始提示词" [ref=e281]:
                  - /placeholder: 请帮我查询全球使用AI最多的职业/行业
                  - text: 修改 projectName 常量
                - generic [ref=e282]: 17 / 8000
              - generic [ref=e283]:
                - strong [ref=e284]: 下一步：方案确认
                - generic [ref=e285]: 系统只询问会明显影响结果的细节，再生成最终提示词。
              - generic [ref=e286]:
                - generic [ref=e287]:
                  - generic [ref=e288]:
                    - generic [ref=e289]: 模型版本
                    - generic [ref=e291] [cursor=pointer]:
                      - generic:
                        - combobox "选择模型版本" [ref=e293]
                        - generic [ref=e294]: DeepSeek-V4-Pro
                  - generic [ref=e299] [cursor=pointer]:
                    - generic [ref=e300]: Plan 确认
                    - emphasis [ref=e301]: Plan
                    - generic [ref=e302]:
                      - switch "Plan 确认" [checked]
                  - generic [ref=e305] [cursor=pointer]:
                    - generic [ref=e306]: 示例参考
                    - generic [ref=e307]:
                      - switch "示例参考"
                - button "先确认并增强" [ref=e311] [cursor=pointer]
          - generic [ref=e317]:
            - generic [ref=e318]:
              - generic [ref=e319]: 03 / Structured prompt
              - generic [ref=e320]:
                - group "复制提示词" [ref=e321]:
                  - button "复制提示词" [ref=e322] [cursor=pointer]
                  - button "选择 AI 平台" [ref=e330] [cursor=pointer]
                - button "编辑" [ref=e334] [cursor=pointer]
            - heading "增强结果" [level=2] [ref=e339]
            - button "先确认并再次增强" [ref=e340] [cursor=pointer]
            - paragraph [ref=e346]: 点击复制后，请将内容粘贴至您使用的 AI 软件或网页版。
            - generic "增强结果内容，可滚动查看完整提示词" [ref=e348]:
              - generic [ref=e349]:
                - generic [ref=e350]:
                  - term [ref=e351]: 模型版本
                  - definition [ref=e352]: DeepSeek-V4-Pro
                  - generic [ref=e353]: deepseek
                - generic [ref=e354]:
                  - term [ref=e355]: Template
                  - definition [ref=e356]: FEATURE_DEVELOPMENT
                  - generic [ref=e357]: 4 个结构段落
                - generic [ref=e358]:
                  - term [ref=e359]: Latency
                  - definition [ref=e360]: 128 ms
                  - generic [ref=e361]: Mock 结果
              - article [ref=e362]:
                - generic [ref=e363]:
                  - generic [ref=e364]: "01"
                  - generic [ref=e367]:
                    - generic [ref=e369]:
                      - generic [ref=e370]: 背景
                      - heading "背景" [level=3] [ref=e371]
                    - generic [ref=e374]: Spring Boot 用户服务。
                - generic [ref=e375]:
                  - generic [ref=e376]: "02"
                  - generic [ref=e379]:
                    - generic [ref=e381]:
                      - generic [ref=e382]: 任务
                      - heading "任务目标" [level=3] [ref=e383]
                    - generic [ref=e386]: 为现有 Spring Boot 用户模块增加登录接口。
                - generic [ref=e387]:
                  - generic [ref=e388]: "03"
                  - generic [ref=e391]:
                    - generic [ref=e393]:
                      - generic [ref=e394]: 输出
                      - heading "输入输出" [level=3] [ref=e395]
                    - generic [ref=e398]: 接收用户名和密码，成功后返回登录结果。
                - generic [ref=e399]:
                  - generic [ref=e400]: "04"
                  - generic [ref=e403]:
                    - generic [ref=e405]:
                      - generic [ref=e406]: 约束
                      - heading "约束条件" [level=3] [ref=e407]
                    - generic [ref=e410]: 校验空值和错误凭证，不记录明文密码。
    - button "切换为深色主题" [ref=e411] [cursor=pointer]
  - alert [ref=e414]:
    - paragraph [ref=e418]: 已为 2 个源码文件建立本地索引。
  - alert [ref=e419]:
    - paragraph [ref=e423]: 最终提示词已生成。
```

# Test source

```ts
  762 |   });
  763 | 
  764 |   await page.goto('/workbench');
  765 |   await page.waitForLoadState('networkidle');
  766 |   await openWorkbenchPane(page, 'context');
  767 |   await page.getByText('粘贴当前打开文件').click();
  768 |   await page.getByLabel('文件相对路径').fill('pom.xml');
  769 |   await page.getByPlaceholder('粘贴与当前任务相关的代码片段…')
  770 |     .fill('<artifactId>spring-boot-starter-web</artifactId>');
  771 |   await page.getByRole('button', { name: '加入上下文' }).click();
  772 |   await page.locator('input[type="file"]').nth(1).setInputFiles({
  773 |     name: '审批方案.txt',
  774 |     mimeType: 'text/plain',
  775 |     buffer: Buffer.from('订单超过五万元须财务复核。', 'utf8'),
  776 |   });
  777 |   await expect(page.getByText('审批方案.txt', { exact: true })).toBeVisible();
  778 |   await openWorkbenchPane(page, 'intent');
  779 |   await page.getByLabel('原始提示词').fill('按上传的方案文件实现订单审批');
  780 |   await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  781 |   await confirmContextTransmission(page, '生成确认问题前分析上下文');
  782 |   await confirmContextTransmission(page, '生成最终提示词');
  783 | 
  784 |   expect(mixedPreparationCalls).toBe(1);
  785 | });
  786 | 
  787 | test('用户可以通过 File System Access API 建立本地项目索引', async ({ page }) => {
  788 |   await configurePlanMode(page, { enabled: true, introSeen: true });
  789 |   await page.route('**/api/v1/optimizations/plan', async (route) => {
  790 |     await route.fulfill({
  791 |       status: 200,
  792 |       json: {
  793 |         ...directPlanResponse,
  794 |         data: {
  795 |           ...directPlanResponse.data,
  796 |           planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
  797 |           planningContext: planningContextReference,
  798 |           expiresAt: '2026-09-14T08:30:00Z',
  799 |         },
  800 |       },
  801 |     });
  802 |   });
  803 |   await page.route('**/api/v1/context/planning', async (route) => {
  804 |     const requestBody = route.request().postDataJSON() as {
  805 |       context?: { files?: Array<{ path: string }> };
  806 |     };
  807 |     expect(requestBody.context?.files?.some((file) => file.path.includes('src/main.ts'))).toBe(true);
  808 |     await route.fulfill({ status: 200, json: planningContextResponse });
  809 |   });
  810 |   await page.route('**/api/v1/optimizations', async (route) => {
  811 |     const requestBody = route.request().postDataJSON() as {
  812 |       context?: { files?: Array<{ path: string }> };
  813 |     };
  814 |     expect(requestBody.context?.files?.some((file) => file.path.includes('src/main.ts'))).toBe(true);
  815 |     await route.fulfill({ status: 200, json: optimizationResponse });
  816 |   });
  817 | 
  818 |   await page.addInitScript(async () => {
  819 |     const storageManager = navigator.storage as StorageManager & {
  820 |       getDirectory(): Promise<FileSystemDirectoryHandle>;
  821 |     };
  822 |     const storageRoot = await storageManager.getDirectory();
  823 |     const projectRoot = await storageRoot.getDirectoryHandle('project-index-e2e', { create: true });
  824 |     const sourceDirectory = await projectRoot.getDirectoryHandle('src', { create: true });
  825 |     const sourceHandle = await sourceDirectory.getFileHandle('main.ts', { create: true });
  826 |     const sourceWriter = await sourceHandle.createWritable();
  827 |     await sourceWriter.write('export const projectName = "prompt-optimizer";');
  828 |     await sourceWriter.close();
  829 | 
  830 |     const packageHandle = await projectRoot.getFileHandle('package.json', { create: true });
  831 |     const packageWriter = await packageHandle.createWritable();
  832 |     await packageWriter.write('{"dependencies":{"vue":"3.5.0"}}');
  833 |     await packageWriter.close();
  834 | 
  835 |     const dependencyDirectory = await projectRoot.getDirectoryHandle('node_modules', { create: true });
  836 |     const dependencyHandle = await dependencyDirectory.getFileHandle('ignored.js', { create: true });
  837 |     const dependencyWriter = await dependencyHandle.createWritable();
  838 |     await dependencyWriter.write('window.thirdParty = true;');
  839 |     await dependencyWriter.close();
  840 | 
  841 |     Object.defineProperty(window, 'showDirectoryPicker', {
  842 |       configurable: true,
  843 |       value: async () => projectRoot,
  844 |     });
  845 |   });
  846 | 
  847 |   await page.goto('/workbench');
  848 |   await openWorkbenchPane(page, 'context');
  849 |   await page.getByRole('button', { name: '添加上下文' }).click();
  850 |   await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  851 | 
  852 |   await expect(page.getByText('2 个源码文件已建立本地索引')).toBeVisible();
  853 |   await expect(page.getByText(/2 个代码块/)).toBeVisible();
  854 | 
  855 |   await openWorkbenchPane(page, 'intent');
  856 |   await page.getByLabel('原始提示词').fill('修改 projectName 常量');
  857 |   await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  858 |   await confirmContextTransmission(page, '生成确认问题前分析上下文');
  859 |   await confirmContextTransmission(page, '生成最终提示词');
  860 |   await openWorkbenchPane(page, 'context');
  861 |   await page.getByText(/查看本次代码选择依据/).click();
> 862 |   await expect(page.getByText('src/main.ts', { exact: true })).toBeVisible();
      |                                                                ^ Error: expect(locator).toBeVisible() failed
  863 |   await expect(page.getByText(/任务中的符号/).first()).toBeVisible();
  864 | 
  865 |   await page.evaluate(async () => {
  866 |     const storageManager = navigator.storage as StorageManager & {
  867 |       getDirectory(): Promise<FileSystemDirectoryHandle>;
  868 |     };
  869 |     const storageRoot = await storageManager.getDirectory();
  870 |     const projectRoot = await storageRoot.getDirectoryHandle('project-index-e2e');
  871 |     const sourceDirectory = await projectRoot.getDirectoryHandle('src');
  872 |     const sourceHandle = await sourceDirectory.getFileHandle('main.ts');
  873 |     const sourceWriter = await sourceHandle.createWritable();
  874 |     await sourceWriter.write('export const projectName = "prompt-optimizer-updated";');
  875 |     await sourceWriter.close();
  876 | 
  877 |     const readmeHandle = await projectRoot.getFileHandle('README.md', { create: true });
  878 |     const readmeWriter = await readmeHandle.createWritable();
  879 |     await readmeWriter.write('# Prompt Optimizer');
  880 |     await readmeWriter.close();
  881 |     await projectRoot.removeEntry('package.json');
  882 |   });
  883 | 
  884 |   await openWorkbenchPane(page, 'context');
  885 |   await page.getByRole('button', { name: '增量更新' }).click();
  886 |   await expect(page.getByText(/本轮新增 1 · 更新 1 · 未变化 0 · 删除 1/)).toBeVisible();
  887 | 
  888 |   await page.getByRole('button', { name: '清空', exact: true }).click();
  889 |   await expect(page.getByText(/个源码文件已建立本地索引/)).not.toBeVisible({ timeout: 1_000 });
  890 | });
  891 | 
  892 | test('用户可以暂停并继续本地项目索引', async ({ page }) => {
  893 |   await page.addInitScript(async () => {
  894 |     const storageManager = navigator.storage as StorageManager & {
  895 |       getDirectory(): Promise<FileSystemDirectoryHandle>;
  896 |     };
  897 |     const storageRoot = await storageManager.getDirectory();
  898 |     const projectRoot = await storageRoot.getDirectoryHandle('project-index-pause-e2e', {
  899 |       create: true,
  900 |     });
  901 |     const sourceHandle = await projectRoot.getFileHandle('main.ts', { create: true });
  902 |     const sourceWriter = await sourceHandle.createWritable();
  903 |     await sourceWriter.write('export const pauseAndResume = true;');
  904 |     await sourceWriter.close();
  905 | 
  906 |     Object.defineProperty(window, 'showDirectoryPicker', {
  907 |       configurable: true,
  908 |       value: async () => projectRoot,
  909 |     });
  910 |   });
  911 | 
  912 |   await page.goto('/workbench');
  913 |   await openWorkbenchPane(page, 'context');
  914 |   await page.evaluate(() => {
  915 |     const observer = new MutationObserver(() => {
  916 |       const pauseButton = document.querySelector<HTMLButtonElement>('[data-testid="pause-index"]');
  917 |       if (pauseButton) {
  918 |         pauseButton.click();
  919 |         observer.disconnect();
  920 |       }
  921 |     });
  922 |     observer.observe(document.body, { childList: true, subtree: true });
  923 |   });
  924 |   await page.getByRole('button', { name: '添加上下文' }).click();
  925 |   await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  926 | 
  927 |   await expect(page.getByText(/已暂停，检查点位于/)).toBeVisible();
  928 |   await page.getByRole('button', { name: '继续索引' }).click();
  929 |   await expect(page.getByText('1 个源码文件已建立本地索引')).toBeVisible();
  930 | });
  931 | 
  932 | test('重新打开页面后不恢复之前选择的项目文件夹', async ({ page }) => {
  933 |   await page.addInitScript(async () => {
  934 |     await new Promise<void>((resolve, reject) => {
  935 |       const request = indexedDB.open('prompt-optimizer-project-index', 2);
  936 |       request.onupgradeneeded = () => {
  937 |         const database = request.result;
  938 |         database.createObjectStore('projects', { keyPath: 'id' });
  939 |         const fileStore = database.createObjectStore('files', { keyPath: 'id' });
  940 |         fileStore.createIndex('projectId', 'projectId');
  941 |         fileStore.createIndex('projectPath', ['projectId', 'path'], { unique: true });
  942 |         const chunkStore = database.createObjectStore('chunks', { keyPath: 'id' });
  943 |         chunkStore.createIndex('projectId', 'projectId');
  944 |         chunkStore.createIndex('projectPath', ['projectId', 'path']);
  945 |         chunkStore.createIndex('projectPriority', ['projectId', 'priority']);
  946 |         chunkStore.createIndex('searchTerms', 'searchTerms', { multiEntry: true });
  947 |         database.createObjectStore('sources', { keyPath: 'projectId' });
  948 |       };
  949 |       request.onsuccess = () => {
  950 |         const database = request.result;
  951 |         const transaction = database.transaction(['projects', 'files'], 'readwrite');
  952 |         transaction.objectStore('projects').put({
  953 |           id: 'legacy-project',
  954 |           name: 'legacy-project',
  955 |           rootDirectory: 'legacy-project',
  956 |           status: 'READY',
  957 |           discoveredFiles: 1,
  958 |           eligibleFiles: 1,
  959 |           indexedFiles: 1,
  960 |           metadataOnlyFiles: 0,
  961 |           ignoredFiles: 0,
  962 |           ignoredDirectories: 0,
```