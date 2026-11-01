import { nextTick, readonly, ref } from 'vue';

import { uploadAndIndexDocument, type DeferredVisionUpload } from '@/features/document-upload/documentUploader';
import { isContextVisionLanguage, isDeferredVisionUpload } from '@/features/document-upload/contextVisionBatch';
import { deleteDocumentUpload } from '@/services/documentUploadApi';
import { loadSelectedFileScopes } from '@/features/project-index/projectFileScope';
import type { ContextFileInput, ContextVisionConfirmation, DocumentProcessingPhase, DocumentUploadStatus } from '@/types/api';
import {
  collectCandidateFiles,
  getProjectFileLanguage,
  getProjectFilePath,
  MAX_FILES,
  MAX_READ_CHARS_PER_FILE,
  MAX_TOTAL_CHARACTERS,
  readProjectFiles,
  shouldUseTemporaryDocumentIndex,
  type FileProcessingProgress,
  type ProjectFileSelection,
} from '@/workers/fileReaderCore';

export {
  MAX_FILES,
  UNSUPPORTED_EXTENSIONS,
  readProjectFiles,
} from '@/workers/fileReaderCore';
export type { FileProcessingProgress, ProjectFileSelection } from '@/workers/fileReaderCore';

type WorkerResponse =
  | { type: 'progress'; progress: FileProcessingProgress }
  | { type: 'done'; files: ProjectFileSelection['files']; warnings: string[] }
  | { type: 'error'; message: string };

interface WorkerRequest {
  files: File[];
}

const readFilesInWorker = (
  files: File[],
  onProgress: (progress: FileProcessingProgress) => void,
  signal: AbortSignal,
): Promise<ProjectFileSelection> =>
  new Promise((resolve, reject) => {
    const worker = new Worker(
      new URL('../workers/fileReader.worker.ts', import.meta.url),
      { type: 'module' },
    );
    const stop = (): void => {
      worker.terminate();
      signal.removeEventListener('abort', onAbort);
    };
    const onAbort = (): void => {
      stop();
      reject(new Error('文件读取已取消。'));
    };
    if (signal.aborted) { onAbort(); return; }
    signal.addEventListener('abort', onAbort, { once: true });
    worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
      const message = event.data;
      if (message.type === 'progress') {
        onProgress(message.progress);
        return;
      }
      stop();
      if (message.type === 'done') {
        resolve({ files: message.files, warnings: message.warnings });
      } else {
        reject(new Error(message.message));
      }
    };
    worker.onerror = (event) => {
      stop();
      reject(new Error(event.message || '文件读取线程启动失败'));
    };
    worker.postMessage({ files } satisfies WorkerRequest);
  });

const MAX_DOCUMENTS_PER_SELECTION = 100;
const MAX_TOTAL_DOCUMENT_BYTES = 200 * 1024 * 1024;

const mapDocumentPhase = (
  phase: DocumentProcessingPhase,
): FileProcessingProgress['phase'] => {
  switch (phase) {
    case 'UPLOADING': return 'upload';
    case 'QUEUED': return 'queue';
    case 'AWAITING_CONFIRMATION': return 'queue';
    case 'EXTRACTING': return 'extract';
    case 'INDEXING': return 'index';
    case 'SUMMARIZING': return 'summarize';
    default: return 'index';
  }
};

const deleteDocumentReferences = async (files: readonly ContextFileInput[]): Promise<void> => {
  const documentIds = Array.from(new Set(
    files.map((file) => file.documentId).filter((id): id is string => Boolean(id)),
  ));
  await Promise.all(documentIds.map((id) => deleteDocumentUpload(id).catch(() => undefined)));
};

export type ContextVisionPolicy = 'local-only' | 'batch-review' | 'per-file';

export interface VisionBatchSettlement {
  documentId: string;
  confirmation: ContextVisionConfirmation;
}

export const useProjectFiles = (options?: {
  respectGitignore?: () => boolean;
  /** 未提供时，沿用 reviewVision 的逐文件确认；两者都没有则图片和 PDF 只做本地提取。 */
  visionPolicy?: () => ContextVisionPolicy;
  reviewVision?: (status: DocumentUploadStatus, signal?: AbortSignal) => Promise<ContextVisionConfirmation>;
  /** 整批停在确认阶段后调用。返回 null 表示放弃这些文件，已上传的会话会被删除。 */
  settleVisionBatch?: (
    pending: readonly DocumentUploadStatus[],
    signal: AbortSignal,
  ) => Promise<readonly VisionBatchSettlement[] | null>;
}) => {
  const isReading = ref(false);
  const warnings = ref<string[]>([]);
  const progress = ref<FileProcessingProgress | null>(null);
  let operationVersion = 0;
  let activeAbortController: AbortController | undefined;

  // 让出主线程，给浏览器一次重绘机会，避免界面在大量文件时看起来没有响应。
  const yieldToBrowser = (): Promise<void> =>
    new Promise((resolve) => setTimeout(resolve, 0));

  const selectFileArray = async (fileArray: readonly File[]): Promise<ContextFileInput[]> => {
    const currentOperation = ++operationVersion;
    const isCurrentOperation = (): boolean => currentOperation === operationVersion;
    activeAbortController?.abort();
    if (fileArray.length === 0) {
      activeAbortController = undefined;
      warnings.value = [];
      progress.value = null;
      return [];
    }
    const abortController = new AbortController();
    activeAbortController = abortController;
    const uploadedDocuments: ContextFileInput[] = [];

    isReading.value = true;
    progress.value = {
      current: 0,
      total: fileArray.length,
      fileName: '',
      percent: 0,
      accepted: 0,
      skipped: 0,
      phase: 'scan',
    };
    if (import.meta.env.DEV) {
      console.info('[file-upload] start', {
        total: fileArray.length,
        at: new Date().toISOString(),
      });
    }

    try {
      // 第一阶段只扫描文件名和大小，绝不全量复制或克隆十六万文件。
      // 扫描分片执行，每 500 条让出一次主线程；FileList 由 ContextPanel 延迟清空，保证扫描期间有效。
      const scanWarnings: string[] = [];
      const scanStartedAt = performance.now();
      const excludedByProject = await loadSelectedFileScopes(fileArray, options?.respectGitignore?.() ?? true);
      if (!isCurrentOperation()) return [];
      const { files: candidates, stats } = await collectCandidateFiles(
        fileArray,
        MAX_FILES,
        async (scanned, total, selected) => {
          if (!isCurrentOperation()) {
            return;
          }
          progress.value = {
            current: scanned,
            total,
            fileName: '',
            percent: total === 0 ? 100 : Math.round((scanned / total) * 100),
            accepted: selected,
            skipped: 0,
            phase: 'scan',
          };
          await yieldToBrowser();
        },
        excludedByProject,
      );
      if (!isCurrentOperation()) {
        return [];
      }
      if (import.meta.env.DEV) {
        console.info('[file-upload] scan finished', {
          fileListLength: fileArray.length,
          scanned: stats.total,
           selected: stats.selected,
           pathIgnored: stats.pathIgnored,
           sensitive: stats.sensitive,
          unsupported: stats.unsupported,
          oversized: stats.oversized,
          tookMs: Math.round(performance.now() - scanStartedAt),
        });
      }
      progress.value = {
        current: stats.total,
        total: stats.total,
        fileName: '',
        percent: stats.total === 0 ? 100 : 100,
        accepted: stats.selected,
         skipped: stats.pathIgnored + stats.sensitive + stats.unsupported + stats.oversized,
        phase: 'scan',
      };

      // 先渲染出进度条容器，再开始读取。
      await nextTick();
      await yieldToBrowser();

      if (stats.pathIgnored > 0) {
        scanWarnings.push(`已按项目规则跳过 ${stats.pathIgnored} 个依赖目录、构建产物或排除范围内的文件。`);
      }
      if (stats.unscanned > 0) scanWarnings.push(`另有 ${stats.unscanned} 个文件未扫描：已达到本批扫描或选择上限，请分批选择，或使用项目本地索引。`);
      if (stats.sensitive > 0) {
        scanWarnings.push(`已跳过 ${stats.sensitive} 个敏感文件（密钥、私钥、.env 或凭据文件），不会上传。`);
      }
      if (stats.unsupported > 0) {
        scanWarnings.push(`已跳过 ${stats.unsupported} 个暂不支持的文件。`);
      }
      if (stats.oversized > 0) {
        scanWarnings.push(`已跳过 ${stats.oversized} 个超过大小限制的文件。`);
      }
      if (candidates.length === 0) {
        scanWarnings.push('没有找到可读取的文件。');
        warnings.value = scanWarnings;
        return [];
      }

      const inlineCandidates: File[] = [];
      const documentCandidates: File[] = [];
      let selectedDocumentBytes = 0;
      let skippedDocumentBudget = 0;
      // 使用候选总数保守估算 Worker 的最低均摊额度；分流后剩余文件可用额度只会增加。
      const inlineBudget = Math.min(MAX_READ_CHARS_PER_FILE,
        Math.max(1_024, Math.floor(MAX_TOTAL_CHARACTERS / candidates.length)));
      for (const candidate of candidates) {
        if (!shouldUseTemporaryDocumentIndex(candidate, inlineBudget)) {
          inlineCandidates.push(candidate);
          continue;
        }
        if (documentCandidates.length >= MAX_DOCUMENTS_PER_SELECTION
            || selectedDocumentBytes + candidate.size > MAX_TOTAL_DOCUMENT_BYTES) {
          skippedDocumentBudget += 1;
          continue;
        }
        documentCandidates.push(candidate);
        selectedDocumentBytes += candidate.size;
      }
      if (skippedDocumentBudget > 0) {
        scanWarnings.push(
          `有 ${skippedDocumentBudget} 个文档超出本批次 100 个/200 MB 的临时解析预算，请分批上传。`,
        );
      }

      // 第二阶段只把候选文件发给 Worker，主线程保持响应。
      const readStartedAt = performance.now();
      progress.value = {
        current: 0,
        total: inlineCandidates.length + documentCandidates.length,
        fileName: '',
        percent: 0,
        accepted: 0,
        skipped: 0,
        phase: 'read',
      };
      await yieldToBrowser();

      let selection: ProjectFileSelection;
      if (inlineCandidates.length === 0) {
        selection = { files: [], warnings: [] };
      } else {
        try {
          // 优先在 Web Worker 中解析，主线程保持响应；Worker 不可用时回退到主线程时间切片。
          selection = await readFilesInWorker(inlineCandidates, (next) => {
            if (isCurrentOperation()) {
              progress.value = next;
            }
          }, abortController.signal);
        } catch (workerError) {
          if (!isCurrentOperation()) {
            return [];
          }
          if (import.meta.env.DEV) {
            console.info('[file-reader] worker unavailable, fallback to main thread', workerError);
          }
          selection = await readProjectFiles(inlineCandidates, async (next) => {
            if (!isCurrentOperation()) {
              return;
            }
            progress.value = next;
            await yieldToBrowser();
          });
        }
      }
      if (!isCurrentOperation()) {
        await deleteDocumentReferences(uploadedDocuments);
        return [];
      }

      const documentWarnings: string[] = [];
      let failedDocuments = 0;
      const deferredVision: DeferredVisionUpload[] = [];
      const documentOrder = new Map(documentCandidates.map((file, index) => [getProjectFilePath(file), index]));
      // 本地提取不请求视觉审阅，因此可以恢复两路上传。逐文件弹窗仍保持串行，避免两张确认叠在一起。
      const visionPolicy = options?.visionPolicy?.()
        ?? (options?.reviewVision ? 'per-file' : 'local-only');
      const activeProgress = new Map<number, FileProcessingProgress>();
      const publishProgress = (): void => {
        if (!isCurrentOperation() || !activeProgress.size) return;
        // 展示最早尚未完成的文件，避免并行响应使文件名、字节数来回闪烁。
        const first = activeProgress.get(Math.min(...activeProgress.keys()))!;
        progress.value = { ...first, accepted: selection.files.length + uploadedDocuments.length,
          skipped: skippedDocumentBudget + failedDocuments + inlineCandidates.length - selection.files.length };
      };
      const rememberDocument = (file: ContextFileInput, warningsForFile: readonly string[]): void => {
        uploadedDocuments.push(file);
        documentWarnings.push(...warningsForFile.map((warning) => `${file.path}：${warning}`));
      };
      const processDocument = async (index: number): Promise<void> => {
        const candidate = documentCandidates[index];
        if (!isCurrentOperation()) return;
        const acceptedBeforeDocument = selection.files.length + uploadedDocuments.length;
        const language = getProjectFileLanguage(candidate);
        const report = (documentProgress: { phase: DocumentProcessingPhase; percent: number; uploadedBytes: number; totalBytes: number; extractedCharacters: number; indexedChunks: number }): void => {
          if (!isCurrentOperation()) return;
          activeProgress.set(index, {
            current: inlineCandidates.length + index + 1,
            total: inlineCandidates.length + documentCandidates.length,
            fileName: getProjectFilePath(candidate),
            percent: documentProgress.percent,
            accepted: acceptedBeforeDocument,
            skipped: skippedDocumentBudget + failedDocuments + inlineCandidates.length - selection.files.length,
            phase: mapDocumentPhase(documentProgress.phase),
            uploadedBytes: documentProgress.uploadedBytes,
            totalBytes: documentProgress.totalBytes,
            extractedCharacters: documentProgress.extractedCharacters,
            indexedChunks: documentProgress.indexedChunks,
          });
          publishProgress();
        };
        try {
          const deferVisionReview = visionPolicy === 'batch-review' && isContextVisionLanguage(language);
          const outcome = deferVisionReview
            ? await uploadAndIndexDocument(candidate, getProjectFilePath(candidate), language, report, abortController.signal, undefined, true)
            : await uploadAndIndexDocument(candidate, getProjectFilePath(candidate), language, report, abortController.signal,
              visionPolicy === 'per-file' ? options?.reviewVision : undefined);
          if (isDeferredVisionUpload(outcome)) {
            deferredVision.push(outcome);
            return;
          }
          rememberDocument(outcome.file, outcome.status.warnings);
        } catch (error: unknown) {
          if (abortController.signal.aborted || !isCurrentOperation()) {
            return;
          }
          const message = error instanceof Error ? error.message : '解析失败';
          failedDocuments += 1;
          documentWarnings.push(`${getProjectFilePath(candidate)}：${message}`);
        } finally {
          activeProgress.delete(index);
          publishProgress();
        }
      };
      let nextDocument = 0;
      const parallelDocuments = visionPolicy === 'per-file'
        && documentCandidates.some(file => isContextVisionLanguage(getProjectFileLanguage(file))) ? 1 : 2;
      await Promise.all(Array.from({ length: Math.min(parallelDocuments, documentCandidates.length) }, async () => {
        while (isCurrentOperation() && nextDocument < documentCandidates.length) await processDocument(nextDocument++);
      }));
      if (!isCurrentOperation()) {
        await Promise.all(deferredVision.map((item) => item.discard()));
        await deleteDocumentReferences(uploadedDocuments);
        return [];
      }
      if (deferredVision.length > 0) {
        let cancelled = false;
        try {
          const decisions = await options?.settleVisionBatch?.(deferredVision.map((item) => item.status), abortController.signal) ?? null;
          if (!isCurrentOperation() || decisions === null) {
            cancelled = isCurrentOperation();
          } else {
            const byId = new Map(decisions.map((decision) => [decision.documentId, decision.confirmation]));
            for (const item of deferredVision) {
              if (!isCurrentOperation()) break;
              try {
                const confirmation = byId.get(item.status.documentId) ?? { modelId: '', consent: false, pages: [] };
                const uploaded = await item.finish(confirmation);
                rememberDocument(uploaded.file, uploaded.status.warnings);
              } catch (error: unknown) {
                if (abortController.signal.aborted || !isCurrentOperation()) break;
                failedDocuments += 1;
                documentWarnings.push(`${item.status.path}：${error instanceof Error ? error.message : '解析失败'}`);
              }
            }
          }
        } catch (error: unknown) {
          await deleteDocumentReferences(uploadedDocuments);
          throw error;
        } finally {
          // finish 成功后 discard 不会再删除；尚未提交或失败残留的会话在这里清掉。
          await Promise.all(deferredVision.map((item) => item.discard()));
        }
        if (!isCurrentOperation()) {
          await deleteDocumentReferences(uploadedDocuments);
          return [];
        }
        if (cancelled) {
          documentWarnings.push(`已取消 ${deferredVision.length} 个图片或 PDF 的识别确认，这些文件未加入上下文。`);
        }
      }
      uploadedDocuments.sort((left, right) => (documentOrder.get(left.path) ?? 0) - (documentOrder.get(right.path) ?? 0));
      if (import.meta.env.DEV) {
        console.info('[file-upload] read finished', {
          files: selection.files.length + uploadedDocuments.length,
          tookMs: Math.round(performance.now() - readStartedAt),
        });
      }
      // 最后一份上传也可能在 reset 后才返回，不能再写警告或交回旧引用。
      if (!isCurrentOperation()) {
        await deleteDocumentReferences(uploadedDocuments);
        return [];
      }
      warnings.value = [...scanWarnings, ...selection.warnings, ...documentWarnings];
      return [...selection.files, ...uploadedDocuments];
    } finally {
      if (isCurrentOperation()) {
        isReading.value = false;
        progress.value = null;
        if (activeAbortController === abortController) {
          activeAbortController = undefined;
        }
      }
    }
  };

  const selectFiles = async (fileList: FileList | null): Promise<ContextFileInput[]> =>
    selectFileArray(fileList ? Array.from(fileList) : []);

  const reset = (): void => {
    operationVersion += 1;
    activeAbortController?.abort();
    activeAbortController = undefined;
    isReading.value = false;
    warnings.value = [];
    progress.value = null;
  };

  return {
    isReading: readonly(isReading),
    warnings: readonly(warnings),
    progress: readonly(progress),
    selectFiles,
    selectFileArray,
    reset,
  };
};
