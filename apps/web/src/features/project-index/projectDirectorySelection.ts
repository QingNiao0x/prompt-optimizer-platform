import { withRelativePath } from '@/composables/fileDrop';
import { getProjectFilePath, isSensitiveFile, isSafeRelativeFilePath } from '@/workers/fileReaderCore';
import { loadSelectedFileScopes } from './projectFileScope';
import { countDirectoryEntries, loadProjectFileScope, streamDirectoryEntries,
  type DirectoryScanSnapshot, type DirectoryHandleLike, type DirectoryCountProgress, type DirectoryCountSummary } from './fileSystemDirectorySource';
import type { ProjectSourceEntry } from './projectIndexer';
import type { ProjectFileScope } from './projectFileScope';

/** 多根目录只保存可克隆的原生授权句柄和路径前缀；单目录沿用旧授权记录与相对路径。 */
export type ProjectDirectorySource = SelectedProjectFiles | DirectoryHandleLike | {
  kind: 'directory-selection'; name: string; roots: { prefix: string; handle: DirectoryHandleLike }[];
};

/** File 和逻辑路径分别克隆到 Worker；选择文件的快照用于暂停恢复。 */
export interface SelectedProjectFiles {
  kind: 'file-selection';
  name: string;
  excludedFiles?: number;
  files: { path: string; file: File }[];
}

/** 同名输入显式分配唯一逻辑路径，不让后选内容覆盖先选内容。 */
export const createFileSelection = (files: readonly File[]): SelectedProjectFiles => {
  const used: string[] = [];
  const safe = files.filter(file => isSafeRelativeFilePath(getProjectFilePath(file))
    && !isSensitiveFile(getProjectFilePath(file)));
  return { kind: 'file-selection', name: `${files.length} 个文件`, excludedFiles: files.length - safe.length, files: safe.map(file => {
    const path = uniqueDirectoryPrefix(getProjectFilePath(file), used);
    used.push(path);
    return { path, file };
  }) };
};

/** 文件列表使用与目录入口相同的范围规则，路径独立传输避免克隆丢失自定义相对路径。 */
export const selectedFileEntries = async (source: SelectedProjectFiles, respectGitignore = true) => {
  const files = source.files.map(item => ({ ...item, file: withRelativePath(item.file, item.path) }));
  const excluded = await loadSelectedFileScopes(files.map(item => item.file), respectGitignore);
  return files.filter(item => !excluded(item.file));
};

/** 同名目录不能覆盖；前缀固定后随检查点保留，继续与增量更新使用同一组路径。 */
export const uniqueDirectoryPrefix = (name: string, used: readonly string[]): string => {
  let prefix = name, suffix = 2;
  while (used.some(item => item.toLocaleLowerCase() === prefix.toLocaleLowerCase())) prefix = `${name} (${suffix++})`;
  return prefix;
};

/** 将一次确认的目录清单固定为可持久化来源，单根仍使用历史记录结构。 */
export const createDirectorySelection = (handles: readonly DirectoryHandleLike[]): ProjectDirectorySource => {
  if (!handles.length) throw new Error('请先选择至少一个文件夹');
  if (handles.length === 1) return handles[0]!;
  const roots: { prefix: string; handle: DirectoryHandleLike }[] = [];
  for (const handle of handles) roots.push({ prefix: uniqueDirectoryPrefix(handle.name, roots.map(root => root.prefix)), handle });
  return { kind: 'directory-selection', name: `${handles.length} 个文件夹`, roots };
};

/** 统一读取旧单根与新多根记录；不改变已保存的前缀。 */
export const directoryRoots = (source: ProjectDirectorySource): { prefix: string; handle: DirectoryHandleLike }[] =>
  source.kind === 'file-selection' ? [] : source.kind === 'directory-selection' ? source.roots : [{ prefix: '', handle: source }];

export interface PreparedDirectoryRoot { prefix: string; handle: DirectoryHandleLike; scope: ProjectFileScope; snapshot?: DirectoryScanSnapshot }

/** 每个根的忽略规则只作用于本根；前缀在过滤之后添加，避免 A 根规则误删 B 根资料。 */
export const prepareDirectoryRoots = async (source: ProjectDirectorySource, respectGitignore = true): Promise<PreparedDirectoryRoot[]> => {
  const roots: PreparedDirectoryRoot[] = [];
  for (const root of directoryRoots(source)) roots.push({ ...root, scope: await loadProjectFileScope(root.handle, respectGitignore) });
  return roots;
};

const prefixed = (prefix: string, path: string): string => prefix ? `${prefix}/${path}` : path;

/** 汇总每根实际扫描进度并共用总预算，暂停或达到上限后不再打开后续目录。 */
export const countSelectedDirectories = async (
  roots: PreparedDirectoryRoot[], onProgress: (progress: DirectoryCountProgress) => void,
  shouldStop: () => boolean, maxFiles: number,
): Promise<DirectoryCountSummary> => {
  const total: DirectoryCountSummary = { totalFiles: 0, ignoredDirectories: 0, scopeExcludedFiles: 0, excludedDirectorySamples: [] };
  let remainingHandles = 50_000;
  for (const root of roots) {
    if (shouldStop() || total.totalFiles >= maxFiles) break;
    root.snapshot = { entries: [], complete: false, overflow: false, limit: remainingHandles };
    const count = await countDirectoryEntries(root.handle, progress => onProgress({
      ...progress, currentPath: prefixed(root.prefix, progress.currentPath),
      discoveredFiles: total.totalFiles + progress.discoveredFiles,
      ignoredDirectories: total.ignoredDirectories + progress.ignoredDirectories,
    }), root.scope, shouldStop, maxFiles - total.totalFiles, root.snapshot);
    remainingHandles -= root.snapshot.entries.length;
    total.totalFiles += count.totalFiles;
    total.ignoredDirectories += count.ignoredDirectories;
    total.scopeExcludedFiles += count.scopeExcludedFiles;
    total.excludedDirectorySamples.push(...count.excludedDirectorySamples.map(path => prefixed(root.prefix, path)));
    total.excludedDirectorySamples = total.excludedDirectorySamples.slice(0, 20);
  }
  return total;
};

/** 复用完整文件流与批次背压，不为多选构建全目录正文数组。 */
export const streamSelectedDirectories = async function* (roots: PreparedDirectoryRoot[]): AsyncGenerator<ProjectSourceEntry> {
  for (const root of roots) for await (const entry of streamDirectoryEntries(root.handle, root.scope, root.snapshot)) {
    yield { ...entry, path: prefixed(root.prefix, entry.path) };
  }
};
