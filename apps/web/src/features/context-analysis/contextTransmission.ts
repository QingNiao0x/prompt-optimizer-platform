import type { ContextFileInput, ContextSnapshot } from '@/types/api';

export type TransmissionPhase = 'ANALYSIS' | 'PLAN_CONTEXT' | 'ENHANCEMENT';
export type TransmissionStatus = 'FULL' | 'PARTIAL' | 'NONE' | 'PENDING' | 'UNKNOWN';

export interface ContextTransmissionEntry {
  path: string;
  backendStatus: TransmissionStatus;
  contentStatus: TransmissionStatus;
  sourceCharacters?: number;
  requestCharacters: number;
  selectedCharacters: number;
  indexedDocument: boolean;
  message: string;
  /** 只保存服务端返回的本次安全片段，不从原始文件构造预览。 */
  preview: string;
}

export interface ContextTransmissionReport {
  phase: TransmissionPhase;
  status: 'PENDING' | 'COMPLETE' | 'FAILED';
  requestId?: string;
  /** 模拟增强未调用外部模型，不能将其展示为外部发送成功。 */
  mock?: boolean;
  entries: ContextTransmissionEntry[];
}

const binaryLanguages = new Set(['bmp', 'doc', 'docx', 'dps', 'et', 'gif', 'jpeg', 'jpg', 'odt',
  'ods', 'odp', 'pdf', 'png', 'ppt', 'pptx', 'webp', 'wps', 'xls', 'xlsx']);
const sourcePath = (path: string): string => path.replace(/\\/g, '/').replace(/#chunk-\d+$/, '');

/** 包含预算前所有候选，保留前端省略项；引用文档和二进制不能按内联字符推断全文覆盖。 */
export const createContextTransmission = (
  candidates: readonly ContextFileInput[], prepared: readonly ContextFileInput[], phase: TransmissionPhase,
): ContextTransmissionReport => {
  // 已选择列表和检索候选可能引用同一个文件；按精确路径去重，不能把重复引用算成两份正文。
  const uniqueCandidates = [...new Map(candidates.map(file => [file.path, file])).values()];
  const paths = [...new Set([...uniqueCandidates, ...prepared].map(file => sourcePath(file.path)))];
  return { phase, status: 'PENDING', entries: paths.map(path => {
    const originals = uniqueCandidates.filter(file => sourcePath(file.path) === path);
    const sent = prepared.filter(file => sourcePath(file.path) === path);
    const indexedDocument = originals.some(file => Boolean(file.documentId));
    const binary = originals.some(file => binaryLanguages.has(file.language.toLowerCase()));
    const retrievedChunks = originals.some(file => /#chunk-\d+$/.test(file.path));
    const originalCharacters = originals.reduce((count, file) => count + file.content.length, 0);
    const requestCharacters = sent.reduce((count, file) => count + file.content.length, 0);
    const backendStatus = sent.length === 0 ? 'NONE'
      : requestCharacters < originalCharacters && !indexedDocument ? 'PARTIAL' : 'FULL';
    return { path, backendStatus, contentStatus: sent.length ? 'PENDING' : 'NONE',
      sourceCharacters: indexedDocument || binary || retrievedChunks ? undefined : originalCharacters,
      requestCharacters, selectedCharacters: 0, indexedDocument, preview: '',
      message: sent.length === 0 ? '未进入本次请求：前端字符或文件数量预算不足，或内容为空。'
        : backendStatus === 'PARTIAL' ? '前端预算不足，只提交已读取内容的一部分。'
        : indexedDocument ? '提交临时文档引用，等待后端选取正文。' : '等待后端安全分析。' };
  }) };
};

/** 以实际返回的上下文摘要为证据；增强 Provider 使用同一摘要，Plan 准备仅证明后端选取。 */
export const completeContextTransmission = (
  report: ContextTransmissionReport, snapshot: ContextSnapshot, requestId: string,
): ContextTransmissionReport => ({ ...report, status: 'COMPLETE', requestId,
  entries: report.entries.map(entry => {
    if (entry.backendStatus === 'NONE') return entry;
    const redacted = snapshot.redactions.some(path => sourcePath(path) === entry.path);
    const snippets = snapshot.fileSnippets.filter(snippet => sourcePath(snippet.path) === entry.path);
    const coverage = snapshot.fileCoverage?.find(item => sourcePath(item.path) === entry.path);
    const warnings = snapshot.warnings.filter(message => message.includes(entry.path));
    if (redacted) return { ...entry, contentStatus: 'NONE', preview: '', selectedCharacters: 0,
      message: '受保护或含敏感内容，原始正文未纳入；预览不展示被过滤内容。' };
    if (!snippets.length || coverage?.extractionStatus === 'FAILED') return { ...entry,
      contentStatus: 'NONE', preview: '', selectedCharacters: 0,
      message: coverage?.message || warnings.join('；')
        || '未纳入本次安全片段：可能受解析、检索、权限或后端预算限制。' };
    const selectedCharacters = snippets.reduce((count, snippet) => count + snippet.content.length, 0);
    const complete = entry.backendStatus === 'FULL' && !snippets.some(snippet => snippet.truncated)
      && (coverage ? coverage.extractionStatus === 'COMPLETE' && !coverage.contextLimited
          && selectedCharacters >= coverage.extractedCharacters
        : entry.sourceCharacters !== undefined && selectedCharacters >= entry.sourceCharacters);
    return { ...entry, contentStatus: complete ? 'FULL' : 'PARTIAL', selectedCharacters,
      preview: snippets.map(snippet => [snippet.summary, snippet.content].filter(Boolean).join('\n')).join('\n\n'),
      message: complete ? '完整纳入本次已读取／提取的内容；不代表模型理解或采用。'
        : entry.backendStatus === 'PARTIAL' ? '前端预算不足，仅纳入已读取内容的一部分。'
        : snippets.some(snippet => snippet.truncated) ? '后端解析或片段预算限制，仅纳入选取的正文或摘要。'
        : coverage?.message || warnings.join('；')
          || '仅纳入本次选取的安全片段或摘要，无法确认原文件全文覆盖。' };
  }),
});

/** 请求失败时没有足够证据证明模型收到内容，保留省略原因而不展示成功状态。 */
export const failContextTransmission = (report: ContextTransmissionReport): ContextTransmissionReport => ({
  ...report, status: 'FAILED', entries: report.entries.map(entry => entry.backendStatus === 'NONE' ? entry
    : { ...entry, contentStatus: 'UNKNOWN', preview: '', selectedCharacters: 0,
        message: '请求未成功，无法确认本次实际选取或模型接收范围。' }),
});
