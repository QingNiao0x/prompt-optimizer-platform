import { expect, test, type Page } from '@playwright/test';

import type {
  ApiResponse,
  ContextSnapshot,
  OptimizationPlan,
  OptimizationResult,
  PlanningContextPreparation,
} from '../src/types/api';
import { openWorkbenchPane } from './workbenchPanes';
import { mockAuthentication } from './authFixture';

test.beforeEach(async ({ page }) => { await mockAuthentication(page); });

const PLAN_MODE_STORAGE_KEY = 'prompt-optimizer.plan-mode.v1';
const PLANNED_ENHANCE_BUTTON = '先确认并增强';
const DIRECT_ENHANCE_BUTTON = '直接增强提示词';

interface PlanModePreference {
  enabled: boolean;
  introSeen: boolean;
}

const configurePlanMode = async (
  page: Page,
  preference: PlanModePreference,
): Promise<void> => {
  await page.addInitScript((value) => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify(value));
  }, preference);
};

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
      summary: 'Maven 项目配置，使用 Spring Boot Web。',
      truncated: false,
    },
  ],
  warnings: [],
  redactions: [],
  analysisVersion: '1.0',
};

const documentContextSnapshot: ContextSnapshot = {
  customDescription: '',
  technologyStack: [],
  dependencies: [],
  directoryTree: ['docs/', 'docs/季度报告.txt'],
  fileSnippets: [{
    path: 'docs/季度报告.txt',
    language: 'text',
    content: '本季度完成核心功能交付，下一季度将重点改善用户体验。',
    summary: '文档概述本季度交付情况和下一季度的用户体验改进计划。',
    truncated: false,
  }],
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
      type: 'BACKGROUND',
      title: '背景',
      content: 'Spring Boot 用户服务。',
    },
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
  ambiguities: [],
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

const planningContextReference = {
  contextId: 'ea9d3453-5bd7-487b-bafb-5ef608dfd895',
  version: `sha256:${'a'.repeat(64)}`,
};

const planningContextResponse: ApiResponse<PlanningContextPreparation> = {
  requestId: 'e2e-planning-context-request',
  data: {
    ...planningContextReference,
    digest: {
      description: contextSnapshot.customDescription,
      technologies: ['Java 21', 'Spring Boot 3'],
      dependencies: ['maven:spring-boot-starter-web@3.3.13'],
      directoryOverview: contextSnapshot.directoryTree,
      fileSummaries: ['pom.xml：Maven 项目配置，使用 Spring Boot Web。'],
      analysisStatus: 'COMPLETE',
      analyzedFileCount: 1,
      warnings: [],
    },
    contextReport: contextSnapshot,
    expiresAt: '2026-09-14T08:30:00Z',
    latencyMs: 12,
  },
};

const confirmContextTransmission = async (page: Page, operationName: string): Promise<void> => {
  const dialog = page.getByRole('dialog', { name: '确认发送上下文' }).filter({
    hasText: operationName,
  });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: '确认发送' }).click();
};

const directPlanResponse: ApiResponse<OptimizationPlan> = {
  requestId: 'e2e-plan-direct',
  data: {
    summary: '需求已经足够明确，可以直接生成最终提示词。',
    questions: [],
    templateCode: 'FEATURE_DEVELOPMENT',
    provider: { provider: 'mock', model: 'deterministic-planner-v2', mock: true },
    latencyMs: 8,
  },
};

const loginPlanResponse: ApiResponse<OptimizationPlan> = {
  requestId: 'e2e-plan-login',
  data: {
    summary: '还需要确认登录方式和完成标准，之后会直接生成最终提示词。',
    questions: [
      {
        id: 'software-login-mode',
        question: '登录成功后采用哪种身份保持方式？',
        hint: '如果项目已有认证方式，优先沿用现有实现。',
        type: 'SINGLE_CHOICE',
        options: [
          {
            id: 'existing',
            label: '沿用项目现有方式',
            description: '先检查现有认证代码',
            answer: '先检查并沿用项目现有的认证与会话机制。',
            recommended: true,
          },
          {
            id: 'jwt',
            label: 'JWT',
            description: '使用访问令牌和刷新令牌',
            answer: '采用 JWT，包含访问令牌、刷新令牌和退出处理。',
            recommended: false,
          },
        ],
        examples: [],
        allowCustomAnswer: true,
      },
      {
        id: 'software-done',
        question: '达到什么结果时，你会认为这项任务已经完成？',
        hint: '填写最关键的可验证结果即可。',
        type: 'FREE_TEXT',
        options: [],
        examples: ['接口正常返回并覆盖异常场景'],
        allowCustomAnswer: true,
      },
    ],
    templateCode: 'FEATURE_DEVELOPMENT',
    provider: { provider: 'mock', model: 'deterministic-planner-v2', mock: true },
    latencyMs: 9,
  },
};

test('用户可以分析项目上下文并生成结构化提示词', async ({ page }) => {
  await configurePlanMode(page, { enabled: true, introSeen: true });
  // 端到端测试只验证浏览器交互和前端请求格式。固定接口响应可以避免消耗模型额度，
  // 也不会因为本地后端、网络或 API Key 状态不同而产生偶发失败。
  const optimizationRequestOrder: string[] = [];
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

  await page.route('**/api/v1/optimizations/plan', async (route) => {
    optimizationRequestOrder.push('plan');
    const requestBody: unknown = route.request().postDataJSON();
    expect(requestBody).toMatchObject({
      rawPrompt: '给用户模块增加登录功能',
      contextDescription: 'Spring Boot 3 模块化单体，使用 PostgreSQL。',
      planningContext: planningContextReference,
    });
    expect(requestBody).not.toHaveProperty('files');
    await route.fulfill({
      status: 200,
      json: {
        ...loginPlanResponse,
        data: {
          ...loginPlanResponse.data,
          planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
          planningContext: planningContextReference,
          expiresAt: '2026-09-14T08:30:00Z',
        },
      },
    });
  });

  await page.route('**/api/v1/context/planning', async (route) => {
    optimizationRequestOrder.push('context');
    const requestBody: unknown = route.request().postDataJSON();
    expect(requestBody).toMatchObject({
      rawPrompt: '给用户模块增加登录功能',
      context: {
        customDescription: 'Spring Boot 3 模块化单体，使用 PostgreSQL。',
        files: [expect.objectContaining({ path: 'pom.xml' })],
      },
    });
    await route.fulfill({ status: 200, json: planningContextResponse });
  });

  await page.route('**/api/v1/optimizations', async (route) => {
    optimizationRequestOrder.push('final');
    const requestBody: unknown = route.request().postDataJSON();
    expect(requestBody).toMatchObject({
      rawPrompt: '给用户模块增加登录功能',
      enhancement: {
        includePermissionBoundaries: true,
      },
      planConfirmation: {
        planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
        planningContext: planningContextReference,
        answers: [
          expect.objectContaining({ questionId: 'software-login-mode' }),
          expect.objectContaining({ questionId: 'software-done' }),
        ],
      },
    });
    await route.fulfill({ status: 200, json: optimizationResponse });
  });

  await page.goto('/workbench');
  await page.waitForLoadState('networkidle');
  await openWorkbenchPane(page, 'intent');
  await expect(page.getByText('把想法写下来。', { exact: true })).toBeVisible();
  await expect(page.getByText('工程细节，交给上下文。', { exact: true })).toBeVisible();
  await expect(page.getByLabel('原始提示词')).toHaveAttribute(
    'placeholder',
    '请帮我查询全球使用AI最多的职业/行业',
  );

  await openWorkbenchPane(page, 'context');
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

  await page.getByRole('button', { name: '分析上下文资料' }).click();
  await confirmContextTransmission(page, '分析上下文资料');
  await expect(page.getByText('代码项目', { exact: true })).toBeVisible();
  await expect(page.getByText('项目概要', { exact: true })).toBeVisible();
  await expect(page.getByText('功能模块', { exact: true })).toBeVisible();
  await expect(page.getByText('依赖信息', { exact: true })).toBeVisible();
  await expect(page.getByText('目录结构', { exact: true })).toBeVisible();
  await expect(page.getByText('Spring Boot 3', { exact: true })).toBeVisible();
  await expect(page.getByText('Maven 项目配置，使用 Spring Boot Web。')).toBeVisible();
  await expect(page.getByText('1 个依赖 · 3 个目录节点')).toBeVisible();

  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  await confirmContextTransmission(page, '生成确认问题前分析上下文');
  const planDialog = page.getByRole('dialog', { name: '确认关键细节' });
  await expect(planDialog.getByRole('heading', { name: '把关键细节确认清楚' })).toBeVisible();
  await expect(planDialog.getByText('登录成功后采用哪种身份保持方式？')).toBeVisible();
  await expect(planDialog.getByText('选择任务模板')).toHaveCount(0);
  await expect(planDialog.getByText('确认缺失维度')).toHaveCount(0);
  await planDialog.getByRole('button', { name: /JWT/ }).click();
  await planDialog.getByRole('button', { name: '下一题' }).click();
  await planDialog.getByLabel('填写回答').fill('登录成功、失败和令牌刷新场景都有自动化测试。');
  await planDialog.getByRole('button', { name: '生成最终提示词' }).click();
  await confirmContextTransmission(page, '生成最终提示词');

  expect(optimizationRequestOrder).toEqual(['context', 'plan', 'final']);

  await openWorkbenchPane(page, 'result');
  await expect(page.getByText('deepseek', { exact: true })).toBeVisible();
  const resultContent = page.getByLabel('增强结果内容，可滚动查看完整提示词');
  await expect(resultContent.getByRole('heading', { name: '任务目标' })).toBeVisible();
  await expect(resultContent.getByRole('heading', { name: '输入输出' })).toBeVisible();
  await expect(resultContent.getByRole('heading', { name: '约束条件' })).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: '待确认事项' })).toHaveCount(0);
});

test('科研需求会逐项询问业务细节并在全部回答后生成结果', async ({ page }) => {
  await configurePlanMode(page, { enabled: true, introSeen: true });
  const researchPrompt = '分析2015-2025年某地区心脑血管疾病死亡率，比较不同人群并进行YLL和Arriaga分解';
  const researchPlan: ApiResponse<OptimizationPlan> = {
    requestId: 'research-plan',
    data: {
      summary: '我已理解你的研究目标。还需要确认研究范围、数据口径和交付方式。',
      questions: [
        {
          id: 'research-region',
          question: '这项研究具体覆盖哪个地区？',
          hint: '请填写明确的省、市、国家或区域名称。',
          type: 'FREE_TEXT',
          options: [],
          examples: ['广东省', '北京市'],
          allowCustomAnswer: true,
        },
        {
          id: 'research-tool',
          question: '你希望使用哪种分析工具？',
          hint: '系统会据此调整代码和图表实现。',
          type: 'SINGLE_CHOICE',
          options: [
            { id: 'r', label: 'R', description: '适合流行病学统计', answer: '使用 R 完成全部分析。', recommended: true },
            { id: 'python', label: 'Python', description: '适合自动化分析', answer: '使用 Python 完成全部分析。', recommended: false },
          ],
          examples: [],
          allowCustomAnswer: true,
        },
      ],
      templateCode: 'RESEARCH_ANALYSIS',
      provider: { provider: 'mock', model: 'deterministic-planner-v2', mock: true },
      latencyMs: 10,
    },
  };
  const researchResult: OptimizationResult = {
    ...optimizationResult,
    optimizedPrompt: '## 背景\n广东省心脑血管疾病死亡率研究\n\n## 任务\n完成趋势、YLL和Arriaga分解',
    sections: [
      { type: 'BACKGROUND', title: '背景', content: '广东省心脑血管疾病死亡率研究' },
      { type: 'TASK', title: '任务', content: '完成趋势、YLL和Arriaga分解' },
      { type: 'OUTPUT', title: '输出', content: '输出表格、图表和 R 代码' },
      { type: 'CONSTRAINTS', title: '约束', content: '不得编造数据' },
    ],
    templateCode: 'RESEARCH_ANALYSIS',
  };

  await page.route('**/api/v1/optimizations/plan', async (route) => {
    await route.fulfill({ status: 200, json: researchPlan });
  });
  await page.route('**/api/v1/optimizations', async (route) => {
    const requestBody = route.request().postDataJSON() as {
      planConfirmation?: { answers?: Array<{ questionId: string; answer: string }> };
    };
    expect(requestBody.planConfirmation?.answers).toEqual([
      expect.objectContaining({ questionId: 'research-region', answer: '广东省' }),
      expect.objectContaining({ questionId: 'research-tool', answer: '使用 R 完成全部分析。' }),
    ]);
    await route.fulfill({ status: 200, json: { requestId: 'research-final', data: researchResult } });
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill(researchPrompt);
  await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  const dialog = page.getByRole('dialog', { name: '确认关键细节' });
  await dialog.getByLabel('填写回答').fill('广东省');
  await dialog.getByRole('button', { name: '下一题' }).click();
  await dialog.getByRole('button', { name: /^R/ }).click();
  await dialog.getByRole('button', { name: '生成最终提示词' }).click();

  await expect(page.getByText('最终提示词已生成。')).toBeVisible();
  await openWorkbenchPane(page, 'result');
  await expect(page.getByText('广东省心脑血管疾病死亡率研究')).toBeVisible();
  await expect(page.getByText('RESEARCH_ANALYSIS', { exact: true })).toBeVisible();
});

test('最终生成失败时在计划弹窗内显示可读错误并保留回答', async ({ page }) => {
  await configurePlanMode(page, { enabled: true, introSeen: true });
  const oneQuestionPlan: ApiResponse<OptimizationPlan> = {
    requestId: 'error-plan',
    data: {
      ...loginPlanResponse.data,
      questions: [loginPlanResponse.data.questions[1]!],
    },
  };
  await page.route('**/api/v1/optimizations/plan', async (route) => {
    await route.fulfill({ status: 200, json: oneQuestionPlan });
  });
  await page.route('**/api/v1/optimizations', async (route) => {
    await route.fulfill({
      status: 503,
      json: {
        requestId: 'failed-final-request',
        error: {
          code: 'PROVIDER_RATE_LIMITED',
          message: '模型服务当前请求繁忙，请稍后重试。',
          retryable: true,
          details: {},
        },
      },
    });
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  const dialog = page.getByRole('dialog', { name: '确认关键细节' });
  const answer = dialog.getByLabel('填写回答');
  await answer.fill('正常和异常登录场景都有自动化测试。');
  await dialog.getByRole('button', { name: '生成最终提示词' }).click();

  await expect(dialog.getByRole('alert')).toContainText('模型服务当前请求繁忙，请稍后重试。');
  await expect(answer).toHaveValue('正常和异常登录场景都有自动化测试。');
});

test('计划会话过期后可以重新生成问题并完成增强', async ({ page }) => {
  await configurePlanMode(page, { enabled: true, introSeen: true });
  let planCalls = 0;
  let finalCalls = 0;
  await page.route('**/api/v1/optimizations/plan', async (route) => {
    planCalls += 1;
    await route.fulfill({
      status: 200,
      json: {
        requestId: `expiry-plan-${planCalls}`,
        data: {
          ...loginPlanResponse.data,
          questions: [loginPlanResponse.data.questions[1]!],
          planId: planCalls === 1
            ? 'd53d3b67-62b2-4505-89dd-4ca88f837391'
            : '3a0e2ec5-1898-4b10-85ac-a9be1268ea1a',
          planningContext: null,
          expiresAt: '2026-09-20T12:30:00Z',
        },
      } satisfies ApiResponse<OptimizationPlan>,
    });
  });
  await page.route('**/api/v1/optimizations', async (route) => {
    finalCalls += 1;
    const requestBody = route.request().postDataJSON() as {
      planConfirmation?: { planId?: string };
    };
    if (finalCalls === 1) {
      expect(requestBody.planConfirmation?.planId).toBe('d53d3b67-62b2-4505-89dd-4ca88f837391');
      await route.fulfill({
        status: 400,
        json: {
          requestId: 'expired-final-request',
          error: {
            code: 'INVALID_ARGUMENT',
            message: '确认问题已过期，请重新生成。',
            retryable: false,
            details: {},
          },
        },
      });
      return;
    }
    expect(requestBody.planConfirmation?.planId).toBe('3a0e2ec5-1898-4b10-85ac-a9be1268ea1a');
    await route.fulfill({ status: 200, json: optimizationResponse });
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();

  let dialog = page.getByRole('dialog', { name: '确认关键细节' });
  await dialog.getByLabel('填写回答').fill('覆盖正常和异常登录场景。');
  await dialog.getByRole('button', { name: '生成最终提示词' }).click();
  await expect(dialog.getByRole('alert')).toContainText('确认问题已过期，请重新生成。');
  await dialog.getByRole('button', { name: '返回修改需求' }).click();

  await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  dialog = page.getByRole('dialog', { name: '确认关键细节' });
  await dialog.getByLabel('填写回答').fill('覆盖正常和异常登录场景。');
  await dialog.getByRole('button', { name: '生成最终提示词' }).click();

  await expect(page.getByText('最终提示词已生成。')).toBeVisible();
  expect(planCalls).toBe(2);
  expect(finalCalls).toBe(2);
});

test('普通文档分析只展示内容概要，不套用代码项目信息', async ({ page }) => {
  await page.route('**/api/v1/context/analyze', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        requestId: 'e2e-document-context-request',
        data: documentContextSnapshot,
      } satisfies ApiResponse<ContextSnapshot>,
    });
  });

  await page.goto('/workbench');
  await page.waitForLoadState('networkidle');
  await openWorkbenchPane(page, 'context');
  await page.locator('input[type="file"]').nth(1).setInputFiles({
    name: '季度报告.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('本季度完成核心功能交付，下一季度将重点改善用户体验。'),
  });
  await expect(page.getByText('个文件已加入上下文', { exact: false })).toBeVisible();

  await page.getByRole('button', { name: '分析上下文资料' }).click();
  await confirmContextTransmission(page, '分析上下文资料');

  await expect(page.getByText('普通文档', { exact: true })).toBeVisible();
  await expect(page.getByText('文件内容概要', { exact: true })).toBeVisible();
  await expect(page.getByText(documentContextSnapshot.fileSnippets[0]?.summary ?? '')).toBeVisible();
  await expect(page.getByText('项目概要', { exact: true })).toHaveCount(0);
  await expect(page.getByText('功能模块', { exact: true })).toHaveCount(0);
  await expect(page.getByText('技术栈', { exact: true })).toHaveCount(0);
});

test('未单独分析上下文时，一键增强仍返回并展示项目分析结果', async ({ page }) => {
  await configurePlanMode(page, { enabled: true, introSeen: true });
  let contextAnalyzeCalls = 0;
  let planningContextCalls = 0;
  await page.route('**/api/v1/context/analyze', async (route) => {
    contextAnalyzeCalls += 1;
    await route.fulfill({ status: 200, json: contextResponse });
  });
  await page.route('**/api/v1/optimizations/plan', async (route) => {
    const requestBody: unknown = route.request().postDataJSON();
    expect(requestBody).toMatchObject({ planningContext: planningContextReference });
    await route.fulfill({
      status: 200,
      json: {
        ...directPlanResponse,
        data: {
          ...directPlanResponse.data,
          planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
          planningContext: planningContextReference,
          expiresAt: '2026-09-14T08:30:00Z',
        },
      },
    });
  });
  await page.route('**/api/v1/context/planning', async (route) => {
    planningContextCalls += 1;
    await route.fulfill({ status: 200, json: planningContextResponse });
  });
  await page.route('**/api/v1/optimizations', async (route) => {
    const requestBody: unknown = route.request().postDataJSON();
    expect(requestBody).toMatchObject({
      rawPrompt: '为示例项目补充健康检查接口',
      context: {
        files: [expect.objectContaining({ path: 'pom.xml' })],
      },
    });
    await route.fulfill({ status: 200, json: optimizationResponse });
  });

  await page.goto('/workbench');
  await page.waitForLoadState('networkidle');
  await openWorkbenchPane(page, 'context');
  await page.getByText('粘贴当前打开文件').click();
  await page.getByLabel('文件相对路径').fill('pom.xml');
  await page.getByPlaceholder('粘贴与当前任务相关的代码片段…').fill(
    '<dependency><artifactId>spring-boot-starter-web</artifactId></dependency>',
  );
  await page.getByRole('button', { name: '加入上下文' }).click();
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('为示例项目补充健康检查接口');
  await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  await confirmContextTransmission(page, '生成确认问题前分析上下文');
  await confirmContextTransmission(page, '生成最终提示词');
  expect(contextAnalyzeCalls).toBe(0);
  expect(planningContextCalls).toBe(1);
  await openWorkbenchPane(page, 'context');
  await expect(page.getByText('代码项目', { exact: true })).toBeVisible();
  await expect(page.getByText('项目概要', { exact: true })).toBeVisible();
  await expect(page.getByText('功能模块', { exact: true })).toBeVisible();
});

test('用户可以通过 File System Access API 建立本地项目索引', async ({ page }) => {
  await configurePlanMode(page, { enabled: true, introSeen: true });
  await page.route('**/api/v1/optimizations/plan', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        ...directPlanResponse,
        data: {
          ...directPlanResponse.data,
          planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
          planningContext: planningContextReference,
          expiresAt: '2026-09-14T08:30:00Z',
        },
      },
    });
  });
  await page.route('**/api/v1/context/planning', async (route) => {
    const requestBody = route.request().postDataJSON() as {
      context?: { files?: Array<{ path: string }> };
    };
    expect(requestBody.context?.files?.some((file) => file.path.includes('src/main.ts'))).toBe(true);
    await route.fulfill({ status: 200, json: planningContextResponse });
  });
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

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.getByRole('button', { name: '选择本地项目文件夹' }).click();

  await expect(page.getByText('2 个源码文件已建立本地索引')).toBeVisible();
  await expect(page.getByText(/2 个代码块/)).toBeVisible();

  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('修改 projectName 常量');
  await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();
  await confirmContextTransmission(page, '生成确认问题前分析上下文');
  await confirmContextTransmission(page, '生成最终提示词');
  await openWorkbenchPane(page, 'context');
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

  await openWorkbenchPane(page, 'context');
  await page.getByRole('button', { name: '增量更新' }).click();
  await expect(page.getByText(/本轮新增 1 · 更新 1 · 未变化 0 · 删除 1/)).toBeVisible();

  await page.getByRole('button', { name: '清空', exact: true }).click();
  await expect(page.getByText(/个源码文件已建立本地索引/)).not.toBeVisible({ timeout: 1_000 });
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

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
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

test('重新打开页面后不恢复之前选择的项目文件夹', async ({ page }) => {
  await page.addInitScript(async () => {
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('prompt-optimizer-project-index', 2);
      request.onupgradeneeded = () => {
        const database = request.result;
        database.createObjectStore('projects', { keyPath: 'id' });
        const fileStore = database.createObjectStore('files', { keyPath: 'id' });
        fileStore.createIndex('projectId', 'projectId');
        fileStore.createIndex('projectPath', ['projectId', 'path'], { unique: true });
        const chunkStore = database.createObjectStore('chunks', { keyPath: 'id' });
        chunkStore.createIndex('projectId', 'projectId');
        chunkStore.createIndex('projectPath', ['projectId', 'path']);
        chunkStore.createIndex('projectPriority', ['projectId', 'priority']);
        chunkStore.createIndex('searchTerms', 'searchTerms', { multiEntry: true });
        database.createObjectStore('sources', { keyPath: 'projectId' });
      };
      request.onsuccess = () => {
        const database = request.result;
        const transaction = database.transaction(['projects', 'files'], 'readwrite');
        transaction.objectStore('projects').put({
          id: 'legacy-project',
          name: 'legacy-project',
          rootDirectory: 'legacy-project',
          status: 'READY',
          discoveredFiles: 1,
          eligibleFiles: 1,
          indexedFiles: 1,
          metadataOnlyFiles: 0,
          ignoredFiles: 0,
          ignoredDirectories: 0,
          failedFiles: 0,
          addedFiles: 1,
          updatedFiles: 0,
          unchangedFiles: 0,
          removedFiles: 0,
          changedPaths: ['src/main.ts'],
          chunkCount: 0,
          indexedCharacters: 0,
          estimatedIndexBytes: 512,
          scanLimitReached: false,
          storageLimitReached: false,
          retention: 'PERSISTENT',
          sessionId: '',
          expiresAt: '',
          scanId: 'legacy-scan',
          lastCheckpointPath: 'src/main.ts',
          startedAt: new Date().toISOString(),
          completedAt: new Date().toISOString(),
        });
        transaction.objectStore('files').put({
          id: 'legacy-project\u0000src/main.ts',
          projectId: 'legacy-project',
          path: 'src/main.ts',
        });
        transaction.oncomplete = () => {
          database.close();
          resolve();
        };
        transaction.onerror = () => reject(transaction.error);
      };
      request.onerror = () => reject(request.error);
    });
    localStorage.setItem('prompt-optimizer.current-project-index.v1', 'legacy-project');
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  // 全量并发运行时工作台是懒加载页面，以核心控件出现作为初始化完成标志。
  await expect(
    page.getByRole('button', { name: '添加文档、表格、演示稿或图片' }),
  ).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('1 个源码文件已建立本地索引')).not.toBeVisible();
  await expect.poll(() => page.evaluate(() =>
    localStorage.getItem('prompt-optimizer.current-project-index.v1'))).toBeNull();
});

test('重新打开页面后不保留之前上传的单个文件', async ({ page }) => {
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  const fileChooserPromise = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: '添加文档、表格、演示稿或图片' }).click();
  const fileChooser = await fileChooserPromise;
  await fileChooser.setFiles({
    name: '临时需求说明.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('只用于当前页面的需求说明。', 'utf8'),
  });

  await expect(page.getByText('临时需求说明.txt', { exact: true })).toBeVisible({ timeout: 15_000 });
  await page.reload();
  await openWorkbenchPane(page, 'context');

  await expect(page.getByText('临时需求说明.txt', { exact: true })).not.toBeVisible();
  await expect(page.getByRole('button', { name: '添加文档、表格、演示稿或图片' })).toBeVisible();
});

test('关闭 Plan 确认后直接生成，不进入方案确认', async ({ page }) => {
  let planCalls = 0;
  let planningContextCalls = 0;
  await page.route('**/api/v1/optimizations/plan', async (route) => {
    planCalls += 1;
    await route.abort();
  });
  await page.route('**/api/v1/context/planning', async (route) => {
    planningContextCalls += 1;
    await route.abort();
  });
  await page.route('**/api/v1/optimizations', async (route) => {
    const requestBody = route.request().postDataJSON() as {
      context?: { files?: Array<{ path: string }> };
      enhancement?: { templateCode?: string };
      planConfirmation?: unknown;
    };
    expect(requestBody.planConfirmation).toBeNull();
    expect(requestBody.enhancement?.templateCode).toBe('AUTO');
    expect(requestBody.context?.files).toEqual([
      expect.objectContaining({ path: 'requirements.md' }),
    ]);
    await route.fulfill({
      status: 200,
      json: {
        requestId: 'direct-optimization',
        data: {
          ...optimizationResult,
          ambiguities: ['登录方式尚未确认。'],
        },
      } satisfies ApiResponse<OptimizationResult>,
    });
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.getByText('粘贴当前打开文件').click();
  await page.getByLabel('文件相对路径').fill('requirements.md');
  await page.getByPlaceholder('粘贴与当前任务相关的代码片段…').fill('登录接口需要返回明确错误码。');
  await page.getByRole('button', { name: '加入上下文' }).click();
  await openWorkbenchPane(page, 'intent');
  await expect(page.getByText('将直接生成最终提示词。若关键事实不足，结果中会列出待确认事项。')).toBeVisible();
  await expect(page.getByText('Plan', { exact: true })).toHaveCount(0);
  await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  await page.getByRole('button', { name: DIRECT_ENHANCE_BUTTON }).click();
  await confirmContextTransmission(page, '直接增强提示词');

  await expect(page.getByRole('dialog', { name: '确认关键细节' })).toHaveCount(0);
  await expect(page.getByRole('dialog', { name: '先确认关键细节' })).toHaveCount(0);
  await expect(page.getByText('待确认事项', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '直接再次增强' })).toBeVisible();
  expect(planCalls).toBe(0);
  expect(planningContextCalls).toBe(0);
});

test('首次主动开启 Plan 后显示说明并记住选择', async ({ page }) => {
  let planCalls = 0;
  let finalCalls = 0;
  await page.route('**/api/v1/optimizations/plan', async (route) => {
    planCalls += 1;
    await route.fulfill({
      status: 200,
      json: {
        ...directPlanResponse,
        data: {
          ...directPlanResponse.data,
          planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
          planningContext: null,
          expiresAt: '2026-09-14T08:30:00Z',
        },
      } satisfies ApiResponse<OptimizationPlan>,
    });
  });
  await page.route('**/api/v1/optimizations', async (route) => {
    finalCalls += 1;
    const requestBody = route.request().postDataJSON() as {
      enhancement?: { templateCode?: string };
      planConfirmation?: { planId?: string };
    };
    expect(requestBody.enhancement?.templateCode).toBe('FEATURE_DEVELOPMENT');
    expect(requestBody.planConfirmation?.planId).toBe('d53d3b67-62b2-4505-89dd-4ca88f837391');
    await route.fulfill({ status: 200, json: optimizationResponse });
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  const planSwitch = page.getByLabel('Plan 确认');
  await expect(planSwitch).toHaveAttribute('aria-checked', 'false');
  await expect(page.getByRole('button', { name: DIRECT_ENHANCE_BUTTON })).toBeVisible();

  await page.locator('label.switch-control').filter({ hasText: 'Plan 确认' }).click();
  await expect(planSwitch).toHaveAttribute('aria-checked', 'true');
  await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();

  const introDialog = page.getByRole('dialog', { name: '先确认关键细节' });
  await expect(introDialog).toBeVisible();
  await introDialog.getByRole('button', { name: '立即体验' }).click();
  await expect(page.getByText('最终提示词已生成。')).toBeVisible();
  await expect(page.getByRole('button', { name: '先确认并再次增强' })).toBeVisible();
  expect(planCalls).toBe(1);
  expect(finalCalls).toBe(1);

  await page.getByRole('button', { name: '先确认并再次增强' }).click();
  await expect.poll(() => planCalls).toBe(2);
  await expect.poll(() => finalCalls).toBe(2);

  await page.reload();
  await openWorkbenchPane(page, 'intent');
  await expect(page.getByLabel('Plan 确认')).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON })).toBeVisible();
  await expect(page.getByRole('dialog', { name: '先确认关键细节' })).toHaveCount(0);
});

test('首次说明中拒绝 Plan 后立即直接增强并记住选择', async ({ page }) => {
  let planCalls = 0;
  await page.route('**/api/v1/optimizations/plan', async (route) => {
    planCalls += 1;
    await route.abort();
  });
  await page.route('**/api/v1/optimizations', async (route) => {
    const requestBody = route.request().postDataJSON() as {
      enhancement?: { templateCode?: string };
      planConfirmation?: unknown;
    };
    expect(requestBody.enhancement?.templateCode).toBe('AUTO');
    expect(requestBody.planConfirmation).toBeNull();
    await route.fulfill({ status: 200, json: optimizationResponse });
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.locator('label.switch-control').filter({ hasText: 'Plan 确认' }).click();
  await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  await page.getByRole('button', { name: PLANNED_ENHANCE_BUTTON }).click();

  const introDialog = page.getByRole('dialog', { name: '先确认关键细节' });
  await introDialog.getByRole('button', { name: '不启用，直接增强' }).click();
  await expect(page.getByText('最终提示词已生成。')).toBeVisible();
  await openWorkbenchPane(page, 'intent');
  await expect(page.getByRole('button', { name: DIRECT_ENHANCE_BUTTON })).toBeVisible();
  expect(planCalls).toBe(0);
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), PLAN_MODE_STORAGE_KEY))
    .toBe(JSON.stringify({ enabled: false, introSeen: true }));
});

test('未开启 Plan 时待确认事项折叠，增强结果保持在视口内', async ({ page }) => {
  await configurePlanMode(page, { enabled: false, introSeen: true });
  const ambiguities = Array.from({ length: 8 }, (_, index) =>
    `待确认事项 ${index + 1}：需要核对登录、权限、错误处理和验收口径，这段说明故意写长以便占满结果列。`,
  );
  await page.route('**/api/v1/models', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        requestId: 'models',
        data: [{
          id: 'deepseek-chat',
          displayName: 'DeepSeek',
          provider: 'deepseek',
          defaultModel: true,
        }],
      },
    });
  });
  await page.route('**/api/v1/optimizations', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        requestId: 'ambiguity-layout',
        data: {
          ...optimizationResult,
          ambiguities,
        },
      } satisfies ApiResponse<OptimizationResult>,
    });
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('给用户模块增加登录功能');
  await page.getByRole('button', { name: DIRECT_ENHANCE_BUTTON }).click();
  await expect(page.getByText('最终提示词已生成。')).toBeVisible();
  await openWorkbenchPane(page, 'result');

  const toggle = page.getByRole('button', { name: '待确认事项，8 项，需要人工核对' });
  const resultContent = page.getByLabel('增强结果内容，可滚动查看完整提示词');
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('heading', { name: '增强结果' })).toBeInViewport();
  await expect(page.getByRole('button', { name: '复制' })).toBeInViewport();
  await expect(page.getByRole('button', { name: '编辑' })).toBeInViewport();
  await expect(page.getByRole('button', { name: '直接再次增强' })).toBeInViewport();
  await expect(resultContent).toBeInViewport();
  await expect(page.locator('#ambiguity-details')).toBeHidden();

  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  const details = page.locator('#ambiguity-details');
  await expect(details).toBeVisible();
  await expect(details).toContainText(ambiguities[7]);

  const viewport = page.viewportSize();
  const detailsBox = await details.boundingBox();
  const expandedResult = await resultContent.boundingBox();
  const toggleBox = await toggle.boundingBox();
  expect(viewport).not.toBeNull();
  expect(detailsBox).not.toBeNull();
  expect(expandedResult).not.toBeNull();
  expect(toggleBox).not.toBeNull();
  if (!viewport || !detailsBox || !expandedResult || !toggleBox) {
    return;
  }
  expect(toggleBox.height).toBeGreaterThanOrEqual(44);
  expect(expandedResult.height).toBeGreaterThan(120);
  expect(expandedResult.y + expandedResult.height).toBeLessThanOrEqual(viewport.height + 1);
  expect(detailsBox.y + detailsBox.height).toBeLessThanOrEqual(expandedResult.y + 2);
  if (viewport.width <= 900) {
    expect(detailsBox.height).toBeLessThanOrEqual(viewport.height * 0.4 + 8);
  }

  await page.getByRole('button', { name: '编辑' }).click();
  await expect(toggle).toHaveCount(0);
  await expect(page.locator('.edit-section-list textarea').first()).toBeVisible();
  await page.getByRole('button', { name: '取消' }).click();
  await expect(page.getByRole('button', { name: '待确认事项，8 项，需要人工核对' }))
    .toHaveAttribute('aria-expanded', 'false');
});
