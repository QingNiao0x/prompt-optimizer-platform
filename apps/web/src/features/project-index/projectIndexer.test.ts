import { describe, expect, it } from 'vitest';

import {
  indexProject,
  retrieveProjectContext,
  retrieveProjectContextWithReport,
  getChunkSelectionLimit,
  type IndexedProjectChunk,
  type IndexedProjectFile,
  type ProjectIndexRepository,
  type ProjectIndexSummary,
  type ProjectSourceEntry,
} from './projectIndexer';

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
    const acceptedPaths = new Set(paths);
    return this.chunks
      .filter((chunk) => chunk.projectId === projectId && acceptedPaths.has(chunk.path))
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
