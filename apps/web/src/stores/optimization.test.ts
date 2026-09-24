import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/services/promptOptimizerApi', () => ({
  analyzeContext: vi.fn(),
  createOptimizationPlan: vi.fn(),
  listAvailableModels: vi.fn(),
  optimizePrompt: vi.fn(),
  preparePlanningContext: vi.fn(),
}));

import type { ProjectIndexSummary } from '@/features/project-index/projectIndexer';
import { projectIndexRepository } from '@/features/project-index/indexedDbProjectIndexRepository';
import {
  createOptimizationPlan,
  listAvailableModels,
  optimizePrompt,
  preparePlanningContext,
} from '@/services/promptOptimizerApi';
import type { OptimizationResult } from '@/types/api';

import { useOptimizationStore } from './optimization';

const readyIndex = (): ProjectIndexSummary => ({
  id: 'same-project',
  rootName: 'sample-project',
  status: 'READY',
  discoveredFiles: 2,
  eligibleFiles: 2,
  indexedFiles: 2,
  ignoredFiles: 0,
  ignoredDirectories: 0,
  metadataOnlyFiles: 0,
  failedFiles: 0,
  addedFiles: 2,
  updatedFiles: 0,
  unchangedFiles: 0,
  removedFiles: 0,
  changedPaths: [],
  chunkCount: 2,
  indexedCharacters: 100,
  estimatedIndexBytes: 2_000,
  scanLimitReached: false,
  storageLimitReached: false,
  retention: 'SESSION',
  sessionId: 'test-session',
  expiresAt: '2099-01-01T00:00:00.000Z',
  scanId: 'scan-1',
  lastCheckpointPath: 'src/main.ts',
  startedAt: '2026-08-30T00:00:00.000Z',
  completedAt: '2026-08-30T00:00:01.000Z',
});

describe('optimization store project context', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    setActivePinia(createPinia());
    vi.stubGlobal('window', {
      localStorage: {
        getItem: () => null,
        setItem: () => undefined,
        removeItem: () => undefined,
      },
    });
  });

  it('should retain a manually supplied active file after refreshing the same index', () => {
    const store = useOptimizationStore();
    store.setProjectIndex(readyIndex());
    store.addFile({
      path: 'src/main.ts',
      language: 'typescript',
      content: 'export const active = true;',
    });

    store.setProjectIndex({
      ...readyIndex(),
      updatedFiles: 1,
      changedPaths: ['src/main.ts'],
      scanId: 'scan-2',
    });

    expect(store.files).toHaveLength(1);
    expect(store.activeFilePath).toBe('src/main.ts');
  });

  it('should retain an uploaded solution document when a code folder is selected or indexed', async () => {
    const store = useOptimizationStore();
    const solution = {
      path: '方案/订单审批方案.txt',
      language: 'text',
      content: '订单超过五万元须由财务复核。',
    };
    store.addFile(solution);
    store.setFiles([{ path: 'backend/pom.xml', language: 'xml', content: '<project />' }]);

    const preparedFiles = await store.prepareContextFiles('按方案实现订单审批');
    expect(preparedFiles.map((file) => file.path))
      .toEqual(['方案/订单审批方案.txt', 'backend/pom.xml']);
    vi.mocked(preparePlanningContext).mockResolvedValue({
      requestId: 'mixed-context',
      data: {
        contextId: 'mixed-context-id',
        version: `sha256:${'b'.repeat(64)}`,
        digest: {
          description: '',
          technologies: ['Spring Boot'],
          dependencies: [],
          directoryOverview: ['backend/', '方案/'],
          fileSummaries: ['方案/订单审批方案.txt：订单超过五万元须由财务复核。'],
          analysisStatus: 'COMPLETE',
          analyzedFileCount: 2,
          warnings: [],
        },
        contextReport: resultFixture().contextReport,
        expiresAt: '2026-09-14T08:30:00Z',
        latencyMs: 1,
      },
    });
    expect(await store.preparePlanningContext('按方案实现订单审批', preparedFiles)).toBe(true);
    expect(preparePlanningContext).toHaveBeenCalledWith(expect.objectContaining({
      context: expect.objectContaining({ files: preparedFiles }),
    }));

    store.setProjectIndex(readyIndex());
    expect(store.files).toContainEqual(solution);
  });

  it('should give a separately uploaded solution document a slot when the code folder reaches the file cap', () => {
    const store = useOptimizationStore();
    store.setFiles(Array.from({ length: 1_000 }, (_, index) => ({
      path: `src/Module${index}.java`, language: 'java', content: 'class Module {}',
    })));

    store.addFile({
      path: '方案/审批方案.txt', language: 'text', content: '超过五万元须财务复核。',
    });

    expect(store.files).toHaveLength(1_000);
    expect(store.files.some((file) => file.path === '方案/审批方案.txt')).toBe(true);
  });

  it('should send a solution added after a large code folder before code fills backend snippet slots', async () => {
    const store = useOptimizationStore();
    store.setFiles(Array.from({ length: 45 }, (_, index) => ({
      path: `src/Module${index}.java`, language: 'java', content: 'class Module {}',
    })));
    store.addFile({ path: '审批方案.txt', language: 'text', content: '审批超过五万元须财务复核。' });

    const prepared = await store.prepareContextFiles('按方案实现审批');

    expect(prepared[0]?.path).toBe('审批方案.txt');
    expect(prepared).toHaveLength(46);
  });

  it('should preserve complete binary payloads before backend document extraction', async () => {
    const store = useOptimizationStore();
    const base64 = 'A'.repeat(180_000);
    store.addFile({
      path: 'docs/report.pdf',
      language: 'pdf',
      content: base64,
    });

    const files = await store.prepareContextFiles('总结报告');

    expect(files).toHaveLength(1);
    expect(files[0]?.content).toHaveLength(base64.length);
  });

  it('should keep a temporary document reference even when the inline character budget is empty', async () => {
    const store = useOptimizationStore();
    store.addFile({
      path: 'docs/long-report.docx',
      language: 'docx',
      content: '',
      documentId: 'document-123',
      sizeBytes: 40_000_000,
    });

    const files = await store.prepareContextFiles('查找验收标准');

    expect(files).toEqual([{
      path: 'docs/long-report.docx',
      language: 'docx',
      content: '',
      documentId: 'document-123',
      sizeBytes: 40_000_000,
    }]);
  });

  it('should migrate a retired model selection to the server default model', async () => {
    vi.mocked(listAvailableModels).mockResolvedValue({
      requestId: 'models-request',
      data: [
        {
          id: 'tokenhub:deepseek/deepseek-flash',
          displayName: 'DeepSeek-V4.1-Flash',
          provider: 'tokenhub',
          defaultModel: false,
        },
        {
          id: 'tokenhub:deepseek-v4-pro-0813',
          displayName: 'DeepSeek-V4-Pro',
          provider: 'tokenhub',
          defaultModel: true,
        },
        {
          id: 'tokenhub:glm-5.3-flashx',
          displayName: 'GLM-5.3-FlashX',
          provider: 'tokenhub',
          defaultModel: false,
        },
      ],
    });
    const store = useOptimizationStore();
    store.selectedModel = 'deepseek:deepseek-chat';

    await store.loadAvailableModels();

    expect(store.availableModels.map((model) => model.id)).toEqual([
      'tokenhub:deepseek/deepseek-flash',
      'tokenhub:deepseek-v4-pro-0813',
      'tokenhub:glm-5.3-flashx',
    ]);
    expect(store.selectedModel).toBe('tokenhub:deepseek-v4-pro-0813');
  });

  it('should prepare context first and pass its reference into the plan request', async () => {
    const contextId = 'ea9d3453-5bd7-487b-bafb-5ef608dfd895';
    const version = `sha256:${'a'.repeat(64)}`;
    vi.mocked(preparePlanningContext).mockResolvedValue({
      requestId: 'context-request',
      data: {
        contextId,
        version,
        digest: {
          description: 'Spring Boot 用户服务',
          technologies: ['Spring Boot 3'],
          dependencies: [],
          directoryOverview: ['pom.xml'],
          fileSummaries: ['pom.xml：Maven 项目配置'],
          analysisStatus: 'COMPLETE',
          analyzedFileCount: 1,
          warnings: [],
        },
        contextReport: resultFixture().contextReport,
        expiresAt: '2026-09-14T08:30:00Z',
        latencyMs: 12,
      },
    });
    vi.mocked(createOptimizationPlan).mockResolvedValue({
      requestId: 'plan-request',
      data: {
        summary: '请确认登录方式。',
        questions: [],
        templateCode: 'FEATURE_DEVELOPMENT',
        provider: { provider: 'mock', model: 'planner', mock: true },
        latencyMs: 4,
        planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
        planningContext: { contextId, version },
        expiresAt: '2026-09-14T08:30:00Z',
      },
    });
    const store = useOptimizationStore();
    store.customDescription = 'Spring Boot 用户服务';
    const files = [{ path: 'pom.xml', content: '<project />', language: 'xml' }];

    expect(await store.preparePlanningContext('添加登录功能', files)).toBe(true);
    expect(await store.createOptimizationPlan('添加登录功能')).toBe(true);

    expect(preparePlanningContext).toHaveBeenCalledWith(expect.objectContaining({
      rawPrompt: '添加登录功能',
      context: expect.objectContaining({ files }),
    }));
    expect(createOptimizationPlan).toHaveBeenCalledWith(expect.objectContaining({
      rawPrompt: '添加登录功能',
      planningContext: { contextId, version },
    }));
    expect(store.contextSnapshot).toEqual(resultFixture().contextReport);
  });

  it('should use AUTO instead of reusing the previous plan template for direct enhancement', async () => {
    vi.mocked(createOptimizationPlan).mockResolvedValue({
      requestId: 'research-plan',
      data: {
        summary: '请确认研究范围。',
        questions: [],
        templateCode: 'RESEARCH_ANALYSIS',
        provider: { provider: 'mock', model: 'planner', mock: true },
        latencyMs: 4,
      },
    });
    vi.mocked(optimizePrompt).mockResolvedValue({
      requestId: 'direct-result',
      data: {
        ...resultFixture(),
        templateCode: 'FEATURE_DEVELOPMENT',
      },
    });
    const store = useOptimizationStore();

    expect(await store.createOptimizationPlan('分析死亡率趋势')).toBe(true);
    expect(store.templateCode).toBe('RESEARCH_ANALYSIS');
    expect(await store.runOptimization([], { rawPrompt: '给用户模块增加登录功能' })).toBe(true);

    expect(optimizePrompt).toHaveBeenCalledWith(expect.objectContaining({
      enhancement: expect.objectContaining({ templateCode: 'AUTO' }),
      planConfirmation: null,
    }));
    expect(store.plan).toBeUndefined();
    expect(store.planningContext).toBeUndefined();
    expect(store.templateCode).toBe('FEATURE_DEVELOPMENT');
  });

  it('should clear view state without starting a second project-index deletion', () => {
    const deleteProject = vi.spyOn(projectIndexRepository, 'deleteProject');
    const store = useOptimizationStore();
    store.setProjectIndex(readyIndex());

    store.clearFiles();

    expect(store.projectIndex).toBeUndefined();
    expect(deleteProject).not.toHaveBeenCalled();
  });

  it('should restore a removed platform constraint and allow undoing the edit', () => {
    const store = useOptimizationStore();
    const original = resultFixture();
    store.result = original;

    const saved = store.saveEditedSections(original.sections.map((section) =>
      section.type === 'CONSTRAINTS'
        ? {
            ...section,
            content: '保留用户约束。\n\n平台强制约束（不得删除或弱化）：\n- 不得泄露 Token。',
          }
        : { ...section }));

    expect(saved).toBe(true);
    expect(store.result?.optimizedPrompt).toContain('禁止读取受保护路径。');
    expect(store.canUndoResult).toBe(true);

    expect(store.undoResult()).toBe(true);
    expect(store.result).toEqual(original);
  });
});

const resultFixture = (): OptimizationResult => ({
  optimizedPrompt: '原始最终提示词',
  sections: [
    { type: 'BACKGROUND', title: '背景', content: '研究背景。' },
    { type: 'TASK', title: '任务', content: '完成分析。' },
    { type: 'OUTPUT', title: '输出', content: '输出报告。' },
    {
      type: 'CONSTRAINTS',
      title: '约束',
      content: [
        '保留用户约束。',
        '',
        '平台强制约束（不得删除或弱化）：',
        '- 不得泄露 Token。',
        '- 禁止读取受保护路径。',
      ].join('\n'),
    },
  ],
  contextReport: {
    customDescription: '',
    technologyStack: [],
    dependencies: [],
    directoryTree: [],
    fileSnippets: [],
    warnings: [],
    redactions: [],
    analysisVersion: 'v1',
  },
  ambiguities: [],
  appliedConstraints: ['不得泄露 Token。', '禁止读取受保护路径。'],
  templateCode: 'RESEARCH_ANALYSIS',
  provider: { provider: 'mock', model: 'model', mock: true },
  latencyMs: 1,
});
