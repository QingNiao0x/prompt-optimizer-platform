import { isAxiosError } from 'axios';

import {
  completeDocumentUpload,
  createDocumentUpload,
  deleteDocumentUpload,
  getDocumentUploadStatus,
  uploadDocumentChunk,
  confirmContextVision,
} from '@/services/documentUploadApi';
import type {
  ContextFileInput,
  DocumentProcessingPhase,
  DocumentUploadStatus,
  ContextVisionConfirmation,
} from '@/types/api';

const STATUS_POLL_INTERVAL_MS = 500;
const MAX_POLL_INTERVAL_MS = 2_000;
const PROCESSING_TIMEOUT_MS = 10 * 60 * 1_000;
const MAX_CHUNK_RETRIES = 2;
const MAX_PARALLEL_CHUNKS = 3;

export interface DocumentUploadProgress {
  phase: DocumentProcessingPhase;
  percent: number;
  uploadedBytes: number;
  totalBytes: number;
  extractedCharacters: number;
  indexedChunks: number;
}

export interface UploadedDocument {
  file: ContextFileInput;
  status: DocumentUploadStatus;
}

/**
 * 分片发送原始文件并等待后端完成全文解析。返回的 Pinia 数据只有临时索引引用，
 * 不会把几十 MB 的正文或 Base64 留在页面状态和后续 JSON 请求中。
 */
export const uploadAndIndexDocument = async (
  source: File,
  path: string,
  language: string,
  onProgress?: (progress: DocumentUploadProgress) => void,
  signal?: AbortSignal,
  reviewVision?: (status: DocumentUploadStatus, signal?: AbortSignal) => Promise<ContextVisionConfirmation>,
): Promise<UploadedDocument> => {
  let documentId = '';
  try {
    const visionRequested = Boolean(reviewVision) && ['jpeg', 'jpg', 'png', 'pdf'].includes(language);
    const created = await createDocumentUpload({ path, language, sizeBytes: source.size,
      ...(visionRequested ? { visionRequested: true } : {}) }, signal);
    documentId = created.data.documentId;
    const chunkSize = created.data.chunkSizeBytes;
    await uploadChunks(source, documentId, chunkSize, onProgress, signal);

    // 后端按同一文档幂等完成；丢失响应可以重发，但创建和视觉确认不能盲目重放。
    let status = (await retryTransport(() => completeDocumentUpload(documentId, signal), signal)).data;
    onProgress?.(toProgress(status));
    let deadline = Date.now() + PROCESSING_TIMEOUT_MS;
    let pollInterval = STATUS_POLL_INTERVAL_MS;
    while (!isTerminal(status.phase)) {
      if (status.phase === 'AWAITING_CONFIRMATION') {
        if (!reviewVision || !status.visionReview) throw new Error('本次识别需要明确确认，请重新从工作台添加文件。');
        const reviewStarted = Date.now();
        const confirmation = await reviewVision(status, signal);
        throwIfAborted(signal);
        deadline += Date.now() - reviewStarted;
        // 同一会话只确认一次；确认响应丢失时先查询，不重复发送模型任务。
        try { status = (await confirmContextVision(documentId, confirmation, signal)).data; }
        catch (failure: unknown) {
          throwIfAborted(signal);
          const observed = (await retryTransport(() => getDocumentUploadStatus(documentId, signal), signal)).data;
          if (observed.phase === 'AWAITING_CONFIRMATION') throw failure;
          status = observed;
        }
        onProgress?.(toProgress(status));
        continue;
      }
      await delay(pollInterval, signal);
      const previous = status;
      // 标签页在后台时定时器可能被浏览器延迟，先查询服务端，不能仅凭页面时间判定后台失败。
      status = (await retryTransport(() => getDocumentUploadStatus(documentId, signal), signal)).data;
      const advanced = status.phase !== previous.phase || status.progressPercent > previous.progressPercent
        || status.extractedCharacters > previous.extractedCharacters || status.chunkCount > previous.chunkCount;
      if (advanced) deadline = Date.now() + PROCESSING_TIMEOUT_MS;
      pollInterval = advanced ? STATUS_POLL_INTERVAL_MS : Math.min(MAX_POLL_INTERVAL_MS, pollInterval * 1.5);
      onProgress?.(toProgress(status));
      if (!isTerminal(status.phase) && status.phase !== 'AWAITING_CONFIRMATION' && Date.now() >= deadline) {
        throw new Error('文档处理连续 10 分钟没有进展，请稍后重试或检查服务端队列。');
      }
    }
    if (status.phase === 'FAILED' || status.phase === 'CANCELLED') {
      throw new Error(status.errorMessage || '文档解析失败，请检查文件是否损坏。');
    }
    return {
      file: {
        path,
        language,
        content: '',
        documentId,
        sizeBytes: source.size,
      },
      status,
    };
  } catch (error: unknown) {
    if (documentId) {
      await deleteDocumentUpload(documentId).catch(() => undefined);
    }
    throw error;
  }
};

/** 有界流水上传：只保留三片在途数据；失败先停止并等待兄弟请求结束，再允许清理会话。 */
const uploadChunks = async (source: File, documentId: string, chunkSize: number,
  onProgress?: (progress: DocumentUploadProgress) => void, signal?: AbortSignal): Promise<void> => {
  if (!Number.isSafeInteger(chunkSize) || chunkSize <= 0) throw new Error('服务端返回了无效的上传分片大小。');
  const chunkCount = Math.ceil(source.size / chunkSize);
  const loaded = new Array<number>(chunkCount).fill(0);
  const controller = new AbortController();
  const abort = (): void => controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  let nextIndex = 0;
  let failure: unknown;
  let failed = false;
  const report = (index: number, bytes: number): void => {
    if (controller.signal.aborted) return;
    // 重试和乱序进度不重复累计，浏览器省略 upload 事件时由成功响应补足。
    loaded[index] = Math.max(loaded[index] ?? 0, bytes);
    const uploadedBytes = Math.min(source.size, loaded.reduce((sum, value) => sum + value, 0));
    onProgress?.({ phase: 'UPLOADING', percent: source.size ? Math.round(uploadedBytes / source.size * 40) : 0,
      uploadedBytes, totalBytes: source.size, extractedCharacters: 0, indexedChunks: 0 });
  };
  const worker = async (): Promise<void> => {
    try {
      while (nextIndex < chunkCount) {
        throwIfAborted(controller.signal);
        const index = nextIndex++;
        const start = index * chunkSize;
        const chunk = source.slice(start, Math.min(source.size, start + chunkSize));
        await uploadChunkWithRetry(documentId, index, chunk, controller.signal, bytes => report(index, bytes));
        report(index, chunk.size);
      }
    } catch (error: unknown) {
      if (!failed) { failure = error; failed = true; }
      controller.abort();
    }
  };
  try {
    await Promise.all(Array.from({ length: Math.min(MAX_PARALLEL_CHUNKS, chunkCount) }, worker));
    if (failed) throw failure;
    throwIfAborted(signal);
  } finally { signal?.removeEventListener('abort', abort); }
};

const uploadChunkWithRetry = async (
  documentId: string,
  chunkIndex: number,
  chunk: Blob,
  signal: AbortSignal | undefined,
  onProgress: (loaded: number) => void,
): Promise<void> => {
  await retryTransport(() => uploadDocumentChunk(
        documentId,
        chunkIndex,
        chunk,
        (event) => onProgress(Math.min(chunk.size, event.loaded)),
        signal,
      ), signal);
};

/** 仅重试传输故障和暂时不可用；身份、格式、容量和业务冲突不靠重复请求解决。 */
const retryTransport = async <T>(operation: () => Promise<T>, signal?: AbortSignal): Promise<T> => {
  for (let attempt = 0; ; attempt += 1) {
    throwIfAborted(signal);
    try { return await operation(); }
    catch (error: unknown) {
      throwIfAborted(signal);
      const transient = isAxiosError(error) && error.code !== 'ERR_CANCELED'
        && (!error.response || [408, 502, 503, 504].includes(error.response.status));
      if (!transient || attempt >= MAX_CHUNK_RETRIES) throw error;
      await delay(500 * 2 ** attempt, signal);
    }
  }
};

const toProgress = (status: DocumentUploadStatus): DocumentUploadProgress => ({
  phase: status.phase,
  percent: status.progressPercent,
  uploadedBytes: status.uploadedBytes,
  totalBytes: status.fileSizeBytes,
  extractedCharacters: status.extractedCharacters,
  indexedChunks: status.chunkCount,
});

const isTerminal = (phase: DocumentProcessingPhase): boolean =>
  phase === 'READY' || phase === 'PARTIAL' || phase === 'FAILED' || phase === 'CANCELLED';

const throwIfAborted = (signal?: AbortSignal): void => {
  if (signal?.aborted) {
    throw new DOMException('文档上传已取消', 'AbortError');
  }
};

const delay = (milliseconds: number, signal?: AbortSignal): Promise<void> =>
  new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('文档上传已取消', 'AbortError'));
      return;
    }
    let timer: ReturnType<typeof setTimeout>;
    const handleAbort = (): void => {
      clearTimeout(timer);
      reject(new DOMException('文档上传已取消', 'AbortError'));
    };
    timer = setTimeout(() => {
      signal?.removeEventListener('abort', handleAbort);
      resolve();
    }, milliseconds);
    signal?.addEventListener('abort', handleAbort, { once: true });
  });
