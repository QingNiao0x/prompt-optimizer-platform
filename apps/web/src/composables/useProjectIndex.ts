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
import { createIsolatedProjectId, isIsolatedProject } from '@/features/project-index/projectIndexStorage';
import { indexFailureMessage } from '@/features/project-index/projectIndexFailure';
import {
  type ProjectIndexMode,
  type ProjectIndexProgress,
  type ProjectIndexSummary,
  type ProjectIndexPreparationProgress,
} from '@/features/project-index/projectIndexer';
import type { DiscoveredDocument } from '@/workers/projectIndex.worker';

interface WorkerIndexResult {
  summary: ProjectIndexSummary;
  documents: DiscoveredDocument[];
  omittedDocuments: number;
}

type WorkerResponse =
  | { type: 'progress'; progress: ProjectIndexProgress }
  | ({ type: 'paused' } & WorkerIndexResult)
  | ({ type: 'done' } & WorkerIndexResult)
  | { type: 'error'; message: string; summary?: ProjectIndexSummary };

interface WorkerStartRequest {
  type: 'start';
  projectId: string;
  rootHandle: DirectoryHandleLike;
  limits: EffectiveProjectIndexLimits;
  retention: ProjectIndexRetention;
  sessionId: string;
  expiresAt: string;
  mode: ProjectIndexMode;
  respectGitignore?: boolean;
}

export interface ProjectIndexStartOptions {
  respectGitignore?: boolean;
  limits: EffectiveProjectIndexLimits;
  retention: ProjectIndexRetention;
  autoCleanupDays: number;
  onDocumentsDiscovered?: (documents: DiscoveredDocument[], omittedDocuments: number) => void;
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
  const canRetry = ref(false);
  let retryProjectId = '';
  let activeWorker: Worker | undefined;
  let activeWorkerReject: ((reason: Error) => void) | undefined;
  let activeProjectId = '';
  let currentRootHandle: DirectoryHandleLike | undefined;
  let operationVersion = 0;
  let activeProjectHasData = false;
  let disposePreparation: (() => void) | undefined;

  const indexDirectory = async (
    options: ProjectIndexStartOptions,
  ): Promise<ProjectIndexSummary | undefined> => {
    if (isSelecting.value || isIndexing.value) return undefined;
    const selectionId = ++operationVersion;
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
      if (selectionId === operationVersion) isSelecting.value = false;
    }
    if (selectionId !== operationVersion) return undefined;
    const projectId = createIsolatedProjectId();
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
    if (isSelecting.value || isIndexing.value) return undefined;
    const preparationId = ++operationVersion;
    isSelecting.value = true;
    try {
      const projectId = retryProjectId || summary.value?.id;
      if (!projectId) {
        throw new Error('没有可继续处理的本地项目索引');
      }
      const rootHandle = currentRootHandle
        ?? await projectIndexRepository.findProjectSource(projectId);
      if (!rootHandle) {
        throw new Error('未找到项目目录授权，请重新选择项目文件夹');
      }
      await ensureReadPermission(rootHandle);
      if (preparationId !== operationVersion) return undefined;
      currentRootHandle = rootHandle;
      isSelecting.value = false;
      return runIndex(projectId, rootHandle, options, mode);
    } finally {
      if (preparationId === operationVersion) isSelecting.value = false;
    }
  };

  const runIndex = async (
    projectId: string,
    rootHandle: DirectoryHandleLike,
    options: ProjectIndexStartOptions,
    mode: ProjectIndexMode,
  ): Promise<ProjectIndexSummary | undefined> => {
    const operationId = ++operationVersion;
    activeProjectId = projectId;
    retryProjectId = projectId;
    activeProjectHasData = summary.value?.id === projectId || (mode !== 'FULL' && activeProjectHasData);
    canRetry.value = false;
    isIndexing.value = true;
    isPausing.value = false;
    progress.value = emptyProgress();
    errorMessage.value = '';
    const preparationStartedAt = performance.now();
    const reportPreparation = (preparation: ProjectIndexPreparationProgress): void => {
      if (operationId !== operationVersion) return;
      progress.value = { ...emptyProgress(), preparation, elapsedMs: performance.now() - preparationStartedAt };
    };
    const stopObserving = projectIndexRepository.observePreparation(reportPreparation, projectId);
    // 原生存储提交期间可能没有逐条回调；只更新已用时间，不虚构文件数或百分比。
    const clock = setInterval(() => {
      if (operationId === operationVersion && progress.value?.phase === 'PREPARING') {
        progress.value = { ...progress.value, elapsedMs: performance.now() - preparationStartedAt };
      }
    }, 1000);
    const stopPreparation = (): void => { stopObserving(); clearInterval(clock); };
    disposePreparation = stopPreparation;
    try {
      const sessionId = getBrowserSessionId();
      const expiresAt = createExpiryDate(options.autoCleanupDays);
      // 新目录只准备自己的存储，不等待无关旧项目的大型迁移或删除事务。
      if (isIsolatedProject(projectId)) reportPreparation({ stage: 'PREPARING_STORAGE' });
      await projectIndexRepository.prepareProject(projectId, expiresAt, options.retention);
      assertOperationActive(operationId, operationVersion);
      stopObserving();
      // 失败索引保留至原定期限；继续时校验已有指纹，目录句柄不自动重新取得授权。
      reportPreparation({ stage: 'SAVING_SOURCE' });
      activeProjectHasData = true;
      await projectIndexRepository.saveProjectSource(projectId, rootHandle);
      assertOperationActive(operationId, operationVersion);
      if (options.retention === 'PERSISTENT' && !projectId.startsWith('pi-b1-')) {
        reportPreparation({ stage: 'REQUESTING_STORAGE' });
        await requestPersistentStorage();
        assertOperationActive(operationId, operationVersion);
      }
      reportPreparation({ stage: 'STARTING_WORKER' });
      const result = await indexInWorker(projectId, rootHandle, {
        limits: options.limits,
        retention: options.retention,
        sessionId,
        expiresAt,
        mode,
        respectGitignore: options.respectGitignore,
      });
      assertOperationActive(operationId, operationVersion);
      summary.value = result.summary;
      options.onDocumentsDiscovered?.(result.documents, result.omittedDocuments);
      if (result.summary.status === 'READY') projectIndexRepository.scheduleCleanup();
      return result.summary;
    } catch (error) {
      if (error instanceof ProjectIndexCancelledError) {
        return undefined;
      }
      if (operationId !== operationVersion) return undefined;
      errorMessage.value = indexFailureMessage(error);
      canRetry.value = true;
      throw Object.assign(new Error(errorMessage.value), { cause: error });
    } finally {
      stopPreparation();
      if (disposePreparation === stopPreparation) disposePreparation = undefined;
      if (operationId === operationVersion) {
        activeWorker?.terminate();
        activeWorker = undefined;
        activeWorkerReject = undefined;
        activeProjectId = '';
        isIndexing.value = false;
        isPausing.value = false;
      }
    }
  };

  const indexInWorker = (
    projectId: string,
    rootHandle: DirectoryHandleLike,
    options: Omit<WorkerStartRequest, 'type' | 'projectId' | 'rootHandle'>,
  ): Promise<WorkerIndexResult> =>
    new Promise((resolve, reject) => {
      const worker = new Worker(
        new URL('../workers/projectIndex.worker.ts', import.meta.url),
        { type: 'module' },
      );
      activeWorker = worker;
      activeWorkerReject = reject;
      worker.onmessage = (event: MessageEvent<WorkerResponse>) => {
        if (activeWorker !== worker) return;
        const message = event.data;
        if (message.type === 'progress') {
          progress.value = message.progress;
          return;
        }
        worker.terminate();
        activeWorker = undefined;
        activeWorkerReject = undefined;
        if (message.type === 'done' || message.type === 'paused') {
          resolve({
            summary: message.summary,
            documents: message.documents,
            omittedDocuments: message.omittedDocuments,
          });
        } else {
          if (message.summary) summary.value = message.summary;
          reject(new Error(message.message));
        }
      };
      worker.onerror = (event) => {
        if (activeWorker !== worker) return;
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
    disposePreparation?.();
    disposePreparation = undefined;
    isSelecting.value = false;
    const projectId = activeProjectId || retryProjectId || summary.value?.id || '';
    const deleteStoredProject = activeProjectHasData || summary.value?.id === projectId;
    activeWorker?.terminate();
    activeWorker = undefined;
    activeWorkerReject?.(new ProjectIndexCancelledError());
    activeWorkerReject = undefined;
    if (summary.value?.id === projectId) {
      summary.value = undefined;
    }
    currentRootHandle = undefined;
    retryProjectId = '';
    canRetry.value = false;
    errorMessage.value = '';
    activeProjectId = '';
    activeProjectHasData = false;
    isIndexing.value = false;
    isPausing.value = false;
    progress.value = undefined;
    // 尚在打开旧库的新任务没有写入自身数据；取消无需再排队等待旧库迁移，旧项目继续保留。
    if (projectId && deleteStoredProject) {
      await projectIndexRepository.deleteProject(projectId);
    }
  };

  const resetPageState = (): void => {
    // 页面离开时只终止当前任务并清空内存引用，不主动删除 IndexedDB 中的索引数据。
    operationVersion += 1;
    disposePreparation?.();
    disposePreparation = undefined;
    isSelecting.value = false;
    activeWorker?.terminate();
    activeWorker = undefined;
    activeWorkerReject?.(new ProjectIndexCancelledError());
    activeWorkerReject = undefined;
    activeProjectId = '';
    activeProjectHasData = false;
    currentRootHandle = undefined;
    isSelecting.value = false;
    isIndexing.value = false;
    isPausing.value = false;
    progress.value = undefined;
    summary.value = undefined;
    retryProjectId = '';
    canRetry.value = false;
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
    canRetry: readonly(canRetry),
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
  phase: 'PREPARING',
  preparation: { stage: 'OPENING_STORAGE' },
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
