import type { ContextFileInput } from '@/types/api';
import { isSafeRelativeFilePath, isSensitiveFile as isProtectedContextFile } from '@/workers/fileReaderCore';

export const INDEX_CHUNK_CHARACTERS = 6_000;
export const MAX_INDEXABLE_FILE_BYTES = 50 * 1024 * 1024;
export const DEFAULT_CONTEXT_CHARACTERS = 120_000;
const INDEX_FORMAT_VERSION = 3;

const DEFAULT_INDEX_LIMITS: ProjectIndexLimits = {
  maxScanFiles: 1_000_000,
  maxIndexBytes: 2 * 1024 * 1024 * 1024,
  maxIndexableFileBytes: MAX_INDEXABLE_FILE_BYTES,
};

const FILE_BATCH_SIZE = 200;
const CHUNK_BATCH_SIZE = 300;
const MAX_PENDING_BATCH_BYTES = 4 * 1024 * 1024;
const TEXT_SAMPLE_BYTES = 8_192;
const MAX_SEARCH_TERMS_PER_CHUNK = 40;
const MAX_RECORDED_CHANGED_PATHS = 500;
const PROGRESS_FILE_INTERVAL = 32;
const PROGRESS_TIME_INTERVAL_MS = 150;
const PROGRESS_BYTE_INTERVAL = 2 * 1024 * 1024;
const RETRIEVAL_SCORE = {
  activeFile: 10_000,
  pinnedFile: 8_000,
  symbolMatch: 1_200,
  directDependency: 900,
  changedFile: 600,
  pathMatch: 400,
  contentMatch: 40,
  projectConfig: 15,
} as const;
/**
 * 同一个文件的片段上限按相关性分层。这个上限只约束单文件，最终结果仍受
 * maxChunks 和 maxCharacters 共同限制，避免一个大文件独占模型上下文。
 */
export const DYNAMIC_CHUNK_LIMITS = {
  default: 3,
  changedFile: 4,
  symbolMatch: 5,
  activeFile: 6,
  pinnedFile: 8,
  relatedFile: 2,
} as const;
const SECRET_ASSIGNMENT_PATTERN = /((?:api[_-]?key|secret|password|token|authorization)\s*[:=]\s*["']?)([^\s"',;]{8,})/gi;
const PRIVATE_KEY_BLOCK_PATTERN = /-----BEGIN [^-]*PRIVATE KEY-----[\s\S]*?-----END [^-]*PRIVATE KEY-----/gi;
const SENSITIVE_NPMRC_PATTERN = /^\s*(?:_auth|_authToken|password|username)\s*=|:_authToken\s*=/im;

const IGNORED_FILE_SUFFIXES = [
  '.log', '.tmp', '.temp', '.cache', '.pid', '.seed', '.trace', '.dump',
  '.class', '.jar', '.war', '.ear', '.dll', '.exe', '.so', '.dylib', '.o', '.obj',
  '.png', '.jpg', '.jpeg', '.gif', '.bmp', '.webp', '.ico', '.pdf', '.zip', '.rar',
  '.7z', '.gz', '.tar', '.mp3', '.wav', '.mp4', '.mov', '.avi', '.woff', '.woff2',
  '.ttf', '.otf', '.map', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
] as const;

const SENSITIVE_FILE_NAMES = new Set([
  '.env', '.env.local', '.env.production', '.env.development',
  'id_rsa', 'id_ed25519', 'credentials', 'credentials.json',
]);

const PRIORITY_FILE_NAMES = new Set([
  'pom.xml', 'build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts',
  'package.json', 'package-lock.json', 'pnpm-lock.yaml', 'yarn.lock',
  'requirements.txt', 'pyproject.toml', 'pipfile', 'poetry.lock',
  'go.mod', 'go.sum', 'cargo.toml', 'cargo.lock', 'composer.json', 'gemfile',
  'dockerfile', 'docker-compose.yml', 'docker-compose.yaml', 'compose.yml', 'compose.yaml',
  'makefile', 'jenkinsfile', 'readme.md', 'tsconfig.json', 'vite.config.ts',
  '.gitignore', '.editorconfig', '.npmrc', '.prettierrc', '.eslintrc', '.env.example',
]);

const LANGUAGE_BY_EXTENSION: Record<string, string> = {
  adoc: 'asciidoc', astro: 'astro', bash: 'shell', bat: 'batch', c: 'c', cc: 'cpp',
  cfg: 'ini', cjs: 'javascript', clj: 'clojure', cljs: 'clojure', conf: 'config',
  cpp: 'cpp', cs: 'csharp', cshtml: 'razor', csproj: 'xml', css: 'css', cts: 'typescript',
  dart: 'dart', ejs: 'html', erb: 'html', ex: 'elixir', exs: 'elixir', fish: 'shell',
  fs: 'fsharp', fsproj: 'xml', fsx: 'fsharp', ftl: 'freemarker', go: 'go', gql: 'graphql',
  gradle: 'gradle', graphql: 'graphql', groovy: 'groovy', h: 'c', hbs: 'handlebars',
  hcl: 'hcl', hh: 'cpp', hpp: 'cpp', hs: 'haskell', html: 'html', hxx: 'cpp',
  ini: 'ini', ipynb: 'json', java: 'java', js: 'javascript', json: 'json', jsonc: 'json',
  jsp: 'jsp', jsx: 'javascript', kt: 'kotlin', kts: 'kotlin', less: 'less', lua: 'lua',
  md: 'markdown', mdx: 'markdown', mjs: 'javascript', ml: 'ocaml', mli: 'ocaml',
  mts: 'typescript', nix: 'nix', php: 'php', phtml: 'php', pl: 'perl', pm: 'perl',
  prisma: 'prisma', properties: 'properties', proto: 'protobuf', ps1: 'powershell',
  psm1: 'powershell', pug: 'pug', py: 'python', pyi: 'python', pyx: 'python',
  r: 'r', rb: 'ruby', razor: 'razor', rmd: 'rmarkdown', rs: 'rust', rst: 'restructuredtext',
  sass: 'sass', sc: 'scala', scala: 'scala', scss: 'scss', sh: 'shell', sln: 'text',
  sql: 'sql', svelte: 'svelte', svg: 'xml', swift: 'swift', tag: 'jsp', tex: 'latex',
  tf: 'hcl', thrift: 'thrift', toml: 'toml', ts: 'typescript', tsv: 'tsv',
  tsx: 'typescript', txt: 'text', vb: 'vb', vbproj: 'xml', vbs: 'vb', vue: 'vue',
  xaml: 'xml', xhtml: 'html', xml: 'xml', yaml: 'yaml', yml: 'yaml', zsh: 'shell',
};

const LANGUAGE_BY_FILE_NAME: Record<string, string> = {
  dockerfile: 'dockerfile', gemfile: 'ruby', jenkinsfile: 'groovy', makefile: 'makefile',
  pipfile: 'toml', procfile: 'text', rakefile: 'ruby', '.gitignore': 'gitignore',
  '.editorconfig': 'editorconfig', '.npmrc': 'ini', '.prettierrc': 'json', '.eslintrc': 'json',
};

export type ProjectIndexStatus = 'INDEXING' | 'PAUSED' | 'READY' | 'FAILED';
export type ProjectIndexMode = 'FULL' | 'INCREMENTAL' | 'RESUME';

export type ProjectSourceEntry =
  | { kind: 'file'; path: string; file: File }
  | { kind: 'ignored-directory'; path: string };

export interface ProjectIndexSummary {
  id: string;
  rootName: string;
  status: ProjectIndexStatus;
  discoveredFiles: number;
  eligibleFiles: number;
  indexedFiles: number;
  ignoredFiles: number;
  ignoredDirectories: number;
  metadataOnlyFiles: number;
  failedFiles: number;
  addedFiles: number;
  updatedFiles: number;
  unchangedFiles: number;
  removedFiles: number;
  changedPaths: string[];
  chunkCount: number;
  indexedCharacters: number;
  estimatedIndexBytes: number;
  scanLimitReached: boolean;
  storageLimitReached: boolean;
  retention: 'SESSION' | 'PERSISTENT';
  sessionId: string;
  expiresAt: string;
  scanId: string;
  lastCheckpointPath: string;
  startedAt: string;
  completedAt?: string;
  pausedAt?: string;
  errorMessage?: string;
}

export interface ProjectIndexProgress {
  phase: 'SCANNING' | 'INDEXING' | 'PAUSED' | 'COMPLETED';
  currentPath: string;
  processedFiles: number;
  totalFiles?: number;
  percent?: number;
  filesPerSecond: number;
  elapsedMs: number;
  etaMs?: number;
  currentFileBytesRead?: number;
  currentFileSize?: number;
  discoveredFiles: number;
  eligibleFiles: number;
  indexedFiles: number;
  ignoredFiles: number;
  failedFiles: number;
  addedFiles: number;
  updatedFiles: number;
  unchangedFiles: number;
  chunkCount: number;
  indexedCharacters: number;
}

export interface IndexedProjectFile {
  id: string;
  projectId: string;
  path: string;
  language: string;
  size: number;
  lastModified: number;
  priority: number;
  chunkCount: number;
  metadataOnly: boolean;
  fingerprint: string;
  lastSeenScanId: string;
  indexedCharacters: number;
  estimatedIndexBytes: number;
  symbols: string[];
  imports: string[];
}

export interface IndexedProjectChunk {
  id: string;
  projectId: string;
  path: string;
  language: string;
  chunkIndex: number;
  content: string;
  priority: number;
  searchTerms: string[];
  symbols: string[];
  imports: string[];
}

export interface ProjectIndexRepository {
  beginProject(summary: ProjectIndexSummary): Promise<void>;
  findFiles(projectId: string, paths: string[]): Promise<IndexedProjectFile[]>;
  writeBatch(
    files: IndexedProjectFile[],
    chunks: IndexedProjectChunk[],
    replacedPaths?: string[],
  ): Promise<void>;
  pauseProject(summary: ProjectIndexSummary): Promise<void>;
  completeProject(summary: ProjectIndexSummary): Promise<void>;
  removeUnseenFiles(projectId: string, scanId: string): Promise<number>;
  failProject(projectId: string, message: string): Promise<void>;
  findCandidateChunks(
    projectId: string,
    searchTerms: string[],
    limit: number,
  ): Promise<IndexedProjectChunk[]>;
  findChunksByPaths(
    projectId: string,
    paths: string[],
    limit: number,
  ): Promise<IndexedProjectChunk[]>;
  deleteProject(projectId: string): Promise<void>;
}

export interface ProjectIndexLimits {
  maxScanFiles: number;
  maxIndexBytes: number;
  maxIndexableFileBytes: number;
}

export interface IndexProjectOptions {
  projectId: string;
  rootName: string;
  entries: AsyncIterable<ProjectSourceEntry>;
  repository: ProjectIndexRepository;
  mode?: ProjectIndexMode;
  limits?: ProjectIndexLimits;
  retention?: 'SESSION' | 'PERSISTENT';
  sessionId?: string;
  expiresAt?: string;
  totalFiles?: number;
  shouldPause?: () => boolean;
  onProgress?: (progress: ProjectIndexProgress) => void;
}

export interface RetrieveProjectContextOptions {
  projectId: string;
  query: string;
  maxCharacters?: number;
  maxChunks?: number;
  activeFilePath?: string;
  pinnedPaths?: string[];
  changedPaths?: string[];
}

export interface ProjectContextSelection {
  path: string;
  chunkIndex: number;
  score: number;
  reasons: string[];
}

export interface ProjectContextRetrievalResult {
  files: ContextFileInput[];
  selections: ProjectContextSelection[];
  totalCharacters: number;
}

interface FileClassification {
  action: 'INDEX' | 'IGNORE' | 'METADATA_ONLY';
  language: string;
  priority: number;
}

interface CodeMetadata {
  symbols: string[];
  imports: string[];
}

interface RankedChunk {
  chunk: IndexedProjectChunk;
  score: number;
  reasons: string[];
}

/**
 * 流式消费目录项并写入仓库。实现过程中只保留有限批次，不会把完整项目装入内存。
 */
export const indexProject = async (options: IndexProjectOptions): Promise<ProjectIndexSummary> => {
  const limits = options.limits ?? DEFAULT_INDEX_LIMITS;
  const mode = options.mode ?? 'FULL';
  const scanId = createScanId();
  const summary: ProjectIndexSummary = {
    id: options.projectId,
    rootName: options.rootName,
    status: 'INDEXING',
    discoveredFiles: 0,
    eligibleFiles: 0,
    indexedFiles: 0,
    ignoredFiles: 0,
    ignoredDirectories: 0,
    metadataOnlyFiles: 0,
    failedFiles: 0,
    addedFiles: 0,
    updatedFiles: 0,
    unchangedFiles: 0,
    removedFiles: 0,
    changedPaths: [],
    chunkCount: 0,
    indexedCharacters: 0,
    estimatedIndexBytes: 0,
    scanLimitReached: false,
    storageLimitReached: false,
    retention: options.retention ?? 'SESSION',
    sessionId: options.sessionId ?? '',
    expiresAt: options.expiresAt ?? '',
    scanId,
    lastCheckpointPath: '',
    startedAt: new Date().toISOString(),
  };
  const pendingFiles: IndexedProjectFile[] = [];
  const pendingChunks: IndexedProjectChunk[] = [];
  const replacedPaths = new Set<string>();
  let sourceBatch: Extract<ProjectSourceEntry, { kind: 'file' }>[] = [];
  let pendingBatchBytes = 0;
  let lastReportedFiles = -1;
  let lastReportedAt = 0;
  let lastReportedFileBytes = 0;
  let lastReportedPath = '';
  const indexingStartedAt = performance.now();

  const flush = async (): Promise<void> => {
    if (pendingFiles.length === 0 && pendingChunks.length === 0 && replacedPaths.size === 0) {
      return;
    }
    await options.repository.writeBatch(
      pendingFiles.splice(0),
      pendingChunks.splice(0),
      Array.from(replacedPaths),
    );
    replacedPaths.clear();
    pendingBatchBytes = 0;
  };

  const flushIfNeeded = async (): Promise<void> => {
    if (
      pendingFiles.length >= FILE_BATCH_SIZE
      || pendingChunks.length >= CHUNK_BATCH_SIZE
      || pendingBatchBytes >= MAX_PENDING_BATCH_BYTES
    ) {
      await flush();
    }
  };

  const report = (
    phase: ProjectIndexProgress['phase'],
    currentPath: string,
    currentFileBytesRead?: number,
    currentFileSize?: number,
  ): void => {
    const now = performance.now();
    const progressUnits = summary.discoveredFiles + summary.ignoredDirectories;
    const processedFiles = summary.discoveredFiles;
    const samePath = currentPath === lastReportedPath;
    const advancedBytes = samePath && currentFileBytesRead !== undefined
      ? currentFileBytesRead - lastReportedFileBytes
      : 0;
    if (
      phase !== 'COMPLETED'
      && lastReportedFiles >= 0
      && progressUnits - lastReportedFiles < PROGRESS_FILE_INTERVAL
      && now - lastReportedAt < PROGRESS_TIME_INTERVAL_MS
      && advancedBytes < PROGRESS_BYTE_INTERVAL
    ) {
      return;
    }
    lastReportedFiles = progressUnits;
    lastReportedAt = now;
    lastReportedPath = currentPath;
    lastReportedFileBytes = currentFileBytesRead ?? 0;
    const elapsedMs = Math.max(0, now - indexingStartedAt);
    const filesPerSecond = elapsedMs > 0 ? processedFiles * 1_000 / elapsedMs : 0;
    const totalFiles = options.totalFiles;
    const percent = totalFiles !== undefined && totalFiles > 0
      ? Math.min(100, Math.round((summary.discoveredFiles / totalFiles) * 100))
      : undefined;
    const remainingFiles = totalFiles === undefined
      ? undefined
      : Math.max(0, totalFiles - summary.discoveredFiles);
    const etaMs = remainingFiles !== undefined && filesPerSecond > 0
      ? remainingFiles / filesPerSecond * 1_000
      : undefined;
    options.onProgress?.({
      phase,
      currentPath,
      processedFiles,
      totalFiles,
      percent,
      filesPerSecond,
      elapsedMs,
      etaMs,
      currentFileBytesRead,
      currentFileSize,
      discoveredFiles: summary.discoveredFiles,
      eligibleFiles: summary.eligibleFiles,
      indexedFiles: summary.indexedFiles,
      ignoredFiles: summary.ignoredFiles,
      failedFiles: summary.failedFiles,
      addedFiles: summary.addedFiles,
      updatedFiles: summary.updatedFiles,
      unchangedFiles: summary.unchangedFiles,
      chunkCount: summary.chunkCount,
      indexedCharacters: summary.indexedCharacters,
    });
  };

  const pauseIfRequested = async (): Promise<boolean> => {
    if (!options.shouldPause?.()) {
      return false;
    }
    await flush();
    summary.status = 'PAUSED';
    summary.pausedAt = new Date().toISOString();
    await options.repository.pauseProject(summary);
    report('PAUSED', summary.lastCheckpointPath);
    return true;
  };

  const processSourceBatch = async (): Promise<boolean> => {
    if (sourceBatch.length === 0) {
      return pauseIfRequested();
    }
    const batch = sourceBatch;
    sourceBatch = [];
    const existingFiles = mode === 'FULL'
      ? []
      : await options.repository.findFiles(
          options.projectId,
          batch.map((entry) => entry.path),
        );
    const existingByPath = new Map(existingFiles.map((file) => [file.path, file]));

    for (const entry of batch) {
      summary.discoveredFiles += 1;
      summary.lastCheckpointPath = entry.path;
      // 必须在内容读取和增量复用之前过滤，旧索引记录也不能绕过当前安全边界。
      if (!isSafeRelativeFilePath(entry.path) || isProtectedContextFile(entry.path.replace(/\\/g, '/'))) {
        summary.ignoredFiles += 1;
        report('INDEXING', entry.path);
        continue;
      }
      const fingerprint = fingerprintOf(entry.file);
      const existing = existingByPath.get(entry.path);
      if (existing?.fingerprint === fingerprint) {
        const restoredCharacters = existing.indexedCharacters ?? 0;
        const restoredBytes = existing.estimatedIndexBytes ?? estimateFileMetadataBytes(entry.path);
        pendingFiles.push({
          ...existing,
          symbols: existing.symbols ?? [],
          imports: existing.imports ?? [],
          lastSeenScanId: scanId,
        });
        pendingBatchBytes += estimateFileMetadataBytes(entry.path);
        summary.eligibleFiles += 1;
        summary.unchangedFiles += 1;
        summary.chunkCount += existing.chunkCount;
        summary.indexedCharacters += restoredCharacters;
        summary.estimatedIndexBytes += restoredBytes;
        if (existing.metadataOnly) {
          summary.metadataOnlyFiles += 1;
        } else {
          summary.indexedFiles += 1;
        }
        await flushIfNeeded();
        report('INDEXING', entry.path);
        continue;
      }

      const classification = await classifyFile(
        entry.path,
        entry.file,
        limits.maxIndexableFileBytes,
      );
      if (classification.action === 'IGNORE') {
        summary.ignoredFiles += 1;
        report('INDEXING', entry.path);
        continue;
      }

      summary.eligibleFiles += 1;
      if (existing) {
        summary.updatedFiles += 1;
        replacedPaths.add(entry.path);
      } else {
        summary.addedFiles += 1;
      }
      if (mode !== 'FULL' && summary.changedPaths.length < MAX_RECORDED_CHANGED_PATHS) {
        summary.changedPaths.push(entry.path);
      }

      const metadataBytes = estimateFileMetadataBytes(entry.path);
      if (classification.action === 'METADATA_ONLY' || summary.storageLimitReached) {
        summary.metadataOnlyFiles += 1;
        pendingFiles.push(createFileRecord(
          options.projectId,
          entry,
          classification,
          0,
          true,
          fingerprint,
          scanId,
          0,
          metadataBytes,
          [],
          [],
        ));
        pendingBatchBytes += metadataBytes;
        summary.estimatedIndexBytes += metadataBytes;
        await flushIfNeeded();
        report('INDEXING', entry.path);
        continue;
      }

      try {
        let chunkCount = 0;
        let fileCharacters = 0;
        let fileEstimatedBytes = metadataBytes;
        let limitedByStorage = false;
        const fileSymbols = new Set<string>();
        const fileImports = new Set<string>();
        for await (const content of streamTextChunks(
          entry.file,
          classification.language,
          (bytesRead) => report('INDEXING', entry.path, bytesRead, entry.file.size),
        )) {
          const codeMetadata = extractCodeMetadata(content, classification.language);
          codeMetadata.symbols.forEach((symbol) => fileSymbols.add(symbol));
          codeMetadata.imports.forEach((reference) => fileImports.add(reference));
          const searchTerms = buildSearchTerms(options.projectId, entry.path, content);
          const estimatedChunkBytes = estimateChunkBytes(
            content,
            searchTerms,
            codeMetadata.symbols,
            codeMetadata.imports,
          );
          if (summary.estimatedIndexBytes + fileEstimatedBytes + estimatedChunkBytes
            > limits.maxIndexBytes) {
            summary.storageLimitReached = true;
            limitedByStorage = true;
            break;
          }
          pendingChunks.push({
            id: `${options.projectId}\u0000${entry.path}\u0000${chunkCount}`,
            projectId: options.projectId,
            path: entry.path,
            language: classification.language,
            chunkIndex: chunkCount,
            content,
            priority: classification.priority,
            searchTerms,
            symbols: codeMetadata.symbols,
            imports: codeMetadata.imports,
          });
          pendingBatchBytes += estimatedChunkBytes;
          chunkCount += 1;
          fileCharacters += content.length;
          fileEstimatedBytes += estimatedChunkBytes;
        }
        pendingFiles.push(createFileRecord(
          options.projectId,
          entry,
          classification,
          chunkCount,
          limitedByStorage,
          fingerprint,
          scanId,
          fileCharacters,
          fileEstimatedBytes,
          Array.from(fileSymbols),
          Array.from(fileImports),
        ));
        pendingBatchBytes += metadataBytes;
        summary.chunkCount += chunkCount;
        summary.indexedCharacters += fileCharacters;
        summary.estimatedIndexBytes += fileEstimatedBytes;
        if (chunkCount > 0) {
          summary.indexedFiles += 1;
        } else {
          summary.metadataOnlyFiles += 1;
        }
      } catch {
        summary.failedFiles += 1;
      }
      await flushIfNeeded();
      report('INDEXING', entry.path);
    }

    await flush();
    report('INDEXING', summary.lastCheckpointPath);
    return pauseIfRequested();
  };

  await options.repository.beginProject(summary);
  report('SCANNING', '');

  try {
    for await (const entry of options.entries) {
      if (entry.kind === 'ignored-directory') {
        summary.ignoredDirectories += 1;
        report('SCANNING', entry.path);
        if (summary.ignoredDirectories % FILE_BATCH_SIZE === 0 && await pauseIfRequested()) {
          return summary;
        }
        continue;
      }

      if (summary.discoveredFiles + sourceBatch.length >= limits.maxScanFiles) {
        summary.scanLimitReached = true;
        break;
      }
      sourceBatch.push(entry);
      if (sourceBatch.length >= FILE_BATCH_SIZE && await processSourceBatch()) {
        return summary;
      }
    }

    if (await processSourceBatch()) {
      return summary;
    }
    summary.removedFiles = await options.repository.removeUnseenFiles(options.projectId, scanId);
    summary.status = 'READY';
    summary.completedAt = new Date().toISOString();
    await options.repository.completeProject(summary);
    report('COMPLETED', '');
    return summary;
  } catch (error) {
    const message = error instanceof Error ? error.message : '项目索引失败';
    summary.status = 'FAILED';
    summary.errorMessage = message;
    await options.repository.failProject(options.projectId, message);
    throw error;
  }
};

/**
 * 从本地索引中取回和当前任务最相关的代码块，模型只接收这部分内容。
 */
export const retrieveProjectContext = async (
  repository: ProjectIndexRepository,
  options: RetrieveProjectContextOptions,
): Promise<ContextFileInput[]> =>
  (await retrieveProjectContextWithReport(repository, options)).files;

/**
 * 统一完成候选召回、优先路径加权、直接依赖扩展和上下文预算裁剪。
 * 调用方只需要提供任务文本和可选路径提示，不需要理解内部评分规则。
 */
export const retrieveProjectContextWithReport = async (
  repository: ProjectIndexRepository,
  options: RetrieveProjectContextOptions,
): Promise<ProjectContextRetrievalResult> => {
  const maxCharacters = options.maxCharacters ?? DEFAULT_CONTEXT_CHARACTERS;
  const maxChunks = options.maxChunks ?? 40;
  const terms = extractTerms(options.query);
  const pathHints = createPathHints(options);
  const preferredPaths = createPreferredPaths(options);
  const candidateLimit = Math.max(200, maxChunks * 10);
  const [matchedChunks, preferredChunks] = await Promise.all([
    repository.findCandidateChunks(options.projectId, terms, candidateLimit),
    preferredPaths.length > 0
      ? repository.findChunksByPaths(options.projectId, preferredPaths, candidateLimit)
      : Promise.resolve([]),
  ]);
  const candidates = uniqueChunks([...matchedChunks, ...preferredChunks]);
  const seedChunks = rankChunks(candidates, terms, pathHints, new Set())
    .slice(0, Math.min(8, maxChunks));
  const dependencyTerms = new Set(
    seedChunks.flatMap(({ chunk }) => chunk.imports ?? []).slice(0, 64),
  );

  if (dependencyTerms.size > 0) {
    const relatedChunks = await repository.findCandidateChunks(
      options.projectId,
      Array.from(dependencyTerms),
      candidateLimit,
    );
    for (const chunk of relatedChunks) {
      candidates.set(chunk.id, normalizeChunk(chunk));
    }
  }

  const ranked = rankChunks(candidates, terms, pathHints, dependencyTerms);

  const selected: ContextFileInput[] = [];
  const selections: ProjectContextSelection[] = [];
  const chunksPerPath = new Map<string, number>();
  let usedCharacters = 0;
  for (const { chunk, score, reasons } of ranked) {
    if (selected.length >= maxChunks || usedCharacters >= maxCharacters) {
      break;
    }
    const selectedFromPath = chunksPerPath.get(chunk.path) ?? 0;
    if (selectedFromPath >= getChunkSelectionLimit(reasons)) {
      continue;
    }
    const remaining = maxCharacters - usedCharacters;
    const content = chunk.content.slice(0, remaining);
    if (!content) {
      continue;
    }
    selected.push({
      path: `${chunk.path}#chunk-${chunk.chunkIndex + 1}`,
      content: redactSecrets(content),
      language: chunk.language,
    });
    selections.push({
      path: chunk.path,
      chunkIndex: chunk.chunkIndex,
      score,
      reasons,
    });
    chunksPerPath.set(chunk.path, selectedFromPath + 1);
    usedCharacters += content.length;
  }
  return { files: selected, selections, totalCharacters: usedCharacters };
};

/**
 * 根据片段入选原因计算单文件配额。
 * 多个条件同时满足时取最高配额，例如当前打开且被用户固定的文件最多 8 段。
 */
export const getChunkSelectionLimit = (reasons: readonly string[]): number => {
  const reasonSet = new Set(reasons);
  if (reasonSet.has('用户固定文件')) {
    return DYNAMIC_CHUNK_LIMITS.pinnedFile;
  }
  if (reasonSet.has('当前打开文件')) {
    return DYNAMIC_CHUNK_LIMITS.activeFile;
  }
  if (reasonSet.has('任务中的符号')) {
    return DYNAMIC_CHUNK_LIMITS.symbolMatch;
  }
  if (reasonSet.has('最近变更文件')) {
    return DYNAMIC_CHUNK_LIMITS.changedFile;
  }
  if (reasonSet.has('被高相关代码直接依赖') || reasonSet.has('关键工程配置')) {
    return DYNAMIC_CHUNK_LIMITS.relatedFile;
  }
  return DYNAMIC_CHUNK_LIMITS.default;
};

const classifyFile = async (
  path: string,
  file: File,
  maxIndexableFileBytes: number,
): Promise<FileClassification> => {
  const normalizedPath = path.replace(/\\/g, '/').toLowerCase();
  const fileName = normalizedPath.slice(normalizedPath.lastIndexOf('/') + 1);
  if (isSensitiveFile(fileName) || isIgnoredFile(normalizedPath)) {
    return { action: 'IGNORE', language: 'binary', priority: 100 };
  }
  if (fileName === '.npmrc' && await containsSensitiveConfiguration(file)) {
    return { action: 'IGNORE', language: 'sensitive', priority: 100 };
  }

  const language = languageOf(fileName);
  if (!language && !(await looksLikeText(file))) {
    return { action: 'IGNORE', language: 'binary', priority: 100 };
  }
  if (language && !(await looksLikeText(file))) {
    return { action: 'IGNORE', language: 'binary', priority: 100 };
  }
  if (file.size > maxIndexableFileBytes && !language) {
    return { action: 'METADATA_ONLY', language: 'text', priority: priorityOf(fileName) };
  }
  return {
    action: 'INDEX',
    language: language ?? 'text',
    priority: priorityOf(fileName),
  };
};

const estimateFileMetadataBytes = (path: string): number => path.length * 2 + 512;

const estimateChunkBytes = (
  content: string,
  searchTerms: string[],
  symbols: string[],
  imports: string[],
): number =>
  content.length * 2
  + searchTerms.reduce((total, term) => total + term.length * 2, 0)
  + symbols.reduce((total, symbol) => total + symbol.length * 2, 0)
  + imports.reduce((total, reference) => total + reference.length * 2, 0)
  + 1_024;

const createFileRecord = (
  projectId: string,
  entry: Extract<ProjectSourceEntry, { kind: 'file' }>,
  classification: FileClassification,
  chunkCount: number,
  metadataOnly: boolean,
  fingerprint: string,
  scanId: string,
  indexedCharacters: number,
  estimatedIndexBytes: number,
  symbols: string[],
  imports: string[],
): IndexedProjectFile => ({
  id: `${projectId}\u0000${entry.path}`,
  projectId,
  path: entry.path,
  language: classification.language,
  size: entry.file.size,
  lastModified: entry.file.lastModified,
  priority: classification.priority,
  chunkCount,
  metadataOnly,
  fingerprint,
  lastSeenScanId: scanId,
  indexedCharacters,
  estimatedIndexBytes,
  symbols,
  imports,
});

/**
 * 浏览器提供的大小和修改时间无需读取正文，适合作为增量扫描的第一层快速指纹。
 * 后续如需识别“大小与时间均未变化但内容变化”的极端情况，可再增加按需内容哈希。
 */
const fingerprintOf = (file: File): string =>
  `${INDEX_FORMAT_VERSION}:${file.size}:${file.lastModified}`;

const createScanId = (): string => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `scan-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

const isSensitiveFile = (fileName: string): boolean =>
  SENSITIVE_FILE_NAMES.has(fileName)
  || (fileName.startsWith('.env.') && fileName !== '.env.example')
  || fileName.endsWith('.pem')
  || fileName.endsWith('.key')
  || fileName.endsWith('.p12')
  || fileName.endsWith('.jks');

const isIgnoredFile = (path: string): boolean => {
  if (path.split('/').some((segment) => segment === 'logs' || segment === 'log')) {
    return true;
  }
  return IGNORED_FILE_SUFFIXES.some((suffix) => path.endsWith(suffix));
};

const languageOf = (fileName: string): string | undefined => {
  const byName = LANGUAGE_BY_FILE_NAME[fileName];
  if (byName) {
    return byName;
  }
  if (fileName === '.env.example') {
    return 'dotenv';
  }
  const separator = fileName.lastIndexOf('.');
  return separator >= 0 ? LANGUAGE_BY_EXTENSION[fileName.slice(separator + 1)] : undefined;
};

const priorityOf = (fileName: string): number => {
  if (PRIORITY_FILE_NAMES.has(fileName)) {
    return 0;
  }
  return languageOf(fileName) ? 10 : 20;
};

const looksLikeText = async (file: File): Promise<boolean> => {
  if (file.size === 0) {
    return true;
  }
  const sample = new Uint8Array(await file.slice(0, TEXT_SAMPLE_BYTES).arrayBuffer());
  let suspiciousBytes = 0;
  for (const byte of sample) {
    if (byte === 0) {
      return false;
    }
    if (byte < 9 || (byte > 13 && byte < 32)) {
      suspiciousBytes += 1;
    }
  }
  return suspiciousBytes / sample.length < 0.08;
};

const containsSensitiveConfiguration = async (file: File): Promise<boolean> => {
  const sample = await file.slice(0, 64 * 1024).text();
  return SENSITIVE_NPMRC_PATTERN.test(sample);
};

const STRUCTURED_CHUNK_LANGUAGES = new Set([
  'c', 'cpp', 'csharp', 'fsharp', 'go', 'java', 'javascript', 'kotlin', 'php', 'python',
  'ruby', 'rust', 'scala', 'swift', 'typescript', 'vue', 'xml', 'yaml', 'json', 'toml',
  'ini', 'sql', 'hcl', 'razor', 'jsp', 'pug', 'less', 'sass',
]);

const STRUCTURED_BOUNDARY_PATTERN = /^(?:\s*)(?:class|interface|enum|record|struct|function|async\s+function|def|async\s+def|func|fn|public|private|protected|export|import|package|module|\b[A-Za-z_$][\w$]*\s*[:=]|[-#]{2,}|<\/?[A-Za-z])/;

/**
 * 优先在声明、函数、类和配置边界切块；当单块超过两倍窗口仍没有边界时强制切块。
 * 这是一种无 AST 依赖的启发式策略，保证浏览器本地索引不因某种语言解析失败而丢失正文。
 */
const streamTextChunks = async function* (
  file: File,
  language: string,
  onBytesRead?: (bytesRead: number) => void,
): AsyncGenerator<string> {
  const reader = file.stream().getReader();
  const decoder = new TextDecoder('utf-8', { fatal: false });
  let buffer = '';
  let chunk = '';
  const structured = STRUCTURED_CHUNK_LANGUAGES.has(language);
  let bytesRead = 0;

  const flushAtLineBoundary = function* (): Generator<string> {
    const lines = buffer.split(/(?<=\n)/);
    buffer = lines.pop() ?? '';
    for (const line of lines) {
      const atBoundary = structured && STRUCTURED_BOUNDARY_PATTERN.test(line);
      const reachedWindow = chunk.length >= INDEX_CHUNK_CHARACTERS;
      if (reachedWindow && (atBoundary || !structured)) {
        yield chunk;
        chunk = '';
      }
      chunk += line;
      if (chunk.length >= INDEX_CHUNK_CHARACTERS * 2) {
        yield chunk;
        chunk = '';
      }
    }
  };

  try {
    while (true) {
      const result = await reader.read();
      if (result.done) {
        break;
      }
      bytesRead += result.value.byteLength;
      onBytesRead?.(bytesRead);
      buffer += decoder.decode(result.value, { stream: true });
      yield* flushAtLineBoundary();
    }
    buffer += decoder.decode();
    chunk += buffer;
    if (chunk.length > 0) {
      yield chunk;
    }
  } finally {
    reader.releaseLock();
  }
};

const buildSearchTerms = (projectId: string, path: string, content: string): string[] =>
  extractTerms(`${path}\n${content}`)
    .slice(0, MAX_SEARCH_TERMS_PER_CHUNK)
    .map((term) => `${projectId}:${term}`);

const extractCodeMetadata = (content: string, language: string): CodeMetadata => {
  const symbols = new Set<string>();
  const imports = new Set<string>();
  const addSymbolMatches = (pattern: RegExp): void => {
    for (const match of content.matchAll(pattern)) {
      const symbol = match[1]?.toLowerCase();
      if (symbol && !CONTROL_FLOW_NAMES.has(symbol)) {
        symbols.add(symbol);
      }
    }
  };

  addSymbolMatches(/\b(?:class|interface|enum|record|trait|struct|type)\s+([A-Za-z_$][\w$]*)/g);
  addSymbolMatches(/\b(?:function|def|fn)\s+([A-Za-z_$][\w$]*)/g);
  addSymbolMatches(/\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)/g);
  if (SOURCE_CODE_LANGUAGES.has(language)) {
    addSymbolMatches(/^\s*(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/gm);
    addSymbolMatches(/[;{}]\s*(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/g);
    addSymbolMatches(
      /^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|suspend|override)\s+)*(?:[\w$<>\[\],.?]+\s+)+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^\{]+)?\{/gm,
    );
  }

  const addImportMatches = (pattern: RegExp): void => {
    for (const match of content.matchAll(pattern)) {
      for (const term of extractTerms(match[1] ?? '')) {
        imports.add(term);
      }
    }
  };
  addImportMatches(/\bfrom\s+["']([^"']+)["']/g);
  addImportMatches(/\b(?:import|require)\s*\(?\s*["']([^"']+)["']/g);
  addImportMatches(/^\s*import\s+([A-Za-z0-9_.$/:-]+)\s*;?/gm);
  addImportMatches(/^\s*from\s+([A-Za-z0-9_.$/:-]+)\s+import\s+/gm);

  return {
    symbols: Array.from(symbols).slice(0, 128),
    imports: Array.from(imports).slice(0, 128),
  };
};

const extractTerms = (value: string): string[] => {
  const terms = new Set<string>();
  const normalized = value.toLowerCase();
  for (const match of normalized.matchAll(/[a-z_$][a-z0-9_$.-]{1,63}/g)) {
    terms.add(match[0]);
    for (const part of match[0].split(/[._$-]+/)) {
      if (part.length >= 2) {
        terms.add(part);
      }
    }
  }
  for (const match of normalized.matchAll(/[\u3400-\u9fff]{2,16}/g)) {
    const text = match[0];
    terms.add(text);
    for (let index = 0; index < text.length - 1; index += 1) {
      terms.add(text.slice(index, index + 2));
    }
  }
  return Array.from(terms);
};

const createPreferredPaths = (options: RetrieveProjectContextOptions): string[] =>
  Array.from(new Set([
    options.activeFilePath,
    ...(options.pinnedPaths ?? []),
    ...(options.changedPaths ?? []),
  ].filter((path): path is string => Boolean(path?.trim()))
    .map((path) => path.replace(/\\/g, '/'))));

const createPathHints = (options: RetrieveProjectContextOptions): Map<string, string[]> => {
  const hints = new Map<string, string[]>();
  const add = (path: string | undefined, reason: string): void => {
    if (!path?.trim()) {
      return;
    }
    const normalized = normalizePath(path);
    const reasons = hints.get(normalized) ?? [];
    if (!reasons.includes(reason)) {
      reasons.push(reason);
    }
    hints.set(normalized, reasons);
  };
  add(options.activeFilePath, '当前打开文件');
  options.pinnedPaths?.forEach((path) => add(path, '用户固定文件'));
  options.changedPaths?.forEach((path) => add(path, '最近变更文件'));
  return hints;
};

const uniqueChunks = (chunks: IndexedProjectChunk[]): Map<string, IndexedProjectChunk> => {
  const unique = new Map<string, IndexedProjectChunk>();
  for (const chunk of chunks) {
    unique.set(chunk.id, normalizeChunk(chunk));
  }
  return unique;
};

const normalizeChunk = (chunk: IndexedProjectChunk): IndexedProjectChunk => ({
  ...chunk,
  symbols: chunk.symbols ?? [],
  imports: chunk.imports ?? [],
});

const rankChunks = (
  chunks: Map<string, IndexedProjectChunk>,
  terms: string[],
  pathHints: Map<string, string[]>,
  dependencyTerms: Set<string>,
): RankedChunk[] => Array.from(chunks.values())
  .map((chunk) => scoreChunk(chunk, terms, pathHints, dependencyTerms))
  .sort((left, right) =>
    right.score - left.score
    || left.chunk.priority - right.chunk.priority
    || left.chunk.path.localeCompare(right.chunk.path)
    || left.chunk.chunkIndex - right.chunk.chunkIndex);

const scoreChunk = (
  chunk: IndexedProjectChunk,
  terms: string[],
  pathHints: Map<string, string[]>,
  dependencyTerms: Set<string>,
): RankedChunk => {
  const path = chunk.path.toLowerCase();
  const content = chunk.content.toLowerCase();
  const symbols = chunk.symbols ?? [];
  const reasons = [...(pathHints.get(normalizePath(chunk.path)) ?? [])];
  let score = Math.max(0, 30 - chunk.priority);
  if (reasons.includes('当前打开文件')) {
    score += RETRIEVAL_SCORE.activeFile;
  }
  if (reasons.includes('用户固定文件')) {
    score += RETRIEVAL_SCORE.pinnedFile;
  }
  if (reasons.includes('最近变更文件')) {
    score += RETRIEVAL_SCORE.changedFile;
  }
  if (chunk.priority === 0) {
    score += RETRIEVAL_SCORE.projectConfig;
    reasons.push('关键工程配置');
  }
  for (const term of terms) {
    if (symbols.some((symbol) => symbol.includes(term) || term.includes(symbol))) {
      score += RETRIEVAL_SCORE.symbolMatch;
      addReason(reasons, '任务中的符号');
    }
    if (path.includes(term)) {
      score += RETRIEVAL_SCORE.pathMatch;
      addReason(reasons, '路径与任务匹配');
    }
    if (content.includes(term)) {
      score += RETRIEVAL_SCORE.contentMatch;
      addReason(reasons, '内容与任务匹配');
    }
  }
  const pathTerms = extractTerms(path);
  const isDependency = symbols.some((symbol) => dependencyTerms.has(symbol))
    || pathTerms.some((term) => dependencyTerms.has(term));
  if (isDependency) {
    score += RETRIEVAL_SCORE.directDependency;
    addReason(reasons, '被高相关代码直接依赖');
  }
  if (reasons.length === 0) {
    reasons.push('项目索引候选');
  }
  return { chunk, score, reasons };
};

const normalizePath = (path: string): string => path.replace(/\\/g, '/').toLowerCase();

const addReason = (reasons: string[], reason: string): void => {
  if (!reasons.includes(reason)) {
    reasons.push(reason);
  }
};

const CONTROL_FLOW_NAMES = new Set(['if', 'for', 'while', 'switch', 'catch']);

const SOURCE_CODE_LANGUAGES = new Set([
  'c', 'cpp', 'csharp', 'dart', 'elixir', 'fsharp', 'go', 'groovy', 'java', 'javascript',
  'kotlin', 'ocaml', 'perl', 'php', 'python', 'ruby', 'rust', 'scala', 'swift', 'typescript',
  'vue',
]);

/**
 * 本地索引允许保留源码，但任何离开浏览器的代码块都必须再次执行轻量脱敏。
 */
const redactSecrets = (content: string): string =>
  content
    .replace(PRIVATE_KEY_BLOCK_PATTERN, '[REDACTED PRIVATE KEY]')
    .replace(SECRET_ASSIGNMENT_PATTERN, '$1[REDACTED]');
