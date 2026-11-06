import { expect, test } from '@playwright/test';
import type { ContextSnapshot, OptimizationRequest, OptimizationResult } from '../src/types/api';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

const originalFiles = [
  { path: 'requirements.txt', content: '邮件要交代影响范围和后续沟通方式。日期待定，不承诺赔偿。' },
  { path: 'reference.md', content: '# 邮件参考\n```text\n先说明情况，再给下一步安排。\n```\n' },
  { path: 'internal-notes.txt', content: 'UNSELECTED_SOURCE_CONTENT_MUST_NOT_BE_COPIED' },
];

const contextReport = (): ContextSnapshot => ({
  customDescription: '', technologyStack: [], dependencies: [], directoryTree: originalFiles.map(file => file.path),
  warnings: ['reference.md仅选取本次相关片段。'], redactions: [], analysisStatus: 'PARTIAL', analysisVersion: 'fixture',
  fileSnippets: originalFiles.map((file, index) => ({ ...file, summary: '', language: index === 1 ? 'markdown' : 'text',
    truncated: index === 1, ...(index === 1 ? { purpose: 'STYLE_REFERENCE' as const } : {}) })),
  fileCoverage: originalFiles.map((file, index) => ({ path: file.path, extractionStatus: 'COMPLETE',
    sourceBytes: Buffer.byteLength(file.content), extractedCharacters: file.content.length, indexedChunks: 1, selectedChunks: 1,
    selectedCharacters: file.content.length, contextLimited: index === 1, message: '' })),
});

for (const structured of [false, true]) {
  test(`用户选择资料到新接收窗口的完整交接，${structured ? '结构化深色' : '自然浅色'}`, async ({ page }, testInfo) => {
    await page.route('**/api/**', route => route.fulfill({ status: 501,
      json: { error: { message: '此交接夹具未登记接口' } } }));
    await mockAuthentication(page);
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.addInitScript(() => localStorage.setItem('prompt-optimizer.plan-mode.v1',
      JSON.stringify({ enabled: false, introSeen: true })));
    await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
      { id: 'fixture', displayName: '受控测试模型', provider: 'fixture', defaultModel: true },
    ] } }));
    const sections = [
      { type: 'BACKGROUND' as const, title: '背景', content: '客户项目需要延期。' },
      { type: 'TASK' as const, title: '任务', content: '撰写客户延期邮件，交代影响和后续沟通。' },
      { type: 'OUTPUT' as const, title: '输出', content: '交付可发送的邮件正文。' },
      { type: 'CONSTRAINTS' as const, title: '约束', content: '日期待定，不承诺赔偿。' },
    ];
    const body = structured ? sections.map(section => `## ${section.title}\n${section.content}`).join('\n\n')
      : '撰写客户延期邮件，交代影响和后续沟通。日期待定，不承诺赔偿。';
    const result: OptimizationResult = {
      recordId: '11111111-1111-4111-8111-111111111111', editRevision: 0,
      resultKind: structured ? 'SECTIONS' : 'TEXT', generationPipeline: structured ? 'LEGACY' : 'NATURAL',
      promptFormat: structured ? 'STRUCTURED' : 'COMPACT', optimizedPrompt: body, sections: structured ? sections : [],
      contextReport: contextReport(), ambiguities: [], appliedConstraints: [], templateCode: 'GENERAL',
      provider: { provider: 'fixture', model: 'fixture', mock: true }, latencyMs: 1,
    };
    const requests: OptimizationRequest[] = [];
    await page.route('**/api/v2/optimizations', route => {
      requests.push(route.request().postDataJSON());
      return route.fulfill({ json: { data: result } });
    });

    await page.goto('/workbench');
    await openWorkbenchPane(page, 'context');
    await page.getByTestId('context-file-input').setInputFiles(originalFiles.map(file => ({
      name: file.path, mimeType: 'text/plain', buffer: Buffer.from(file.content),
    })));
    const styleRow = page.locator('.file-list > li').filter({ hasText: 'reference.md' });
    await styleRow.locator('.file-purpose .el-select__wrapper').click();
    await page.getByRole('option', { name: '格式或风格参考', exact: true }).click();
    await openWorkbenchPane(page, 'intent');
    await page.getByLabel('原始提示词', { exact: true }).fill('结合requirements.txt和reference.md撰写客户延期邮件，日期由我确认，不承诺赔偿。');
    if (structured) {
      await page.getByRole('switch', { name: '结构化提示词', exact: true }).locator('..').click();
      await page.getByRole('button', { name: '切换为深色主题', exact: true }).click();
    }
    await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
    const transmission = page.getByRole('dialog', { name: '确认发送上下文' });
    await expect.poll(async () => requests.length > 0 || await transmission.isVisible()).toBe(true);
    if (await transmission.isVisible()) await transmission.getByRole('button', { name: '确认发送', exact: true }).click();
    await expect.poll(() => requests.length).toBe(1);
    expect(requests[0]!.context.files).toHaveLength(3);
    expect(requests[0]!.context.files.find(file => file.path === 'reference.md')?.purpose).toBe('STYLE_REFERENCE');
    expect(requests[0]!.enhancement.promptFormat).toBe(structured ? 'STRUCTURED' : 'COMPACT');
    await openWorkbenchPane(page, 'result');
    await page.getByTestId('prompt-handoff').click();
    const dialog = page.getByRole('dialog', { name: '交给执行 AI', exact: true });
    const preview = dialog.getByLabel('完整交接内容预览', { exact: true });
    await expect(preview).toHaveValue(body);
    const requirements = dialog.getByRole('checkbox', { name: 'requirements.txt', exact: true });
    const reference = dialog.getByRole('checkbox', { name: 'reference.md', exact: true });
    const checklist = dialog.getByRole('checkbox', { name: /附带原文件核对清单/ });
    // Element Plus隐藏原生输入框，用户实际点击其外层可见标签。
    await requirements.locator('xpath=ancestor::label').click();
    await reference.locator('xpath=ancestor::label').click();
    await checklist.locator('xpath=ancestor::label').click();
    await expect(requirements).toBeChecked();
    await expect(reference).toBeChecked();
    await expect(checklist).toBeChecked();
    await expect(dialog).toContainText('格式或风格参考');
    await expect(dialog).toContainText('当前选取的节选');
    const prepared = await preview.inputValue();
    expect(prepared).toContain(body);
    expect(prepared).toContain(originalFiles[0]!.content);
    expect(prepared).toContain(originalFiles[1]!.content);
    expect(prepared).toContain('原文件核对清单');
    expect(prepared).not.toContain(originalFiles[2]!.content);
    await preview.scrollIntoViewIfNeeded();
    // Chrome桌面滚动定位存在亚像素舍入，预览仍须有99.9%的面积可见。
    await expect(preview).toBeInViewport({ ratio: 0.999 });
    await expect(dialog.locator('.el-dialog__title')).toBeInViewport({ ratio: 1 });
    await expect(dialog.getByRole('button', { name: '复制交接内容', exact: true })).toBeInViewport({ ratio: 1 });
    await expect(page.locator('.el-message')).toHaveCount(0);
    await page.screenshot({ path: testInfo.outputPath('prompt-handoff.png'), fullPage: true, animations: 'disabled' });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await page.bringToFront();
    await dialog.getByRole('button', { name: '复制交接内容', exact: true }).click();
    await expect.poll(async () => (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')).toBe(prepared);

    // 接收窗口只有一个空输入框，实际粘贴不依赖平台状态或文件索引。这里验证交接，模型质量另行验收。
    const receiver = await page.context().newPage();
    await receiver.setContent('<label for="input">接收端消息</label><textarea id="input"></textarea>');
    await receiver.bringToFront();
    const received = receiver.getByLabel('接收端消息');
    await received.focus();
    await received.press('Control+V');
    await expect(received).toHaveValue(prepared);
    await receiver.close();
    await page.bringToFront();
    await dialog.getByRole('button', { name: '关闭', exact: true }).click();
    await page.locator('.copy-main-button').click();
    await expect.poll(async () => (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')).toBe(body);
    await page.getByTestId('prompt-handoff').click();
    await expect(preview).toHaveValue(body);
    await expect(dialog.getByRole('checkbox', { name: 'requirements.txt', exact: true })).not.toBeChecked();
    expect(requests).toHaveLength(1);
  });
}
