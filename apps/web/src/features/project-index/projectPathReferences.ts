/** 明确的 @路径用于目标召回；引用文字不再拆成 src、扩展名等普通任务词。 */
export interface ProjectPathReference {
  path: string;
  start: number;
  end: number;
}

/** 只识别显式路径语法；邮箱中的 @ 不具备路径引用边界。 */
export const extractProjectPathReferences = (query: string): ProjectPathReference[] => {
  const result: ProjectPathReference[] = [];
  const pattern = /(?<![A-Za-z0-9_@])@(?:`([^`]+)`|"([^"]+)"|'([^']+)'|([^\s,，。;；:：!?！？()（）\[\]【】<>"'`]+))/g;
  for (const match of query.matchAll(pattern)) {
    const path = normalizeReferencedPath(match[1] ?? match[2] ?? match[3] ?? match[4] ?? '');
    if (!path || (!path.includes('/') && !/\.[A-Za-z0-9]+$/.test(path))) continue;
    result.push({ path, start: match.index, end: match.index + match[0].length });
  }
  return result;
};

/** 在已选项目内解析相对路径；只去除真实 rootName，不猜测任意项目名前缀。 */
export const referencedPathVariants = (reference: string, rootName?: string): string[] => {
  const path = normalizeReferencedPath(reference);
  if (!path) return [];
  const root = normalizeReferencedPath(rootName ?? '');
  return root && path.startsWith(`${root}/`)
    ? [path, path.slice(root.length + 1)] : [path];
};

/** 目录匹配以斜线为边界，records 不会命中 records-old。 */
export const matchesReferencedPath = (path: string, reference: string): boolean =>
  path === reference || path.startsWith(`${reference}/`);

const normalizeReferencedPath = (value: string): string => {
  const path = value.trim().replace(/\\/g, '/').replace(/^\.\//, '').replace(/\/+$/, '');
  // 不把绝对路径和父目录跳转转换为所选项目内的另一条路径。
  return !path || path.startsWith('/') || /^[A-Za-z]:/.test(path)
    || path.split('/').some((segment) => segment === '..') ? '' : path.replace(/\/{2,}/g, '/');
};
