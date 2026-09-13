import {
  completeDocumentUpload,
  createDocumentUpload,
  deleteDocumentUpload,
  getDocumentUploadStatus,
  uploadDocumentChunk,
} from '@/services/documentUploadApi';
import type {
  ContextFileInput,
  DocumentProcessingPhase,
  DocumentUploadStatus,
} from '@/types/api';

const STATUS_POLL_INTERVAL_MS = 400;
const PROCESSING_TIMEOUT_MS = 10 * 60 * 1_000;
const MAX_CHUNK_RETRIES = 2;

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
): Promise<UploadedDocument> => {
  let documentId = '';
  try {
    const created = await createDocumentUpload({ path, language, sizeBytes: source.size }, signal);
    documentId = created.data.documentId;
    const chunkSize = created.data.chunkSizeBytes;
    const chunkCount = Math.ceil(source.size / chunkSize);
    let uploadedBytes = 0;

    for (let index = 0; index < chunkCount; index += 1) {
      throwIfAborted(signal);
      const start = index * chunkSize;
      const chunk = source.slice(start, Math.min(source.size, start + chunkSize));
      await uploadChunkWithRetry(documentId, index, chunk, signal, (loaded) => {
        const currentUploaded = Math.min(source.size, uploadedBytes + loaded);
        onProgress?.({
          phase: 'UPLOADING',
          percent: Math.round((currentUploaded / source.size) * 40),
          uploadedBytes: currentUploaded,
          totalBytes: source.size,
          extractedCharacters: 0,
          indexedChunks: 0,
        });
      });
      uploadedBytes += chunk.size;
    }

    let status = (await completeDocumentUpload(documentId, signal)).data;
    onProgress?.(toProgress(status));
    const deadline = Date.now() + PROCESSING_TIMEOUT_MS;
    while (!isTerminal(status.phase)) {
      if (Date.now() >= deadline) {
        throw new Error('文档解析超过 10 分钟，请稍后重试或拆分文档。');
      }
      await delay(STATUS_POLL_INTERVAL_MS, signal);
      status = (await getDocumentUploadStatus(documentId, signal)).data;
      onProgress?.(toProgress(status));
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

const uploadChunkWithRetry = async (
  documentId: string,
  chunkIndex: number,
  chunk: Blob,
  signal: AbortSignal | undefined,
  onProgress: (loaded: number) => void,
): Promise<void> => {
  let lastError: unknown;
  for (let attempt = 0; attempt <= MAX_CHUNK_RETRIES; attempt += 1) {
    throwIfAborted(signal);
    try {
      await uploadDocumentChunk(
        documentId,
        chunkIndex,
        chunk,
        (event) => onProgress(Math.min(chunk.size, event.loaded)),
        signal,
      );
      return;
    } catch (error: unknown) {
      lastError = error;
      if (attempt < MAX_CHUNK_RETRIES) {
        await delay(250 * (attempt + 1), signal);
      }
    }
  }
  throw lastError;
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
