/**
 * 包管理器缓存不属于用户项目事实；在遍历和备用 FileList 扫描入口复用，
 * 避免将无扩展名的大型缓存包当作正文索引。仅匹配完整目录名，不忽略普通业务目录。
 */
export const DEPENDENCY_CACHE_DIRECTORY_NAMES = [
  '.m2', '.npm', '.npm-cache', '_cacache', '.pnpm-store',
] as const;
