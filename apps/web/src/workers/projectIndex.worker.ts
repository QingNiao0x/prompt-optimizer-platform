import {
  countDirectoryEntries,
  streamDirectoryEntries,
  type DirectoryHandleLike,
} from '@/features/project-index/fileSystemDirectorySource';
import { projectIndexRepository } from '@/features/project-index/indexedDbProjectIndexRepository';
import {
  indexProject,
  type ProjectSourceEntry,
  type ProjectIndexMode,
  type ProjectIndexLimits,
  type ProjectIndexProgress,
  type ProjectIndexSummary,
} from '@/features/project-index/projectIndexer';
import {
  isBinaryDocumentFile,
  isSafeRelativeFilePath,
  isSensitiveFile,
  MAX_DOCUMENT_FILE_BYTES,
} from '@/workers/fileReaderCore';

export interface DiscoveredDocument {
  path: string;
  file: File;
}

interface StartProjectIndexRequest {
  type: 'start';
  projectId: string;
  rootHandle: DirectoryHandleLike;
  limits: ProjectIndexLimits;
  retention: 'SESSION' | 'PERSISTENT';
  sessionId: string;
  expiresAt: string;
  mode: ProjectIndexMode;
}

type ProjectIndexWorkerRequest = StartProjectIndexRequest | { type: 'pause' };

type ProjectIndexWorkerResponse =
  | { type: 'progress'; progress: ProjectIndexProgress }
  | { type: 'paused'; summary: ProjectIndexSummary; documents: DiscoveredDocument[]; omittedDocuments: number }
  | { type: 'done'; summary: ProjectIndexSummary; documents: DiscoveredDocument[]; omittedDocuments: number }
  | { type: 'error'; message: string };

const scope = self as unknown as {
  onmessage: ((event: MessageEvent<ProjectIndexWorkerRequest>) => void) | null;
  postMessage(message: ProjectIndexWorkerResponse): void;
};

/**
 * 目录遍历、文本读取、分块和 IndexedDB 写入均在 Worker 中完成，避免阻塞 Vue 主线程。
 */
let pauseRequested = false;
let isRunning = false;

scope.onmessage = async (event: MessageEvent<ProjectIndexWorkerRequest>): Promise<void> => {
  if (event.data.type === 'pause') {
    pauseRequested = true;
    return;
  }
  if (isRunning) {
    scope.postMessage({ type: 'error', message: '已有项目索引任务正在执行' });
    return;
  }

  const { projectId, rootHandle, limits, retention, sessionId, expiresAt, mode } = event.data;
  pauseRequested = false;
  isRunning = true;
  try {
    const documents: DiscoveredDocument[] = [];
    let omittedDocuments = 0;
    // 与源码索引共用一次目录遍历，只收集安全文件的元数据和 File 引用，不读取附件正文。
    const entries = async function* (): AsyncGenerator<ProjectSourceEntry> {
      for await (const entry of streamDirectoryEntries(rootHandle)) {
        if (entry.kind === 'file' && isBinaryDocumentFile(entry.file)) {
          if (!isSafeRelativeFilePath(entry.path) || isSensitiveFile(entry.path)
              || entry.file.size > MAX_DOCUMENT_FILE_BYTES || documents.length >= 100) {
            omittedDocuments += 1;
          } else {
            documents.push({ path: entry.path, file: entry.file });
          }
        }
        yield entry;
      }
    };
    const countingStartedAt = performance.now();
    const directorySummary = await countDirectoryEntries(rootHandle, (progress) => {
      const elapsedMs = Math.max(0, performance.now() - countingStartedAt);
      scope.postMessage({
        type: 'progress',
        progress: {
          phase: 'SCANNING',
          currentPath: progress.currentPath,
          processedFiles: progress.discoveredFiles,
          filesPerSecond: elapsedMs > 0 ? progress.discoveredFiles * 1_000 / elapsedMs : 0,
          elapsedMs,
          discoveredFiles: progress.discoveredFiles,
          eligibleFiles: 0,
          indexedFiles: 0,
          ignoredFiles: 0,
          failedFiles: 0,
          addedFiles: 0,
          updatedFiles: 0,
          unchangedFiles: 0,
          chunkCount: 0,
          indexedCharacters: 0,
        },
      });
    });
    const summary = await indexProject({
      projectId,
      rootName: rootHandle.name,
      entries: entries(),
      repository: projectIndexRepository,
      limits,
      retention,
      sessionId,
      expiresAt,
      totalFiles: directorySummary.totalFiles,
      mode,
      shouldPause: () => pauseRequested,
      onProgress: (progress) => scope.postMessage({ type: 'progress', progress }),
    });
    scope.postMessage({
      type: summary.status === 'PAUSED' ? 'paused' : 'done',
      summary,
      documents,
      omittedDocuments,
    });
  } catch (error) {
    scope.postMessage({
      type: 'error',
      message: error instanceof Error ? error.message : '本地项目索引失败',
    });
  } finally {
    isRunning = false;
  }
};
