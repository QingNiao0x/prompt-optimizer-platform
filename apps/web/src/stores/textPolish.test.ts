import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useTextPolishStore } from './textPolish';
import { useAuthStore } from './auth';
import { textPolishApi as api } from '@/services/textPolishApi';
import type { PolishDocument, PolishInput } from '@/types/textPolish';

vi.mock('@/services/textPolishApi', () => ({ textPolishApi: { capabilities: vi.fn(), generate: vi.fn(), operation: vi.fn(), edit: vi.fn(), retrySave: vi.fn(), history: vi.fn(), detail: vi.fn(), remove: vi.fn() } }));
const input: PolishInput = { sourceText: '原文', style: 'NATURAL', depth: 'LIGHT', audience: '', requirements: '', lockedTexts: [], modelId: 'fixture' };
const doc = (text = '润色正文'): PolishDocument => ({ id: 'record', input: { ...input }, generatedText: text, polishedText: text, sourceRecordId: null, sourceMode: 'RAW', modelId: 'fixture', modelName: '测试模型', mock: false, editRevision: 0, checks: [], historySaved: true, persistentHistory: true, createdAt: '2026-10-25T00:00:00Z' });
beforeEach(() => { setActivePinia(createPinia()); vi.resetAllMocks(); vi.mocked(api.capabilities).mockResolvedValue({ models: [{ id: 'fixture', displayName: '测试模型', defaultModel: true, mock: false }], maxInputCharacters: 12000, maxOutputCharacters: 24000, persistentHistory: true }); });
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
});
