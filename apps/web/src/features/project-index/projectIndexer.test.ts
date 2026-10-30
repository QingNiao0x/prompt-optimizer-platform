import { describe, expect, it, vi } from 'vitest';

import {
  indexProject,
  retrieveProjectContext,
  retrieveProjectContextWithReport,
  getChunkSelectionLimit,
  type IndexedProjectChunk,
  type IndexedProjectFile,
  type ProjectIndexRepository,
  type ProjectIndexSummary,
  type ProjectIndexProgress,
  type ProjectSourceEntry,
} from './projectIndexer';
import { matchesReferencedPath, referencedPathVariants } from './projectPathReferences';

class MemoryProjectIndexRepository implements ProjectIndexRepository {
  readonly files: IndexedProjectFile[] = [];
  readonly chunks: IndexedProjectChunk[] = [];
  summary?: ProjectIndexSummary;

  async beginProject(summary: ProjectIndexSummary): Promise<void> {
    this.summary = summary;
  }

  async findFiles(projectId: string, paths: string[]): Promise<IndexedProjectFile[]> {
    const acceptedPaths = new Set(paths);
    return this.files.filter((file) =>
      file.projectId === projectId && acceptedPaths.has(file.path));
  }

  async writeBatch(
    files: IndexedProjectFile[],
    chunks: IndexedProjectChunk[],
    replacedPaths: string[] = [],
  ): Promise<void> {
    const replaced = new Set(replacedPaths);
    if (replaced.size > 0) {
      removeMatching(this.chunks, (chunk) =>
        files.some((file) => file.projectId === chunk.projectId)
        && replaced.has(chunk.path));
    }
    for (const file of files) {
      const existingIndex = this.files.findIndex((item) => item.id === file.id);
      if (existingIndex >= 0) {
        this.files[existingIndex] = file;
      } else {
        this.files.push(file);
      }
    }
    for (const chunk of chunks) {
      const existingIndex = this.chunks.findIndex((item) => item.id === chunk.id);
      if (existingIndex >= 0) {
        this.chunks[existingIndex] = chunk;
      } else {
        this.chunks.push(chunk);
      }
    }
  }

  async pauseProject(summary: ProjectIndexSummary): Promise<void> {
    this.summary = summary;
  }

  async completeProject(summary: ProjectIndexSummary): Promise<void> {
    this.summary = summary;
  }

  async removeUnseenFiles(projectId: string, scanId: string): Promise<number> {
    const removedPaths = new Set(
      this.files
        .filter((file) => file.projectId === projectId && file.lastSeenScanId !== scanId)
        .map((file) => file.path),
    );
    removeMatching(this.files, (file) =>
      file.projectId === projectId && removedPaths.has(file.path));
    removeMatching(this.chunks, (chunk) =>
      chunk.projectId === projectId && removedPaths.has(chunk.path));
    return removedPaths.size;
  }

  async failProject(projectId: string, message: string): Promise<void> {
    if (this.summary?.id === projectId) {
      this.summary = { ...this.summary, status: 'FAILED', errorMessage: message };
    }
  }

  async findCandidateChunks(
    projectId: string,
    searchTerms: string[],
    limit: number,
  ): Promise<IndexedProjectChunk[]> {
    const terms = searchTerms.map((term) => `${projectId}:${term}`);
    return this.chunks
      .filter((chunk) => chunk.projectId === projectId)
      .filter((chunk) => chunk.priority === 0
        || terms.length === 0
        || terms.some((term) => chunk.searchTerms.includes(term)))
      .slice(0, limit);
  }

  async findChunksByPaths(
    projectId: string,
    paths: string[],
    limit: number,
  ): Promise<IndexedProjectChunk[]> {
    const acceptedPaths = paths.flatMap((path) => referencedPathVariants(path, this.summary?.rootName));
    return this.chunks
      .filter((chunk) => chunk.projectId === projectId && acceptedPaths.some((path) =>
        matchesReferencedPath(chunk.path, path)))
      .slice(0, limit);
  }

  async deleteProject(projectId: string): Promise<void> {
    removeMatching(this.files, (file) => file.projectId === projectId);
    removeMatching(this.chunks, (chunk) => chunk.projectId === projectId);
    this.summary = this.summary?.id === projectId ? undefined : this.summary;
  }
}

const removeMatching = <T>(items: T[], predicate: (item: T) => boolean): void => {
  for (let index = items.length - 1; index >= 0; index -= 1) {
    const item = items[index];
    if (item !== undefined && predicate(item)) {
      items.splice(index, 1);
    }
  }
};

const sourceOf = async function* (entries: ProjectSourceEntry[]): AsyncGenerator<ProjectSourceEntry> {
  for (const entry of entries) {
    yield entry;
  }
};

const sourceFile = (path: string, content: string): ProjectSourceEntry => ({
  kind: 'file',
  path,
  file: new File([content], path.split('/').at(-1) ?? path, {
    type: 'text/plain',
    lastModified: 1,
  }),
});

const versionedSourceFile = (
  path: string,
  content: string,
  lastModified: number,
): ProjectSourceEntry => ({
  kind: 'file',
  path,
  file: new File([content], path.split('/').at(-1) ?? path, {
    type: 'text/plain',
    lastModified,
  }),
});

describe('indexProject', () => {
  it('preloads small files with bounded concurrency and retains complete content and metadata', async () => {
    let active = 0;
    let peak = 0;
    const entries = Array.from({ length: 17 }, (_, index) => {
      const file = new File([`import { tail } from './tail';\nexport const marker${index} = '中文😀';`], `${index}.ts`);
      const original = file.arrayBuffer.bind(file);
      vi.spyOn(file, 'arrayBuffer').mockImplementation(async () => {
        active++; peak = Math.max(peak, active);
        await new Promise(resolve => setTimeout(resolve, 1));
        const bytes = await original(); active--; return bytes;
      });
      return { kind: 'file' as const, path: file.name, file };
    });
    const repository = new MemoryProjectIndexRepository();
    const summary = await indexProject({ projectId: 'parallel', rootName: 'demo', repository, entries: sourceOf(entries) });
    expect(summary.indexedFiles).toBe(17);
    expect(peak).toBeGreaterThan(1);
    expect(peak).toBeLessThanOrEqual(6);
    for (const entry of entries) {
      expect(entry.file.arrayBuffer).toHaveBeenCalledOnce();
      expect(repository.chunks.filter(c => c.path === entry.path).map(c => c.content).join('')).toBe(await entry.file.text());
    }
    expect(repository.files.every(f => f.imports.includes('tail'))).toBe(true);
  });

  it('counts failures and empty files separately and never finishes progress before saving', async () => {
    const repository = new MemoryProjectIndexRepository();
    const file = new File(['unreadable'], 'broken.ts');
    vi.spyOn(file, 'arrayBuffer').mockRejectedValue(new Error('unreadable'));
    const secret = new File(['private'], '.env');
    const secretRead = vi.spyOn(secret, 'arrayBuffer');
    const progress: ProjectIndexProgress[] = [];
    const complete = repository.completeProject.bind(repository);
    repository.completeProject = async summary => {
      expect(progress.every(value => (value.percent ?? 0) < 100)).toBe(true);
      await complete(summary);
    };
    const summary = await indexProject({ projectId: 'counts', rootName: 'demo', repository, totalFiles: 4,
      entries: sourceOf([sourceFile('good.ts', 'export const ok = true;'), sourceFile('empty.txt', ''),
        { kind: 'file', path: 'broken.ts', file }, { kind: 'file', path: '.env', file: secret }]),
      onProgress: value => progress.push(value) });
    expect(summary).toMatchObject({ discoveredFiles: 4, indexedFiles: 1, metadataOnlyFiles: 1, failedFiles: 1, ignoredFiles: 1 });
    expect(progress.at(-1)).toMatchObject({ percent: 100, processedFiles: 4 });
    expect(secretRead).not.toHaveBeenCalled();
  });

  it('does not count a partially stored file as complete and retries it with a larger budget', async () => {
    const repository = new MemoryProjectIndexRepository();
    const entries = [sourceFile('rules.txt', '规则正文\n'.repeat(5000) + '重要尾部规则')];
    const first = await indexProject({ projectId: 'partial', rootName: 'demo', repository, entries: sourceOf(entries),
      limits: { maxScanFiles: 10, maxIndexBytes: 20_000, maxIndexableFileBytes: 1_000_000 } });
    expect(first.storageLimitReached).toBe(true);
    expect(first.indexedFiles).toBe(0);
    expect(first.metadataOnlyFiles).toBe(1);
    expect(repository.files[0]?.fingerprint).toMatch(/^incomplete:/);
    const second = await indexProject({ projectId: 'partial', rootName: 'demo', repository, entries: sourceOf(entries), mode: 'INCREMENTAL' });
    expect(second.indexedFiles).toBe(1);
    expect(second.unchangedFiles).toBe(0);
    expect(repository.chunks.map(c => c.content).join('')).toContain('重要尾部规则');
  });

  it('keeps empty files in the same count category on incremental refresh', async () => {
    const repository = new MemoryProjectIndexRepository();
    const options = { projectId: 'empty', rootName: 'demo', repository };
    const entries = [sourceFile('empty.txt', '')];
    await indexProject({ ...options, entries: sourceOf(entries) });
    const summary = await indexProject({ ...options, entries: sourceOf(entries), mode: 'INCREMENTAL' });
    expect(summary).toMatchObject({ indexedFiles: 0, metadataOnlyFiles: 1, unchangedFiles: 1 });
  });

  it('keeps a scan limited result below 100 percent', async () => {
    const repository = new MemoryProjectIndexRepository();
    const progress: ProjectIndexProgress[] = [];
    const summary = await indexProject({ projectId: 'limited', rootName: 'demo', repository, totalFiles: 2,
      entries: sourceOf([sourceFile('a.ts', 'a'), sourceFile('b.ts', 'b')]),
      limits: { maxScanFiles: 1, maxIndexBytes: 100_000, maxIndexableFileBytes: 100_000 },
      onProgress: value => progress.push(value) });
    expect(summary.scanLimitReached).toBe(true);
    expect(progress.at(-1)?.percent).toBe(50);
  });
  it('should bound single-line chunks without losing Chinese content or supplementary characters', async () => {
    const repository = new MemoryProjectIndexRepository();
    const content = '方案📖'.repeat(18_000) + '尾部审批要求';
    await indexProject({ projectId: 'single-line', rootName: 'fixture', repository,
      entries: sourceOf([sourceFile('plan.txt', content)]) });
    expect(repository.chunks.length).toBeGreaterThan(1);
    expect(repository.chunks.every((chunk) => chunk.content.length <= 12_000)).toBe(true);
    expect(repository.chunks.map((chunk) => chunk.content).join('')).toBe(content);
    const result = await retrieveProjectContext(repository, { projectId: 'single-line', query: '尾部审批要求' });
    expect(result.some((file) => file.content.includes('尾部审批要求'))).toBe(true);
  });
  it('should preserve unvisited indexed files when an incremental scan hits its limit', async () => {
    const repository = new MemoryProjectIndexRepository();
    const entries = [sourceFile('a.txt', 'first'), sourceFile('b.md', 'second')];
    const options = { projectId: 'limited-refresh', rootName: 'fixture', repository };
    await indexProject({ ...options, entries: sourceOf(entries) });
    const result = await indexProject({ ...options, entries: sourceOf(entries),
      mode: 'INCREMENTAL', limits: { maxScanFiles: 1, maxIndexBytes: 10_000_000, maxIndexableFileBytes: 1_000_000 } });
    expect(result.scanLimitReached).toBe(true);
    expect(result.removedFiles).toBe(0);
    expect(repository.files.map((file) => file.path)).toEqual(['a.txt', 'b.md']);
  });

  it('should write a large file in bounded batches and reuse its completed fingerprint', async () => {
    const repository = new MemoryProjectIndexRepository();
    let maximumBatchChunks = 0;
    const write = repository.writeBatch.bind(repository);
    repository.writeBatch = async (files, chunks, replaced) => {
      maximumBatchChunks = Math.max(maximumBatchChunks, chunks.length);
      await write(files, chunks, replaced);
    };
    const entry = sourceFile('rules.txt', '规则正文\n'.repeat(400_000) + '尾部财务审批规则');
    const options = { projectId: 'streamed-rules', rootName: 'fixture', repository };
    const initial = await indexProject({ ...options, entries: sourceOf([entry]) });
    expect(initial.failedFiles).toBe(0);
    expect(repository.chunks.at(-1)?.content).toContain('尾部财务审批规则');
    expect(maximumBatchChunks).toBeLessThanOrEqual(300);
    expect(repository.chunks.length).toBeGreaterThan(maximumBatchChunks);
    const refreshed = await indexProject({ ...options, entries: sourceOf([entry]), mode: 'INCREMENTAL' });
    expect(refreshed.unchangedFiles).toBe(1);
    expect(refreshed.chunkCount).toBe(initial.chunkCount);
  });

  it('should reject unsafe paths and protected files before reading their content', async () => {
    const repository = new MemoryProjectIndexRepository();
    const paths = ['../report.txt', 'C:report.txt', '/report.txt', 'config/application-prod.yml',
      'docs/.env', 'docs/id_rsa'];
    const result = await indexProject({
      projectId: 'unsafe-paths', rootName: 'fixture', repository,
      entries: sourceOf(paths.map((path) => sourceFile(path, 'synthetic content'))),
    });
    expect(repository.files).toHaveLength(0);
    expect(result.ignoredFiles).toBe(paths.length);
  });
  it('should stream every eligible source file without stopping at one thousand files', async () => {
    const repository = new MemoryProjectIndexRepository();
    const entries = Array.from({ length: 1_205 }, (_, index) =>
      sourceFile(`src/module-${index}.ts`, `export const value${index} = ${index};`));

    const summary = await indexProject({
      projectId: 'project-all-files',
      rootName: 'large-project',
      entries: sourceOf(entries),
      repository,
    });

    expect(summary.status).toBe('READY');
    expect(summary.discoveredFiles).toBe(1_205);
    expect(summary.indexedFiles).toBe(1_205);
    expect(repository.files).toHaveLength(1_205);
  });

  it('should report indexing progress before a small batch has completely finished', async () => {
    const repository = new MemoryProjectIndexRepository();
    const progress: number[] = [];
    const entries = Array.from({ length: 100 }, (_, index) =>
      sourceFile(`src/progress-${index}.ts`, `export const value${index} = ${index};`));

    await indexProject({
      projectId: 'project-progress',
      rootName: 'progress-project',
      entries: sourceOf(entries),
      repository,
      onProgress: (snapshot) => {
        if (snapshot.phase === 'INDEXING') {
          progress.push(snapshot.processedFiles);
        }
      },
    });

    expect(progress.some((processed) => processed > 0 && processed < entries.length)).toBe(true);
  });

  it('should retrieve business rules after the first forty terms of a chunk', async () => {
    const repository = new MemoryProjectIndexRepository();
    const vocabulary = Array.from({ length: 300 }, (_, index) => `identifier${index}`).join(' ');

    await indexProject({
      projectId: 'project-search-term-budget',
      rootName: 'search-term-project',
      entries: sourceOf([sourceFile('docs/rules.md', vocabulary + '\n超过五万元需要财务复核。')]),
      repository,
    });

    const matches = await retrieveProjectContext(repository, {
      projectId: 'project-search-term-budget', query: '财务复核 identifier299',
    });
    expect(matches).toHaveLength(1);
    expect(matches[0]?.content).toContain('超过五万元需要财务复核');
  });

  it('should index unknown text and exclude logs while recording ignored directories', async () => {
    const repository = new MemoryProjectIndexRepository();
    const entries: ProjectSourceEntry[] = [
      sourceFile('config/custom.rules', 'feature.enabled=true\nowner=QingNiao'),
      sourceFile('logs/application.log', 'large runtime log'),
      { kind: 'ignored-directory', path: 'node_modules' },
    ];

    const summary = await indexProject({
      projectId: 'project-file-rules',
      rootName: 'rules-project',
      entries: sourceOf(entries),
      repository,
    });

    expect(summary.indexedFiles).toBe(1);
    expect(summary.ignoredFiles).toBe(1);
    expect(summary.ignoredDirectories).toBe(1);
    expect(repository.files[0]).toMatchObject({
      path: 'config/custom.rules',
      language: 'text',
    });
  });

  it('should preserve all large source content while preferring declaration boundaries', async () => {
    const repository = new MemoryProjectIndexRepository();
    const sections = Array.from({ length: 80 }, (_, index) =>
      `public class Module${index} {\n  public String value() { return "${'x'.repeat(160)}"; }\n}\n`,
    );
    const content = `package sample;\nimport java.util.List;\n${sections.join('')}`;

    await indexProject({
      projectId: 'project-structured-chunks',
      rootName: 'structured-project',
      entries: sourceOf([sourceFile('src/Modules.java', content)]),
      repository,
    });

    const chunks = repository.chunks
      .filter((chunk) => chunk.path === 'src/Modules.java')
      .sort((left, right) => left.chunkIndex - right.chunkIndex);
    expect(chunks.length).toBeGreaterThan(1);
    expect(chunks.map((chunk) => chunk.content).join('')).toBe(content);
    expect(chunks.slice(1).every((chunk) => chunk.content.trimStart().startsWith('public class')))
      .toBe(true);
  });

  it('should index known source files even when they exceed the per-file soft limit', async () => {
    const repository = new MemoryProjectIndexRepository();
    const content = `export const header = true;\n${'export function work() { return true; }\n'.repeat(200)}`;

    const summary = await indexProject({
      projectId: 'project-large-known-source',
      rootName: 'large-known-source',
      entries: sourceOf([sourceFile('src/large.ts', content)]),
      repository,
      limits: {
        maxScanFiles: 10,
        maxIndexBytes: 10 * 1024 * 1024,
        maxIndexableFileBytes: 100,
      },
    });

    expect(summary.indexedFiles).toBe(1);
    expect(summary.metadataOnlyFiles).toBe(0);
    expect(repository.chunks.map((chunk) => chunk.content).join('')).toBe(content);
  });

  it('should split large source files into overlapping chunks instead of truncating the tail', async () => {
    const repository = new MemoryProjectIndexRepository();
    const markerAtTail = 'TAIL_METHOD_SHOULD_BE_INDEXED';
    const content = `${'export const item = 1;\n'.repeat(700)}${markerAtTail}`;

    const summary = await indexProject({
      projectId: 'project-large-file',
      rootName: 'chunk-project',
      entries: sourceOf([sourceFile('src/LargeService.ts', content)]),
      repository,
    });

    expect(summary.chunkCount).toBeGreaterThan(1);
    expect(repository.chunks.at(-1)?.content).toContain(markerAtTail);
  });

  it('should stop at the configured scan limit and report the reason', async () => {
    const repository = new MemoryProjectIndexRepository();
    const entries = Array.from({ length: 5 }, (_, index) =>
      sourceFile(`src/file-${index}.ts`, `export const value${index} = ${index};`));

    const summary = await indexProject({
      projectId: 'project-scan-budget',
      rootName: 'limited-project',
      entries: sourceOf(entries),
      repository,
      limits: {
        maxScanFiles: 3,
        maxIndexBytes: 1_000_000,
        maxIndexableFileBytes: 1_000_000,
      },
    });

    expect(summary.discoveredFiles).toBe(3);
    expect(summary.scanLimitReached).toBe(true);
  });

  it('should retain metadata when the local index budget is exhausted', async () => {
    const repository = new MemoryProjectIndexRepository();

    const summary = await indexProject({
      projectId: 'project-storage-budget',
      rootName: 'limited-project',
      entries: sourceOf([
        sourceFile('src/First.ts', 'export const first = 1;'),
        sourceFile('src/Second.ts', 'export const second = 2;'),
      ]),
      repository,
      limits: {
        maxScanFiles: 100,
        maxIndexBytes: 1,
        maxIndexableFileBytes: 1_000_000,
      },
    });

    expect(summary.storageLimitReached).toBe(true);
    expect(summary.indexedFiles).toBe(0);
    expect(summary.metadataOnlyFiles).toBe(2);
    expect(repository.files).toHaveLength(2);
  });

  it('should only rebuild changed files and remove files missing from an incremental scan', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({
      projectId: 'project-incremental',
      rootName: 'incremental-project',
      entries: sourceOf([
        versionedSourceFile('src/Stable.ts', 'export const stable = 1;', 1),
        versionedSourceFile('src/Changed.ts', 'export const changed = 1;', 1),
        versionedSourceFile('src/Removed.ts', 'export const removed = 1;', 1),
      ]),
      repository,
    });

    const summary = await indexProject({
      projectId: 'project-incremental',
      rootName: 'incremental-project',
      mode: 'INCREMENTAL',
      entries: sourceOf([
        versionedSourceFile('src/Stable.ts', 'export const stable = 1;', 1),
        versionedSourceFile('src/Changed.ts', 'export const changed = 2;', 2),
        versionedSourceFile('src/Added.ts', 'export const added = 1;', 1),
      ]),
      repository,
    });

    expect(summary.unchangedFiles).toBe(1);
    expect(summary.updatedFiles).toBe(1);
    expect(summary.addedFiles).toBe(1);
    expect(summary.removedFiles).toBe(1);
    expect(repository.files.map((file) => file.path).sort()).toEqual([
      'src/Added.ts',
      'src/Changed.ts',
      'src/Stable.ts',
    ]);
    expect(repository.chunks.find((chunk) => chunk.path === 'src/Changed.ts')?.content)
      .toContain('changed = 2');
  });

  it('should preserve a paused checkpoint and skip completed files when resumed', async () => {
    const repository = new MemoryProjectIndexRepository();
    const entries = Array.from({ length: 450 }, (_, index) =>
      versionedSourceFile(`src/file-${index}.ts`, `export const value${index} = ${index};`, 1));
    let pauseChecks = 0;

    const paused = await indexProject({
      projectId: 'project-resume',
      rootName: 'resume-project',
      entries: sourceOf(entries),
      repository,
      shouldPause: () => {
        pauseChecks += 1;
        return pauseChecks === 1;
      },
    });

    expect(paused.status).toBe('PAUSED');
    expect(paused.discoveredFiles).toBe(200);

    const resumed = await indexProject({
      projectId: 'project-resume',
      rootName: 'resume-project',
      mode: 'RESUME',
      entries: sourceOf(entries),
      repository,
    });

    expect(resumed.status).toBe('READY');
    expect(resumed.unchangedFiles).toBe(200);
    expect(resumed.addedFiles).toBe(250);
    expect(repository.files).toHaveLength(450);
  });
});

describe('retrieveProjectContext', () => {
  it('should not expand an explicit directory from file names repeated in the delivery scope', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'delivery-path-terms', rootName: 'sample', repository,
      entries: sourceOf([
        sourceFile('src/entries/create.js', 'export function createEntry() { return true; }'),
        sourceFile('src/entries/import.js', 'export function importEntries() { return true; }'),
        sourceFile('src/entries-old/create.js', 'export function createLegacyEntry() { return "legacy"; }'),
        sourceFile('src/other/import.js', 'export function importLegacyEntries() { return "other"; }'),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'delivery-path-terms',
      query: '修改 @sample/src/entries，只允许修改 src/entries/create.js 和 src/entries/import.js，不新增文件',
      changedPaths: repository.files.map((file) => file.path),
    });
    expect(result.selections.map((item) => item.path).sort()).toEqual(['src/entries/create.js', 'src/entries/import.js']);
  });

  it('should retain an explicitly named complete symbol outside the referenced module', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'explicit-complete-symbol', rootName: 'sample', repository,
      entries: sourceOf([
        sourceFile('src/entries/create.js', 'export function createEntry() { return true; }'),
        sourceFile('src/shared/normalize.js', 'export function normalizeBusinessDate() { return true; }'),
        sourceFile('src/legacy/normalize.js', 'export function normalizeLegacyBusinessDate() { return true; }'),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'explicit-complete-symbol', query: '修改 @sample/src/entries 并检查 normalizeBusinessDate',
    });
    expect(result.selections.map((item) => item.path).sort()).toEqual(['src/entries/create.js', 'src/shared/normalize.js']);
  });

  it('should retain a separate complete relative path alongside an at-directory reference', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'parallel-relative-target', rootName: 'sample', repository,
      entries: sourceOf([
        sourceFile('src/entries/create.js', 'export function createEntry() { return true; }'),
        sourceFile('src/shared/normalize.ts', 'export const normalize = true;'),
        sourceFile('src/legacy/normalize.ts', 'export const normalize = false;'),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'parallel-relative-target', query: '修改 @sample/src/entries，同时修改 src/shared/normalize.ts',
    });
    expect(result.selections.map((item) => item.path).sort()).toEqual(['src/entries/create.js', 'src/shared/normalize.ts']);
  });

  it('should not restrict ordinary retrieval to a bare file that is not in the index', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'unmatched-bare-file', rootName: 'sample', repository,
      entries: sourceOf([sourceFile('src/guide/format.ts', 'export const guide = "日期格式规则";')]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'unmatched-bare-file', query: '了解 src/missing/file.ts 的日期格式规则',
    });
    expect(result.selections.map(item => item.path)).toEqual(['src/guide/format.ts']);
    expect(result.references).toEqual([]);
  });

  it('should bind an unqualified complete symbol to the explicitly referenced module before same-named legacy symbols', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'scoped-symbol-shadow', rootName: 'sample', repository,
      entries: sourceOf([
        sourceFile('src/current/create.js', 'export function createSurvey() { return "current"; }'),
        sourceFile('src/legacy/create.js', 'export function createSurvey() { return "legacy"; }'),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'scoped-symbol-shadow', query: '修改 @sample/src/current 的 createSurvey，限制未来日期',
    });
    expect(result.selections.map(item => item.path)).toEqual(['src/current/create.js']);
  });

  it('should retain the symbol clue of an unmatched bare file without claiming the requested path was found', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'unmatched-symbol-clue', rootName: 'sample', repository,
      entries: sourceOf([sourceFile('src/shared/retry.ts', 'export function retryPayment() { return true; }')]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'unmatched-symbol-clue', query: '解释 src/missing/retryPayment.ts 的实现',
    });
    expect(result.selections.map(item => item.path)).toEqual(['src/shared/retry.ts']);
    expect(result.references).toEqual([]);
  });

  it('should recall an explicit directory before generic matches exhaust the candidate budget', async () => {
    const repository = new MemoryProjectIndexRepository();
    const noise = Array.from({ length: 450 }, (_, index) => sourceFile(
      `src/utils/noise${index}.ts`, 'export const shared = "调查时间 新增 导入";',
    ));
    await indexProject({ projectId: 'explicit-directory', rootName: 'sample-project', repository,
      entries: sourceOf([...noise,
        sourceFile('src/views/records/Entry.vue', 'const entryDate = "日期录入";'),
        sourceFile('src/views/records/Import.vue', 'const importedDate = "日期校验";'),
        sourceFile('src/views/records-old/Old.vue', 'const oldDate = "调查时间 新增 导入";'),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'explicit-directory', query: '请在模块@sample-project/src/views/records 新增和导入时限制调查时间',
      maxChunks: 40,
      changedPaths: [...noise.map((entry) => entry.path), 'src/views/records-old/Old.vue'],
    });
    expect(result.selections.map((item) => item.path)).toEqual([
      'src/views/records/Entry.vue', 'src/views/records/Import.vue',
    ]);
    expect(result.selections.every((item) => item.reasons.includes('用户明确引用路径'))).toBe(true);
  });

  it('should not fill the context from shared path words and short substring symbol matches', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'weak-matches', rootName: 'sample', repository,
      entries: sourceOf(Array.from({ length: 20 }, (_, index) => sourceFile(
        `src/utils/example${index}.ts`, 'export const it = true; export const as = "ready";',
      ))) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'weak-matches', query: 'src ts capacity', maxChunks: 40,
    });
    expect(result.files).toEqual([]);
  });

  it('should retain bounded project configuration for standalone technology analysis', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'technology-context', rootName: 'sample', repository,
      entries: sourceOf([
        sourceFile('package.json', '{"dependencies":{"vue":"3"}}'),
        sourceFile('pom.xml', '<project>spring-boot</project>'),
        sourceFile('tsconfig.json', '{"compilerOptions":{"strict":true}}'),
        sourceFile('src/unrelated.ts', 'export const example = 1;'),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'technology-context', query: '技术栈 依赖 项目结构 配置', maxChunks: 40,
    });
    expect(result.files).toHaveLength(2);
    expect(result.files.every((file) => !file.path.startsWith('src/'))).toBe(true);
  });

  it('should record an unresolved reference without substituting a similarly named directory', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'missing-target', rootName: 'sample', repository,
      entries: sourceOf([
        sourceFile('src/views/records-old/Old.vue', 'const old = "调查时间 新增 导入 日期限制";'),
        ...Array.from({ length: 20 }, (_, index) => sourceFile(
          `src/other/Noise${index}.vue`, 'const shared = "调查时间 新增 导入 日期限制";',
        )),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'missing-target', query: '修改 @sample/src/views/records 的调查时间，新增和导入功能都增加日期限制',
      changedPaths: repository.files.map((file) => file.path),
    });
    expect(result.files).toEqual([]);
    expect(result.references).toEqual([
      { reference: 'sample/src/views/records', matchedPaths: [], selectedPaths: [], status: 'NO_MATCHING_CHUNKS' },
    ]);
  });

  it('should not expand a vue import to unrelated vue files', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'bounded-dependencies', rootName: 'sample', repository,
      entries: sourceOf([
        sourceFile('src/target/Entry.vue', 'import { ref } from "vue"; import { dateRule } from "./dateRule"; const entry = ref(0);'),
        sourceFile('src/target/dateRule.ts', 'export function dateRule() { return true; }'),
        ...Array.from({ length: 12 }, (_, index) => sourceFile(`src/other/Other${index}.vue`, 'const unrelated = 1;')),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'bounded-dependencies', query: '修改 @src/target/Entry.vue', maxChunks: 20,
    });
    expect(result.selections.map((item) => item.path)).toEqual(['src/target/Entry.vue', 'src/target/dateRule.ts']);
  });

  it('should cover separate files in an explicit directory before repeating a large file', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'directory-diversity', rootName: 'sample', repository,
      entries: sourceOf([
        sourceFile('src/forms/Entry.vue', `const form = 1;\n/*${'large source content '.repeat(2_000)}*/`),
        sourceFile('src/forms/Import.vue', 'const imported = 1;'),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'directory-diversity', query: '修改 @sample/src/forms', maxChunks: 2,
    });
    expect(result.selections.map((item) => item.path)).toEqual(['src/forms/Entry.vue', 'src/forms/Import.vue']);
  });

  it('should resolve relative dependencies by full path instead of importing every same-named module', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({ projectId: 'relative-dependencies', rootName: 'sample', repository,
      entries: sourceOf([
        sourceFile('src/views/records/dateRules.spec.js', 'import { createSurvey } from "./create.js"; import { check } from "../../shared/DateCheck.js";'),
        sourceFile('src/views/records/create.js', 'export function createSurvey() { return true; }'),
        sourceFile('src/views/records-old/create.js', 'export function createSurvey() { return "legacy"; }'),
        sourceFile('src/shared/DateCheck.js', 'export function check() { return true; }'),
      ]) });
    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'relative-dependencies', query: '修改 @sample/src/views/records',
      changedPaths: repository.files.map((file) => file.path), maxChunks: 40,
    });
    expect(result.selections.some((item) => item.path === 'src/views/records-old/create.js')).toBe(false);
    expect(result.selections.find((item) => item.path === 'src/shared/DateCheck.js')?.reasons)
      .toContain('被高相关代码直接依赖');
  });

  it('should allocate dynamic per-file chunk limits by retrieval reason', () => {
    expect(getChunkSelectionLimit([])).toBe(3);
    expect(getChunkSelectionLimit(['最近变更文件'])).toBe(4);
    expect(getChunkSelectionLimit(['任务中的符号'])).toBe(5);
    expect(getChunkSelectionLimit(['当前打开文件'])).toBe(6);
    expect(getChunkSelectionLimit(['用户固定文件'])).toBe(8);
    expect(getChunkSelectionLimit(['关键工程配置'])).toBe(2);
    expect(getChunkSelectionLimit(['被高相关代码直接依赖'])).toBe(2);
    expect(getChunkSelectionLimit(['当前打开文件', '用户固定文件'])).toBe(8);
  });

  it('should return task-relevant chunks within the character budget', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({
      projectId: 'project-search',
      rootName: 'search-project',
      entries: sourceOf([
        sourceFile('package.json', '{"dependencies":{"vue":"3.5.0"}}'),
        sourceFile('src/payment/RefundService.ts', 'export function refundOrder() { return "refund"; }'),
        sourceFile('src/user/ProfileService.ts', 'export function updateProfile() { return "profile"; }'),
      ]),
      repository,
    });

    const context = await retrieveProjectContext(repository, {
      projectId: 'project-search',
      query: '实现 refundOrder 退款处理',
      maxCharacters: 20_000,
      maxChunks: 10,
    });

    expect(context.some((file) => file.path.includes('RefundService.ts'))).toBe(true);
    expect(context.reduce((total, file) => total + file.content.length, 0)).toBeLessThanOrEqual(20_000);
  });

  it('should redact secrets before indexed chunks leave the browser', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({
      projectId: 'project-secret',
      rootName: 'secret-project',
      entries: sourceOf([
        sourceFile('config/application.yml', 'api-key: super-secret-token-value\nfeature: enabled'),
      ]),
      repository,
    });

    const context = await retrieveProjectContext(repository, {
      projectId: 'project-secret',
      query: 'application feature',
    });

    expect(context[0]?.content).toContain('[REDACTED]');
    expect(context[0]?.content).not.toContain('super-secret-token-value');
  });

  it('should expand a symbol match to the source files imported by that code', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({
      projectId: 'project-dependency-retrieval',
      rootName: 'dependency-project',
      entries: sourceOf([
        sourceFile(
          'src/refund/RefundController.ts',
          'import { RefundService } from "./RefundService";\nexport class RefundController {}',
        ),
        sourceFile(
          'src/refund/RefundService.ts',
          'export class RefundService { executeRefund() { return true; } }',
        ),
        sourceFile('src/profile/ProfileService.ts', 'export class ProfileService {}'),
      ]),
      repository,
    });

    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'project-dependency-retrieval',
      query: '修改 RefundController 的退款入口',
      maxCharacters: 20_000,
      maxChunks: 3,
    });

    expect(result.files.some((file) => file.path.includes('RefundController.ts'))).toBe(true);
    expect(result.files.some((file) => file.path.includes('RefundService.ts'))).toBe(true);
    expect(repository.chunks.find((chunk) => chunk.path === 'src/refund/RefundService.ts')?.symbols)
      .toContain('executerefund');
    expect(result.selections.find((item) => item.path === 'src/refund/RefundService.ts')?.reasons)
      .toContain('被高相关代码直接依赖');
  });

  it('should prioritize the active file and recently changed files with visible reasons', async () => {
    const repository = new MemoryProjectIndexRepository();
    await indexProject({
      projectId: 'project-priority-retrieval',
      rootName: 'priority-project',
      entries: sourceOf([
        sourceFile('src/user/UserService.ts', 'export function updateUser() { return true; }'),
        sourceFile('src/admin/AdminService.ts', 'export function updateAdmin() { return true; }'),
        sourceFile('src/audit/AuditService.ts', 'export function updateAudit() { return true; }'),
      ]),
      repository,
    });

    const result = await retrieveProjectContextWithReport(repository, {
      projectId: 'project-priority-retrieval',
      query: '修改 update 逻辑',
      activeFilePath: 'src/admin/AdminService.ts',
      changedPaths: ['src/audit/AuditService.ts'],
      maxCharacters: 20_000,
      maxChunks: 2,
    });

    expect(result.selections.map((item) => item.path)).toEqual([
      'src/admin/AdminService.ts',
      'src/audit/AuditService.ts',
    ]);
    expect(result.selections[0]?.reasons).toContain('当前打开文件');
    expect(result.selections[1]?.reasons).toContain('最近变更文件');
  });
});
