import type { ContextFileInput } from '@/types/api';

export const MAX_FILES = 1_000;
export const MAX_FILE_BYTES = 300_000;
export const MAX_BINARY_FILE_BYTES = 1_000_000;
export const MAX_TOTAL_CHARACTERS = 4_000_000;
// 单个文件读取阶段最多保留的字符数；构建锁文件和超大源码只截断，不整文件丢弃。
export const MAX_READ_CHARS_PER_FILE = 64_000;
// 扫描阶段最多检查的目录项数量。十六万乃至百万文件的项目只扫前几万项，
// 避免主线程在收集候选文件时长时间无响应；其余项计入“已跳过”提示。
export const MAX_SCAN_FILES = 50_000;
export const MAX_SPREADSHEET_SHEETS = 8;
export const MAX_SPREADSHEET_ROWS = 200;
export const MAX_SPREADSHEET_COLUMNS = 30;
export const MAX_SPREADSHEET_CHARACTERS = 120_000;

const SUPPORTED_EXTENSIONS = new Set([
  'bmp', 'css', 'doc', 'docx', 'env.example', 'gif', 'go', 'gradle', 'html',
  'java', 'jpeg', 'jpg', 'js', 'json', 'jsx', 'kt', 'kts', 'md', 'png', 'properties',
  'py', 'rs', 'scss', 'sql', 'ts', 'tsx', 'txt', 'vue', 'webp', 'xls', 'xlsx',
  'xml', 'yaml', 'yml', 'c', 'cc', 'cpp', 'cxx', 'h', 'hh', 'hpp', 'hxx', 'cs',
  'rb', 'php', 'swift', 'scala', 'sc', 'dart', 'sh', 'bash', 'zsh', 'fish', 'bat',
  'cmd', 'ps1', 'psm1', 'pl', 'pm', 'lua', 'r', 'rmd', 'erl', 'hrl', 'ex', 'exs',
  'clj', 'cljs', 'cljc', 'hs', 'lhs', 'ml', 'mli', 'fs', 'fsx', 'vb', 'vbs',
  'groovy', 'toml', 'ini', 'cfg', 'conf', 'env', 'dotenv', 'csv', 'tsv',
  'graphql', 'gql', 'prisma', 'proto', 'thrift', 'tf', 'hcl', 'nix', 'lock',
  'mod', 'sum', 'work', 'svelte', 'astro', 'mdx', 'ipynb', 'rst', 'tex', 'adoc',
]);

const BINARY_EXTENSIONS = new Set(['bmp', 'doc', 'docx', 'gif', 'jpeg', 'jpg', 'png', 'webp']);

// 这些文件最能代表技术栈和工程约束，扫描时排在最前，保证不会被后面的文件挤掉。
const PRIORITY_FILE_NAMES = new Set([
  'pom.xml', 'build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts',
  'package.json', 'requirements.txt', 'pyproject.toml', 'go.mod', 'go.sum',
  'cargo.toml', 'cargo.lock', 'composer.json', 'gemfile', 'pubspec.yaml',
  'dockerfile', 'docker-compose.yml', 'docker-compose.yaml', 'compose.yml', 'compose.yaml',
  'readme.md', 'tsconfig.json', 'vite.config.ts', 'vue.config.js', '.env.example',
]);

const SOURCE_EXTENSION_PATTERN = /\.(ts|tsx|js|jsx|vue|svelte|astro|java|kt|kts|py|go|rs|c|cpp|h|hpp|cs|rb|php|swift|scala|dart|sh|ps1|ex|exs|clj|hs|ml|fs)$/i;

// 大型前端项目里这些目录往往包含海量第三方或构建产物，默认跳过可显著提升可用文件的命中率。
export const IGNORED_PATH_SEGMENTS = new Set([
  '.git', 'node_modules', 'target', 'build', 'dist', 'out', '.idea', '.vscode',
  '.next', '.nuxt', 'coverage', '__pycache__', '.venv', 'venv', 'vendor',
]);

// 明确暂不支持的后缀，供界面和用户文档展示，帮助用户提前了解限制。
export const UNSUPPORTED_EXTENSIONS = [
  '.pdf', '.ppt', '.pptx', '.odt', '.ods', '.odp',
  '.wps', '.et', '.dps', '.zip', '.rar', '.7z', '.tar', '.gz',
  '.exe', '.dll', '.so', '.dylib', '.jar', '.class', '.bin',
  '.mp3', '.wav', '.mp4', '.avi', '.mkv', '.mov', '.flv',
  '.ttf', '.otf', '.woff', '.woff2', '.ico', '.svg', '.psd',
] as const;

const LANGUAGE_BY_EXTENSION: Record<string, string> = {
  bmp: 'bmp',
  css: 'css',
  doc: 'doc',
  docx: 'docx',
  gif: 'gif',
  go: 'go',
  gradle: 'gradle',
  html: 'html',
  java: 'java',
  jpeg: 'jpeg',
  jpg: 'jpeg',
  js: 'javascript',
  json: 'json',
  jsx: 'javascript',
  kt: 'kotlin',
  kts: 'kotlin',
  md: 'markdown',
  png: 'png',
  properties: 'properties',
  py: 'python',
  rs: 'rust',
  scss: 'scss',
  sql: 'sql',
  ts: 'typescript',
  tsx: 'typescript',
  txt: 'text',
  vue: 'vue',
  webp: 'webp',
  xls: 'spreadsheet',
  xlsx: 'spreadsheet',
  xml: 'xml',
  yaml: 'yaml',
  yml: 'yaml',
  c: 'c',
  cc: 'cpp',
  cpp: 'cpp',
  cxx: 'cpp',
  h: 'c',
  hh: 'cpp',
  hpp: 'cpp',
  hxx: 'cpp',
  cs: 'csharp',
  rb: 'ruby',
  php: 'php',
  swift: 'swift',
  scala: 'scala',
  sc: 'scala',
  dart: 'dart',
  sh: 'shell',
  bash: 'shell',
  zsh: 'shell',
  fish: 'shell',
  bat: 'batch',
  cmd: 'batch',
  ps1: 'powershell',
  psm1: 'powershell',
  pl: 'perl',
  pm: 'perl',
  lua: 'lua',
  r: 'r',
  rmd: 'rmarkdown',
  erl: 'erlang',
  hrl: 'erlang',
  ex: 'elixir',
  exs: 'elixir',
  clj: 'clojure',
  cljs: 'clojure',
  cljc: 'clojure',
  hs: 'haskell',
  lhs: 'haskell',
  ml: 'ocaml',
  mli: 'ocaml',
  fs: 'fsharp',
  fsx: 'fsharp',
  vb: 'vb',
  vbs: 'vb',
  groovy: 'groovy',
  toml: 'toml',
  ini: 'ini',
  cfg: 'ini',
  conf: 'ini',
  env: 'dotenv',
  dotenv: 'dotenv',
  csv: 'csv',
  tsv: 'tsv',
  graphql: 'graphql',
  gql: 'graphql',
  prisma: 'prisma',
  proto: 'protobuf',
  thrift: 'thrift',
  tf: 'hcl',
  hcl: 'hcl',
  nix: 'nix',
  lock: 'lockfile',
  mod: 'go',
  sum: 'go',
  work: 'go',
  svelte: 'svelte',
  astro: 'astro',
  mdx: 'markdown',
  ipynb: 'json',
  rst: 'restructuredtext',
  tex: 'latex',
  adoc: 'asciidoc',
};

export interface ProjectFileSelection {
  files: ContextFileInput[];
  warnings: string[];
}

export interface FileProcessingProgress {
  current: number;
  total: number;
  fileName: string;
  percent: number;
  accepted: number;
  skipped: number;
  phase: 'scan' | 'read';
}

export type FileProgressCallback = (progress: FileProcessingProgress) => void;

export interface FileScanStats {
  total: number;
  pathIgnored: number;
  unsupported: number;
  oversized: number;
  selected: number;
}

const getExtension = (fileName: string): string => {
  const normalizedName = fileName.toLowerCase();
  if (normalizedName.endsWith('.env.example')) {
    return 'env.example';
  }
  const separatorIndex = normalizedName.lastIndexOf('.');
  return separatorIndex >= 0 ? normalizedName.slice(separatorIndex + 1) : '';
};

/**
 * 只看文件名和大小，从完整 FileList 中挑出值得读取的候选文件。
 * 不读取内容、不创建超大数组，因此十几万甚至上百万文件也能快速扫描。
 */
export const collectCandidateFiles = async (
  fileList: FileList,
  limit: number,
  onProgress?: (scanned: number, total: number, selected: number) => void | Promise<void>,
): Promise<{ files: File[]; stats: FileScanStats }> => {
  const scanEnd = Math.min(fileList.length, MAX_SCAN_FILES);
  const stats: FileScanStats = {
    total: scanEnd,
    pathIgnored: 0,
    unsupported: 0,
    oversized: 0,
    selected: 0,
  };
  const priorityFiles: File[] = [];
  const sourceFiles: File[] = [];
  const otherFiles: File[] = [];
  const progressEvery = 500;

  for (let index = 0; index < scanEnd; index += 1) {
    if (stats.selected >= limit) {
      await onProgress?.(index, scanEnd, stats.selected);
      break;
    }
    const file = fileList[index];
    const relativePath = (file.webkitRelativePath || file.name).replace(/\\/g, '/');
    // 手动扫描路径分隔符，避免每个文件都创建 split 数组，降低十六万文件扫描的开销。
    if (hasIgnoredSegment(relativePath)) {
      stats.pathIgnored += 1;
      continue;
    }
    const extension = getExtension(file.name);
    if (!SUPPORTED_EXTENSIONS.has(extension)) {
      stats.unsupported += 1;
      continue;
    }
    const maxBytes = BINARY_EXTENSIONS.has(extension) ? MAX_BINARY_FILE_BYTES : MAX_FILE_BYTES;
    if (file.size > maxBytes) {
      stats.oversized += 1;
      continue;
    }
    const name = relativePath.slice(relativePath.lastIndexOf('/') + 1).toLowerCase();
    if (PRIORITY_FILE_NAMES.has(name)) {
      priorityFiles.push(file);
    } else if (SOURCE_EXTENSION_PATTERN.test(name)) {
      sourceFiles.push(file);
    } else {
      otherFiles.push(file);
    }
    stats.selected += 1;
    if (index % progressEvery === 0 || index === scanEnd - 1) {
      await onProgress?.(index + 1, scanEnd, stats.selected);
    }
  }

  if (fileList.length > scanEnd) {
    stats.pathIgnored += fileList.length - scanEnd;
  }

  return { files: [...priorityFiles, ...sourceFiles, ...otherFiles].slice(0, limit), stats };
};

/**
 * 判断相对路径中是否包含依赖目录、构建产物或 IDE 目录。
 */
const hasIgnoredSegment = (relativePath: string): boolean => {
  let segmentStart = 0;
  for (let index = 0; index <= relativePath.length; index += 1) {
    if (index < relativePath.length && relativePath[index] !== '/') {
      continue;
    }
    const segment = relativePath.slice(segmentStart, index);
    if (segment && IGNORED_PATH_SEGMENTS.has(segment)) {
      return true;
    }
    segmentStart = index + 1;
  }
  return false;
};

/**
 * 将表格的前几张工作表转换为纯文本，让上下文分析器可以像处理代码文件一样处理表格内容。
 * 行、列、工作表和总字符数均有限制，避免大型导出表拖慢模型请求。
 */
const readSpreadsheetContent = async (file: File): Promise<string> => {
  const XLSX = await import('xlsx');
  const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array', cellText: true });
  const sections: string[] = [`# 表格文件：${file.name}`];
  let remainingCharacters = MAX_SPREADSHEET_CHARACTERS;

  for (const sheetName of workbook.SheetNames.slice(0, MAX_SPREADSHEET_SHEETS)) {
    if (remainingCharacters <= 0) {
      break;
    }

    const worksheet = workbook.Sheets[sheetName];
    if (!worksheet) {
      continue;
    }
    const rows = XLSX.utils.sheet_to_json<unknown[]>(worksheet, {
      header: 1,
      raw: false,
      blankrows: false,
      defval: '',
    });
    const tableText = rows
      .slice(0, MAX_SPREADSHEET_ROWS)
      .map((row) => row
        .slice(0, MAX_SPREADSHEET_COLUMNS)
        .map((cell) => String(cell).replace(/[\t\r\n]+/g, ' ').trim())
        .join('\t'))
      .filter((row) => row.trim().length > 0)
      .join('\n')
      .slice(0, remainingCharacters);

    if (tableText) {
      sections.push(`## 工作表：${sheetName}\n${tableText}`);
      remainingCharacters -= tableText.length;
    }
  }

  return sections.join('\n\n');
};

/**
 * 把二进制内容转换为 Base64。分块拼接避免大数组展开触发调用栈限制。
 */
const toBase64 = (buffer: ArrayBuffer): string => {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
};

const readSupportedFileContent = async (file: File, extension: string): Promise<string> => {
  if (extension === 'xls' || extension === 'xlsx') {
    return readSpreadsheetContent(file);
  }
  if (BINARY_EXTENSIONS.has(extension)) {
    return toBase64(await file.arrayBuffer());
  }
  return file.text();
};

/**
 * 纯文件解析核心，不依赖 Vue。浏览器通过 Web Worker 调用，测试通过主线程直接调用。
 * 每处理一个文件都会回调一次进度，包含当前文件、已接受数量、已跳过数量和百分比。
 */
export const readProjectFiles = async (
  fileList: readonly File[],
  onProgress?: FileProgressCallback,
): Promise<ProjectFileSelection> => {
  const candidates = fileList.slice(0, MAX_FILES);
  const total = candidates.length;
  const warnings: string[] = [];
  const acceptedFiles: ContextFileInput[] = [];
  const unsupportedExtensions = new Set<string>();
  let unsupportedCount = 0;
  let oversizedCount = 0;
  let failedCount = 0;
  let totalCharacters = 0;
  let truncatedCount = 0;
  let skippedLargeBinaryCount = 0;

  const report = (current: number, fileName: string): void => {
    const percent = total === 0 ? 100 : Math.round((current / total) * 100);
    onProgress?.({
      current,
      total,
      fileName,
      percent,
      accepted: acceptedFiles.length,
      skipped: unsupportedCount + oversizedCount + failedCount,
      phase: 'read',
    });
  };

  report(0, '');
  for (let index = 0; index < candidates.length; index += 1) {
    const file = candidates[index];
    report(index, file.name);
    const extension = getExtension(file.name);
    if (!SUPPORTED_EXTENSIONS.has(extension)) {
      unsupportedCount += 1;
      unsupportedExtensions.add(`.${extension}`);
      continue;
    }
    const maxBytes = BINARY_EXTENSIONS.has(extension)
      ? MAX_BINARY_FILE_BYTES
      : MAX_FILE_BYTES;
    if (file.size > maxBytes) {
      oversizedCount += 1;
      continue;
    }

    try {
      const rawContent = await readSupportedFileContent(file, extension);
      const isBinary = BINARY_EXTENSIONS.has(extension);
      // 动态分配剩余字符预算，保证 1,000 个候选文件都能进入上下文，
      // 而不是先读大文件、后把剩余候选全部丢弃。
      const remainingFiles = candidates.length - index;
      const perFileAllowance = Math.max(
        1_024,
        Math.min(
          MAX_READ_CHARS_PER_FILE,
          Math.floor((MAX_TOTAL_CHARACTERS - totalCharacters) / remainingFiles),
        ),
      );
      if (isBinary && rawContent.length > perFileAllowance) {
        // 截断 Base64 会破坏二进制解析，超大二进制文件直接跳过而不是产生坏数据。
        skippedLargeBinaryCount += 1;
        continue;
      }
      const content = !isBinary && rawContent.length > perFileAllowance
        ? rawContent.slice(0, perFileAllowance)
        : rawContent;
      if (!isBinary && rawContent.length > perFileAllowance) {
        truncatedCount += 1;
      }
      totalCharacters += content.length;
      acceptedFiles.push({
        path: file.webkitRelativePath || file.name,
        content,
        language: LANGUAGE_BY_EXTENSION[extension] ?? 'text',
      });
    } catch {
      failedCount += 1;
    }
  }
  report(candidates.length, '');

  if (truncatedCount > 0) {
    warnings.push(`为控制上下文总预算，有 ${truncatedCount} 个文件仅保留了部分内容。`);
  }
  if (skippedLargeBinaryCount > 0) {
    warnings.push(`有 ${skippedLargeBinaryCount} 个超大二进制文件被跳过，以避免破坏二进制解析。`);
  }
  if (fileList.length > MAX_FILES) {
    warnings.push(`一次最多读取 ${MAX_FILES} 个文件，其余文件已忽略。`);
  }
  if (unsupportedCount > 0) {
    const suffixList = Array.from(unsupportedExtensions).sort().join('、');
    warnings.push(`已忽略 ${unsupportedCount} 个暂不支持的文件（${suffixList}）。`);
  }
  if (oversizedCount > 0) {
    warnings.push(`已忽略 ${oversizedCount} 个超过大小限制的文件（文本/表格 300 KB，Word/图片 1 MB）。`);
  }
  if (failedCount > 0) {
    warnings.push(`有 ${failedCount} 个文件读取失败，请重新选择。`);
  }

  return { files: acceptedFiles, warnings };
};
