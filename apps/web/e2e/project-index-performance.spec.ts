import { expect, test } from '@playwright/test';

/** 对照只模拟旧版遗漏目录过滤，实际分块、全文词表和 IndexedDB 均使用生产实现。 */
test('包管理器缓存不拖慢项目索引且增量更新清除旧缓存条目', async ({ page }) => {
  await page.route('**/__index_cache_fixture', (route) => route.fulfill({
    contentType: 'text/html', body: '<!doctype html><title>缓存目录索引回归</title>',
  }));
  await page.goto('/__index_cache_fixture');
  const metrics = await page.evaluate(async () => {
    const repositoryModule = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const indexerModule = '/src/features/project-index/projectIndexer.ts';
    const directoryModule = '/src/features/project-index/fileSystemDirectorySource.ts';
    const { IndexedDbProjectIndexRepository } = await import(repositoryModule) as
      typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    const { indexProject, retrieveProjectContext } = await import(indexerModule) as
      typeof import('../src/features/project-index/projectIndexer');
    const { streamDirectoryEntries } = await import(directoryModule) as
      typeof import('../src/features/project-index/fileSystemDirectorySource');
    type Directory = import('../src/features/project-index/fileSystemDirectorySource').DirectoryHandleLike;
    type Handle = import('../src/features/project-index/fileSystemDirectorySource').FileSystemHandleLike;
    type Entry = import('../src/features/project-index/projectIndexer').ProjectSourceEntry;
    const repository = new IndexedDbProjectIndexRepository();
    const projectIds = ['cache-before', 'cache-after'];
    let cacheReads = 0;
    const cachedText = Array.from({ length: 30_000 }, (_, i) => `dependency_symbol_${i}`).join(' ');
    const file = (name: string, content: string, cache = false): Handle => ({
      kind: 'file', name,
      async getFile() {
        if (cache) cacheReads++;
        return new File([content], name, { lastModified: 1 });
      },
    });
    const directory = (name: string, children: Handle[]): Directory => ({
      kind: 'directory', name, async *values() { yield* children; },
    });
    const root = directory('fixture', [
      directory('.npm-cache', [directory('_cacache', Array.from({ length: 8 }, (_, i) =>
        file(`hash${i}`, cachedText, true)))]),
      directory('.m2', [directory('repository', [file('pom.xml', '<project>cached dependency</project>', true)])]),
      file('pom.xml', '<project><artifactId>spring-boot-starter-web</artifactId></project>'),
      directory('docs', [file('rules.txt', '业务背景\n'.repeat(10_000) + '退费超过五万元必须财务复核。')]),
      file('BUSINESS_RULES', '无扩展名的用户文档也必须保留'),
    ]);
    // 仅用于对照基线：旧版未排除这两个目录，索引入口本身保持不变。
    async function* unfiltered(handle: Directory, prefix = ''): AsyncGenerator<Entry> {
      for await (const child of handle.values()) {
        const path = prefix ? `${prefix}/${child.name}` : child.name;
        if (child.kind === 'directory') yield* unfiltered(child, path);
        else yield { kind: 'file', path, file: await child.getFile() };
      }
    }
    try {
      const beforeStarted = performance.now();
      const before = await indexProject({ repository, projectId: projectIds[0], rootName: root.name,
        entries: unfiltered(root) });
      const beforeMs = performance.now() - beforeStarted;
      cacheReads = 0;
      const afterStarted = performance.now();
      const after = await indexProject({ repository, projectId: projectIds[1], rootName: root.name,
        entries: streamDirectoryEntries(root) });
      const afterMs = performance.now() - afterStarted;
      const refreshed = await indexProject({ repository, projectId: projectIds[0], rootName: root.name,
        entries: streamDirectoryEntries(root), mode: 'INCREMENTAL' });
      const selected = await retrieveProjectContext(repository, { projectId: projectIds[0],
        query: '退费超过五万元财务复核', maxChunks: 4 });
      const stale = await repository.findChunksByPaths(projectIds[0], ['.npm-cache/_cacache/hash0'], 1);
      return { beforeMs, afterMs, beforeFiles: before.indexedFiles, afterFiles: after.indexedFiles,
        cacheReadsAfterFix: cacheReads, removedFiles: refreshed.removedFiles,
        unchangedFiles: refreshed.unchangedFiles, staleChunks: stale.length,
        tailRule: selected.some((chunk) => chunk.content.includes('退费超过五万元必须财务复核')) };
    } finally {
      for (const projectId of projectIds) await repository.deleteProject(projectId);
    }
  });
  console.log('CACHE_INDEX_PERFORMANCE', JSON.stringify(metrics));
  expect(metrics.beforeFiles).toBe(12);
  expect(metrics.afterFiles).toBe(3);
  expect(metrics.cacheReadsAfterFix).toBe(0);
  expect(metrics.removedFiles).toBe(9);
  expect(metrics.unchangedFiles).toBe(3);
  expect(metrics.staleChunks).toBe(0);
  expect(metrics.tailRule).toBe(true);
});

/** 真实浏览器写入基准，记录全文词表成本并验证后段业务规则仍可召回。 */
test('全文本地索引保持有界写入并保留尾部业务规则', async ({ page }) => {
  await page.route('**/__index_performance_fixture', (route) => route.fulfill({
    contentType: 'text/html', body: '<!doctype html><title>索引性能回归</title>',
  }));
  await page.goto('/__index_performance_fixture');
  const metrics = await page.evaluate(async () => {
    const repositoryModule = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const indexerModule = '/src/features/project-index/projectIndexer.ts';
    const { IndexedDbProjectIndexRepository } = await import(repositoryModule) as
      typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    const { indexProject, retrieveProjectContext } = await import(indexerModule) as
      typeof import('../src/features/project-index/projectIndexer');
    type Chunk = import('../src/features/project-index/projectIndexer').IndexedProjectChunk;
    type IndexedFile = import('../src/features/project-index/projectIndexer').IndexedProjectFile;
    let writeMs = 0;
    let terms = 0;
    let maxTermsPerChunk = 0;
    let batches = 0;
    class MeasuredRepository extends IndexedDbProjectIndexRepository {
      override async writeBatch(files: IndexedFile[], chunks: Chunk[], paths: string[] = []) {
        terms += chunks.reduce((total, chunk) => total + chunk.searchTerms.length, 0);
        maxTermsPerChunk = Math.max(maxTermsPerChunk, ...chunks.map((chunk) => chunk.searchTerms.length));
        batches += 1;
        const started = performance.now();
        await super.writeBatch(files, chunks, paths);
        writeMs += performance.now() - started;
      }
    }
    const repository = new MeasuredRepository();
    const projectId = 'performance-project';
    const text = Array.from({ length: 900 }, (_, index) => `field${index}`).join(' ');
    async function* entries() {
      for (let index = 0; index < 240; index += 1) {
        const path = `docs/policy-${index}.txt`;
        yield { kind: 'file' as const, path,
          file: new File([text, `\n尾部业务规则：refund_policy_${index} 超过五万元必须财务复核。`], path) };
      }
    }
    try {
      const started = performance.now();
      const summary = await indexProject({ projectId, rootName: 'performance', repository, entries: entries() });
      const indexMs = performance.now() - started;
      const retrievalStarted = performance.now();
      const selected = await retrieveProjectContext(repository, {
        projectId, query: 'refund_policy_239 财务复核', maxChunks: 4,
      });
      return { indexMs, writeMs, retrievalMs: performance.now() - retrievalStarted,
        terms, maxTermsPerChunk, batches, indexedFiles: summary.indexedFiles,
        hasTailRule: selected.some((file) => file.content?.includes('refund_policy_239')) };
    } finally {
      await repository.deleteProject(projectId);
    }
  });
  console.log('INDEX_PERFORMANCE', JSON.stringify(metrics));
  expect(metrics.indexedFiles).toBe(240);
  expect(metrics.hasTailRule).toBe(true);
  expect(metrics.maxTermsPerChunk).toBeGreaterThan(40);
});
