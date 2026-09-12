import type { ProjectSourceEntry } from './projectIndexer';

const IGNORED_DIRECTORY_NAMES = new Set([
  '.git', '.hg', '.svn', '.idea', '.vscode',
  'node_modules', 'vendor', 'target', 'build', 'dist', 'out', 'coverage',
  '.next', '.nuxt', '.output', '.cache', '.gradle', '.mvn',
  '__pycache__', '.pytest_cache', '.mypy_cache', '.ruff_cache', '.tox',
  '.venv', 'venv', 'env', 'logs', 'log',
]);
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
}

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
): AsyncGenerator<ProjectSourceEntry> {
  yield* walkDirectory(root, '');
};

/**
 * 预先统计可遍历文件数量，为索引阶段提供真实百分比。这里只枚举句柄，不读取文件正文。
 */
export const countDirectoryEntries = async (
  root: DirectoryHandleLike,
  onProgress?: (progress: DirectoryCountProgress) => void,
): Promise<DirectoryCountSummary> => {
  const summary: DirectoryCountSummary = { totalFiles: 0, ignoredDirectories: 0 };
  let lastReportedFiles = -1;
  let lastReportedAt = 0;

  const report = (currentPath: string, force = false): void => {
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
    for await (const handle of directory.values()) {
      const path = parentPath ? `${parentPath}/${handle.name}` : handle.name;
      if (handle.kind === 'directory') {
        if (IGNORED_DIRECTORY_NAMES.has(handle.name.toLowerCase())) {
          summary.ignoredDirectories += 1;
          report(path);
          continue;
        }
        await countDirectory(handle, path);
        continue;
      }
      summary.totalFiles += 1;
      report(path);
    }
  };

  report('', true);
  await countDirectory(root, '');
  report('', true);
  return summary;
};

const walkDirectory = async function* (
  directory: DirectoryHandleLike,
  parentPath: string,
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
      if (IGNORED_DIRECTORY_NAMES.has(handle.name.toLowerCase())) {
        yield { kind: 'ignored-directory', path };
        continue;
      }
      yield* walkDirectory(handle, path);
      continue;
    }

    pendingFiles.push({ path, handle });
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
