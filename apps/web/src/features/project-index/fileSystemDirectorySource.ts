import type { ProjectSourceEntry } from './projectIndexer';
import { createProjectFileScope, readProjectIgnore, type ProjectFileScope } from './projectFileScope';
const FILE_HANDLE_BATCH_SIZE = 64;
const FILE_METADATA_CONCURRENCY = 6;

export interface FileHandleLike {
  kind: 'file';
  name: string;
  getFile(): Promise<File>;
}

export interface DirectoryHandleLike {
  kind: 'directory';
  name: string;
  values(): AsyncIterableIterator<FileSystemHandleLike>;
  getFileHandle?(name: string): Promise<FileHandleLike>;
  queryPermission?(options?: { mode?: 'read' }): Promise<PermissionState>;
  requestPermission?(options?: { mode?: 'read' }): Promise<PermissionState>;
}

export type FileSystemHandleLike = FileHandleLike | DirectoryHandleLike;

interface PendingFileHandle {
  path: string;
  handle: FileHandleLike;
}

export interface DirectoryCountProgress {
  currentPath: string;
  discoveredFiles: number;
  ignoredDirectories: number;
}

export interface DirectoryCountSummary {
  totalFiles: number;
  ignoredDirectories: number;
  scopeExcludedFiles: number;
  excludedDirectorySamples: string[];
}

/** 所选根目录之外的规则不读取；关闭时仍保留生成目录和后续敏感文件的强制过滤。 */
export const loadProjectFileScope = async (
  root: DirectoryHandleLike,
  respectGitignore = true,
): Promise<ProjectFileScope> => {
  if (!respectGitignore || !root.getFileHandle) return createProjectFileScope();
  try {
    const handle = await root.getFileHandle('.gitignore');
    return createProjectFileScope(await readProjectIgnore(await handle.getFile()));
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotFoundError') return createProjectFileScope();
    throw error;
  }
};

interface DirectoryPickerWindow extends Window {
  showDirectoryPicker?: (options?: { mode?: 'read' | 'readwrite' }) => Promise<DirectoryHandleLike>;
}

/**
 * File System Access API 只在安全上下文和部分桌面浏览器中可用。
 */
export const supportsFileSystemAccess = (): boolean =>
  typeof window !== 'undefined'
  && typeof (window as DirectoryPickerWindow).showDirectoryPicker === 'function';

/**
 * 必须在用户点击事件的同步调用链中执行，否则浏览器会拒绝弹出目录选择器。
 */
export const pickProjectDirectory = async (): Promise<DirectoryHandleLike> => {
  const picker = (window as DirectoryPickerWindow).showDirectoryPicker;
  if (!picker) {
    throw new Error('当前浏览器不支持 File System Access API');
  }
  return picker.call(window, { mode: 'read' });
};

/**
 * 深度优先遍历目录。每发现一个文件立即产出，不构造完整 FileList。
 */
export const streamDirectoryEntries = async function* (
  root: DirectoryHandleLike,
  scope: ProjectFileScope = createProjectFileScope(),
): AsyncGenerator<ProjectSourceEntry> {
  yield* walkDirectory(root, '', scope);
};

/**
 * 预先统计可遍历文件数量，为索引阶段提供真实百分比。这里只枚举句柄，不读取文件正文。
 */
export const countDirectoryEntries = async (
  root: DirectoryHandleLike,
  onProgress?: (progress: DirectoryCountProgress) => void,
  scope: ProjectFileScope = createProjectFileScope(),
  shouldStop: () => boolean = () => false,
  maxFiles = Number.MAX_SAFE_INTEGER,
): Promise<DirectoryCountSummary> => {
  const summary: DirectoryCountSummary = { totalFiles: 0, ignoredDirectories: 0,
    scopeExcludedFiles: 0, excludedDirectorySamples: [] };
  let lastReportedFiles = -1;
  let lastReportedAt = 0;
  let currentLocation = root.name;

  const report = (currentPath: string, force = false): void => {
    currentLocation = currentPath;
    const now = performance.now();
    if (
      !force
      && lastReportedFiles >= 0
      && summary.totalFiles - lastReportedFiles < 256
      && now - lastReportedAt < 150
    ) {
      return;
    }
    lastReportedFiles = summary.totalFiles;
    lastReportedAt = now;
    onProgress?.({ currentPath, discoveredFiles: summary.totalFiles, ...summary });
  };

  const countDirectory = async (directory: DirectoryHandleLike, parentPath: string): Promise<void> => {
    report(parentPath || root.name);
    for await (const handle of directory.values()) {
      if (shouldStop() || summary.totalFiles >= maxFiles) return;
      const path = parentPath ? `${parentPath}/${handle.name}` : handle.name;
      if (handle.kind === 'directory') {
        if (scope.excludes(path, true)) {
          summary.ignoredDirectories += 1;
          if (summary.excludedDirectorySamples.length < 20) summary.excludedDirectorySamples.push(path);
          report(path);
          continue;
        }
        await countDirectory(handle, path);
        continue;
      }
      if (!scope.excludes(path)) summary.totalFiles += 1;
      else summary.scopeExcludedFiles += 1;
      report(path);
    }
  };

  report(root.name, true);
  // 原生目录枚举等待期间也报告当前位置；只重复真实计数，不把等待伪装成新发现的文件。
  const heartbeat = onProgress ? setInterval(() => report(currentLocation, true), 1000) : undefined;
  try {
    await countDirectory(root, '');
    report(root.name, true);
    return summary;
  } finally { if (heartbeat !== undefined) clearInterval(heartbeat); }
};

const walkDirectory = async function* (
  directory: DirectoryHandleLike,
  parentPath: string,
  scope: ProjectFileScope,
): AsyncGenerator<ProjectSourceEntry> {
  let pendingFiles: PendingFileHandle[] = [];

  const flushPendingFiles = async (): Promise<ProjectSourceEntry[]> => {
    const batch = pendingFiles;
    pendingFiles = [];
    return mapWithConcurrency(batch, FILE_METADATA_CONCURRENCY, async (entry) => ({
      kind: 'file' as const,
      path: entry.path,
      file: await entry.handle.getFile(),
    }));
  };

  for await (const handle of directory.values()) {
    const path = parentPath ? `${parentPath}/${handle.name}` : handle.name;
    if (handle.kind === 'directory') {
      for (const entry of await flushPendingFiles()) {
        yield entry;
      }
      if (scope.excludes(path, true)) {
        yield { kind: 'ignored-directory', path };
        continue;
      }
      yield* walkDirectory(handle, path, scope);
      continue;
    }

    // 在 getFile 前剪枝，避免对缓存、私有运行数据或用户声明排除的文件发起磁盘读取。
    if (!scope.excludes(path)) pendingFiles.push({ path, handle });
    if (pendingFiles.length >= FILE_HANDLE_BATCH_SIZE) {
      for (const entry of await flushPendingFiles()) {
        yield entry;
      }
    }
  }

  for (const entry of await flushPendingFiles()) {
    yield entry;
  }
};

const mapWithConcurrency = async <TInput, TOutput>(
  input: TInput[],
  concurrency: number,
  mapper: (value: TInput) => Promise<TOutput>,
): Promise<TOutput[]> => {
  const results = new Array<TOutput>(input.length);
  let nextIndex = 0;
  const worker = async (): Promise<void> => {
    while (nextIndex < input.length) {
      const index = nextIndex;
      nextIndex += 1;
      const value = input[index];
      if (value !== undefined) {
        results[index] = await mapper(value);
      }
    }
  };
  const workers = Array.from(
    { length: Math.min(concurrency, input.length) },
    () => worker(),
  );
  await Promise.all(workers);
  return results;
};
