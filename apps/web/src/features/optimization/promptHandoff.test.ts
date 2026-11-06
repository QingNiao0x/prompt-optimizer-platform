import { describe, expect, it } from 'vitest';
import { buildPromptHandoff, preparePromptHandoff } from './promptHandoff';
import type { ContextSnapshot, FileAnalysisCoverage, OptimizationResult } from '@/types/api';

const snapshot = (): ContextSnapshot => ({
  customDescription: '', technologyStack: [], dependencies: [], directoryTree: [], analysisVersion: 'fixture',
  warnings: [], redactions: [], fileCoverage: [], fileSnippets: [
    { path: 'requirements.txt', language: 'text', content: '事实A，需要保留日期待定。', summary: '', truncated: false, purpose: 'FACT_REFERENCE' },
    { path: 'reference.md', language: 'markdown', content: '未选资料B', summary: '', truncated: true, purpose: 'STYLE_REFERENCE' },
  ],
});
const coverage = (path: string, overrides: Partial<FileAnalysisCoverage> = {}): FileAnalysisCoverage => ({
  path, extractionStatus: 'COMPLETE', sourceBytes: 100, extractedCharacters: 100, indexedChunks: 1, selectedChunks: 1,
  selectedCharacters: 100, contextLimited: false, message: '', ...overrides,
});
const result = (context = snapshot()): OptimizationResult => ({
  optimizedPrompt: '撰写客户邮件，日期待定，不承诺赔偿。', sections: [], promptFormat: 'COMPACT', resultKind: 'TEXT',
  contextReport: context, ambiguities: [], appliedConstraints: [], templateCode: 'GENERAL',
  provider: { provider: 'fixture', model: 'fixture', mock: true }, latencyMs: 0,
});

describe('explicit material handoff', () => {
  it.each(['COMPACT', 'STRUCTURED'] as const)('keeps default copying identical in %s mode', format => {
    const source = { ...result(), promptFormat: format };
    expect(buildPromptHandoff(source, preparePromptHandoff(source.contextReport), [], false)).toBe(source.optimizedPrompt);
  });

  it('carries only selected text, preserving source purpose and current body', () => {
    const source = result();
    const handoff = preparePromptHandoff(source.contextReport);
    const text = buildPromptHandoff(source, handoff, ['snippet-0', 'unknown-id'], false);
    expect(text).toContain(source.optimizedPrompt);
    expect(text).toContain('事实A，需要保留日期待定。');
    expect(text).toContain('requirements.txt');
    expect(text).toContain('事实参考');
    expect(text).not.toContain('未选资料B');
    expect(text).not.toContain('reference.md');
    expect(source.optimizedPrompt).toBe('撰写客户邮件，日期待定，不承诺赔偿。');
  });

  it('exports names and role-based original-file guidance only after the checklist is selected', () => {
    const source = result();
    const text = buildPromptHandoff(source, preparePromptHandoff(source.contextReport), [], true);
    expect(text).toContain('原文件核对清单');
    expect(text).toContain('reference.md');
    expect(text).toContain('需要参照版式或画面时');
    expect(text).not.toContain('未选资料B');
    expect(text).not.toContain('事实A，需要保留日期待定。');
  });

  it('excludes protected, invalid and server-redacted paths even when all ids or the checklist are requested', () => {
    const context = snapshot();
    const paths = ['.env', 'secrets/private.pem', 'private.key', 'application-prod.yml', '../outside.txt', 'C:/outside.txt', 'hidden.txt'];
    context.fileSnippets = paths.map(path => ({ path, content: 'MUST_STAY_EXCLUDED', language: 'text', summary: '', truncated: false }));
    context.fileCoverage = paths.map(path => coverage(path));
    context.redactions = ['HIDDEN.TXT'];
    const source = result(context);
    const handoff = preparePromptHandoff(context);
    expect(handoff.excludedFiles).toBe(paths.length);
    expect(handoff.materials).toHaveLength(0);
    expect(handoff.files).toHaveLength(0);
    expect(buildPromptHandoff(source, handoff, ['snippet-0', 'coverage-0'], true)).toBe(source.optimizedPrompt);
  });

  it('preserves partial and failed states without inventing file contents', () => {
    const context = snapshot();
    context.fileCoverage = [coverage('requirements.txt', { extractionStatus: 'PARTIAL' }),
      coverage('reference.md', { extractionStatus: 'FAILED', message: '图像未识别' }),
      coverage('missing.csv', { extractionStatus: 'FAILED', message: '没有可读取的片段' })];
    const handoff = preparePromptHandoff(context);
    expect(handoff.materials[0]?.coverage).toContain('部分解析');
    expect(handoff.materials[1]?.available).toBe(false);
    expect(handoff.materials[1]?.content).toBe('');
    expect(handoff.materials[2]?.available).toBe(false);
    const text = buildPromptHandoff(result(context), handoff, handoff.materials.map(item => item.id), true);
    expect(text).toContain('事实A');
    expect(text).not.toContain('未选资料B');
    expect(text).toContain('missing.csv');
  });

  it('keeps code fences inside selected references as data', () => {
    const context = snapshot();
    context.fileSnippets[0]!.content = '```markdown\n# 内部原文\n````\n继续原文';
    const text = buildPromptHandoff(result(context), preparePromptHandoff(context), ['snippet-0'], false);
    expect(text).toContain('`````text\n```markdown\n# 内部原文\n````\n继续原文\n`````');
  });

  it('does not convert generated summaries or historical metadata into original documents', () => {
    const context = snapshot();
    context.fileSnippets[0] = { ...context.fileSnippets[0]!, content: '', summary: '摘要不等于原始资料' };
    const handoff = preparePromptHandoff(context);
    expect(handoff.materials[0]?.available).toBe(false);
    expect(buildPromptHandoff(result(context), handoff, ['snippet-0'], false)).not.toContain('摘要不等于原始资料');
    expect(preparePromptHandoff()).toEqual({ materials: [], files: [], excludedFiles: 0 });
  });

  it('offers saved project context separately and never reads later selections', () => {
    const context = snapshot();
    context.customDescription = '原保存项目说明';
    const source = result(context);
    const original = JSON.stringify(source);
    const handoff = preparePromptHandoff(context);
    expect(buildPromptHandoff(source, handoff, [], false)).not.toContain('原保存项目说明');
    expect(buildPromptHandoff(source, handoff, ['description'], false)).toContain('原保存项目说明');
    expect(JSON.stringify(source)).toBe(original);
  });

  it('keeps chunk identities while deduplicating the original-file checklist', () => {
    const context = snapshot();
    context.fileSnippets = [
      { ...context.fileSnippets[0]!, path: 'src/main.ts#chunk-1', content: '片段一' },
      { ...context.fileSnippets[0]!, path: 'src/main.ts#chunk-2', content: '片段二' },
    ];
    const handoff = preparePromptHandoff(context);
    expect(handoff.files).toHaveLength(1);
    expect(handoff.files[0]?.path).toBe('src/main.ts');
    const text = buildPromptHandoff(result(context), handoff, ['snippet-0', 'snippet-1'], true);
    expect(text).toContain('src/main.ts#chunk-1');
    expect(text).toContain('src/main.ts#chunk-2');
    expect(text).toContain('片段一');
    expect(text).toContain('片段二');
  });
});
