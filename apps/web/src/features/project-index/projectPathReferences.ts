/** 路径引用用于精确目标召回；普通文件候选须索引命中，引用文字不拆成路径片段或函数名。 */
export interface ProjectPathReference {
  path: string;
  start: number;
  end: number;
  /** 未带@的完整相对文件路径只在当前索引实际命中时成为明确目标。 */
  indexedFileCandidate?: true;
}

/** @支持文件和目录；普通相对文件路径只识别完整文件名，不将职业/行业或URL识别为项目路径。 */
export const extractProjectPathReferences = (query: string): ProjectPathReference[] => {
  const result: ProjectPathReference[] = [];
  const pattern = /(?<![A-Za-z0-9_@])@(?:`([^`]+)`|"([^"]+)"|'([^']+)'|([^\s,，。;；:：!?！？()（）\[\]【】<>"'`]+))/g;
  for (const match of query.matchAll(pattern)) {
    const path = normalizeReferencedPath(match[1] ?? match[2] ?? match[3] ?? match[4] ?? '');
    if (!path || (!path.includes('/') && !/\.[A-Za-z0-9]+$/.test(path))) continue;
    result.push({ path, start: match.index, end: match.index + match[0].length });
  }
  const files = /(?<![\p{L}\p{N}_$./\\:@-])(?:[\p{L}\p{N}_$.-]+[\\/])+[\p{L}\p{N}_$.-]+\.[A-Za-z][A-Za-z0-9]{0,15}(?![\p{L}\p{N}_$./\\-])/gu;
  for (const match of query.matchAll(files)) {
    const start = match.index, end = start + match[0].length;
    if (result.some(reference => start < reference.end && end > reference.start)) continue;
    const path = normalizeReferencedPath(match[0]);
    if (path) result.push({ path, start, end, indexedFileCandidate: true });
  }
  return result.sort((left, right) => left.start - right.start);
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
