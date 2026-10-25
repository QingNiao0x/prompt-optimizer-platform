import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from './auth';
import { useOptimizationStore } from './optimization';
import { useTrialStore } from './trial';
import { optimizePrompt, saveNaturalPrompt } from '@/services/promptOptimizerApi';
import { getTrialStatus, initializeTrialSession } from '@/services/trialApi';
import type { OptimizationResult } from '@/types/api';
import type { TrialStatus } from '@/types/trial';

vi.mock('@/services/promptOptimizerApi', () => ({
  analyzeContext: vi.fn(), createOptimizationPlan: vi.fn(), optimizePrompt: vi.fn(),
  preparePlanningContext: vi.fn(), listAvailableModels: vi.fn(), saveNaturalPrompt: vi.fn(),
}));
vi.mock('@/services/trialApi', () => ({ getTrialStatus: vi.fn(), initializeTrialSession: vi.fn() }));

const quota = (remaining: number): TrialStatus => ({ authenticated: false, available: true, quotas: {
  imagePrompt: { limit: 1, used: 0, remaining: 1 }, enhancement: { limit: 3, used: 3 - remaining, remaining },
  textPolish: { limit: 3, used: 0, remaining: 3 },
} });
const result = (): OptimizationResult => ({
  resultKind: 'TEXT', generationPipeline: 'NATURAL', chainVersion: '1', framework: 'ICIO',
  editRevision: 0, sections: [], promptFormat: 'COMPACT', optimizedPrompt: '写一封简短的会议邀请邮件。',
  contextReport: { customDescription: '', technologyStack: [], dependencies: [], directoryTree: [], fileSnippets: [],
    warnings: [], redactions: [], analysisVersion: 'test' }, ambiguities: [], appliedConstraints: [],
  templateCode: 'GENERAL', provider: { provider: 'mock', model: 'fixture', mock: true }, latencyMs: 1,
});

/** 访客模型结果使用无 recordId 的临时正文；所有配额变化由模拟服务端返回，不模拟本地扣额。 */
describe('访客提示词优化额度与边界', () => {
  beforeEach(() => {
    vi.resetAllMocks(); setActivePinia(createPinia());
    vi.stubGlobal('window', { localStorage: { getItem: () => null, setItem: () => undefined, removeItem: () => undefined } });
    vi.mocked(initializeTrialSession).mockResolvedValue();
    vi.mocked(optimizePrompt).mockResolvedValue({ requestId: 'fixture', data: result() });
  });

  it('余额为零或初始化失败时不发送增强请求', async () => {
    const store = useOptimizationStore(); store.rawPrompt = '写会议邀请邮件';
    vi.mocked(getTrialStatus).mockRejectedValueOnce(new Error('试用服务不可用'));
    expect(await store.runOptimization([])).toBe(false); expect(store.errorMessage).toContain('试用服务不可用');
    vi.mocked(getTrialStatus).mockResolvedValue(quota(0));
    expect(await store.runOptimization([])).toBe(false); expect(store.errorMessage).toContain('免费体验次数已用完');
    expect(optimizePrompt).not.toHaveBeenCalled();
  });

  it('三次受理尝试包含失败，刷新服务器余额后拒绝第四次且新任务不恢复次数', async () => {
    const store = useOptimizationStore(); store.rawPrompt = '写会议邀请邮件';
    vi.mocked(getTrialStatus).mockResolvedValueOnce(quota(3)).mockResolvedValueOnce(quota(2))
      .mockResolvedValueOnce(quota(1)).mockResolvedValue(quota(0));
    expect(await store.runOptimization([])).toBe(true);
    vi.mocked(optimizePrompt).mockRejectedValueOnce(new Error('模型处理失败'));
    expect(await store.runOptimization([])).toBe(false);
    expect(await store.runOptimization([])).toBe(true);
    expect(getTrialStatus).toHaveBeenCalledTimes(4); expect(useTrialStore().remaining('PROMPT_ENHANCEMENT')).toBe(0);
    store.resetTask(); store.rawPrompt = '新的任务';
    expect(await store.runOptimization([])).toBe(false); expect(optimizePrompt).toHaveBeenCalledTimes(3);
  });

  it('拒绝携带 Plan 确认或上传文件的访客增强请求', async () => {
    const store = useOptimizationStore(); store.rawPrompt = '整理报告';
    vi.mocked(getTrialStatus).mockResolvedValue(quota(3));
    expect(await store.runOptimization([], { planConfirmation: { planId: 'private-plan', answers: [] } })).toBe(false);
    expect(await store.runOptimization([{ path: 'private.txt', language: 'text', content: '不得提交的资料' }])).toBe(false);
    expect(store.errorMessage).toContain('Plan 和资料上传需要注册登录'); expect(optimizePrompt).not.toHaveBeenCalled();
    expect(getTrialStatus).toHaveBeenCalledOnce();
  });

  it('试用结果及其编辑撤销只保留在内存，不调用正式历史保存', async () => {
    const store = useOptimizationStore(); store.rawPrompt = '写会议邀请邮件';
    vi.mocked(getTrialStatus).mockResolvedValue(quota(2));
    expect(await store.runOptimization([])).toBe(true); expect(store.result?.recordId).toBeUndefined();
    expect(await store.saveEditedText('本页修改的会议邀请要求。')).toBe(true);
    expect(store.result?.optimizedPrompt).toBe('本页修改的会议邀请要求。');
    expect(await store.undoResultAndPersist()).toBe(true);
    expect(store.result?.optimizedPrompt).toBe(result().optimizedPrompt); expect(saveNaturalPrompt).not.toHaveBeenCalled();
  });

  it('已登录用户继续沿用正式增强，不受访客三次额度影响', async () => {
    useAuthStore().user = { userId: 'fixture-user', tenantId: 'fixture-tenant', workspaceId: 'fixture-workspace',
      email: 'fixture@example.test', displayName: '正式测试用户', platformAdmin: false };
    const store = useOptimizationStore(); store.rawPrompt = '正式账号任务';
    for (let attempt = 0; attempt < 4; attempt += 1) expect(await store.runOptimization([])).toBe(true);
    expect(optimizePrompt).toHaveBeenCalledTimes(4); expect(getTrialStatus).not.toHaveBeenCalled();
    expect(initializeTrialSession).not.toHaveBeenCalled();
  });
});
