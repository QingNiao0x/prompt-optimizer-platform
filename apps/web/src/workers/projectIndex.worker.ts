import {
  streamDirectoryEntries,
  countDirectoryEntries,
  loadProjectFileScope,
  type DirectoryHandleLike,
} from '@/features/project-index/fileSystemDirectorySource';
import { projectIndexRepository } from '@/features/project-index/indexedDbProjectIndexRepository';
import { indexFailureMessage, ProjectIndexInterruptedError } from '@/features/project-index/projectIndexFailure';
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
  respectGitignore?: boolean;
}

type ProjectIndexWorkerRequest = StartProjectIndexRequest | { type: 'pause' };

type ProjectIndexWorkerResponse =
  | { type: 'progress'; progress: ProjectIndexProgress }
  | { type: 'paused'; summary: ProjectIndexSummary; documents: DiscoveredDocument[]; omittedDocuments: number }
  | { type: 'done'; summary: ProjectIndexSummary; documents: DiscoveredDocument[]; omittedDocuments: number }
  | { type: 'error'; message: string; summary?: ProjectIndexSummary };

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

  const { projectId, rootHandle, limits, retention, sessionId, expiresAt, mode, respectGitignore } = event.data;
  pauseRequested = false;
  isRunning = true;
  try {
    const startedAt = performance.now();
    const fileScope = await loadProjectFileScope(rootHandle, respectGitignore);
    // 为真实百分比仅预枚举句柄，不读取正文、不保存全项目 File 数组。统计与索引共用冻结的规则。
    const counted = await countDirectoryEntries(rootHandle, (count) => scope.postMessage({
      type: 'progress', progress: {
        phase: 'SCANNING', currentPath: count.currentPath, processedFiles: 0,
        discoveredFiles: count.discoveredFiles, indexedFiles: 0, eligibleFiles: 0,
        ignoredFiles: 0, ignoredDirectories: count.ignoredDirectories, failedFiles: 0,
        addedFiles: 0, updatedFiles: 0, unchangedFiles: 0, chunkCount: 0, indexedCharacters: 0,
        filesPerSecond: 0, elapsedMs: performance.now() - startedAt,
      },
    }), fileScope, () => pauseRequested, limits.maxScanFiles + 1);
    const documents: DiscoveredDocument[] = [];
    let omittedDocuments = 0;
    // 附件与正文共用处理遍历；预统计不调用 getFile，办公文档仍需随后单独确认上传。
    const entries = async function* (): AsyncGenerator<ProjectSourceEntry> {
      if (pauseRequested) return;
      for await (const entry of streamDirectoryEntries(rootHandle, fileScope)) {
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
    const summary = await indexProject({
      projectId,
      rootName: rootHandle.name,
      entries: entries(),
      repository: projectIndexRepository,
      limits,
      retention,
      sessionId,
      expiresAt,
      mode,
      totalFiles: counted.totalFiles,
      scanDurationMs: performance.now() - startedAt,
      scopeExcludedFiles: counted.scopeExcludedFiles,
      excludedDirectorySamples: counted.excludedDirectorySamples,
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
      message: indexFailureMessage(error),
      summary: error instanceof ProjectIndexInterruptedError ? error.summary : undefined,
    });
  } finally {
    isRunning = false;
  }
};
