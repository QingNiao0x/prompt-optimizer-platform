import ignore from 'ignore';
import { DEPENDENCY_CACHE_DIRECTORY_NAMES } from './dependencyCacheDirectories';

/** 两个目录入口共用高置信度产物规则；tmp/temp 由项目自己的规则决定，不能误删业务包。 */
export const PROJECT_GENERATED_DIRECTORIES = new Set([
  ...DEPENDENCY_CACHE_DIRECTORY_NAMES,
  '.git', '.hg', '.svn', '.idea', '.vscode', 'node_modules', 'vendor',
  'target', 'build', 'dist', 'out', 'coverage', '.next', '.nuxt', '.output',
  '.cache', '.gradle', '.mvn', '__pycache__', '.pytest_cache', '.mypy_cache',
  '.ruff_cache', '.tox', '.venv', 'venv', 'env', 'logs', 'log', '.codegraph',
  'playwright-report', 'test-results', '.turbo', '.parcel-cache',
]);

export interface ProjectFileScope {
  /** 仅匹配相对所选根目录的路径；安全过滤由读取入口额外强制执行。 */
  excludes(path: string, directory?: boolean): boolean;
}

/** 根目录规则使用标准解析器处理通配、路径与否定，不以是否被 Git 跟踪决定纳入范围。 */
export const createProjectFileScope = (gitignore = ''): ProjectFileScope => {
  let matcher = ignore({ ignorecase: false }).add(gitignore);
  let checks = 0;
  return {
    excludes(path, directory = false) {
      const relative = path.replace(/\\/g, '/').replace(/^\.\//, '');
      const parts = relative.split('/');
      const directories = directory ? parts : parts.slice(0, -1);
      if (directories.some((part) => PROJECT_GENERATED_DIRECTORIES.has(part.toLowerCase()))) {
        return true;
      }
      // 非法路径交由统一安全检查拒绝，避免第三方规则解析抛错而终止整批。
      if (!relative || relative.startsWith('/') || relative.split('/').includes('..') || relative.includes(':')) {
        return false;
      }
      // 解析器缓存匹配路径；定期重建相同规则，防止百万级目录把路径缓存留在内存。
      if (++checks >= 8192) { matcher = ignore({ ignorecase: false }).add(gitignore); checks = 0; }
      return matcher.ignores(directory ? `${relative.replace(/\/$/, '')}/` : relative);
    },
  };
};

export const MAX_PROJECT_IGNORE_BYTES = 128 * 1024;

/** 超限或不可读时明确失败，不能静默放宽范围并读取原本应排除的私有材料。 */
export const readProjectIgnore = async (file: File): Promise<string> => {
  if (file.size > MAX_PROJECT_IGNORE_BYTES) throw new Error('根目录 .gitignore 超过 128 KiB，请关闭项目忽略规则后重试或精简该文件。');
  return file.text();
};

/** FileList 仅对确实来自文件夹根部的规则生效，单独手选的文件不套用其他文件夹的规则。 */
export const loadSelectedFileScopes = async (
  files: readonly File[], respectGitignore = true,
): Promise<(file: File) => boolean> => {
  const roots = new Map<string, ProjectFileScope>();
  if (respectGitignore) {
    for (const file of files) {
      const path = file.webkitRelativePath?.replace(/\\/g, '/');
      const parts = path?.split('/') ?? [];
      if (parts.length === 2 && parts[1] === '.gitignore') {
        roots.set(parts[0]!, createProjectFileScope(await readProjectIgnore(file)));
      }
    }
  }
  return (file) => {
    const path = file.webkitRelativePath?.replace(/\\/g, '/') ?? '';
    const separator = path.indexOf('/');
    return separator > 0 && (roots.get(path.slice(0, separator))?.excludes(path.slice(separator + 1)) ?? false);
  };
};
