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
                - generic [ref=e53]: 0 / 4000
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
              - generic [ref=e82]:
                - button "增量更新" [ref=e83] [cursor=pointer]
                - button "删除本地索引" [ref=e85] [cursor=pointer]
              - note "暂不支持的文件格式" [ref=e87]:
                - generic [ref=e88]: 暂不支持
                - generic [ref=e89]:
                  - generic [ref=e90]: .zip
                  - generic [ref=e92]: .rar
                  - generic [ref=e94]: .7z
                  - generic [ref=e96]: .tar
                  - generic [ref=e98]: .gz
                  - generic [ref=e100]: .exe
                  - generic [ref=e102]: .dll
                  - generic [ref=e104]: .so
                  - generic [ref=e106]: .dylib
                  - generic [ref=e108]: .jar
                  - generic [ref=e110]: .class
                  - generic [ref=e112]: .bin
                  - generic [ref=e114]: .mp3
                  - generic [ref=e116]: .wav
                  - generic [ref=e118]: .mp4
                  - generic [ref=e120]: .avi
                  - generic [ref=e122]: .mkv
                  - generic [ref=e124]: .mov
                  - generic [ref=e126]: .flv
                  - generic [ref=e128]: .ttf
                  - generic [ref=e130]: .otf
                  - generic [ref=e132]: .woff
                  - generic [ref=e134]: .woff2
                  - generic [ref=e136]: .ico
                  - generic [ref=e138]: .psd
              - generic [ref=e140]:
                - strong [ref=e141]: project-index-e2e
                - generic [ref=e142]: 2 个源码文件已建立本地索引
                - generic [ref=e143]: 2 个代码块 · 0 个文件已忽略 · 0 个超大文件仅保留元数据
                - generic [ref=e144]: 本轮新增 2 · 更新 0 · 未变化 0 · 删除 0
                - group [ref=e145]:
                  - generic "查看本次代码选择依据（2 段， 78 字符）" [active] [ref=e146] [cursor=pointer]
                  - list [ref=e147]:
                    - listitem [ref=e148]:
                      - generic "src/main.ts" [ref=e149]
                      - generic [ref=e150]: 任务中的符号、内容与任务匹配
                    - listitem [ref=e151]:
                      - generic "package.json" [ref=e152]
                      - generic [ref=e153]: 关键工程配置
            - group [ref=e154]:
              - generic "粘贴当前打开文件" [ref=e155] [cursor=pointer]
            - region "本次资料发送范围" [ref=e159]:
              - generic [ref=e160]:
                - strong [ref=e161]: 本次增强资料范围
                - generic [ref=e162]: 已核对
              - paragraph [ref=e164]: 本次为模拟增强，未调用外部模型；这里显示纳入增强输入的正文。
              - generic [ref=e165]: 完整范围指本次已读取或提取的正文。本地项目索引不会全部上传。
              - list [ref=e169]:
                - listitem [ref=e170]:
                  - generic [ref=e171]:
                    - generic "src/main.ts" [ref=e172]
                    - generic [ref=e173]: 未选取正文
                  - generic [ref=e175]: 未纳入本次安全片段：可能受解析、检索、权限或后端预算限制。
                - listitem [ref=e176]:
                  - generic [ref=e177]:
                    - generic "package.json" [ref=e178]
                    - generic [ref=e179]: 未选取正文
                  - generic [ref=e181]: 未纳入本次安全片段：可能受解析、检索、权限或后端预算限制。
            - generic [ref=e182]:
              - generic [ref=e183]:
                - generic [ref=e184]: 识别结果
                - generic [ref=e188]: 代码项目
              - generic [ref=e190]:
                - heading "项目概要" [level=3] [ref=e191]
                - paragraph [ref=e192]: 根据文件路径、构建清单和源码证据，当前识别为软件项目，主要技术栈包括Java 21、Spring Boot 3。 可见模块主要包括后端服务。 本次上下文包含 1 个文件摘要、1 项依赖和 3 个目录节点。
              - generic [ref=e193]:
                - generic [ref=e194]:
                  - generic [ref=e195]: 技术栈
                  - generic [ref=e196]: 2 项
                - generic [ref=e197]:
                  - article [ref=e198]:
                    - generic [ref=e199]:
                      - generic [ref=e200]: Java 21
                      - generic [ref=e202]: 100%
                    - paragraph [ref=e203]: 来源：pom.xml
                  - article [ref=e204]:
                    - generic [ref=e205]:
                      - generic [ref=e206]: Spring Boot 3
                      - generic [ref=e208]: 100%
                    - paragraph [ref=e209]: 来源：pom.xml
              - generic [ref=e210]:
                - generic [ref=e211]:
                  - generic [ref=e212]: 功能模块
                  - generic [ref=e213]: 1 个
                - list [ref=e217]:
                  - listitem [ref=e218]:
                    - generic [ref=e219]:
                      - strong [ref=e220]: 后端服务
                      - generic [ref=e221]: 1 个来源文件
                    - code [ref=e222]: 项目根目录
                    - paragraph [ref=e223]: 根据构建文件和源码识别出的服务端实现。
              - group [ref=e226]:
                - generic "依赖信息 1 项" [ref=e227] [cursor=pointer]:
                  - generic [ref=e228]: 依赖信息
                  - generic [ref=e229]: 1 项
              - group [ref=e230]:
                - generic "目录结构 3 个节点" [ref=e231] [cursor=pointer]:
                  - generic [ref=e232]: 目录结构
                  - generic [ref=e233]: 3 个节点
              - generic [ref=e234]:
                - generic [ref=e235]:
                  - generic [ref=e236]: 代码文件摘要
                  - generic [ref=e237]: 1 个文件
                - list [ref=e241]:
                  - listitem [ref=e242]:
                    - generic [ref=e243]:
                      - strong [ref=e244]: pom.xml
                      - generic [ref=e245]: xml
                    - paragraph [ref=e247]: Maven 项目配置，使用 Spring Boot Web。
              - paragraph [ref=e250]: 1 个依赖 · 3 个目录节点
          - button "重新分析上下文" [ref=e251] [cursor=pointer]
    - button "切换为深色主题" [ref=e256] [cursor=pointer]
  - alert [ref=e259]:
    - paragraph [ref=e263]: 已为 2 个源码文件建立本地索引。
  - alert [ref=e264]:
    - paragraph [ref=e268]: 最终提示词已生成。
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
> 862 |   await expect(page.locator('.project-index-summary').getByText('src/main.ts', { exact: true })).toBeVisible();
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