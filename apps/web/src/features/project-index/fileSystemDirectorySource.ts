import type { ProjectSourceEntry } from './projectIndexer';

const IGNORED_DIRECTORY_NAMES = new Set([
  '.git', '.hg', '.svn', '.idea', '.vscode',
  'node_modules', 'vendor', 'target', 'build', 'dist', 'out', 'coverage',
  '.next', '.nuxt', '.output', '.cache', '.gradle', '.mvn',
  '__pycache__', '.pytest_cache', '.mypy_cache', '.ruff_cache', '.tox',
  '.venv', 'venv', 'env', 'logs', 'log',
]);

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

const walkDirectory = async function* (
  directory: DirectoryHandleLike,
  parentPath: string,
): AsyncGenerator<ProjectSourceEntry> {
  for await (const handle of directory.values()) {
    const path = parentPath ? `${parentPath}/${handle.name}` : handle.name;
    if (handle.kind === 'directory') {
      if (IGNORED_DIRECTORY_NAMES.has(handle.name.toLowerCase())) {
        yield { kind: 'ignored-directory', path };
        continue;
      }
      yield* walkDirectory(handle, path);
      continue;
    }

    yield {
      kind: 'file',
      path,
      file: await handle.getFile(),
    };
  }
};
