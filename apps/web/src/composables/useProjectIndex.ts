import { onScopeDispose, readonly, ref, shallowRef } from 'vue';

import type {
  EffectiveProjectIndexLimits,
  ProjectIndexRetention,
} from '@/features/project-context-config/projectContextConfig';
import {
  pickProjectDirectory,
  supportsFileSystemAccess,
  type DirectoryHandleLike,
} from '@/features/project-index/fileSystemDirectorySource';
import { projectIndexRepository } from '@/features/project-index/indexedDbProjectIndexRepository';
import {
  type ProjectIndexMode,
  type ProjectIndexProgress,
  type ProjectIndexSummary,
} from '@/features/project-index/projectIndexer';

type WorkerResponse =
  | { type: 'progress'; progress: ProjectIndexProgress }
  | { type: 'paused'; summary: ProjectIndexSummary }
  | { type: 'done'; summary: ProjectIndexSummary }
  | { type: 'error'; message: string };

interface WorkerStartRequest {
  type: 'start';
  projectId: string;
  rootHandle: DirectoryHandleLike;
  limits: EffectiveProjectIndexLimits;
  retention: ProjectIndexRetention;
  sessionId: string;
  expiresAt: string;
  mode: ProjectIndexMode;
}

export interface ProjectIndexStartOptions {
  limits: EffectiveProjectIndexLimits;
  retention: ProjectIndexRetention;
  autoCleanupDays: number;
}

const SESSION_ID_KEY = 'prompt-optimizer.project-index-session.v1';
const LEGACY_CURRENT_PROJECT_KEY = 'prompt-optimizer.current-project-index.v1';

export const useProjectIndex = () => {
  const isSelecting = ref(false);
  const isIndexing = ref(false);
  const isPausing = ref(false);
  const progress = shallowRef<ProjectIndexProgress>();
  const summary = shallowRef<ProjectIndexSummary>();
  const errorMessage = ref('');
  let activeWorker: Worker | undefined;
  let activeWorkerReject: ((reason: Error) => void) | undefined;
  let activeProjectId = '';
  let currentRootHandle: DirectoryHandleLike | undefined;
  let operationVersion = 0;

  const indexDirectory = async (
    options: ProjectIndexStartOptions,
  ): Promise<ProjectIndexSummary | undefined> => {
    isSelecting.value = true;
    errorMessage.value = '';
    let rootHandle: DirectoryHandleLike;
    try {
      // 目录选择器必须直接位于点击事件的调用链中，前面不能插入异步等待。
      rootHandle = await pickProjectDirectory();
    } catch (error) {
      if (isUserCancellation(error)) {
        return undefined;
      }
      throw error;
    } finally {
      isSelecting.value = false;
    }

    const projectId = createProjectId();
    currentRootHandle = rootHandle;
    return runIndex(projectId, rootHandle, options, 'FULL');
  };

  const resumeIndexing = async (
    options: ProjectIndexStartOptions,
  ): Promise<ProjectIndexSummary | undefined> => runExistingIndex(options, 'RESUME');

  const refreshIndex = async (
    options: ProjectIndexStartOptions,
  ): Promise<ProjectIndexSummary | undefined> => runExistingIndex(options, 'INCREMENTAL');

  const runExistingIndex = async (
    options: ProjectIndexStartOptions,
    mode: Extract<ProjectIndexMode, 'INCREMENTAL' | 'RESUME'>,
  ): Promise<ProjectIndexSummary | undefined> => {
    const existing = summary.value;
    if (!existing) {
      throw new Error('没有可继续处理的本地项目索引');
    }
    const rootHandle = currentRootHandle
      ?? await projectIndexRepository.findProjectSource(existing.id);
    if (!rootHandle) {
      throw new Error('未找到项目目录授权，请重新选择项目文件夹');
    }
    await ensureReadPermission(rootHandle);
    currentRootHandle = rootHandle;
    return runIndex(existing.id, rootHandle, options, mode);
  };

  const runIndex = async (
    projectId: string,
    rootHandle: DirectoryHandleLike,
    options: ProjectIndexStartOptions,
    mode: ProjectIndexMode,
  ): Promise<ProjectIndexSummary | undefined> => {
    const operationId = ++operationVersion;
    activeProjectId = projectId;
    isIndexing.value = true;
    isPausing.value = false;
    progress.value = emptyProgress();
    errorMessage.value = '';
    try {
      const sessionId = getBrowserSessionId();
      await projectIndexRepository.cleanupProjects();
      assertOperationActive(operationId, operationVersion);
      // cleanupProjects 可能刚清理了同编号的失败任务，启动前重新保存目录句柄。
      await projectIndexRepository.saveProjectSource(projectId, rootHandle);
      assertOperationActive(operationId, operationVersion);
      if (options.retention === 'PERSISTENT') {
        await requestPersistentStorage();
        assertOperationActive(operationId, operationVersion);
      }
      const result = await indexInWorker(projectId, rootHandle, {
        limits: options.limits,
        retention: options.retention,
        sessionId,
        expiresAt: createExpiryDate(options.autoCleanupDays),
        mode,
      });
      summary.value = result;
      return result;
    } catch (error) {
      if (error instanceof ProjectIndexCancelledError) {
        return undefined;
      }
      errorMessage.value = error instanceof Error ? error.message : '本地项目索引失败';
      throw error;
    } finally {
      activeWorker?.terminate();
      activeWorker = undefined;
      activeWorkerReject = undefined;
      activeProjectId = '';
      isIndexing.value = false;
      isPausing.value = false;
    }
  };

  const indexInWorker = (
    projectId: string,
    rootHandle: DirectoryHandleLike,
    options: Omit<WorkerStartRequest, 'type' | 'projectId' | 'rootHandle'>,
  ): Promise<ProjectIndexSummary> =>
    new Promise((resolve, reject) => {
      const worker = new Worker(
        new URL('../workers/projectIndex.worker.ts', import.meta.url),
        { type: 'module' },
      );
      activeWorker = worker;
      activeWorkerReject = reject;
      worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
        const message = event.data;
        if (message.type === 'progress') {
          progress.value = message.progress;
          return;
        }
        worker.terminate();
        activeWorker = undefined;
        activeWorkerReject = undefined;
        if (message.type === 'done' || message.type === 'paused') {
          resolve(message.summary);
        } else {
          reject(new Error(message.message));
        }
      };
      worker.onerror = (event) => {
        worker.terminate();
        activeWorker = undefined;
        activeWorkerReject = undefined;
        reject(new Error(event.message || '项目索引线程启动失败'));
      };
      worker.postMessage({
        type: 'start',
        projectId,
        rootHandle,
        ...options,
      } satisfies WorkerStartRequest);
      if (isPausing.value) {
        worker.postMessage({ type: 'pause' });
      }
    });

  const pauseIndexing = (): void => {
    if (!isIndexing.value || isPausing.value) {
      return;
    }
    isPausing.value = true;
    activeWorker?.postMessage({ type: 'pause' });
  };

  const cancelIndexing = async (): Promise<void> => {
    operationVersion += 1;
    const projectId = activeProjectId || summary.value?.id || '';
    activeWorker?.terminate();
    activeWorker = undefined;
    activeWorkerReject?.(new ProjectIndexCancelledError());
    activeWorkerReject = undefined;
    if (summary.value?.id === projectId) {
      summary.value = undefined;
    }
    currentRootHandle = undefined;
    activeProjectId = '';
    isIndexing.value = false;
    isPausing.value = false;
    progress.value = undefined;
    if (projectId) {
      await projectIndexRepository.deleteProject(projectId);
    }
  };

  const resetPageState = (): void => {
    // 页面离开时只终止当前任务并清空内存引用，不主动删除 IndexedDB 中的索引数据。
    operationVersion += 1;
    activeWorker?.terminate();
    activeWorker = undefined;
    activeWorkerReject?.(new ProjectIndexCancelledError());
    activeWorkerReject = undefined;
    activeProjectId = '';
    currentRootHandle = undefined;
    isSelecting.value = false;
    isIndexing.value = false;
    isPausing.value = false;
    progress.value = undefined;
    summary.value = undefined;
    errorMessage.value = '';
  };

  onScopeDispose(() => {
    resetPageState();
  });

  return {
    isSupported: supportsFileSystemAccess(),
    isSelecting: readonly(isSelecting),
    isIndexing: readonly(isIndexing),
    isPausing: readonly(isPausing),
    progress: readonly(progress),
    summary: readonly(summary),
    errorMessage: readonly(errorMessage),
    indexDirectory,
    resumeIndexing,
    refreshIndex,
    clearPersistedProjectSelection,
    resetPageState,
    pauseIndexing,
    cancelIndexing,
  };
};

const emptyProgress = (): ProjectIndexProgress => ({
  phase: 'SCANNING',
  currentPath: '',
  processedFiles: 0,
  filesPerSecond: 0,
  elapsedMs: 0,
  discoveredFiles: 0,
  eligibleFiles: 0,
  indexedFiles: 0,
  ignoredFiles: 0,
  failedFiles: 0,
  addedFiles: 0,
  updatedFiles: 0,
  unchangedFiles: 0,
  chunkCount: 0,
  indexedCharacters: 0,
});

const createProjectId = (): string => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `project-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

const ensureReadPermission = async (rootHandle: DirectoryHandleLike): Promise<void> => {
  const permission = await rootHandle.queryPermission?.({ mode: 'read' });
  if (permission === undefined || permission === 'granted') {
    return;
  }
  const requested = await rootHandle.requestPermission?.({ mode: 'read' });
  if (requested !== 'granted') {
    throw new Error('项目目录读取权限已失效，请重新授权');
  }
};

const requestPersistentStorage = async (): Promise<void> => {
  if (navigator.storage?.persist) {
    await navigator.storage.persist().catch(() => false);
  }
};

const getBrowserSessionId = (): string => {
  try {
    const existing = window.sessionStorage.getItem(SESSION_ID_KEY);
    if (existing) {
      return existing;
    }
    const created = createProjectId();
    window.sessionStorage.setItem(SESSION_ID_KEY, created);
    return created;
  } catch {
    return createProjectId();
  }
};

const createExpiryDate = (days: number): string => {
  const expiresAt = new Date();
  expiresAt.setDate(expiresAt.getDate() + days);
  return expiresAt.toISOString();
};

export const clearPersistedProjectSelection = (): void => {
  try {
    // 兼容旧版本：只移除用于恢复左侧列表的指针，不删除用户电脑中的任何源文件。
    window.localStorage.removeItem(LEGACY_CURRENT_PROJECT_KEY);
  } catch {
    // 浏览器禁用本地存储时，新页面的 Pinia 状态仍然默认为空。
  }
};

const isUserCancellation = (error: unknown): boolean =>
  error instanceof DOMException && error.name === 'AbortError';

class ProjectIndexCancelledError extends Error {
  constructor() {
    super('用户已停止建立项目索引');
    this.name = 'ProjectIndexCancelledError';
  }
}

const assertOperationActive = (operationId: number, currentVersion: number): void => {
  if (operationId !== currentVersion) {
    throw new ProjectIndexCancelledError();
  }
};
