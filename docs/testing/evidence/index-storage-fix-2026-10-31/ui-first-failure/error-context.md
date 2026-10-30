# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: project-index-progress.spec.ts >> 真实 Worker 显示百分比和互斥计数，项目忽略规则可以关闭
- Location: e2e\project-index-progress.spec.ts:5:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('241 个文本文件已完整建立本地索引')
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByText('241 个文本文件已完整建立本地索引')

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
    - link "提示词优化":
      - /url: /workbench
    - link "素材转提示词":
      - /url: /image-to-prompt
    - link "文章祛AI味":
      - /url: /text-polish
    - link "历史":
      - /url: /history
    - link "设置":
      - /url: /settings
  - text: 测试用户
  - button "退出登录"
  - text: 测试模型
- main:
  - heading "提示词优化工作台" [level=1]
  - paragraph: 把需求表达清楚，让 AI 更好执行。
  - list "提示词处理流程":
    - listitem: 项目事实
    - listitem: 需求明确化
    - listitem: 优化结果
  - complementary:
    - text: 01 / Context
    - heading "项目上下文" [level=2]
    - text: 自定义项目描述
    - paragraph: 描述当前项目、架构偏好或团队约束，不是产品公告。
    - textbox "自定义项目描述":
      - /placeholder: 例如：公共卫生研究，数据来自年度死因登记；或 Spring Boot 3 项目，使用 PostgreSQL。
    - text: 0 / 4000 本地资料与项目文件
    - button "Choose File"
    - button "Choose File"
    - button "添加上下文" [disabled]:
      - img
      - text: 添加上下文
    - region "拖入文件或文件夹添加上下文":
      - img
      - strong: 正在建立本地项目索引
      - progressbar "项目文件处理进度": 91.3%
      - text: 已处理 222 / 243 · 全文索引 222 · 跳过 0 · 不完整 0 · 失败 0 · 45 文件/秒 · 预计剩余 1 秒 当前文件：file-113.txt
    - checkbox "按根目录 .gitignore 排除文件" [checked] [disabled]
    - text: 按根目录 .gitignore 排除文件
    - paragraph: 目录内的文件按项目规则筛选；如需包含被忽略的资料，可取消勾选。依赖、构建产物和敏感文件仍会跳过。
    - paragraph: 可建立项目索引，也可单独上传报告、论文、表格、演示文稿和图片。
    - paragraph: 可添加代码、Word、Excel、PPT、PDF 等文件或整个文件夹；支持的浏览器会在本地索引文件夹中的代码，办公文档上传解析前会先请你确认。
    - paragraph: 确认上传后，文档按 1 MiB 分片发送至本项目后端，解析后只保留最长 2 小时的临时全文索引； 一键增强时仅选取与当前任务相关的片段。
    - button "暂停索引"
    - button "取消并删除索引"
    - note "暂不支持的文件格式": 暂不支持 .zip .rar .7z .tar .gz .exe .dll .so .dylib .jar .class .bin .mp3 .wav .mp4 .avi .mkv .mov .flv .ttf .otf .woff .woff2 .ico .psd
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
  - text: 测试模型
  - img
  - text: Plan 确认
  - switch "Plan 确认"
  - text: 结构化提示词
  - switch "结构化提示词"
  - button "直接增强提示词" [disabled]
  - text: 03 / Optimized prompt
  - heading "增强结果" [level=2]
  - heading "结果会在这里展开" [level=3]
  - paragraph: 输入一个简短需求并点击“直接增强提示词”， 系统会结合背景与资料生成可直接使用的任务说明。
- button "切换为深色主题"
```

# Test source

```ts
  1  | import { expect, test } from '@playwright/test';
  2  | import { mockAuthentication } from './authFixture';
  3  | import { openWorkbenchPane } from './workbenchPanes';
  4  | 
  5  | test('真实 Worker 显示百分比和互斥计数，项目忽略规则可以关闭', async ({ page }, testInfo) => {
  6  |   await mockAuthentication(page);
  7  |   // 索引界面测试不访问真实账号偏好；新增设置加载不能触发真实服务的 401 跳转。
  8  |   await page.route('**/api/v1/me/settings', route => route.fulfill({
  9  |     status: 503, json: { error: { message: '受控偏好加载不可用' } },
  10 |   }));
  11 |   await page.route('**/api/v1/analytics/events', route => route.fulfill({ status: 204 }));
  12 |   await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
  13 |     { id: 'mock:default', displayName: '测试模型', provider: 'Mock', defaultModel: true },
  14 |   ] } }));
  15 |   await page.goto('/workbench');
  16 |   await openWorkbenchPane(page, 'context');
  17 |   await page.evaluate(async () => {
  18 |     const root = await (await navigator.storage.getDirectory()).getDirectoryHandle('percent-project', { create: true });
  19 |     const write = async (directory: FileSystemDirectoryHandle, name: string, content: string) => {
  20 |       const writer = await (await directory.getFileHandle(name, { create: true })).createWritable();
  21 |       await writer.write(content); await writer.close();
  22 |     };
  23 |     await write(root, '.gitignore', '/tmp/');
  24 |     const excluded = await root.getDirectoryHandle('tmp', { create: true });
  25 |     await write(excluded, 'intentional.txt', '用户主动纳入的资料');
  26 |     await write(root, 'empty.txt', '');
  27 |     await write(root, '.env', 'protected');
  28 |     const text = Array.from({ length: 900 }, (_, i) => `term${i}`).join(' ') + '\n尾部完整规则。';
  29 |     for (let offset = 0; offset < 240; offset += 6) {
  30 |       await Promise.all(Array.from({ length: 6 }, (_, i) => write(root, `file-${offset + i}.txt`, text)));
  31 |     }
  32 |     Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
  33 |     const samples: number[] = [];
  34 |     Object.assign(window, { indexProgressSamples: samples });
  35 |     new MutationObserver(() => {
  36 |       const node = document.querySelector('.read-progress[role="progressbar"]');
  37 |       const value = node?.getAttribute('aria-valuenow');
  38 |       if (value && node?.textContent?.includes('%')) samples.push(Number(value));
  39 |     }).observe(document.body, { subtree: true, childList: true, attributes: true, characterData: true });
  40 |   });
  41 |   await page.getByRole('button', { name: '添加上下文' }).click();
  42 |   await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  43 |   await expect(page.locator('.read-progress .el-progress__text')).toContainText('%');
  44 |   await page.locator('.folder-dropzone').screenshot({ path: testInfo.outputPath('index-progress.png') });
> 45 |   await expect(page.getByText('241 个文本文件已完整建立本地索引')).toBeVisible();
     |                                                      ^ Error: expect(locator).toBeVisible() failed
  46 |   await expect(page.getByText(/范围内处理 243 个文件：完整 241 · 跳过 1 · 未完整索引 1 · 失败 0/)).toBeVisible();
  47 |   await expect(page.getByText(/范围规则另排除 1 个目录/)).toBeVisible();
  48 |   const samples = await page.evaluate(() => (window as unknown as { indexProgressSamples: number[] }).indexProgressSamples);
  49 |   expect(samples.some(value => value > 0 && value < 100)).toBe(true);
  50 |   const box = await page.locator('.read-progress').count();
  51 |   expect(box).toBe(0);
  52 |   await page.getByText('按根目录 .gitignore 排除文件', { exact: true }).click();
  53 |   await expect(page.getByRole('checkbox', { name: '按根目录 .gitignore 排除文件' })).not.toBeChecked();
  54 |   await page.getByRole('button', { name: '增量更新' }).click();
  55 |   await expect(page.getByText('242 个文本文件已完整建立本地索引')).toBeVisible();
  56 |   await expect(page.getByText(/范围规则另排除 0 个目录/)).toBeVisible();
  57 |   await expect(page.getByText(/本轮新增 1 · 更新 0 · 未变化 242 · 删除 0/)).toBeVisible();
  58 | });
  59 | 
```