import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useTextPolishStore } from './textPolish';
import { useAuthStore } from './auth';
import { useTrialStore } from './trial';
import { textPolishApi as api } from '@/services/textPolishApi';
import { getTrialStatus, initializeTrialSession } from '@/services/trialApi';
import type { PolishDocument, PolishInput } from '@/types/textPolish';
import type { TrialStatus } from '@/types/trial';

vi.mock('@/services/textPolishApi', () => ({ textPolishApi: { capabilities: vi.fn(), generate: vi.fn(), operation: vi.fn(), edit: vi.fn(), retrySave: vi.fn(), history: vi.fn(), detail: vi.fn(), remove: vi.fn() } }));
vi.mock('@/services/trialApi', () => ({ getTrialStatus: vi.fn(), initializeTrialSession: vi.fn() }));
const guestQuota = (remaining: number): TrialStatus => ({ authenticated: false, available: true, quotas: {
  imagePrompt: { limit: 1, used: 0, remaining: 1 }, enhancement: { limit: 3, used: 0, remaining: 3 },
  textPolish: { limit: 3, used: 3 - remaining, remaining },
} });
const input: PolishInput = { sourceText: '原文', style: 'NATURAL', depth: 'LIGHT', audience: '', requirements: '', lockedTexts: [], modelId: 'fixture' };
const doc = (text = '润色正文'): PolishDocument => ({ id: 'record', input: { ...input }, generatedText: text, polishedText: text, sourceRecordId: null, sourceMode: 'RAW', modelId: 'fixture', modelName: '测试模型', mock: false, editRevision: 0, checks: [], historySaved: true, persistentHistory: true, createdAt: '2026-10-25T00:00:00Z' });
beforeEach(() => {
  setActivePinia(createPinia()); vi.resetAllMocks();
  vi.mocked(initializeTrialSession).mockResolvedValue();
  // 这些用例验证登录用户的持久化记录，显式区别于不写历史的访客试用状态。
  useAuthStore().user = { userId: 'fixture-user', tenantId: 'fixture-tenant', workspaceId: 'fixture-workspace',
    email: 'fixture@example.test', displayName: '历史夹具', platformAdmin: false };
  vi.mocked(api.capabilities).mockResolvedValue({ models: [{ id: 'fixture', displayName: '测试模型', defaultModel: true, mock: false }], maxInputCharacters: 12000, maxOutputCharacters: 24000, persistentHistory: true });
});
describe('文章润色状态', () => {
  it('默认自然流畅且切换文体不调用模型', async () => { const s = useTextPolishStore(); await s.load(); expect(s.style).toBe('NATURAL'); s.style = 'FORMAL'; s.depth = 'RESTRUCTURE'; expect(api.generate).not.toHaveBeenCalled(); });
  it('来源清晰：原文重生与当前稿继续分别传递绑定', async () => {
    const s = useTextPolishStore(); await s.load(); s.sourceText = '原文'; vi.mocked(api.generate).mockResolvedValue(doc());
    await s.generate(); expect(vi.mocked(api.generate).mock.calls[0][2]).toBe('RAW');
    await s.generate(); expect(vi.mocked(api.generate).mock.calls[1].slice(2)).toEqual(['ORIGINAL', 'record', 0]);
    vi.mocked(api.generate).mockResolvedValue({ ...doc('第二稿'), id: 'next', input: { ...input, sourceText: '润色正文' }, sourceMode: 'CURRENT', sourceRecordId: 'record' });
    await s.generate(true); expect(vi.mocked(api.generate).mock.calls[2].slice(2)).toEqual(['CURRENT', 'record', 0]);
    expect(s.sourceText).toBe('润色正文');
    s.undo(); expect(s.sourceText).toBe('原文'); expect(s.currentText).toBe('润色正文');
    await s.generate(); expect(vi.mocked(api.generate).mock.calls[3].slice(2)).toEqual(['ORIGINAL', 'record', 0]);
  });
  it('生成期间输入变化保留当前稿并单列新结果', async () => {
    const s = useTextPolishStore(); await s.load(); s.sourceText = '原文'; s.install(doc('旧稿'));
    let resolve!: (value: PolishDocument) => void; vi.mocked(api.generate).mockReturnValue(new Promise(r => { resolve = r; }));
    const work = s.generate(); s.sourceText = '新输入'; resolve(doc('新生成')); await work;
    expect(s.result?.polishedText).toBe('旧稿'); expect(s.pendingResult?.polishedText).toBe('新生成'); expect(s.sourceText).toBe('新输入');
  });
  it('编辑保存冲突保留草稿，失败不覆盖上次结果', async () => {
    const s = useTextPolishStore(); await s.load(); s.install(doc()); s.isEditing = true; s.editDraft = '用户正在改的稿件';
    vi.mocked(api.edit).mockRejectedValue(new Error('修订冲突')); await s.save();
    expect(s.currentText).toBe('用户正在改的稿件'); expect(s.result?.polishedText).toBe('润色正文'); expect(s.isEditing).toBe(true);
  });
  it('身份切换清理文章并拒绝迟到私有响应', async () => {
    const s = useTextPolishStore(); await s.load(); s.sourceText = '原文';
    let resolve!: (value: PolishDocument) => void; vi.mocked(api.generate).mockReturnValue(new Promise(r => { resolve = r; }));
    const work = s.generate();
    useAuthStore().user = { userId: 'other', tenantId: 'other', workspaceId: 'other', email: '', displayName: '', platformAdmin: false };
    resolve(doc()); await work; expect(s.result).toBeUndefined(); expect(s.pendingResult).toBeUndefined(); expect(s.sourceText).toBe('');
  });
  it('恢复历史还原文体、原文和编辑版本', async () => { const s = useTextPolishStore(); vi.mocked(api.detail).mockResolvedValue({ ...doc(), input: { ...input, style: 'FORMAL' }, editRevision: 7 }); expect(await s.restore('record')).toBe(true); expect(s.style).toBe('FORMAL'); expect(s.result?.editRevision).toBe(7); expect(s.sourceText).toBe('原文'); });

  it('已登录内存历史仍编辑回读，并按 CURRENT 关联已保存的当前稿', async () => {
    const s = useTextPolishStore(); await s.load();
    const original = { ...doc(), persistentHistory: false };
    s.restoreRecord(original); s.isEditing = true; s.editDraft = '账号内存历史的修改正文';
    const edited = { ...original, polishedText: s.editDraft, editRevision: 1 };
    vi.mocked(api.edit).mockResolvedValue(edited);
    await s.save();
    expect(api.edit).toHaveBeenCalledWith('record', edited.polishedText, 0);
    expect(s.result?.editRevision).toBe(1);
    s.reset(); vi.mocked(api.detail).mockResolvedValue(edited);
    expect(await s.restore('record')).toBe(true);
    expect(s.currentText).toBe(edited.polishedText);
    vi.mocked(api.generate).mockResolvedValue({ ...doc('继续润色后的正文'), id: 'next',
      input: { ...input, sourceText: edited.polishedText }, sourceMode: 'CURRENT', sourceRecordId: 'record', persistentHistory: false });
    await s.generate(true);
    expect(vi.mocked(api.generate).mock.calls[0].slice(2)).toEqual(['CURRENT', 'record', 1]);
    expect(s.sourceText).toBe(edited.polishedText);
    expect(getTrialStatus).not.toHaveBeenCalled();
  });

  it('已登录内存历史保存失败可重试，不被误当成访客本地编辑', async () => {
    const s = useTextPolishStore(); await s.load();
    const saved = { ...doc(), persistentHistory: false };
    s.install({ ...saved, historySaved: false });
    vi.mocked(api.retrySave).mockResolvedValue(saved);
    await s.save();
    expect(api.retrySave).toHaveBeenCalledWith('record');
    expect(s.result?.historySaved).toBe(true);
    expect(s.result?.persistentHistory).toBe(false);
    expect(api.generate).not.toHaveBeenCalled();
    expect(getTrialStatus).not.toHaveBeenCalled();
  });

  it('访客额度未知或已用完时不调用润色，提示注册', async () => {
    useAuthStore().user = undefined;
    const s = useTextPolishStore(); await s.load(); s.sourceText = '原文';
    expect(s.canGenerate).toBe(false);
    vi.mocked(getTrialStatus).mockResolvedValue(guestQuota(0)); await s.generate();
    expect(api.generate).not.toHaveBeenCalled(); expect(s.error).toContain('免费体验已用完');
    expect(s.canGenerate).toBe(false);
  });

  it('访客最多三次受理尝试，当前稿以 RAW 提交且本页编辑不写历史', async () => {
    useAuthStore().user = undefined;
    const s = useTextPolishStore(); await s.load(); s.sourceText = '原文';
    vi.mocked(getTrialStatus).mockResolvedValueOnce(guestQuota(3)).mockResolvedValueOnce(guestQuota(2))
      .mockResolvedValueOnce(guestQuota(1)).mockResolvedValue(guestQuota(0));
    vi.mocked(api.generate).mockResolvedValueOnce({ ...doc(), historySaved: false, persistentHistory: false });
    await s.generate();
    s.isEditing = true; s.editDraft = '本页人工修订'; await s.save();
    expect(s.currentText).toBe('本页人工修订'); expect(api.edit).not.toHaveBeenCalled(); expect(api.retrySave).not.toHaveBeenCalled();
    vi.mocked(api.generate).mockResolvedValueOnce({ ...doc('第二次润色'), id: 'second', input: { ...input, sourceText: '本页人工修订' },
      historySaved: false, persistentHistory: false });
    await s.generate(true);
    expect(vi.mocked(api.generate).mock.calls[1].slice(1)).toEqual([
      { ...input, sourceText: '本页人工修订' }, 'RAW', undefined, undefined,
    ]);
    expect(s.sourceText).toBe('本页人工修订');
    vi.mocked(api.generate).mockRejectedValueOnce(new Error('本次处理失败')); await s.generate();
    expect(useTrialStore().remaining('TEXT_POLISH')).toBe(0); expect(getTrialStatus).toHaveBeenCalledTimes(4);
    s.reset(); s.sourceText = '新建文章'; await s.generate();
    expect(api.generate).toHaveBeenCalledTimes(3); expect(s.error).toContain('免费体验已用完');
  });

  it('访客历史列表、详情、删除和直接恢复均不发请求或装载正文', async () => {
    useAuthStore().user = undefined;
    const s = useTextPolishStore(); await s.loadHistory();
    expect(await s.restore('record')).toBe(false); await s.remove('record'); s.restoreRecord(doc());
    expect(api.history).not.toHaveBeenCalled(); expect(api.detail).not.toHaveBeenCalled(); expect(api.remove).not.toHaveBeenCalled();
    expect(s.result).toBeUndefined(); expect(s.sourceText).toBe(''); expect(s.error).toContain('注册登录');
  });
});
