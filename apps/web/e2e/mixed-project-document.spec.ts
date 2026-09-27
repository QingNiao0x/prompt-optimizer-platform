import { readFile } from 'node:fs/promises';

import { expect, test } from '@playwright/test';

import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

const fixturePaths = [
  'README.md',
  'backend/pom.xml',
  'backend/src/main/java/com/example/demo/DemoApplication.java',
  'backend/src/main/java/com/example/demo/controller/HelloController.java',
  'backend/src/main/resources/application.yml',
  'backend/src/test/java/com/example/demo/DemoApplicationTests.java',
  'frontend/package.json',
  'frontend/src/main.ts',
  'frontend/src/views/App.vue',
] as const;

test.beforeEach(async ({ page }) => {
  await mockAuthentication(page);
  // 此用例模拟后端业务接口，平台模型与统计请求也必须隔离，避免真实服务的 401 中断索引。
  await page.route('**/api/v1/analytics/events', (route) => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/models', (route) => route.fulfill({ status: 200, json: {
    data: [{ id: 'mock:default', displayName: '测试模型', provider: 'Mock', defaultModel: true }],
  } }));
});

test('本地样例项目索引与单独方案文件共同参与 Plan 和最终增强', async ({ page }) => {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  const projectFiles = await Promise.all(fixturePaths.map(async (path) => ({
    path,
    content: await readFile(
      new URL(`../../../test-fixtures/sample-spring-vue-project/${path}`, import.meta.url),
      'utf8',
    ),
  })));
  const solution = '订单金额超过五万元时须先由财务复核，再交主管批准。';
  const reference = {
    contextId: 'a1111111-1111-4111-8111-111111111111',
    version: `sha256:${'a'.repeat(64)}`,
  };
  const requestOrder: string[] = [];
  const receivedFiles: string[][] = [];

  await page.addInitScript(async (entries) => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: true, introSeen: true }));
    const storage = await navigator.storage.getDirectory();
    const root = await storage.getDirectoryHandle('sample-spring-vue-project', { create: true });
    for (const entry of entries) {
      const parts = entry.path.split('/');
      let directory = root;
      for (const part of parts.slice(0, -1)) {
        directory = await directory.getDirectoryHandle(part, { create: true });
      }
      const file = await directory.getFileHandle(parts.at(-1)!, { create: true });
      const writer = await file.createWritable();
      await writer.write(entry.content);
      await writer.close();
    }
    Object.defineProperty(window, 'showDirectoryPicker', {
      configurable: true,
      value: async () => root,
    });
  }, projectFiles);

  await page.route('**/api/v1/optimizations/plan-events', async (route) => {
    await route.fulfill({ status: 204 });
  });
  await page.route('**/api/v1/context/planning', async (route) => {
    requestOrder.push('context');
    const body = route.request().postDataJSON() as {
      rawPrompt: string;
      context: { files: Array<{ path: string; content: string }> };
    };
    const paths = body.context.files.map((file) => file.path);
    receivedFiles.push(paths);
    expect(body.rawPrompt).toBe('按审批方案在样例项目开发订单接口');
    expect(paths).toContain('审批方案.txt');
    expect(paths.some((path) => path.startsWith('backend/pom.xml#chunk-'))).toBe(true);
    expect(paths.some((path) => path.startsWith('frontend/package.json#chunk-'))).toBe(true);
    expect(body.context.files.find((file) => file.path === '审批方案.txt')?.content)
      .toContain('财务复核');
    await route.fulfill({ status: 200, json: {
      requestId: 'mixed-context',
      data: {
        ...reference,
        digest: {
          description: '',
          technologies: ['Java', 'Spring Boot', 'Vue', 'TypeScript'],
          dependencies: ['maven:org.springframework.boot:spring-boot-starter-web'],
          directoryOverview: ['backend/', 'frontend/'],
          fileSummaries: ['审批方案.txt：订单超过五万元须财务复核'],
          analysisStatus: 'COMPLETE',
          analyzedFileCount: paths.length,
          warnings: [],
        },
        contextReport: {
          customDescription: '', technologyStack: [], dependencies: [], directoryTree: [],
          fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'test',
        },
        expiresAt: '2099-01-01T00:00:00Z',
        latencyMs: 1,
      },
    } });
  });
  await page.route('**/api/v1/optimizations/plan', async (route) => {
    requestOrder.push('plan');
    const body = route.request().postDataJSON() as Record<string, unknown>;
    expect(body.planningContext).toEqual(reference);
    expect(body).not.toHaveProperty('files');
    await route.fulfill({ status: 200, json: {
      requestId: 'mixed-plan',
      data: {
        planId: 'b2222222-2222-4222-8222-222222222222',
        planningContext: reference,
        summary: '已识别项目技术栈和方案规则，还需确认复核失败时的处理。',
        questions: [{
          id: 'approval-rejection',
          question: '财务复核不通过时，接口应如何返回？',
          hint: '方案文件尚未规定失败响应。',
          type: 'FREE_TEXT',
          options: [], examples: [], allowCustomAnswer: true,
        }],
        templateCode: 'FEATURE_DEVELOPMENT',
        provider: { provider: 'mock', model: 'mixed-test', mock: true },
        latencyMs: 1,
        expiresAt: '2099-01-01T00:00:00Z',
      },
    } });
  });
  await page.route('**/api/v1/optimizations', async (route) => {
    requestOrder.push('final');
    const body = route.request().postDataJSON() as {
      context: { files: Array<{ path: string }> };
      planConfirmation: { answers: Array<{ answer: string }> };
    };
    const paths = body.context.files.map((file) => file.path);
    receivedFiles.push(paths);
    expect(paths).toContain('审批方案.txt');
    expect(paths.some((path) => path.startsWith('backend/pom.xml#chunk-'))).toBe(true);
    expect(body.planConfirmation.answers).toEqual([expect.objectContaining({
      answer: '返回 409 状态与审批失败原因。',
    })]);
    await route.fulfill({ status: 200, json: {
      requestId: 'mixed-final',
      data: {
        optimizedPrompt: [
          '## 背景\nSpring Boot 与 Vue 样例项目，财务复核规则已明确。',
          '## 任务\n实现订单审批接口。',
          '## 输出\n复核失败时返回 409 与失败原因。',
          '## 约束\n沿用现有项目技术栈。',
        ].join('\n\n'),
        sections: [
          { type: 'BACKGROUND', title: '背景', content: 'Spring Boot 与 Vue 样例项目，财务复核规则已明确。' },
          { type: 'TASK', title: '任务', content: '实现订单审批接口。' },
          { type: 'OUTPUT', title: '输出', content: '复核失败时返回 409 与失败原因。' },
          { type: 'CONSTRAINTS', title: '约束', content: '沿用现有项目技术栈。' },
        ],
        contextReport: {
          customDescription: '', technologyStack: [], dependencies: [], directoryTree: [],
          fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'test',
        },
        ambiguities: [], appliedConstraints: [], templateCode: 'FEATURE_DEVELOPMENT',
        provider: { provider: 'mock', model: 'mixed-test', mock: true }, latencyMs: 1,
      },
    } });
  });

  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.getByRole('button', { name: '添加上下文' }).click();
  await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  await expect(page.getByText('9 个源码文件已建立本地索引')).toBeVisible();
  await expect(page.locator('.warning-list')).toHaveCount(0);
  const indexedPaths = await page.evaluate(async () => {
    const request = indexedDB.open('prompt-optimizer-project-index');
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const transaction = database.transaction('files', 'readonly');
    const allFiles = transaction.objectStore('files').getAll();
    const paths = await new Promise<string[]>((resolve, reject) => {
      allFiles.onsuccess = () => resolve((allFiles.result as Array<{ path: string }>).map((file) => file.path));
      allFiles.onerror = () => reject(allFiles.error);
    });
    database.close();
    return paths.sort();
  });
  expect(indexedPaths).toEqual([...fixturePaths].sort());

  await page.getByTestId('context-file-input').setInputFiles({
    name: '审批方案.txt', mimeType: 'text/plain', buffer: Buffer.from(solution, 'utf8'),
  });
  await expect(page.getByText('审批方案.txt', { exact: true })).toBeVisible();
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词').fill('按审批方案在样例项目开发订单接口');
  await page.getByRole('button', { name: '先确认并增强' }).click();
  const firstSend = page.getByRole('dialog', { name: '确认发送上下文' });
  await expect(firstSend).toContainText('生成确认问题前分析上下文');
  await firstSend.getByRole('button', { name: '确认发送' }).click();
  const planDialog = page.getByRole('dialog', { name: '确认关键细节' });
  await expect(planDialog.getByText('财务复核不通过时，接口应如何返回？')).toBeVisible();
  await planDialog.getByLabel('填写回答').fill('返回 409 状态与审批失败原因。');
  await planDialog.getByRole('button', { name: '生成最终提示词' }).click();
  const finalSend = page.getByRole('dialog', { name: '确认发送上下文' });
  await expect(finalSend).toContainText('生成最终提示词');
  await finalSend.getByRole('button', { name: '确认发送' }).click();
  await openWorkbenchPane(page, 'result');
  await expect(page.getByText('复核失败时返回 409 与失败原因。')).toBeVisible();
  expect(requestOrder).toEqual(['context', 'plan', 'final']);
  expect(receivedFiles).toHaveLength(2);
  expect(pageErrors).toEqual([]);
});
