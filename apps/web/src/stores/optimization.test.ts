import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ProjectIndexSummary } from '@/features/project-index/projectIndexer';
import { projectIndexRepository } from '@/features/project-index/indexedDbProjectIndexRepository';
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
