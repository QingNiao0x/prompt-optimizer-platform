import { expect, test, type Page } from '@playwright/test';
import type { OptimizationRequest, OptimizationResult } from '../src/types/api';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

const rule = '明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。';
const raw = '写一封发给客户的项目延期邮件，新日期由我确认，不承诺额外赔偿。';
const fixture = (): OptimizationResult => ({
  resultKind: 'TEXT', generationPipeline: 'NATURAL', chainVersion: '1', framework: 'ICIO',
  recordId: '11111111-1111-4111-8111-111111111111', editRevision: 0,
  optimizedPrompt: '请写一封诚恳的客户延期邮件，保留新日期未定的状态，不承诺赔偿。\n\n' + rule,
  sections: [], promptFormat: 'COMPACT', templateCode: 'GENERAL',
  contextReport: { customDescription: '', technologyStack: [], dependencies: [], directoryTree: [],
    fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'fixture' },
  ambiguities: [], appliedConstraints: [rule],
  constraintGroups: { platformMandatory: [rule], taskSpecific: [], additionalPermissions: [] },
  provider: { provider: 'mock', model: 'fixture', mock: true }, latencyMs: 1,
});

/** 固定响应验证浏览器闭环；路由白名单阻止测试误发真实模型请求，不创建用户账号。 */
const setup = async (page: Page, planning: 'off' | 'zero' | 'question') => {
  await mockAuthentication(page);
  await page.addInitScript(enabled => localStorage.setItem('prompt-optimizer.plan-mode.v1',
    JSON.stringify({ enabled, introSeen: true })), planning !== 'off');
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'mock', displayName: '受控测试模型', provider: 'mock', defaultModel: true },
  ] } }));
  await page.route('**/api/v1/optimizations/plan-events', route => route.fulfill({ status: 204 }));
  const state = { current: fixture(), requests: [] as OptimizationRequest[], plans: 0, edits: 0, failSave: false };
  await page.route('**/api/v2/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v2/optimizations/plan') {
      state.plans++;
      return route.fulfill({ json: { data: {
        summary: '仅确认用户保留的交付日期', templateCode: 'AUTO', provider: fixture().provider,
        latencyMs: 1, planId: '22222222-2222-4222-8222-222222222222',
        questions: planning === 'zero' ? [] : [{ id: 'date', question: '如何处理新日期？', hint: '影响邮件承诺',
          type: 'FREE_TEXT', options: [], examples: [], allowCustomAnswer: true }],
      } } });
    }
    if (path === '/api/v2/optimizations') {
      const input = route.request().postDataJSON() as OptimizationRequest;
      state.requests.push(input);
      const current = fixture();
      if (input.planConfirmation?.answers.length) current.optimizedPrompt += '\n' + input.planConfirmation.answers[0]!.answer;
      state.current = current;
      // 原生TEXT协议省略sections，验证API适配器不会要求模型制造四段。
      return route.fulfill({ json: { data: { ...current, sections: undefined } } });
    }
    if (path.endsWith('/document')) {
      state.edits++;
      if (state.failSave) return route.fulfill({ status: 409,
        json: { error: { code: 'VERSION_CONFLICT', message: '记录版本已变化，请保留修改稿后核对。' } } });
      const input = route.request().postDataJSON() as { optimizedPrompt: string; expectedRevision: number };
      expect(input.expectedRevision).toBe(state.current.editRevision);
      state.current = { ...state.current, optimizedPrompt: input.optimizedPrompt, editRevision: input.expectedRevision + 1 };
      return route.fulfill({ json: { data: { optimizedPrompt: state.current.optimizedPrompt, editRevision: state.current.editRevision } } });
    }
    if (path === '/api/v2/optimization-history') {
      return route.fulfill({ json: { data: { records: [{ id: state.current.recordId,
        rawPromptPreview: raw, templateCode: 'GENERAL', providerName: 'mock', modelName: 'fixture', mock: true,
        latencyMs: 1, createdAt: '2026-10-19T00:00:00Z', resultKind: 'TEXT' }], total: 1, current: 1, size: 10, pages: 1 } } });
    }
    if (path.startsWith('/api/v2/optimization-history/')) {
      return route.fulfill({ json: { data: { ...state.current, id: state.current.recordId, rawPrompt: raw,
        contextSummary: {}, includeExamples: false, includePermissionBoundaries: true, conversationHistory: [],
        permissionPolicy: {}, providerName: 'mock', modelName: 'fixture', mock: true,
        latencyMs: 1, createdAt: '2026-10-19T00:00:00Z' } } });
    }
    return route.fulfill({ status: 400, json: { error: { message: '测试未授权此接口' } } });
  });
  return state;
};

const generate = async (page: Page, plan = false) => {
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词', { exact: true }).fill(raw);
  await page.getByRole('button', { name: plan ? '先确认并增强' : '直接增强提示词', exact: true }).click();
};

test('自然正文编辑、失败保稿、保存复制和持久化撤销保持同一有效内容', async ({ page }) => {
  const state = await setup(page, 'off');
  await generate(page);
  await openWorkbenchPane(page, 'result');
  await expect(page.getByTestId('compact-prompt')).toHaveText(state.current.optimizedPrompt);
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  const input = page.getByLabel('编辑完整自然提示词', { exact: true });
  await expect(input).toHaveValue(state.current.optimizedPrompt);
  await expect(page.locator('#section-TASK')).toHaveCount(0);
  const edit = '请交付客户延期邮件主题与正文，日期待定，保留不赔偿的选择。';
  await input.fill(edit);
  state.failSave = true;
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  await expect(input).toHaveValue(edit);
  await expect(page.locator('.edit-error')).toHaveText('记录版本已变化，请保留修改稿后核对。');
  state.failSave = false;
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  await expect(page.getByTestId('compact-prompt')).toHaveText(edit + '\n\n' + rule);
  await page.bringToFront();
  await page.locator('.copy-main-button').click();
  await expect(page.locator('.copy-main-button')).toContainText('已复制');
  const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n');
  expect(copied).toBe(state.current.optimizedPrompt);
  const detail = await page.evaluate(async () => (await fetch('/api/v2/optimization-history/11111111-1111-4111-8111-111111111111')).json());
  expect(detail.data.optimizedPrompt).toBe(copied);
  await page.getByRole('button', { name: '撤销', exact: true }).click();
  await expect(page.getByTestId('compact-prompt')).toHaveText(fixture().optimizedPrompt);
  expect(state.edits).toBe(3);
  await page.goto('/history');
  await page.getByRole('button', { name: '查看', exact: true }).filter({ visible: true }).click();
  await expect(page.getByRole('dialog', { name: '历史记录详情' }).locator('pre').nth(1)).toHaveText(fixture().optimizedPrompt);
});

for (const mode of ['zero', 'question'] as const) {
  test('新Plan完整适配自然正文：' + mode, async ({ page }) => {
    const state = await setup(page, mode);
    await generate(page, true);
    if (mode === 'question') {
      const dialog = page.getByRole('dialog', { name: '确认关键细节' });
      await dialog.getByLabel('填写回答').fill('交付日期尚未确定，不得编造日期。');
      await dialog.getByRole('button', { name: '生成最终提示词', exact: true }).click();
      await expect(dialog).not.toBeVisible();
    }
    await openWorkbenchPane(page, 'result');
    await expect(page.getByTestId('compact-prompt')).toHaveText(state.current.optimizedPrompt);
    expect(state.plans).toBe(1);
    expect(state.requests).toHaveLength(1);
    expect(state.requests[0]!.planConfirmation?.planId).toBe('22222222-2222-4222-8222-222222222222');
    expect(state.requests[0]!.planConfirmation?.answers).toHaveLength(mode === 'zero' ? 0 : 1);
    expect(state.requests[0]!.enhancement.promptFormat).toBe('COMPACT');
  });
}
