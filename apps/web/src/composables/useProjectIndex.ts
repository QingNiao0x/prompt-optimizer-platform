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
const CURRENT_PROJECT_KEY = 'prompt-optimizer.current-project-index.v1';

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
  let deleteCancelledProject = false;

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
    deleteCancelledProject = false;
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
      rememberCurrentProject(result.id);
      return result;
    } catch (error) {
      if (error instanceof ProjectIndexCancelledError) {
        if (deleteCancelledProject) {
          await projectIndexRepository.deleteProject(projectId).catch(() => undefined);
        }
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

  const restoreCurrentIndex = async (): Promise<ProjectIndexSummary | undefined> => {
    await projectIndexRepository.cleanupProjects();
    const projectId = readCurrentProjectId();
    if (!projectId) {
      return undefined;
    }
    let restored = await projectIndexRepository.findProject(projectId);
    if (!restored || (restored.status !== 'READY' && restored.status !== 'PAUSED'
      && restored.status !== 'INDEXING')) {
      forgetCurrentProject();
      return undefined;
    }
    // 页面意外关闭时 Worker 无法写入最后状态，已提交的批次仍可作为恢复检查点。
    if (restored.status === 'INDEXING') {
      restored = {
        ...restored,
        status: 'PAUSED',
        pausedAt: new Date().toISOString(),
      };
      await projectIndexRepository.pauseProject(restored);
    }
    summary.value = restored;
    currentRootHandle = await projectIndexRepository.findProjectSource(projectId);
    return restored;
  };

  const pauseIndexing = (): void => {
    if (!isIndexing.value || isPausing.value) {
      return;
    }
    isPausing.value = true;
    activeWorker?.postMessage({ type: 'pause' });
  };

  const cancelIndexing = async (): Promise<void> => {
    deleteCancelledProject = true;
    operationVersion += 1;
    const projectId = activeProjectId || summary.value?.id || '';
    activeWorker?.terminate();
    activeWorker = undefined;
    activeWorkerReject?.(new ProjectIndexCancelledError());
    activeWorkerReject = undefined;
    if (projectId) {
      await projectIndexRepository.deleteProject(projectId);
      forgetCurrentProject(projectId);
    }
    if (summary.value?.id === projectId) {
      summary.value = undefined;
    }
    currentRootHandle = undefined;
    activeProjectId = '';
    isIndexing.value = false;
    isPausing.value = false;
    progress.value = undefined;
  };

  onScopeDispose(() => {
    // 组件卸载不删除已提交批次；下次进入工作台时会把中断任务恢复为暂停状态。
    operationVersion += 1;
    deleteCancelledProject = false;
    activeWorker?.terminate();
    activeWorkerReject?.(new ProjectIndexCancelledError());
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
    restoreCurrentIndex,
    pauseIndexing,
    cancelIndexing,
  };
};

const emptyProgress = (): ProjectIndexProgress => ({
  phase: 'SCANNING',
  currentPath: '',
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

const rememberCurrentProject = (projectId: string): void => {
  try {
    window.localStorage.setItem(CURRENT_PROJECT_KEY, projectId);
  } catch {
    // 浏览器禁用本地存储时仍允许当前页面继续使用刚建立的索引。
  }
};

const readCurrentProjectId = (): string => {
  try {
    return window.localStorage.getItem(CURRENT_PROJECT_KEY) ?? '';
  } catch {
    return '';
  }
};

const forgetCurrentProject = (projectId?: string): void => {
  try {
    if (!projectId || readCurrentProjectId() === projectId) {
      window.localStorage.removeItem(CURRENT_PROJECT_KEY);
    }
  } catch {
    // 清理指针失败不会影响 IndexedDB 中源码索引的删除。
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
