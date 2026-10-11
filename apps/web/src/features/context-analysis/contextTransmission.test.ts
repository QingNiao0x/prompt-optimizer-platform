import { describe, expect, it } from 'vitest';

import type { ContextFileInput, ContextSnapshot, FileSnippet } from '@/types/api';
import { completeContextTransmission, createContextTransmission, failContextTransmission } from './contextTransmission';

const file = (path: string, content = '已读取正文'): ContextFileInput => ({ path, language: 'text', content });
const snapshot = (fileSnippets: FileSnippet[] = []): ContextSnapshot => ({ customDescription: '',
  technologyStack: [], dependencies: [], directoryTree: [], fileSnippets, warnings: [], redactions: [], analysisVersion: 'p0' });
const snippet = (source: ContextFileInput, truncated = false): FileSnippet => ({ ...source, summary: '', truncated });

describe('P0 actual context transmission evidence', () => {
  it.each(['readme.txt', ' ././readme.txt '])('deduplicates candidates and matches backend paths: %s', path => {
    const original = file(path);
    const pending = createContextTransmission([original, original], [original], 'ENHANCEMENT');
    expect(pending.entries[0]?.contentStatus).toBe('PENDING');
    const complete = completeContextTransmission(pending, snapshot([snippet({ ...original, path: 'readme.txt' })]), 'p0-full');
    expect(complete.entries[0]?.contentStatus).toBe('FULL');
    expect(complete.entries[0]?.sourceCharacters).toBe(original.content.length);
    expect(complete.entries[0]?.preview).toBe(original.content);
  });

  it('keeps files omitted by the browser budget and distinguishes partial submissions', () => {
    const a = file('A.txt', 'A'.repeat(20));
    const b = file('B.txt', '未发送的正文');
    const prepared = { ...a, content: a.content.slice(0, 5) };
    const report = completeContextTransmission(createContextTransmission([a, b], [prepared], 'ENHANCEMENT'),
      snapshot([snippet(prepared)]), 'p0-budget');
    expect(report.entries.map(entry => entry.contentStatus)).toEqual(['PARTIAL', 'NONE']);
    expect(report.entries[0]?.message).toContain('前端预算');
    expect(report.entries[1]?.preview).toBe('');
    expect(JSON.stringify(report)).not.toContain(b.content);
  });

  it('reports backend snippet truncation and omission without assuming all submitted files were used', () => {
    const a = file('A.txt');
    const b = file('B.txt');
    const response = snapshot([snippet({ ...a, content: '片段' }, true)]);
    response.warnings = ['已达到片段数量上限，未纳入：B.txt'];
    const report = completeContextTransmission(createContextTransmission([a, b], [a, b], 'ENHANCEMENT'), response, 'p0-cap');
    expect(report.entries.map(entry => entry.contentStatus)).toEqual(['PARTIAL', 'NONE']);
    expect(report.entries[1]?.message).toContain('片段数量上限');
  });

  it('never rebuilds a protected preview from the original content', () => {
    const original = file('private.txt', 'SYNTHETIC_SENSITIVE_MARKER');
    const response = snapshot([snippet(file('private.txt', '[CONTENT REDACTED]'))]);
    response.redactions = ['private.txt'];
    const report = completeContextTransmission(createContextTransmission([original], [original], 'ENHANCEMENT'), response, 'p0-protected');
    expect(report.entries[0]?.contentStatus).toBe('NONE');
    expect(report.entries[0]?.preview).toBe('');
    expect(JSON.stringify(report)).not.toContain(original.content);
  });

  it('uses document extraction coverage, not empty inline content, for full versus partial selection', () => {
    const doc = { ...file('report.pdf', ''), documentId: 'synthetic-doc', language: 'pdf' };
    const response = snapshot([snippet(file('report.pdf', '正文'))]);
    response.fileCoverage = [{ path: doc.path, extractionStatus: 'COMPLETE', sourceBytes: 100,
      extractedCharacters: 10, indexedChunks: 5, selectedChunks: 1, selectedCharacters: 2,
      contextLimited: true, message: '本次只选取 1/5 段' }];
    const pending = createContextTransmission([doc], [doc], 'PLAN_CONTEXT');
    expect(completeContextTransmission(pending, response, 'p0-doc').entries[0]?.contentStatus).toBe('PARTIAL');
    response.fileCoverage[0] = { ...response.fileCoverage[0]!, extractedCharacters: 2, selectedChunks: 5,
      contextLimited: false, message: '' };
    expect(completeContextTransmission(pending, response, 'p0-doc-full').entries[0]?.contentStatus).toBe('FULL');
    response.fileCoverage[0].extractionStatus = 'FAILED';
    expect(completeContextTransmission(pending, response, 'p0-doc-failed').entries[0]?.contentStatus).toBe('NONE');
  });

  it('does not call retrieved chunks or binary extraction a complete original file', () => {
    const chunk = file('src/main.ts#chunk-1', '代码块');
    const binary = { ...file('image.png', 'encoded'), language: 'png' };
    const pending = createContextTransmission([chunk, binary], [chunk, binary], 'ANALYSIS');
    const report = completeContextTransmission(pending,
      snapshot([snippet(chunk), snippet(file(binary.path, '图片元数据'))]), 'p0-extract');
    expect(report.phase).toBe('ANALYSIS');
    expect(report.entries.map(entry => entry.contentStatus)).toEqual(['PARTIAL', 'PARTIAL']);
    expect(report.entries[0]?.path).toBe('src/main.ts');
  });

  it('never declares delivery success after a failed request and keeps omission evidence', () => {
    const original = file('A.txt');
    const omitted = file('B.txt');
    const report = failContextTransmission(createContextTransmission([original, omitted], [original], 'ENHANCEMENT'));
    expect(report.status).toBe('FAILED');
    expect(report.entries.map(entry => entry.contentStatus)).toEqual(['UNKNOWN', 'NONE']);
    expect(report.entries.every(entry => entry.preview === '')).toBe(true);
  });
});
