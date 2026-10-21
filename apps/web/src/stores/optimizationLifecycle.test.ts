import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/services/promptOptimizerApi', () => ({
  analyzeContext: vi.fn(), optimizePrompt: vi.fn(), createOptimizationPlan: vi.fn(),
  preparePlanningContext: vi.fn(), listAvailableModels: vi.fn(),
}));
vi.mock('@/services/documentUploadApi', () => ({ deleteDocumentUpload: vi.fn().mockResolvedValue(undefined) }));
vi.mock('@/features/project-index/indexedDbProjectIndexRepository', () => ({
  projectIndexRepository: { deleteProject: vi.fn().mockResolvedValue(undefined) },
}));
vi.mock('@/features/project-index/projectIndexer', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/features/project-index/projectIndexer')>(),
  retrieveProjectContextWithReport: vi.fn(),
}));

import { analyzeContext, createOptimizationPlan, optimizePrompt, preparePlanningContext } from '@/services/promptOptimizerApi';
import { deleteDocumentUpload } from '@/services/documentUploadApi';
import { retrieveProjectContextWithReport, type ProjectIndexSummary } from '@/features/project-index/projectIndexer';
import { projectIndexRepository } from '@/features/project-index/indexedDbProjectIndexRepository';
import { useAuthStore } from './auth';
import type { ApiResponse, ContextSnapshot, OptimizationHistoryDetail, OptimizationResult } from '@/types/api';
import { useOptimizationStore } from './optimization';

const snapshot = (): ContextSnapshot => ({ customDescription: '', technologyStack: [], dependencies: [],
  directoryTree: [], fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'p0' });
const result = (text: string): OptimizationResult => ({ optimizedPrompt: text, sections: [
  { type: 'BACKGROUND', title: '背景', content: '仅为测试资料。' },
  { type: 'TASK', title: '任务', content: text },
  { type: 'OUTPUT', title: '输出', content: '保留原交付。' },
  { type: 'CONSTRAINTS', title: '约束', content: '不得伪造事实。' },
], contextReport: snapshot(), ambiguities: [], appliedConstraints: [], templateCode: 'GENERAL',
provider: { provider: 'mock', model: 'p0', mock: true }, latencyMs: 1 });
const history = (): OptimizationHistoryDetail => ({ id: 'history-b', rawPrompt: '任务 B：写邮件',
  optimizedPrompt: '', sections: [], contextSummary: {}, ambiguities: [], appliedConstraints: [],
  templateCode: 'GENERAL', providerName: 'mock', modelName: 'p0', mock: true, latencyMs: 1,
  createdAt: '2026-10-11T00:00:00Z', includePermissionBoundaries: true,
  conversationHistory: [], permissionPolicy: {} });
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const readyIndex = (): ProjectIndexSummary => ({ id: 'project-a', rootName: 'project-a', status: 'READY',
  discoveredFiles: 1, eligibleFiles: 1, indexedFiles: 1, ignoredFiles: 0, ignoredDirectories: 0,
  metadataOnlyFiles: 0, failedFiles: 0, addedFiles: 1, updatedFiles: 0, unchangedFiles: 0, removedFiles: 0,
  changedPaths: [], chunkCount: 1, indexedCharacters: 10, estimatedIndexBytes: 20, scanLimitReached: false,
  storageLimitReached: false, retention: 'SESSION', sessionId: 'p0', expiresAt: '2099-01-01', scanId: 'p0',
  lastCheckpointPath: '', startedAt: '2026-10-11T00:00:00Z' });

describe('P0 task lifecycle regressions', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    setActivePinia(createPinia());
    vi.stubGlobal('window', { localStorage: { getItem: () => null, setItem: () => undefined, removeItem: () => undefined } });
    vi.mocked(deleteDocumentUpload).mockResolvedValue(undefined);
    vi.mocked(projectIndexRepository.deleteProject).mockResolvedValue(undefined);
    vi.mocked(optimizePrompt).mockResolvedValue({ requestId: 'b', data: result('B 结果') });
  });

  it.each(['attachment', 'folder', 'index'] as const)(
    'A attachments cannot reappear after loading B and adding a new %s', async mode => {
    const store = useOptimizationStore();
    store.selectedModelId = 'chosen-model';
    store.addFile({ path: 'A.txt', language: 'text', content: 'A_PRIVATE_MARKER' });
    store.loadFromHistory(history());
    const fileB = { path: 'B.txt', language: 'text', content: 'B_ONLY' };
    if (mode === 'attachment') store.addFile(fileB);
    else if (mode === 'folder') store.setFiles([fileB]);
    else {
      store.setProjectIndex({ ...readyIndex(), id: 'project-b', rootName: 'project-b' });
      vi.mocked(retrieveProjectContextWithReport).mockResolvedValueOnce({ files: [fileB], selections: [], totalCharacters: 6 });
    }
    await store.runOptimization();
    expect(store.files.map(file => file.path)).toEqual(mode === 'index' ? [] : ['B.txt']);
    expect(vi.mocked(optimizePrompt).mock.lastCall?.[0].context.files.map(file => file.path)).toEqual(['B.txt']);
    expect(JSON.stringify(vi.mocked(optimizePrompt).mock.calls)).not.toContain('A_PRIVATE_MARKER');
    expect(store.selectedModelId).toBe('chosen-model');
  });

  it('keeps independent attachments when replacing a folder within the same task', () => {
    const store = useOptimizationStore();
    store.addFile({ path: 'requirements.txt', language: 'text', content: '独立资料' });
    store.setFiles([{ path: 'folder/main.ts', language: 'typescript', content: 'export {}' }]);
    store.setFiles([{ path: 'other/main.java', language: 'java', content: 'class Main {}' }]);
    expect(store.files.map(file => file.path)).toEqual(['requirements.txt', 'other/main.java']);
  });

  it('rejects a late A result without clearing the busy state or result of B', async () => {
    const a = deferred<ApiResponse<OptimizationResult>>();
    const b = deferred<ApiResponse<OptimizationResult>>();
    vi.mocked(optimizePrompt).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
    const store = useOptimizationStore();
    store.rawPrompt = '任务 A';
    const first = store.runOptimization([]);
    store.loadFromHistory(history());
    const second = store.runOptimization([]);
    a.resolve({ requestId: 'a', data: result('旧 A') });
    expect(await first).toBe(false);
    expect(store.result).toBeUndefined();
    expect(store.isOptimizing).toBe(true);
    b.resolve({ requestId: 'b', data: result('新 B') });
    expect(await second).toBe(true);
    expect(store.result?.optimizedPrompt).toBe('新 B');
  });

  it('ignores a stale context failure after task replacement', async () => {
    const a = deferred<ApiResponse<ContextSnapshot>>();
    vi.mocked(analyzeContext).mockReturnValueOnce(a.promise);
    const store = useOptimizationStore();
    const pending = store.runContextAnalysis([]);
    store.loadFromHistory(history());
    a.reject(new Error('旧 A 故障'));
    expect(await pending).toBe(false);
    expect(store.errorMessage).toBe('');
    expect(store.contextSnapshot).toBeUndefined();
  });

  it('clearing or changing materials invalidates the previous Plan and late preparation', async () => {
    const a = deferred<Awaited<ReturnType<typeof preparePlanningContext>>>();
    vi.mocked(preparePlanningContext).mockReturnValueOnce(a.promise);
    const store = useOptimizationStore();
    const pending = store.preparePlanningContext('任务 A', []);
    store.clearFiles();
    a.resolve({ requestId: 'a', data: { contextId: 'old', version: 'old', expiresAt: '2099', latencyMs: 1,
      contextReport: snapshot(), digest: { description: '', technologies: [], dependencies: [], directoryOverview: [],
        fileSummaries: [], analysisStatus: 'EMPTY', analyzedFileCount: 0, warnings: [] } } });
    expect(await pending).toBe(false);
    expect(store.planningContext).toBeUndefined();
    expect(store.plan).toBeUndefined();
  });

  it('removes stale document references even if server cleanup fails', async () => {
    vi.mocked(deleteDocumentUpload).mockRejectedValue(new Error('temporary failure'));
    const store = useOptimizationStore();
    store.addFile({ path: 'A.pdf', language: 'pdf', content: '', documentId: 'old-document' });
    store.loadFromHistory(history());
    store.addFile({ path: 'B.txt', language: 'text', content: 'B' });
    await store.runOptimization();
    expect(deleteDocumentUpload).toHaveBeenCalledWith('old-document');
    expect(store.files.every(file => file.documentId !== 'old-document')).toBe(true);
  });

  it('keeps the current result unchanged after an invalid edit and accepts the corrected draft', () => {
    const store = useOptimizationStore();
    store.applyReoptimized({ recordId: 'b', result: result('原任务') });
    const draft = store.result!.sections.map(section => ({ ...section,
      content: section.type === 'TASK' ? '用户的新任务' : section.type === 'OUTPUT' ? '' : section.content }));
    expect(store.saveEditedSections(draft)).toBe(false);
    expect(store.result?.sections.find(section => section.type === 'TASK')?.content).toBe('原任务');
    draft.find(section => section.type === 'OUTPUT')!.content = '修正输出';
    expect(store.saveEditedSections(draft)).toBe(true);
    expect(store.result?.optimizedPrompt).toContain('用户的新任务');
    expect(store.errorMessage).toBe('');
  });

  it('rejects files prepared before task B even after a delayed send confirmation', async () => {
    const store = useOptimizationStore();
    store.addFile({ path: 'A.txt', language: 'text', content: 'A_MARKER' });
    const preparedA = await store.prepareContextFiles('A');
    store.loadFromHistory(history());
    expect(await store.runOptimization(preparedA)).toBe(false);
    expect(optimizePrompt).not.toHaveBeenCalled();
  });

  it('does not apply a late local retrieval to task B', async () => {
    const retrieval = deferred<Awaited<ReturnType<typeof retrieveProjectContextWithReport>>>();
    vi.mocked(retrieveProjectContextWithReport).mockReturnValueOnce(retrieval.promise);
    const store = useOptimizationStore();
    store.setProjectIndex(readyIndex());
    const pending = store.prepareContextFiles('A');
    const rejected = expect(pending).rejects.toThrow('任务或资料已变更');
    store.loadFromHistory(history());
    store.addFile({ path: 'B.txt', language: 'text', content: 'B_ONLY' });
    retrieval.resolve({ files: [{ path: 'A.ts#chunk-1', language: 'typescript', content: 'A_RETRIEVAL' }],
      selections: [], totalCharacters: 11 });
    await rejected;
    expect(store.contextRetrieval).toBeUndefined();
    expect(store.files.map(file => file.path)).toEqual(['B.txt']);
  });

  it('invalidates Plan creation and drafts on identity or workspace change while keeping model preferences', async () => {
    const pendingPlan = deferred<Awaited<ReturnType<typeof createOptimizationPlan>>>();
    vi.mocked(createOptimizationPlan).mockReturnValueOnce(pendingPlan.promise);
    const auth = useAuthStore();
    auth.user = { userId: 'a', tenantId: 'tenant-a', workspaceId: 'workspace-a',
      email: 'synthetic-a@example.test', displayName: 'A', platformAdmin: false };
    const store = useOptimizationStore();
    store.rawPrompt = 'A';
    store.selectedModelId = 'chosen';
    store.addFile({ path: 'A.txt', language: 'text', content: 'A' });
    store.resultDraft = result('A draft').sections;
    const pending = store.createOptimizationPlan();
    auth.user = { userId: 'b', tenantId: 'tenant-b', workspaceId: 'workspace-b',
      email: 'synthetic@example.test', displayName: 'B', platformAdmin: false };
    pendingPlan.resolve({ requestId: 'a', data: { planId: 'old-a', summary: 'old', templateCode: 'GENERAL',
      questions: [], provider: { provider: 'mock', model: 'p0', mock: true }, latencyMs: 1 } });
    expect(await pending).toBe(false);
    expect(store.plan).toBeUndefined();
    expect(store.resultDraft).toBeUndefined();
    expect(store.files).toEqual([]);
    expect(store.rawPrompt).toBe('');
    expect(store.selectedModelId).toBe('chosen');
  });
});
