import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';

vi.mock('@/services/promptOptimizerApi', () => ({
  analyzeContext: vi.fn(), createOptimizationPlan: vi.fn(), optimizePrompt: vi.fn(),
  preparePlanningContext: vi.fn(), listAvailableModels: vi.fn(),
}));

import { renderPromptSections } from '@/features/optimization/promptPresentation';
import { optimizePrompt } from '@/services/promptOptimizerApi';
import type { OptimizationHistoryDetail, OptimizationResult, PromptFormat } from '@/types/api';
import { useOptimizationStore } from './optimization';
import { useAuthStore } from './auth';

const fixture = (format: PromptFormat = 'COMPACT'): OptimizationResult => {
  const sections: OptimizationResult['sections'] = [
    { type: 'BACKGROUND', title: '背景', content: '面向已有项目。' },
    { type: 'TASK', title: '任务', content: '实现约定接口。\n```md\n## 代码中的标题\n```' },
    { type: 'OUTPUT', title: '输出', content: '交付实现和测试；只返回约定 JSON。' },
    { type: 'CONSTRAINTS', title: '约束', content: '平台强制约束（不得删除或弱化）：\n- 不得泄露凭据。\n\n新账号需复核；旧账号例外。' },
    { type: 'CLARIFICATIONS', title: '待确认', content: '尚未确定的事实。' },
  ];
  return { promptFormat: format, sections, optimizedPrompt: renderPromptSections(sections, format),
    contextReport: { customDescription: '', technologyStack: [], dependencies: [], directoryTree: [],
      fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'test' },
    ambiguities: ['尚未确定的事实。'], appliedConstraints: ['不得泄露凭据。'], templateCode: 'GENERAL',
    provider: { provider: 'mock', model: 'fixture', mock: true }, latencyMs: 1 };
};
const history = (format?: PromptFormat): OptimizationHistoryDetail => ({
  id: 'existing-record', rawPrompt: '历史任务', ...fixture(format), contextSummary: {},
  ...(format ? { promptFormat: format } : { promptFormat: undefined }),
  providerName: 'mock', modelName: 'fixture', mock: true, latencyMs: 1,
  createdAt: '2026-10-18T00:00:00Z', includePermissionBoundaries: true,
  conversationHistory: [], permissionPolicy: {},
});

describe('explicit prompt presentation', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    setActivePinia(createPinia());
    useAuthStore().user = { userId: 'fixture-user', tenantId: 'fixture-tenant', workspaceId: 'fixture-workspace',
      email: 'fixture@example.test', displayName: '展示测试用户', platformAdmin: false };
    vi.stubGlobal('window', { localStorage: { getItem: () => null, setItem: () => undefined, removeItem: () => undefined } });
  });

  it.each(['COMPACT', 'STRUCTURED'] as const)('edits and undoes a %s two-section result without recreating empty sections', format => {
    const store = useOptimizationStore();
    const source = fixture(format);
    source.sections = source.sections.filter(section => section.type === 'TASK' || section.type === 'CONSTRAINTS');
    source.constraintGroups = { platformMandatory: ['不得泄露凭据。'], taskSpecific: [], additionalPermissions: [] };
    source.optimizedPrompt = renderPromptSections(source.sections, format);
    store.applyReoptimized({ recordId: 'two-sections', result: source });

    const edited = source.sections.map(section => ({ ...section,
      content: section.type === 'TASK' ? '实现约定接口，兼容当前调用方。' : '保留已有约定。' }));
    expect(store.saveEditedSections(edited)).toBe(true);
    expect(store.result!.sections.map(section => section.type)).toEqual(['TASK', 'CONSTRAINTS']);
    expect(store.result!.sections.every(section => section.content.trim())).toBe(true);
    expect(store.result!.optimizedPrompt).toBe(renderPromptSections(store.result!.sections, format));
    expect(store.result!.optimizedPrompt).toContain('不得泄露凭据。');
    expect(store.result!.optimizedPrompt).not.toMatch(/## (背景|输出)/);
    expect(store.undoResult()).toBe(true);
    expect(store.result!.optimizedPrompt).toBe(source.optimizedPrompt);
    expect(store.result!.sections).toEqual(source.sections);
  });

  it.each(['COMPACT', 'STRUCTURED'] as const)('edits %s rules using server ownership and keeps original Plan source on regeneration', async format => {
    const store = useOptimizationStore();
    store.rawPrompt = '实现登录，沿用既有认证。';
    store.setPromptFormat(format);
    const source = fixture(format);
    const permission = '导出真实用户数据前须人工确认。';
    const taskRule = '保留已有认证接口。';
    source.constraintGroups = { platformMandatory: ['不得泄露凭据。'],
      taskSpecific: [taskRule], additionalPermissions: [permission] };
    source.appliedConstraints = ['不得泄露凭据。', taskRule, permission];
    source.sections = source.sections.map(section => ({ ...section,
      content: section.type === 'CONSTRAINTS' ? `- 不得泄露凭据。\n- ${permission}`
        : section.type === 'TASK' ? `实现登录。\n${taskRule}` : section.content }));
    source.optimizedPrompt = renderPromptSections(source.sections, format);
    vi.mocked(optimizePrompt).mockResolvedValue({ requestId: 'grouped', data: source });
    const confirmation = { planId: 'same-plan', answers: [
      { questionId: 'permission', question: '是否允许导出？', answer: '仅经过人工批准' },
    ] };
    expect(await store.runOptimization([], { planConfirmation: confirmation })).toBe(true);
    const firstRequest = JSON.parse(JSON.stringify(vi.mocked(optimizePrompt).mock.calls[0]![0]));
    expect(store.saveEditedSections(store.result!.sections.map(section => ({ ...section,
      content: section.type === 'CONSTRAINTS' ? '新用户需复核，存量用户例外。' : section.content })))).toBe(true);
    expect(store.result?.optimizedPrompt).not.toContain('平台强制约束');
    expect(store.result?.optimizedPrompt.split(taskRule)).toHaveLength(2);
    expect(store.result?.optimizedPrompt).toContain(permission);
    expect(store.result?.optimizedPrompt).toContain('不得泄露凭据。');
    expect(store.result?.constraintGroups).toEqual(source.constraintGroups);
    const edited = store.result!.optimizedPrompt;
    const changedFormat = format === 'COMPACT' ? 'STRUCTURED' : 'COMPACT';
    store.setPromptFormat(changedFormat);
    expect(await store.regenerateWithPromptFormat()).toBe(true);
    expect(vi.mocked(optimizePrompt).mock.calls[1]![0]).toEqual({ ...firstRequest,
      enhancement: { ...firstRequest.enhancement, promptFormat: changedFormat } });
    expect(vi.mocked(optimizePrompt).mock.calls[1]![0]).not.toHaveProperty('constraintGroups');
    expect(store.undoResult()).toBe(true);
    expect(store.result?.optimizedPrompt).toBe(edited);
    expect(store.result?.constraintGroups).toEqual(source.constraintGroups);
  });

  it.each(['COMPACT', 'STRUCTURED'] as const)('sends %s for direct and confirmed Plan enhancement', async format => {
    const store = useOptimizationStore();
    store.rawPrompt = '实现约定接口';
    store.setPromptFormat(format);
    vi.mocked(optimizePrompt).mockResolvedValue({ requestId: 'direct', data: fixture(format) });
    expect(await store.runOptimization([])).toBe(true);
    const planConfirmation = { planId: 'existing-plan', answers: [
      { questionId: 'auth', question: '认证方式？', answer: '沿用现有认证' },
    ] };
    expect(await store.runOptimization([], { planConfirmation })).toBe(true);
    expect(vi.mocked(optimizePrompt).mock.calls.map(([request]) => request.enhancement.promptFormat))
      .toEqual([format, format]);
    expect(vi.mocked(optimizePrompt).mock.lastCall?.[0].planConfirmation).toEqual(planConfirmation);
  });

  it('loads new history as original input and keeps server rule ownership after re-enhancing and editing', async () => {
    const store = useOptimizationStore();
    const source = fixture('COMPACT');
    source.constraintGroups = { platformMandatory: ['不得泄露凭据。'], taskSpecific: [], additionalPermissions: [] };
    source.sections = source.sections.map(section => section.type === 'CONSTRAINTS'
      ? { ...section, content: '不得泄露凭据。' } : section);
    source.optimizedPrompt = renderPromptSections(source.sections, 'COMPACT');
    const record = { ...history('COMPACT'), constraintGroups: source.constraintGroups,
      sections: source.sections, optimizedPrompt: source.optimizedPrompt };
    const recorded = JSON.stringify(record);
    store.loadFromHistory(record);
    expect(store.result).toBeUndefined();
    expect(store.rawPrompt).toBe(record.rawPrompt);
    vi.mocked(optimizePrompt).mockResolvedValue({ requestId: 'history-regeneration', data: source });
    expect(await store.runOptimization([])).toBe(true);
    expect(store.saveEditedSections(source.sections.map(section => section.type === 'CONSTRAINTS'
      ? { ...section, content: '新编辑保留。' } : section))).toBe(true);
    expect(store.result?.constraintGroups).toEqual(source.constraintGroups);
    expect(store.result?.optimizedPrompt).toContain('不得泄露凭据。');
    expect(store.result?.optimizedPrompt).not.toContain('平台强制约束');
    expect(JSON.stringify(record)).toBe(recorded);
  });

  it('retains the complete edit draft and current result when an unclosed constraint fence would swallow required rules', () => {
    const store = useOptimizationStore();
    const source = fixture('COMPACT');
    source.constraintGroups = { platformMandatory: ['不得泄露凭据。'], taskSpecific: [], additionalPermissions: [] };
    store.result = source;
    const draft = source.sections.filter(section => section.type !== 'CLARIFICATIONS').map(section => ({ ...section,
      content: section.type === 'CONSTRAINTS' ? '```text\n尚未闭合' : section.content }));
    store.resultDraft = draft;
    expect(store.saveEditedSections(draft)).toBe(false);
    expect(store.errorMessage).toContain('必要规则无法安全恢复');
    expect(store.result?.optimizedPrompt).toBe(source.optimizedPrompt);
    expect(store.resultDraft).toEqual(draft);
    expect(store.canUndoResult).toBe(false);
    const repaired = draft.map(section => section.type === 'CONSTRAINTS'
      ? { ...section, content: `${section.content}\n\`\`\`` } : section);
    expect(store.saveEditedSections(repaired)).toBe(true);
    expect(store.result?.optimizedPrompt).toContain('不得泄露凭据。');
    expect(store.result?.optimizedPrompt).not.toContain('平台强制约束');
  });

  it.each(['COMPACT', 'STRUCTURED'] as const)('protects required rules from preceding task and output fences in %s', format => {
    const store = useOptimizationStore();
    const source = fixture(format);
    source.constraintGroups = { platformMandatory: ['不得泄露凭据。'], taskSpecific: [], additionalPermissions: [] };
    store.result = source;
    for (const type of ['TASK', 'OUTPUT'] as const) {
      const draft = source.sections.filter(section => section.type !== 'CLARIFICATIONS').map(section => ({ ...section,
        content: section.type === type ? '````text\n```\n外层尚未闭合' : section.content }));
      store.resultDraft = draft;
      expect(store.saveEditedSections(draft)).toBe(false);
      expect(store.errorMessage).toContain('后续必要规则可能被包入代码块');
      expect(store.resultDraft).toEqual(draft);
      expect(store.result?.optimizedPrompt).toBe(source.optimizedPrompt);
      const fixed = draft.map(section => section.type === type
        ? { ...section, content: `${section.content}\n\`\`\`\`` } : section);
      expect(store.saveEditedSections(fixed)).toBe(true);
      expect(store.result?.optimizedPrompt).toContain('不得泄露凭据。');
      store.undoResult();
    }
    const after = source.sections.filter(section => section.type !== 'CLARIFICATIONS');
    after.push({ type: 'EXAMPLES', title: '示例', content: '```text\n规则之后的未闭合片段' });
    expect(store.saveEditedSections(after)).toBe(true);
    expect(store.result?.optimizedPrompt).toContain('不得泄露凭据。');
  });

  it('changes only the next generation preference, retaining current result, Plan and draft', () => {
    const store = useOptimizationStore();
    const original = fixture();
    store.result = original;
    store.plan = { summary: '已确认', questions: [], templateCode: 'GENERAL', provider: original.provider, latencyMs: 1 };
    store.resultDraft = original.sections.map(section => ({ ...section, content: `${section.content}编辑草稿` }));
    const draft = store.resultDraft;
    const plan = store.plan;
    const context = store.captureContext();
    expect(store.setPromptFormat('STRUCTURED')).toBe(true);
    expect(store.result?.optimizedPrompt).toBe(original.optimizedPrompt);
    expect(store.result?.promptFormat).toBe('COMPACT');
    expect(store.hasPendingPromptFormat).toBe(true);
    expect(store.result?.sections).toEqual(original.sections);
    expect(store.result?.appliedConstraints).toEqual(original.appliedConstraints);
    expect(store.resultDraft).toBe(draft);
    expect(store.plan).toBe(plan);
    expect(store.captureContext()).toEqual(context);
    expect(store.setPromptFormat('COMPACT')).toBe(true);
    expect(store.hasPendingPromptFormat).toBe(false);
    expect(store.result?.optimizedPrompt).toBe(original.optimizedPrompt);
    expect(store.result?.optimizedPrompt).toContain('```md\n## 代码中的标题\n```');
    expect(store.result?.optimizedPrompt).toContain('新账号需复核；旧账号例外。');
    expect(store.result?.optimizedPrompt).not.toContain('## 待确认');
    expect(optimizePrompt).not.toHaveBeenCalled();
    expect(store.undoResult()).toBe(false);
    expect(store.resultDraft).toBe(draft);
  });

  it('retains selected form while editing, restoring protected rules and undoing content', () => {
    const store = useOptimizationStore();
    store.result = fixture();
    store.setPromptFormat('STRUCTURED');
    const sections = store.result!.sections.map(section => ({ ...section, content: section.type === 'CONSTRAINTS'
      ? '保留新条件。' : section.type === 'TASK' ? '编辑后的任务。' : section.content }));
    expect(store.saveEditedSections(sections)).toBe(true);
    expect(store.result?.optimizedPrompt).toContain('编辑后的任务。');
    expect(store.result?.optimizedPrompt).not.toContain('## 任务');
    expect(store.result?.optimizedPrompt).toContain('不得泄露凭据。');
    expect(store.undoResult()).toBe(true);
    expect(store.promptFormat).toBe('COMPACT');
    expect(store.result?.optimizedPrompt).toContain('实现约定接口。');
  });

  it('explicitly regenerates with only format changed, preserves exact source and confirmed Plan, and supports undo', async () => {
    const store = useOptimizationStore();
    store.rawPrompt = '原始需求：实现登录';
    const confirmation = { planId: 'same-plan', answers: [
      { questionId: 'login', question: '新入口登录方式？', answer: '邮箱密码' },
    ] };
    const sourceFile = { path: 'rules.md', language: 'markdown', content: '原始资料。' };
    vi.mocked(optimizePrompt).mockResolvedValueOnce({ requestId: 'first', data: fixture() })
      .mockResolvedValueOnce({ requestId: 'second', data: fixture('STRUCTURED') });
    expect(await store.runOptimization([sourceFile], { planConfirmation: confirmation })).toBe(true);
    const firstRequest = JSON.parse(JSON.stringify(vi.mocked(optimizePrompt).mock.calls[0]![0]));
    // 外部对象后续变化不能改写已经成功发送的来源快照。
    sourceFile.content = '不得混入的后续对象变化';
    confirmation.answers[0]!.answer = '不得混入的未提交选择';
    store.setPromptFormat('STRUCTURED');
    expect(store.hasPendingPromptFormat).toBe(true);
    expect(await store.regenerateWithPromptFormat()).toBe(true);
    const second = vi.mocked(optimizePrompt).mock.calls[1]![0];
    expect(second).toEqual({ ...firstRequest, enhancement: { ...firstRequest.enhancement, promptFormat: 'STRUCTURED' } });
    expect(store.result?.promptFormat).toBe('STRUCTURED');
    expect(store.hasPendingPromptFormat).toBe(false);
    expect(store.undoResult()).toBe(true);
    expect(store.result?.promptFormat).toBe('COMPACT');
    expect(store.promptFormat).toBe('COMPACT');
    expect(store.canReuseGenerationSource).toBe(true);
  });

  it.each(['raw', 'context', 'model', 'task'] as const)('does not reuse a source after %s changes', async change => {
    const store = useOptimizationStore();
    store.rawPrompt = '旧需求';
    vi.mocked(optimizePrompt).mockResolvedValue({ requestId: 'first', data: fixture() });
    await store.runOptimization([], { planConfirmation: { planId: 'old-plan', answers: [] } });
    if (change === 'raw') store.rawPrompt = '新需求';
    else if (change === 'context') store.addFile({ path: 'new.md', content: '新资料', language: 'text' });
    else if (change === 'model') store.selectedModelId = 'new-model';
    else store.resetTask();
    store.setPromptFormat('STRUCTURED');
    expect(await store.regenerateWithPromptFormat()).toBe(false);
    expect(optimizePrompt).toHaveBeenCalledTimes(1);
    expect(store.canReuseGenerationSource).toBe(false);
  });

  it('blocks unsaved edits and keeps saved edits available through undo after regeneration', async () => {
    const store = useOptimizationStore();
    store.rawPrompt = '需求';
    vi.mocked(optimizePrompt).mockResolvedValueOnce({ requestId: 'first', data: fixture() })
      .mockResolvedValueOnce({ requestId: 'second', data: fixture('STRUCTURED') });
    await store.runOptimization([]);
    const draft = store.result!.sections.filter(section => section.type !== 'CLARIFICATIONS')
      .map(section => ({ ...section, content: section.type === 'TASK' ? '手工修改保留。' : section.content }));
    store.resultDraft = draft;
    store.setPromptFormat('STRUCTURED');
    expect(await store.regenerateWithPromptFormat()).toBe(false);
    expect(store.resultDraft).toEqual(draft);
    expect(optimizePrompt).toHaveBeenCalledTimes(1);
    expect(store.saveEditedSections(draft)).toBe(true);
    expect(await store.regenerateWithPromptFormat()).toBe(true);
    expect(store.undoResult()).toBe(true);
    expect(store.result?.optimizedPrompt).toContain('手工修改保留。');
  });

  it.each(['failure', 'expired'] as const)('keeps previous result and selected mode after %s', async kind => {
    const store = useOptimizationStore();
    store.rawPrompt = '需求';
    vi.mocked(optimizePrompt).mockResolvedValueOnce({ requestId: 'first', data: fixture() });
    await store.runOptimization([], { planConfirmation: { planId: 'kept-plan', answers: [] } });
    const original = store.result;
    const failure = new AxiosError('Request failed', 'ERR_BAD_RESPONSE');
    failure.response = { status: kind === 'expired' ? 409 : 502, statusText: 'Failure', headers: {}, config: failure.config!,
      data: { requestId: 'failed', error: { code: kind === 'expired' ? 'PLANNING_SESSION_EXPIRED' : 'RESULT_INVALID',
        message: '本次增强未完成。', retryable: false } } };
    vi.mocked(optimizePrompt).mockRejectedValueOnce(failure);
    store.setPromptFormat('STRUCTURED');
    expect(await store.regenerateWithPromptFormat()).toBe(false);
    expect(store.result).toBe(original);
    expect(store.promptFormat).toBe('STRUCTURED');
    expect(store.hasPendingPromptFormat).toBe(true);
    expect(store.planningSessionExpired).toBe(kind === 'expired');
    expect(store.modeRegenerationRawPrompt).toBe('需求');
    expect(store.isOptimizing).toBe(false);
  });

  it.each(['COMPACT', 'STRUCTURED', undefined] as const)('restores %s historical form and resets a new task to compact', format => {
    const store = useOptimizationStore();
    const original = history(format);
    store.loadFromHistory(original);
    expect(store.promptFormat).toBe(format ?? 'STRUCTURED');
    expect(original.promptFormat).toBe(format);
    store.resetTask();
    expect(store.promptFormat).toBe('COMPACT');
  });

  it('locks format during pending work and applies the returned recorded format', async () => {
    const store = useOptimizationStore();
    store.rawPrompt = '任务';
    let complete!: (value: { requestId: string; data: OptimizationResult }) => void;
    vi.mocked(optimizePrompt).mockImplementation(() => new Promise(resolve => { complete = resolve; }));
    const pending = store.runOptimization([]);
    await vi.waitFor(() => expect(optimizePrompt).toHaveBeenCalledOnce());
    expect(store.setPromptFormat('STRUCTURED')).toBe(false);
    expect(store.promptFormat).toBe('COMPACT');
    complete({ requestId: 'completed', data: fixture() });
    expect(await pending).toBe(true);
    expect(store.promptFormat).toBe('COMPACT');
    store.applyReoptimized({ recordId: 'another', result: fixture('STRUCTURED') });
    expect(store.promptFormat).toBe('STRUCTURED');
  });
});
