import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, expect, it, vi } from 'vitest';
import { useOptimizationStore } from './optimization';
import { useAuthStore } from './auth';
import { reviseNaturalPrompt } from '@/services/promptOptimizerApi';
import type { OptimizationResult } from '@/types/api';

vi.mock('@/services/promptOptimizerApi', () => ({ analyzeContext: vi.fn(), createOptimizationPlan: vi.fn(),
  optimizePrompt: vi.fn(), preparePlanningContext: vi.fn(), listAvailableModels: vi.fn(),
  saveNaturalPrompt: vi.fn(), reviseNaturalPrompt: vi.fn() }));

const result = (id = 'source', body = '原稿'): OptimizationResult => ({
  recordId: id, resultKind: 'TEXT', generationPipeline: 'NATURAL', chainVersion: 'core-repair-v3',
  optimizedPrompt: body, sections: [], editRevision: 2, promptFormat: 'COMPACT',
  contextReport: { customDescription: '', technologyStack: [], dependencies: [], directoryTree: [], fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'fixture' },
  ambiguities: [], appliedConstraints: [], templateCode: 'GENERAL', provider: { provider: 'deepseek', model: 'fixture', mock: false }, latencyMs: 1,
});

beforeEach(() => {
  vi.resetAllMocks(); setActivePinia(createPinia());
  vi.stubGlobal('window', { localStorage: { getItem: () => null, setItem: () => undefined, removeItem: () => undefined } });
  useAuthStore().user = { userId: 'u', tenantId: 't', workspaceId: 'w', email: 'fixture@example.test', displayName: 'fixture', platformAdmin: false };
});

it('sends only feedback and selected context and keeps the old result available for undo', async () => {
  const store = useOptimizationStore();
  store.rawPrompt = '输入框的新文字不能改写历史原始需求';
  store.applyReoptimized({ recordId: 'source', result: result() });
  vi.mocked(reviseNaturalPrompt).mockResolvedValue({ requestId: 'revision', data: { recordId: 'next', result: result('next', '新稿') } });
  expect(await store.reviseResult('只修改语气', [], 'source')).toBe(true);
  expect(reviseNaturalPrompt).toHaveBeenCalledWith('source', '只修改语气', 2, { customDescription: '', files: [] });
  expect(store.result?.optimizedPrompt).toBe('新稿');
  expect(store.rawPrompt).toBe('输入框的新文字不能改写历史原始需求');
  expect(store.undoResult()).toBe(true);
  expect(store.result?.recordId).toBe('source');
});

it('keeps the source on failure and never sends unsaved manual changes', async () => {
  const store = useOptimizationStore(); store.applyReoptimized({ recordId: 'source', result: result() });
  store.resultDraft = '未保存手工稿';
  expect(await store.reviseResult('精简', [], 'source')).toBe(false);
  expect(reviseNaturalPrompt).not.toHaveBeenCalled();
  store.resultDraft = undefined;
  vi.mocked(reviseNaturalPrompt).mockRejectedValue(new Error('fixture failure'));
  expect(await store.reviseResult('精简', [], 'source')).toBe(false);
  expect(store.result?.optimizedPrompt).toBe('原稿');
  expect(store.isOptimizing).toBe(false);
});

it('ignores late responses after switching to another task', async () => {
  const store = useOptimizationStore(); store.applyReoptimized({ recordId: 'source', result: result() });
  let finish!: (value: Awaited<ReturnType<typeof reviseNaturalPrompt>>) => void;
  vi.mocked(reviseNaturalPrompt).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const pending = store.reviseResult('精简', [], 'source');
  store.resetTask();
  finish({ requestId: 'late', data: { recordId: 'next', result: result('next') } });
  expect(await pending).toBe(false);
  expect(store.result).toBeUndefined();
});
