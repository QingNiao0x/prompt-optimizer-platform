import { expect, test, type Page } from '@playwright/test';
import type { OptimizationHistoryDetail, OptimizationRequest, OptimizationResult, PromptFormat } from '../src/types/api';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

const rawPrompt = '给合作方写一封简短邮件，请确认周五能否交付；日期不能擅自更改。';
const rule = '明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。';
const regenerateButton = '基于原始需求按所选模式重新增强';
const resultFor = (format: PromptFormat, answer = ''): OptimizationResult => {
  const sections: OptimizationResult['sections'] = [
    { type: 'BACKGROUND', title: '背景', content: '合作方交付沟通。' },
    { type: 'TASK', title: '任务', content: `${format === 'STRUCTURED'
      ? '围绕原定周五交付组织邮件，分别表达按时交付与遇到困难时回复预计时间的要求。'
      : '写一封简短邮件，请确认周五能否交付。'}${answer}` },
    { type: 'OUTPUT', title: '输出', content: '只交付邮件正文。' },
    { type: 'CONSTRAINTS', title: '约束', content: `日期不能擅自更改。\n\n${format === 'STRUCTURED' ? '- ' : ''}${rule}` },
  ];
  return { promptFormat: format, sections, optimizedPrompt: sections.map(section => format === 'COMPACT'
    ? section.content : `## ${section.title}\n${section.content}`).join('\n\n'),
    contextReport: { customDescription: '', technologyStack: [], dependencies: [], directoryTree: [],
      fileSnippets: [], warnings: [], redactions: [], analysisVersion: 'controlled-fixture' },
    ambiguities: [], appliedConstraints: [rule],
    constraintGroups: { platformMandatory: [rule], taskSpecific: [], additionalPermissions: [] }, templateCode: 'GENERAL',
    provider: { provider: 'mock', model: 'controlled-fixture', mock: true }, latencyMs: 1 };
};

/** 仅模拟业务接口与已有受控身份；所有模型响应为固定夹具，不创建账号或发送真实模型请求。 */
const prepare = async (page: Page, plan = false) => {
  await mockAuthentication(page);
  await page.addInitScript(enabled => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled, introSeen: true }));
  }, plan);
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'mock', displayName: '受控测试模型', provider: 'mock', defaultModel: true },
  ] } }));
  await page.route('**/api/v1/optimizations/plan-events', route => route.fulfill({ status: 204 }));
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
};
const switchFormat = async (page: Page) => {
  await openWorkbenchPane(page, 'intent');
  // Element Plus 的 role=switch 位于透明 input；点击可见标签，沿用工作台已有开关交互。
  await page.locator('label.switch-control').filter({ hasText: '结构化提示词' }).click();
};
const copiedText = async (page: Page): Promise<string> => {
  await openWorkbenchPane(page, 'result');
  await page.bringToFront();
  await page.locator('.copy-main-button').click();
  await expect(page.locator('.copy-main-button')).toContainText('已复制');
  // Windows 剪贴板将换行存为 CRLF；只还原换行，不修剪正文、代码缩进或其他空白。
  return (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n');
};

test('开关只选择下次增强模式；显式重新生成失败保旧稿、成功可撤销，真实复制当前结果', async ({ page }) => {
  await prepare(page);
  const requests: OptimizationRequest[] = [];
  await page.route('**/api/v1/optimizations', route => {
    const request = route.request().postDataJSON() as OptimizationRequest;
    requests.push(request);
    if (requests.length === 2) return route.fulfill({ status: 502, json: { requestId: 'controlled-failure',
      error: { code: 'RESULT_INVALID', message: '本次增强未完成。', retryable: false } } });
    return route.fulfill({ json: { data: resultFor(request.enhancement.promptFormat!) } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  const toggle = page.getByRole('switch', { name: '结构化提示词', exact: true });
  await expect(toggle).toHaveAttribute('aria-checked', 'false');
  await page.getByLabel('原始提示词', { exact: true }).fill(rawPrompt);
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  await openWorkbenchPane(page, 'result');
  await expect(page.getByTestId('compact-prompt')).toHaveText(resultFor('COMPACT').optimizedPrompt);
  expect(requests[0]!.enhancement.promptFormat).toBe('COMPACT');

  await switchFormat(page);
  await openWorkbenchPane(page, 'result');
  await expect(page.getByTestId('compact-prompt')).toHaveText(resultFor('COMPACT').optimizedPrompt);
  await expect(page.getByTestId('mode-change-note')).toContainText('模式已更改');
  expect(await copiedText(page)).toBe(resultFor('COMPACT').optimizedPrompt);
  expect(requests).toHaveLength(1);
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await page.locator('#section-TASK').fill('写简短邮件；保留周五，并请对方回复可行时间。');
  await page.locator('#section-CONSTRAINTS').fill('日期不能擅自更改。新客户需复核；旧客户例外。');
  await expect(page.getByRole('button', { name: regenerateButton, exact: true })).toBeDisabled();
  await switchFormat(page);
  await switchFormat(page);
  await openWorkbenchPane(page, 'result');
  await expect(page.locator('#section-TASK')).toHaveValue('写简短邮件；保留周五，并请对方回复可行时间。');
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  const copied = await copiedText(page);
  expect(copied).toContain('保留周五，并请对方回复可行时间。');
  expect(copied).toContain(rule);
  expect(copied).toContain('新客户需复核；旧客户例外。');
  expect(copied).not.toContain('平台强制约束');
  expect(copied).not.toContain('## 任务');
  expect(requests).toHaveLength(1);

  await page.getByRole('button', { name: regenerateButton, exact: true }).click();
  await expect.poll(() => requests.length).toBe(2);
  expect(requests[1]!.enhancement.promptFormat).toBe('STRUCTURED');
  await expect(page.getByText('本次增强未完成。', { exact: true })).toBeVisible();
  expect(await copiedText(page)).toBe(copied);
  await page.getByRole('button', { name: regenerateButton, exact: true }).click();
  await expect.poll(() => requests.length).toBe(3);
  expect(requests[2]!).toEqual({ ...requests[0]!, enhancement: { ...requests[0]!.enhancement, promptFormat: 'STRUCTURED' } });
  await expect(page.locator('.section-list')).toBeVisible();
  expect(await copiedText(page)).toBe(resultFor('STRUCTURED').optimizedPrompt);
  await page.getByRole('button', { name: '撤销', exact: true }).click();
  expect(await copiedText(page)).toBe(copied);
  await openWorkbenchPane(page, 'intent');
  await expect(toggle).toHaveAttribute('aria-checked', 'false');
  await page.getByLabel('原始提示词', { exact: true }).fill('另一项新任务');
  await switchFormat(page);
  await openWorkbenchPane(page, 'result');
  await expect(page.getByRole('button', { name: regenerateButton, exact: true })).toBeDisabled();
  expect(requests).toHaveLength(3);
});

for (const format of ['COMPACT', 'STRUCTURED'] as const) {
test(`Plan用户选择${format}生成后，显式模式重新增强复用同一真实确认及原始需求`, async ({ page }) => {
  await prepare(page, true);
  let planCalls = 0;
  const requests: OptimizationRequest[] = [];
  let recorded = resultFor(format);
  const planRawPrompt = `${rawPrompt}是否在邮件中承诺延期赔偿，由我确认。`;
  const answer = '不承诺延期赔偿，仅询问交付时间';
  await page.route('**/api/v1/optimizations/plan', route => {
    planCalls++;
    return route.fulfill({ json: { data: { summary: '确认沟通要求', templateCode: 'GENERAL',
      planId: 'existing-controlled-plan', latencyMs: 1, provider: recorded.provider,
      // 固定服务端问题仅验证前端题答闭环，不作为自然 Plan 提问必要性的判定证据。
      questions: [{ id: 'compensation', question: '是否在邮件中承诺延期赔偿？', hint: '该业务承诺由你决定。', type: 'FREE_TEXT',
        options: [], examples: [], allowCustomAnswer: true }] } } });
  });
  await page.route('**/api/v1/optimizations', route => {
    const request = route.request().postDataJSON() as OptimizationRequest;
    requests.push(request);
    recorded = resultFor(request.enhancement.promptFormat!, request.planConfirmation?.answers[0]?.answer ?? '');
    return route.fulfill({ json: { data: recorded } });
  });
  const history = (): OptimizationHistoryDetail => ({ id: 'existing-record', rawPrompt: planRawPrompt, ...recorded,
    contextSummary: {}, providerName: 'mock', modelName: 'controlled-fixture', mock: true, latencyMs: 1,
    createdAt: '2026-10-18T00:00:00Z', includePermissionBoundaries: true, includeExamples: false,
    conversationHistory: [], permissionPolicy: {} });
  await page.route('**/api/v1/optimization-history**', route => {
    const isDetail = new URL(route.request().url()).pathname.endsWith('/existing-record');
    return route.fulfill({ json: { data: isDetail ? history() : { current: 1, size: 10, total: requests.length, pages: 1,
      records: [{ id: 'existing-record', templateCode: 'GENERAL', rawPromptPreview: planRawPrompt,
        providerName: 'mock', modelName: 'controlled-fixture', mock: true, latencyMs: 1, createdAt: '2026-10-18T00:00:00Z' }] } } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  if (format === 'STRUCTURED') await switchFormat(page);
  await page.getByLabel('原始提示词', { exact: true }).fill(planRawPrompt);
  await page.getByRole('button', { name: '先确认并增强', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '确认关键细节' });
  await dialog.getByLabel('填写回答').fill(answer);
  await dialog.getByRole('button', { name: '生成最终提示词', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(requests[0]!.enhancement.promptFormat).toBe(format);
  expect(requests[0]!.planConfirmation?.answers[0]?.answer).toBe(answer);
  expect(await copiedText(page)).toBe(recorded.optimizedPrompt);
  await switchFormat(page);
  expect(planCalls).toBe(1);
  expect(requests).toHaveLength(1);
  expect(await copiedText(page)).toBe(recorded.optimizedPrompt);
  await page.getByRole('button', { name: regenerateButton, exact: true }).click();
  await expect.poll(() => requests.length).toBe(2);
  const changedFormat = format === 'STRUCTURED' ? 'COMPACT' : 'STRUCTURED';
  expect(requests[1]).toEqual({ ...requests[0]!, enhancement: { ...requests[0]!.enhancement, promptFormat: changedFormat } });
  expect(planCalls).toBe(1);
  expect(await copiedText(page)).toBe(recorded.optimizedPrompt);
  expect(recorded.optimizedPrompt).toContain(answer);
  expect(recorded.optimizedPrompt).toContain(rule);
  await page.getByRole('button', { name: '撤销', exact: true }).click();

  // 新模式只有生成成功后才形成新历史；撤销不会改写已保存的生成记录。
  await page.goto('/history');
  await page.getByRole('button', { name: '查看', exact: true }).filter({ visible: true }).click();
  await expect(page.getByRole('dialog', { name: '历史记录详情' }).locator('pre').nth(1)).toHaveText(recorded.optimizedPrompt);
  await page.getByRole('dialog', { name: '历史记录详情' }).getByRole('button', { name: 'Close this dialog' }).click();
  await page.getByRole('button', { name: '载入', exact: true }).filter({ visible: true }).click();
  await openWorkbenchPane(page, 'intent');
  await expect(page.getByRole('switch', { name: '结构化提示词', exact: true })).toHaveAttribute('aria-checked', String(changedFormat === 'STRUCTURED'));
  await expect(page.getByLabel('原始提示词', { exact: true })).toHaveValue(planRawPrompt);
  expect(planCalls).toBe(1);
});
}

test('模式重新增强遇到Plan过期时保留原结果及回答，恢复真实问题后再生成', async ({ page }) => {
  await prepare(page, true);
  const planRawPrompt = `${rawPrompt}是否承诺延期赔偿由我确认。`;
  const answer = '不承诺赔偿，只询问时间';
  let planCalls = 0;
  const requests: OptimizationRequest[] = [];
  await page.route('**/api/v1/optimizations/plan', route => {
    planCalls++;
    return route.fulfill({ json: { data: { summary: '确认业务承诺', templateCode: 'GENERAL',
      planId: `plan-${planCalls}`, latencyMs: 1, provider: resultFor('COMPACT').provider,
      questions: [{ id: 'compensation', question: '是否承诺延期赔偿？', hint: '由用户决定', type: 'FREE_TEXT',
        options: [], examples: [], allowCustomAnswer: true }] } } });
  });
  await page.route('**/api/v1/optimizations', route => {
    const request = route.request().postDataJSON() as OptimizationRequest;
    requests.push(request);
    if (requests.length === 2) return route.fulfill({ status: 409, json: { requestId: 'expired',
      error: { code: 'PLANNING_SESSION_EXPIRED', message: '确认已过期。', retryable: false } } });
    return route.fulfill({ json: { data: resultFor(request.enhancement.promptFormat!, answer) } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  await page.getByLabel('原始提示词', { exact: true }).fill(planRawPrompt);
  await page.getByRole('button', { name: '先确认并增强', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '确认关键细节' });
  await dialog.getByLabel('填写回答').fill(answer);
  await dialog.getByRole('button', { name: '生成最终提示词', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await switchFormat(page);
  await openWorkbenchPane(page, 'result');
  await page.getByRole('button', { name: regenerateButton, exact: true }).click();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel('填写回答')).toHaveValue(answer);
  await expect(page.getByTestId('compact-prompt')).toHaveText(resultFor('COMPACT', answer).optimizedPrompt);
  expect(requests).toHaveLength(2);
  expect(planCalls).toBe(2);
  await dialog.getByRole('button', { name: '生成最终提示词', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(requests[2]?.rawPrompt).toBe(planRawPrompt);
  expect(requests[2]?.planConfirmation?.planId).toBe('plan-2');
  expect(requests[2]?.planConfirmation?.answers[0]?.answer).toBe(answer);
  expect(requests[2]?.enhancement.promptFormat).toBe('STRUCTURED');
  expect(await copiedText(page)).toBe(resultFor('STRUCTURED', answer).optimizedPrompt);
});

test('旧记录缺省恢复分节，生成中禁止切换避免迟到响应覆盖', async ({ page }) => {
  await prepare(page);
  const old = resultFor('STRUCTURED');
  const { promptFormat: ignored, constraintGroups: oldGroups, ...legacy } = old;
  void ignored;
  void oldGroups;
  legacy.sections = old.sections.map(section => section.type === 'CONSTRAINTS' ? { ...section,
    content: `日期不能擅自更改。\n平台强制约束（不得删除或弱化）：\n- ${rule}` } : section);
  legacy.optimizedPrompt = legacy.sections.map(section => `## ${section.title}\n${section.content}`).join('\n\n');
  await page.route('**/api/v1/optimization-history**', route => route.fulfill({ json: { data:
    new URL(route.request().url()).pathname.endsWith('/old')
      ? { id: 'old', rawPrompt, ...legacy, contextSummary: {}, providerName: 'mock', modelName: 'old',
        createdAt: '2026-10-17T00:00:00Z', includePermissionBoundaries: true, includeExamples: false,
        conversationHistory: [], permissionPolicy: {} }
      : { records: [{ id: 'old', templateCode: 'GENERAL', rawPromptPreview: rawPrompt,
        providerName: 'mock', modelName: 'old', mock: true, createdAt: '2026-10-17T00:00:00Z' }], total: 1, size: 10, current: 1, pages: 1 } } }));
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  let requested: OptimizationRequest | undefined;
  await page.route('**/api/v1/optimizations', async route => {
    requested = route.request().postDataJSON() as OptimizationRequest;
    await pending;
    await route.fulfill({ json: { data: old } });
  });
  await page.goto('/history');
  await page.getByRole('button', { name: '载入', exact: true }).filter({ visible: true }).click();
  await openWorkbenchPane(page, 'intent');
  const toggle = page.getByRole('switch', { name: '结构化提示词', exact: true });
  await expect(toggle).toHaveAttribute('aria-checked', 'true');
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  await expect.poll(() => requested?.enhancement.promptFormat).toBe('STRUCTURED');
  await expect(toggle).toBeDisabled();
  release();
  await openWorkbenchPane(page, 'result');
  await expect(page.locator('.section-list')).toBeVisible();
});

for (const format of ['COMPACT', 'STRUCTURED'] as const) {
test(`${format}代码规则依服务端来源恢复；未闭合围栏保留草稿，修正后复制五条完整规则`, async ({ page }) => {
  await prepare(page);
  const rules = [rule, '不得泄露密码、Token或API Key。', '保留原定交付格式。', '不得读取受保护路径。', '高风险操作必须人工确认。'];
  const permission = '导出真实用户数据前须人工确认。';
  const generated = resultFor(format);
  generated.appliedConstraints = [...rules, permission];
  generated.constraintGroups = { platformMandatory: rules, taskSpecific: [], additionalPermissions: [permission] };
  generated.sections = generated.sections.map(section => section.type === 'CONSTRAINTS'
    ? { ...section, content: [...rules, permission].map(rule => `- ${rule}`).join('\n') } : section);
  generated.optimizedPrompt = generated.sections.map(section => format === 'COMPACT'
    ? section.content : `## ${section.title}\n${section.content}`).join('\n\n');
  let calls = 0;
  await page.route('**/api/v1/optimizations', route => {
    calls++;
    return route.fulfill({ json: { data: generated } });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'intent');
  if (format === 'STRUCTURED') await switchFormat(page);
  await page.getByLabel('原始提示词', { exact: true }).fill('使用requests库爬取网页标题。');
  await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  expect(await copiedText(page)).toBe(generated.optimizedPrompt);
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await page.locator('#section-TASK').fill('实现可运行代码并保留新的任务要求。');
  await page.locator('#section-CONSTRAINTS').fill('```text\n未闭合的用户草稿');
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  await expect(page.locator('.edit-error')).toHaveText('约束条件中的代码围栏尚未闭合，必要规则无法安全恢复。请补齐围栏后保存，其他修改已保留。');
  await expect(page.locator('#section-TASK')).toHaveValue('实现可运行代码并保留新的任务要求。');
  await expect(page.locator('#section-CONSTRAINTS')).toHaveValue('```text\n未闭合的用户草稿');
  await page.locator('#section-CONSTRAINTS').fill('仅调试时：\n- 不得泄露密码、Token或API Key。');
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  const copied = await copiedText(page);
  expect(copied).not.toContain('平台强制约束');
  expect(copied).toContain('约束条件：');
  expect(copied).toContain('仅调试时：\n- 不得泄露密码、Token或API Key。');
  for (const rule of [...rules, permission]) expect(copied).toContain(rule);
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  expect(await copiedText(page)).toBe(copied);
  expect(calls).toBe(1);
});
}
