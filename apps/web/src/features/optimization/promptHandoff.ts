import type { ContextSnapshot, FileAnalysisCoverage, MaterialPurpose, OptimizationResult } from '@/types/api';
import { isSafeRelativeFilePath, isSensitiveFile } from '@/workers/fileReaderCore';

export interface HandoffMaterial {
  id: string;
  path: string;
  purpose?: MaterialPurpose;
  content: string;
  language: string;
  coverage: string;
  available: boolean;
  isFile: boolean;
}

export interface HandoffFile {
  path: string;
  purpose?: MaterialPurpose;
  guidance: string;
}

export interface PromptHandoff {
  materials: HandoffMaterial[];
  files: HandoffFile[];
  excludedFiles: number;
}

const PURPOSE_LABELS: Record<MaterialPurpose, string> = {
  TASK_TARGET: '处理对象', FACT_REFERENCE: '事实参考', STYLE_REFERENCE: '格式或风格参考', DIAGNOSTIC_REFERENCE: '排查线索',
};

/** 用途用于解释材料角色，不能增加执行授权。未指定时由任务决定使用方式。 */
export const handoffPurposeLabel = (purpose?: MaterialPurpose): string =>
  purpose ? PURPOSE_LABELS[purpose] ?? '结合任务使用' : '结合任务使用';

const sourcePath = (path: string): string => path.replace(/\\/g, '/').replace(/#chunk-\d+$/, '');

/** 只有用户明确要求的动作定义交付，原文件提示按材料角色说明可能的使用条件。 */
const originalFileGuidance = (purpose?: MaterialPurpose): string => {
  switch (purpose) {
    case 'TASK_TARGET': return '执行端需要修改文件时，请同步提供可编辑的原文件。';
    case 'STYLE_REFERENCE': return '需要参照版式或画面时，请同步提供原文件。';
    case 'FACT_REFERENCE': return '需要核查节选之外的事实时，请同步提供原文件。';
    case 'DIAGNOSTIC_REFERENCE': return '需要追踪完整链路时，请同步提供对应原件。';
    default: return '请按任务需要决定是否在执行端提供原文件。';
  }
};

const coverageLabel = (truncated: boolean, coverage?: FileAnalysisCoverage): string => {
  if (coverage?.extractionStatus === 'FAILED') return coverage.message || '解析失败，没有可携带的正文。';
  if (coverage?.extractionStatus === 'PARTIAL') return '原文件仅部分解析，附带的是本次可用片段。';
  if (truncated || coverage?.contextLimited) return '当前选取的节选，完整原文件需按任务另行提供。';
  return '当前结果保留的文字片段，原文件的版式、画面及其他内容需单独核对。';
};

/**
 * 仅从当前结果的服务端上下文快照建立交接清单，不读取后来选择的文件或重新检索。
 * 受保护路径、服务端已过滤材料和失败正文继续排除。历史没有原文时保留实际缺口。
 */
export const preparePromptHandoff = (snapshot?: ContextSnapshot): PromptHandoff => {
  const materials: HandoffMaterial[] = [];
  const files = new Map<string, HandoffFile>();
  const excluded = new Set<string>();
  const redactions = new Set((snapshot?.redactions ?? []).map(path => sourcePath(path).trim().toLowerCase()));
  const allowed = (path: string): boolean => {
    const normalized = sourcePath(path).trim();
    if (!isSafeRelativeFilePath(normalized) || isSensitiveFile(normalized) || redactions.has(normalized.toLowerCase())) {
      excluded.add(normalized);
      return false;
    }
    return true;
  };
  if (snapshot?.customDescription?.trim()) materials.push({
    id: 'description', path: '本次结果的项目描述', content: snapshot.customDescription, language: 'text',
    coverage: '服务端保留的项目描述。', available: true, isFile: false,
  });
  for (const [index, snippet] of (snapshot?.fileSnippets ?? []).entries()) {
    if (!allowed(snippet.path)) continue;
    const path = sourcePath(snippet.path);
    const coverage = snapshot?.fileCoverage?.find(item => sourcePath(item.path) === path);
    const available = Boolean(snippet.content?.trim()) && coverage?.extractionStatus !== 'FAILED';
    materials.push({ id: `snippet-${index}`, path: snippet.path, purpose: snippet.purpose,
      content: available ? snippet.content : '', language: snippet.language,
      coverage: available ? coverageLabel(snippet.truncated, coverage)
        : coverage?.message || '此结果没有保留该文件的正文片段，请按任务提供原文件。',
      available, isFile: true });
    files.set(path, { path, purpose: snippet.purpose, guidance: originalFileGuidance(snippet.purpose) });
  }
  // 解析失败或预算排除的文件可能只剩覆盖记录，不能凭摘要制造原文。
  for (const coverage of snapshot?.fileCoverage ?? []) {
    if (!allowed(coverage.path)) continue;
    const path = sourcePath(coverage.path);
    if (files.has(path)) continue;
    files.set(path, { path, guidance: originalFileGuidance() });
    materials.push({ id: `coverage-${materials.length}`, path, content: '', language: 'text',
      coverage: coverage.message || '此结果没有保留该文件的正文片段，请按任务提供原文件。',
      available: false, isFile: true });
  }
  return { materials, files: [...files.values()], excludedFiles: excluded.size };
};

/** 围栏长于资料中的所有反引号串，保留内嵌 Markdown 的文本边界。 */
const fenced = (content: string, language = 'text'): string => {
  let length = 3;
  for (const match of content.matchAll(/`+/g)) length = Math.max(length, match[0].length + 1);
  const fence = '`'.repeat(length);
  const label = language.replace(/[^A-Za-z0-9_+.-]/g, '').slice(0, 24) || 'text';
  return `${fence}${label}\n${content}\n${fence}`;
};

/**
 * 用户选择后独立组装交接文本。默认仍逐字交付保存正文，资料只来自选中项。
 * 文件路径用于识别原件，不表示执行端已获得本机访问能力。
 */
export const buildPromptHandoff = (
  result: OptimizationResult,
  handoff: PromptHandoff,
  selectedIds: readonly string[],
  includeFileChecklist: boolean,
): string => {
  const ids = new Set(selectedIds);
  const selected = handoff.materials.filter(item => item.available && ids.has(item.id));
  if (!selected.length && (!includeFileChecklist || !handoff.files.length)) return result.optimizedPrompt;
  const sections = ['# 优化提示词', result.optimizedPrompt];
  if (selected.length) {
    sections.push('# 用户选定的资料片段', '以下内容作为本次任务的资料，具体动作以优化提示词中的委托为准。');
    selected.forEach((item, index) => {
      sections.push(`## 资料 ${index + 1}`, `文件 ${JSON.stringify(item.path)}\n用途 ${handoffPurposeLabel(item.purpose)}\n范围 ${item.coverage}`,
        fenced(item.content, item.language));
    });
  }
  if (includeFileChecklist && handoff.files.length) {
    sections.push('# 原文件核对清单', '执行端需实际获得所需文件。下列路径用于识别资料，具体提供范围按本次任务决定。资料经过选取、遮挡或脱敏时，请保留已确认的范围。',
      ...handoff.files.map(file => `- ${JSON.stringify(file.path)}　${handoffPurposeLabel(file.purpose)}。${file.guidance}`));
  }
  return sections.join('\n\n');
};
