import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import type { OptimizationRequest, OptimizationResult } from '../src/types/api';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';
import { loadSyntheticProject } from '../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/synthetic-project.mjs';

const recordId = '27000000-0000-4000-8000-000000000001';
const body = '请修改重点记录模块 src/views/records 的新增和导入功能，使调查时间只能填写今天及以前的有效日历日期。检查 create.js、import.js 和共用 dateRules.js 中的现有校验，保持两条录入路径规则一致。沿用导入逐行反馈及部分成功行为，保留其他字段，检查昨天、今天、明天和非法日期。';

/** 明确落盘，避免line reporter只持有内存附件而遗漏失败请求原件。 */
const captureJson = async (testInfo: TestInfo, name: string, value: unknown) => {
  const path = testInfo.outputPath(name);
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  await testInfo.attach(name, { contentType: 'application/json', path });
};

/** 仅验证真实浏览器索引、请求与显示闭环；受控正文不计入模型质量证据。 */
const setup = async (page: Page) => {
  const unexpected: string[] = [];
  await page.route('**/api/**', async (route) => {
    unexpected.push(new URL(route.request().url()).pathname);
    await route.fulfill({ status: 400, json: { error: { message: '受控浏览器验收未授权此接口' } } });
  });
  await mockAuthentication(page);
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.route('**/api/v1/models', (route) => route.fulfill({ json: { data: [
    { id: 'deepseek:deepseek-flash', displayName: '受控链路模型目录', provider: 'DeepSeek', defaultModel: true },
  ] } }));
  await page.route('**/api/v1/optimizations/plan-events', (route) => route.fulfill({ status: 204 }));
  const requests: OptimizationRequest[] = [];
  const result: OptimizationResult = {
    resultKind: 'TEXT', generationPipeline: 'NATURAL', chainVersion: '45', framework: null,
    recordId, editRevision: 0, optimizedPrompt: body, sections: [], promptFormat: 'COMPACT',
    templateCode: 'GENERAL', contextReport: { customDescription: '', technologyStack: [], dependencies: [],
      directoryTree: [], fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'controlled-browser-fixture' },
    ambiguities: [], appliedConstraints: [], constraintGroups: { platformMandatory: [], taskSpecific: [], additionalPermissions: [] },
    provider: { provider: 'fixture', model: 'controlled-browser-response', mock: true }, latencyMs: 1,
    processing: { completedStages: ['SCOPE_ANALYSIS', 'BODY_GENERATION'], originalPreserved: false },
  };
  await page.route('**/api/v2/optimizations', async (route) => {
    const request = route.request().postDataJSON() as OptimizationRequest;
    requests.push(request);
    result.contextReport.fileSnippets = request.context.files;
    await route.fulfill({ json: { requestId: 'controlled-context-rewrite', data: result } });
  });
  await page.route('**/api/v2/optimization-history**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    const metadata = { resultKind: result.resultKind, generationPipeline: result.generationPipeline,
      chainVersion: result.chainVersion, processing: result.processing, framework: result.framework };
    if (path.endsWith('/optimization-history')) {
      await route.fulfill({ json: { data: { records: [{ id: recordId, rawPromptPreview: requests[0]?.rawPrompt,
        templateCode: 'GENERAL', providerName: 'fixture', modelName: 'controlled-browser-response', mock: true,
        latencyMs: 1, createdAt: '2026-10-27T00:00:00Z', ...metadata }], total: 1, current: 1, size: 10, pages: 1 } } });
      return;
    }
    await route.fulfill({ json: { data: { id: recordId, rawPrompt: requests[0]?.rawPrompt, optimizedPrompt: body,
      promptFormat: 'COMPACT', sections: [], contextSummary: {}, ambiguities: [], appliedConstraints: [],
      constraintGroups: result.constraintGroups, templateCode: 'GENERAL', providerName: 'fixture',
      modelName: 'controlled-browser-response', mock: true, latencyMs: 1, createdAt: '2026-10-27T00:00:00Z',
      includePermissionBoundaries: false, conversationHistory: [], permissionPolicy: {}, ...metadata } } });
  });
  return { requests, unexpected };
};

/** 通过文件夹选择器建立真实 Worker/IndexedDB 索引，不直接预填任务上下文。 */
const selectSyntheticDirectory = async (page: Page) => {
  const fixture = await loadSyntheticProject();
  await page.addInitScript(async ({ rootName, files }) => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
    const storage = await navigator.storage.getDirectory();
    const root = await storage.getDirectoryHandle(rootName, { create: true });
    for (const file of files) {
      const parts = file.path.split('/');
      let directory = root;
      for (const part of parts.slice(0, -1)) directory = await directory.getDirectoryHandle(part, { create: true });
      const handle = await directory.getFileHandle(parts.at(-1)!, { create: true });
      const writer = await handle.createWritable();
      await writer.write(file.content);
      await writer.close();
    }
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
  }, fixture);
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.getByRole('button', { name: '添加上下文', exact: true }).click();
  await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  await expect(page.getByText(`${fixture.files.length} 个源码文件已建立本地索引`, { exact: true })).toBeVisible();
  return fixture;
};

test('自然增强从真实项目索引召回指定目录，显示复制与历史正文一致', async ({ page }, testInfo) => {
  test.slow();
  const state = await setup(page);
  const fixture = await selectSyntheticDirectory(page);
  const indexedPaths = await page.evaluate(async () => {
    const request = indexedDB.open('prompt-optimizer-project-index');
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    const transaction = database.transaction('files', 'readonly');
    const all = transaction.objectStore('files').getAll();
    const paths = await new Promise<string[]>((resolve, reject) => {
      all.onsuccess = () => resolve((all.result as Array<{ path: string }>).map(({ path }) => path));
      all.onerror = () => reject(all.error);
    });
    database.close();
    return paths.sort();
  });
  // 全部噪声仍被索引；目标优先不能靠删减索引范围取得。
  expect(indexedPaths).toEqual(fixture.files.map(({ path }) => path).sort());
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词', { exact: true }).fill(fixture.rawPrompt);
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  await expect(confirmation).toBeVisible();
  await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => state.requests.length).toBe(1);
  const request = state.requests[0]!;
  const basePaths = request.context.files.map(({ path }) => path.split('#chunk-')[0]);
  await captureJson(testInfo, 'actual-browser-optimization-request.json', request);
  const retrieval = await page.evaluate(async () => {
    const modulePath = '/src/stores/optimization.ts';
    const { useOptimizationStore } = await import(modulePath) as typeof import('../src/stores/optimization');
    return useOptimizationStore().contextRetrieval;
  });
  await captureJson(testInfo, 'actual-browser-retrieval-report.json', retrieval);
  expect(request.rawPrompt).toBe(fixture.rawPrompt);
  expect(request.enhancement.promptFormat).toBe('COMPACT');
  expect(request.planConfirmation).toBeNull();
  for (const target of fixture.targetPaths) expect(basePaths).toContain(target);
  expect(basePaths.some((path) => path.startsWith('src/views/records-old/'))).toBe(false);
  expect(basePaths.some((path) => path.startsWith('src/views/records-archive'))).toBe(false);
  expect(request.context.files.length).toBeLessThan(fixture.files.length);
  const captureDirectory = new URL('../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/browser-capture/', import.meta.url);
  await mkdir(captureDirectory, { recursive: true });
  await writeFile(new URL('input.json', captureDirectory), `${JSON.stringify(request, null, 2)}\n`, 'utf8');
  await openWorkbenchPane(page, 'result');
  await expect(page.getByTestId('compact-prompt')).toHaveText(body);
  // 受控结果必须如实标为Mock；实际生成阶段由独立真实模型验收核对。
  await expect(page.locator('.result-meta')).toContainText('Mock 结果');
  await page.bringToFront();
  await page.locator('.copy-main-button').click();
  await expect(page.locator('.copy-main-button')).toContainText('已复制');
  const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n');
  expect(copied).toBe(body);
  expect(copied).not.toContain('UNRELATED_SOURCE_');
  expect(copied).not.toContain('提供的材料：');
  await page.goto('/history');
  await page.getByRole('button', { name: '查看', exact: true }).filter({ visible: true }).click();
  await expect(page.getByRole('dialog', { name: '历史记录详情', exact: true }).locator('pre').nth(1)).toHaveText(copied);
  expect(state.unexpected).toEqual([]);
  await captureJson(testInfo, 'controlled-browser-verdict.json', {
    modelCalls: 0, controlledResponse: true, qualityEvidence: false, indexedFiles: indexedPaths.length,
    selectedPaths: basePaths, displayCopyHistoryEqual: true,
  });
});

test('冻结后的内部新题通过同一真实项目索引定位导入文件及必要依赖', async ({ page }, testInfo) => {
  test.slow();
  const state = await setup(page);
  await selectSyntheticDirectory(page);
  const newCase = JSON.parse(await readFile(new URL('../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/internal-new-case.json', import.meta.url), 'utf8')) as { rawPrompt: string };
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词', { exact: true }).fill(newCase.rawPrompt);
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  await expect(confirmation).toBeVisible();
  await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  await expect.poll(() => state.requests.length).toBe(1);
  const request = state.requests[0]!;
  const paths = request.context.files.map(({ path }) => path.split('#chunk-')[0]);
  await captureJson(testInfo, 'actual-browser-new-case-request.json', request);
  expect(request.rawPrompt).toBe(newCase.rawPrompt);
  expect(request.modelId).toBe('deepseek:deepseek-flash');
  expect(paths).toContain('src/views/records/import.js');
  expect(paths).toContain('src/views/records/dateRules.js');
  expect(paths.some((path) => path.startsWith('src/views/records-old/'))).toBe(false);
  expect(paths.some((path) => path.startsWith('src/views/records-archive'))).toBe(false);
  const captureDirectory = new URL('../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/browser-capture/', import.meta.url);
  await mkdir(captureDirectory, { recursive: true });
  await writeFile(new URL('input-new-case.json', captureDirectory), `${JSON.stringify(request, null, 2)}\n`, 'utf8');
  expect(state.unexpected).toEqual([]);
});
