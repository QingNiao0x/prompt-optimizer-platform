import { expect, test } from '@playwright/test';

import type {
  ApiResponse,
  ContextSnapshot,
  OptimizationResult,
} from '../src/types/api';

const contextSnapshot: ContextSnapshot = {
  customDescription: 'Spring Boot 3 模块化单体，使用 PostgreSQL。',
  technologyStack: [
    { name: 'Java 21', source: 'pom.xml', confidence: 1 },
    { name: 'Spring Boot 3', source: 'pom.xml', confidence: 1 },
  ],
  dependencies: [
    {
      ecosystem: 'maven',
      name: 'spring-boot-starter-web',
      version: '3.3.13',
      source: 'pom.xml',
    },
  ],
  directoryTree: ['src/', 'src/main/', 'src/main/java/'],
  fileSnippets: [
    {
      path: 'pom.xml',
      language: 'xml',
      content: '<artifactId>spring-boot-starter-web</artifactId>',
      truncated: false,
    },
  ],
  warnings: [],
  redactions: [],
  analysisVersion: '1.0',
};

const optimizationResult: OptimizationResult = {
  optimizedPrompt: [
    '任务目标：为 Spring Boot 用户模块增加登录接口。',
    '输入输出：接收用户名和密码，返回登录结果。',
    '约束条件：校验输入，不记录明文密码。',
  ].join('\n'),
  sections: [
    {
      type: 'TASK',
      title: '任务目标',
      content: '为现有 Spring Boot 用户模块增加登录接口。',
    },
    {
      type: 'OUTPUT',
      title: '输入输出',
      content: '接收用户名和密码，成功后返回登录结果。',
    },
    {
      type: 'CONSTRAINTS',
      title: '约束条件',
      content: '校验空值和错误凭证，不记录明文密码。',
    },
  ],
  contextReport: contextSnapshot,
  ambiguities: ['需要确认登录令牌的有效期。'],
  appliedConstraints: ['不记录明文密码'],
  templateCode: 'FEATURE_DEVELOPMENT',
  provider: {
    provider: 'deepseek',
    model: 'deepseek-chat',
    mock: true,
  },
  latencyMs: 128,
};

const contextResponse: ApiResponse<ContextSnapshot> = {
  requestId: 'e2e-context-request',
  data: contextSnapshot,
};

const optimizationResponse: ApiResponse<OptimizationResult> = {
  requestId: 'e2e-optimization-request',
  data: optimizationResult,
};

test('用户可以分析项目上下文并生成结构化提示词', async ({ page }) => {
  // 端到端测试只验证浏览器交互和前端请求格式。固定接口响应可以避免消耗模型额度，
  // 也不会因为本地后端、网络或 API Key 状态不同而产生偶发失败。
  await page.route('**/api/v1/context/analyze', async (route) => {
    const requestBody: unknown = route.request().postDataJSON();
    expect(requestBody).toMatchObject({
      customDescription: 'Spring Boot 3 模块化单体，使用 PostgreSQL。',
      files: [
        {
          path: 'pom.xml',
          language: 'java',
        },
      ],
    });
    await route.fulfill({ status: 200, json: contextResponse });
  });

  await page.route('**/api/v1/optimizations', async (route) => {
    const requestBody: unknown = route.request().postDataJSON();
    expect(requestBody).toMatchObject({
      rawPrompt: '给用户模块增加登录功能',
      enhancement: {
        includePermissionBoundaries: true,
      },
    });
    await route.fulfill({ status: 200, json: optimizationResponse });
  });

  await page.goto('/');
  await page.waitForLoadState('networkidle');
  await expect(page.getByRole('heading', { name: '把想法写下来，工程细节交给上下文。' })).toBeVisible();

  await page.getByLabel('自定义项目描述').fill('Spring Boot 3 模块化单体，使用 PostgreSQL。');
  await page.getByText('粘贴当前打开文件').click();
  await page.getByLabel('文件相对路径').fill('pom.xml');
  const languageSelect = page.getByRole('combobox', { name: '代码语言' });
  await languageSelect.focus();
  await languageSelect.press('ArrowDown');
  await page.getByRole('option', { name: 'Java', exact: true }).click();
  await page.getByPlaceholder('粘贴与当前任务相关的代码片段…').fill([
    '<properties><java.version>21</java.version></properties>',
    '<dependency><artifactId>spring-boot-starter-web</artifactId></dependency>',
  ].join('\n'));
  await page.getByRole('button', { name: '加入上下文' }).click();
  await expect(page.getByText('pom.xml', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: '分析项目上下文' }).click();
  await expect(page.getByRole('dialog', { name: '确认发送项目代码' })).toBeVisible();
  await page.getByRole('button', { name: '确认发送' }).click();
  await expect(page.getByText('Spring Boot 3', { exact: true })).toBeVisible();
  await expect(page.getByText('1 个依赖 · 3 个目录节点')).toBeVisible();

  await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  await page.getByRole('button', { name: '一键增强提示词' }).click();
  await expect(page.getByRole('dialog', { name: '确认发送项目代码' })).toBeVisible();
  await page.getByRole('button', { name: '确认发送' }).click();

  await expect(page.getByText('deepseek', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '任务目标' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '输入输出' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '约束条件' })).toBeVisible();
  await expect(page.getByText('系统识别到 1 个待确认点')).toBeVisible();
});

test('用户可以通过 File System Access API 建立本地项目索引', async ({ page }) => {
  await page.route('**/api/v1/optimizations', async (route) => {
    const requestBody = route.request().postDataJSON() as {
      context?: { files?: Array<{ path: string }> };
    };
    expect(requestBody.context?.files?.some((file) => file.path.includes('src/main.ts'))).toBe(true);
    await route.fulfill({ status: 200, json: optimizationResponse });
  });

  await page.addInitScript(async () => {
    const storageManager = navigator.storage as StorageManager & {
      getDirectory(): Promise<FileSystemDirectoryHandle>;
    };
    const storageRoot = await storageManager.getDirectory();
    const projectRoot = await storageRoot.getDirectoryHandle('project-index-e2e', { create: true });
    const sourceDirectory = await projectRoot.getDirectoryHandle('src', { create: true });
    const sourceHandle = await sourceDirectory.getFileHandle('main.ts', { create: true });
    const sourceWriter = await sourceHandle.createWritable();
    await sourceWriter.write('export const projectName = "prompt-optimizer";');
    await sourceWriter.close();

    const packageHandle = await projectRoot.getFileHandle('package.json', { create: true });
    const packageWriter = await packageHandle.createWritable();
    await packageWriter.write('{"dependencies":{"vue":"3.5.0"}}');
    await packageWriter.close();

    const dependencyDirectory = await projectRoot.getDirectoryHandle('node_modules', { create: true });
    const dependencyHandle = await dependencyDirectory.getFileHandle('ignored.js', { create: true });
    const dependencyWriter = await dependencyHandle.createWritable();
    await dependencyWriter.write('window.thirdParty = true;');
    await dependencyWriter.close();

    Object.defineProperty(window, 'showDirectoryPicker', {
      configurable: true,
      value: async () => projectRoot,
    });
  });

  await page.goto('/');
  await page.getByRole('button', { name: '选择本地项目文件夹' }).click();

  await expect(page.getByText('2 个源码文件已建立本地索引')).toBeVisible();
  await expect(page.getByText(/2 个代码块/)).toBeVisible();

  await page.getByLabel('原始提示词').fill('修改 projectName 常量');
  await page.getByRole('button', { name: '一键增强提示词' }).click();
  await page.getByRole('button', { name: '确认发送' }).click();
  await page.getByText(/查看本次代码选择依据/).click();
  await expect(page.getByText('src/main.ts', { exact: true })).toBeVisible();
  await expect(page.getByText(/任务中的符号/).first()).toBeVisible();

  await page.evaluate(async () => {
    const storageManager = navigator.storage as StorageManager & {
      getDirectory(): Promise<FileSystemDirectoryHandle>;
    };
    const storageRoot = await storageManager.getDirectory();
    const projectRoot = await storageRoot.getDirectoryHandle('project-index-e2e');
    const sourceDirectory = await projectRoot.getDirectoryHandle('src');
    const sourceHandle = await sourceDirectory.getFileHandle('main.ts');
    const sourceWriter = await sourceHandle.createWritable();
    await sourceWriter.write('export const projectName = "prompt-optimizer-updated";');
    await sourceWriter.close();

    const readmeHandle = await projectRoot.getFileHandle('README.md', { create: true });
    const readmeWriter = await readmeHandle.createWritable();
    await readmeWriter.write('# Prompt Optimizer');
    await readmeWriter.close();
    await projectRoot.removeEntry('package.json');
  });

  await page.getByRole('button', { name: '增量更新' }).click();
  await expect(page.getByText(/本轮新增 1 · 更新 1 · 未变化 0 · 删除 1/)).toBeVisible();
});

test('用户可以暂停并继续本地项目索引', async ({ page }) => {
  await page.addInitScript(async () => {
    const storageManager = navigator.storage as StorageManager & {
      getDirectory(): Promise<FileSystemDirectoryHandle>;
    };
    const storageRoot = await storageManager.getDirectory();
    const projectRoot = await storageRoot.getDirectoryHandle('project-index-pause-e2e', {
      create: true,
    });
    const sourceHandle = await projectRoot.getFileHandle('main.ts', { create: true });
    const sourceWriter = await sourceHandle.createWritable();
    await sourceWriter.write('export const pauseAndResume = true;');
    await sourceWriter.close();

    Object.defineProperty(window, 'showDirectoryPicker', {
      configurable: true,
      value: async () => projectRoot,
    });
  });

  await page.goto('/');
  await page.evaluate(() => {
    const observer = new MutationObserver(() => {
      const pauseButton = document.querySelector<HTMLButtonElement>('[data-testid="pause-index"]');
      if (pauseButton) {
        pauseButton.click();
        observer.disconnect();
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  });
  await page.getByRole('button', { name: '选择本地项目文件夹' }).click();

  await expect(page.getByText(/已暂停，检查点位于/)).toBeVisible();
  await page.getByRole('button', { name: '继续索引' }).click();
  await expect(page.getByText('1 个源码文件已建立本地索引')).toBeVisible();
});
