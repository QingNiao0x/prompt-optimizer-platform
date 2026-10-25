import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('@/services/promptOptimizerApi', () => ({
  analyzeContext: vi.fn(), createOptimizationPlan: vi.fn(), optimizePrompt: vi.fn(),
  preparePlanningContext: vi.fn(), listAvailableModels: vi.fn(), saveNaturalPrompt: vi.fn(),
}));
import { saveNaturalPrompt } from '@/services/promptOptimizerApi';
import { useOptimizationStore } from './optimization';
import { useAuthStore } from './auth';
import type { OptimizationResult } from '@/types/api';

const document = (): OptimizationResult => ({
  resultKind: 'TEXT', generationPipeline: 'NATURAL', chainVersion: '1', framework: 'ICIO',
  recordId: 'record-1', editRevision: 0, sections: [], promptFormat: 'COMPACT',
  optimizedPrompt: '原始自然正文。 事实不得捏造。',
  contextReport: { customDescription: '', technologyStack: [], dependencies: [], directoryTree: [], fileSnippets: [],
    warnings: [], redactions: [], analysisVersion: 'test' }, ambiguities: [], appliedConstraints: ['事实不得捏造。'],
  constraintGroups: { platformMandatory: ['事实不得捏造。'], taskSpecific: [], additionalPermissions: [] },
  templateCode: 'GENERAL', provider: { provider: 'mock', model: 'fixture', mock: true }, latencyMs: 1,
});
describe('native natural result lifecycle', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    setActivePinia(createPinia());
    useAuthStore().user = { userId: 'fixture-user', tenantId: 'fixture-tenant', workspaceId: 'fixture-workspace',
      email: 'fixture@example.test', displayName: '自然正文测试用户', platformAdmin: false };
    vi.stubGlobal('window', { localStorage: { getItem: () => null, setItem: () => undefined, removeItem: () => undefined } });
  });
  it('saves the same full text to the server and current result without synthesizing sections', async () => {
    const store = useOptimizationStore();
    store.applyReoptimized({ recordId: 'record-1', result: document() });
    vi.mocked(saveNaturalPrompt).mockImplementation(async (_id, body) => ({
      requestId: 'save', data: { optimizedPrompt: body, editRevision: 1 },
    }));
    expect(await store.saveEditedText('新的完整邮件要求。')).toBe(true);
    expect(saveNaturalPrompt).toHaveBeenCalledWith('record-1', '新的完整邮件要求。\n\n事实不得捏造。', 0);
    expect(store.result?.optimizedPrompt).toBe('新的完整邮件要求。\n\n事实不得捏造。');
    expect(store.result?.sections).toEqual([]);
    expect(store.result?.editRevision).toBe(1);
  });
  it('retains the complete string draft and previous result on save failure', async () => {
    const store = useOptimizationStore();
    const original = document();
    store.applyReoptimized({ recordId: 'record-1', result: original });
    store.resultDraft = '尚未保存的自然修改稿。';
    vi.mocked(saveNaturalPrompt).mockRejectedValue(new Error('版本冲突'));
    expect(await store.saveEditedText(store.resultDraft)).toBe(false);
    expect(store.resultDraft).toBe('尚未保存的自然修改稿。');
    expect(store.result?.optimizedPrompt).toBe(original.optimizedPrompt);
    expect(store.hasUnsavedResultDraft).toBe(true);
  });
  it('ignores an old save response after switching tasks', async () => {
    const store = useOptimizationStore();
    store.applyReoptimized({ recordId: 'record-1', result: document() });
    let finish!: (value: { requestId: string; data: { optimizedPrompt: string; editRevision: number } }) => void;
    vi.mocked(saveNaturalPrompt).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const saving = store.saveEditedText('迟到修改。');
    store.resetTask();
    finish({ requestId: 'old', data: { optimizedPrompt: '迟到修改。', editRevision: 1 } });
    expect(await saving).toBe(false);
    expect(store.result).toBeUndefined();
  });
  it('persists an edit undo using the current revision', async () => {
    const store = useOptimizationStore();
    const original = document();
    store.applyReoptimized({ recordId: 'record-1', result: original });
    vi.mocked(saveNaturalPrompt).mockResolvedValueOnce({
      requestId: 'edit', data: { optimizedPrompt: '新正文。事实不得捏造。', editRevision: 1 },
    }).mockResolvedValueOnce({
      requestId: 'undo', data: { optimizedPrompt: original.optimizedPrompt, editRevision: 2 },
    });
    await store.saveEditedText('新正文。事实不得捏造。');
    expect(await store.undoResultAndPersist()).toBe(true);
    expect(saveNaturalPrompt).toHaveBeenLastCalledWith('record-1', original.optimizedPrompt, 1);
    expect(store.result?.optimizedPrompt).toBe(original.optimizedPrompt);
    expect(store.result?.editRevision).toBe(2);
  });
});
