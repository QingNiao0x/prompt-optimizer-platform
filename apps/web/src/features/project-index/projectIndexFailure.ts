import type { ProjectIndexSummary } from './projectIndexer';

export type IndexFailureStage = 'SCANNING' | 'INDEXING' | 'WRITING';

/** 错误分类跨 Worker 传递，空 DOMException.message 也必须提供可操作的说明。 */
export const indexFailureMessage = (error: unknown): string => {
  const name = error instanceof Error ? error.name : '';
  if (name === 'QuotaExceededError') {
    return '浏览器存储空间不足，索引已中断。已提交的批次会保留；释放本站不再需要的索引后，可点击“继续索引”重试。';
  }
  if (name === 'NotAllowedError') return '项目目录读取权限已失效。点击“继续索引”重新授权，或重新选择目录。';
  if (name === 'NotFoundError') return '项目目录或文件已移动、删除。请恢复目录后继续索引，或重新选择目录。';
  if (name === 'SecurityError') return '浏览器禁止访问本地存储或目录。请检查本站权限后继续索引。';
  if (name === 'AbortError') return '本地索引事务中断，未提交的批次未保存。可点击“继续索引”校验并补建。';
  const detail = error instanceof Error ? error.message.trim() : '';
  return detail || '本地项目索引意外中断。已提交的批次会保留，可继续索引；若仍失败，请重新选择目录。';
};

/** 只携带最后提交的检查点，不把尚未提交的计数伪装成已保存的数据。 */
export class ProjectIndexInterruptedError extends Error {
  readonly cause: unknown;
  readonly summary?: ProjectIndexSummary;
  readonly stage: IndexFailureStage;

  constructor(error: unknown, checkpoint?: ProjectIndexSummary, stage: IndexFailureStage = 'INDEXING') {
    super(indexFailureMessage(error));
    this.cause = error;
    this.name = 'ProjectIndexInterruptedError';
    this.stage = stage;
    this.summary = checkpoint ? { ...checkpoint, status: 'FAILED', errorMessage: this.message } : undefined;
  }
}
