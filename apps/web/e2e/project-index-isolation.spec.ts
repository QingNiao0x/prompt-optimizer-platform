import { expect, test } from '@playwright/test';

/** 原生事务占用旧库时，新项目仍须完成写入与全文检索；旧库内容、期限和恢复路径保持可用。 */
test('旧库事务占用不阻塞新项目，按项目清理不会删除旧库或其他桶', async ({ page }) => {
  await page.route('**/__index_isolation', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>索引隔离复验</title>' }));
  await page.goto('/__index_isolation');
  const result = await page.evaluate(async () => {
    const path = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const { IndexedDbProjectIndexRepository, RoutedProjectIndexRepository } = await import(path) as
      typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    const storagePath = '/src/features/project-index/projectIndexStorage.ts';
    const { createIsolatedProjectId, projectDatabaseLocation, storageBuckets } = await import(storagePath) as
      typeof import('../src/features/project-index/projectIndexStorage');
    const request = <T>(r: IDBRequest<T>): Promise<T> => new Promise((resolve, reject) => {
      r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error);
    });
    type Chunk = import('../src/features/project-index/projectIndexer').IndexedProjectChunk;
    const matches = (actual: Chunk | undefined, expected: Chunk): boolean => !!actual && Object.entries(expected)
      .every(([key, value]) => JSON.stringify(actual[key as keyof Chunk]) === JSON.stringify(value));
    const chunk = (id: string, word: string): Chunk => ({ id: `${id}\0file.ts\0${0}`, projectId: id,
      path: 'file.ts', language: 'typescript', chunkIndex: 0, priority: 10,
      content: `正文与末尾:${word}`, searchTerms: [`${id}:${word}`], symbols: [], imports: [] });
    const legacy = new IndexedDbProjectIndexRepository();
    const original = chunk('legacy', 'old-tail');
    await legacy.writeBatch([], [original]);
    const db = await request(indexedDB.open('prompt-optimizer-project-index'));
    const blocker = db.transaction('chunks', 'readwrite');
    let keepAlive = true, oldFinished = false;
    const pump = (): void => { const r = blocker.objectStore('chunks').get(original.id); r.onsuccess = () => { if (keepAlive) pump(); }; };
    pump();
    const blockedRead = legacy.findCandidateChunks('legacy', ['old-tail'], 1).then(value => { oldFinished = true; return value; });
    const repo = new RoutedProjectIndexRepository(), first = createIsolatedProjectId(), second = createIsolatedProjectId();
    const expiresAt = new Date(Date.now() + 3_600_000).toISOString();
    const start = performance.now();
    try {
      for (const id of [first, second]) {
        await repo.prepareProject(id, expiresAt, 'SESSION');
        await repo.writeBatch([], [chunk(id, 'new-tail')]);
      }
      const reopened = new RoutedProjectIndexRepository();
      const hits = await reopened.findCandidateChunks(first, ['new-tail'], 1);
      const elapsedMs = performance.now() - start;
      const oldStillBlocked = !oldFinished;
      const buckets = storageBuckets()!;
      const bucket = await buckets.open(first);
      const expires = await (bucket as unknown as { expires(): Promise<number> }).expires();
      // 同一项目可被独立仓库实例恢复；清理当前项目不动另一项目，且旧事务仍继续保留。
      await repo.deleteProject(first);
      const secondHits = await repo.findCandidateChunks(second, ['new-tail'], 1);
      const names = await buckets.keys();
      keepAlive = false;
      const old = await blockedRead;
      await repo.deleteProject(second);
      // 显式兼容路径不依赖桶 API：普通独立数据库同样存取完整内容。
      const fallback = 'pi-d1-compatibility';
      await repo.prepareProject(fallback, expiresAt, 'SESSION');
      await repo.writeBatch([], [chunk(fallback, 'fallback-tail')]);
      const fallbackHits = await repo.findCandidateChunks(fallback, ['fallback-tail'], 1);
      await repo.deleteProject(fallback);
      return { oldStillBlocked, elapsedMs, exact: matches(hits[0], chunk(first, 'new-tail')),
        secondPreserved: secondHits[0]?.projectId === second, firstRemoved: !names.includes(first),
        oldPreserved: matches(old[0], original), expires,
        fallbackExact: fallbackHits[0]?.content === chunk(fallback, 'fallback-tail').content,
        distinctLocations: projectDatabaseLocation(first)?.name !== projectDatabaseLocation(second)?.name };
    } finally { keepAlive = false; await blockedRead; db.close(); legacy.close(); }
  });
  expect(result.oldStillBlocked).toBe(true);
  expect(result.elapsedMs).toBeLessThan(10_000);
  expect(result).toMatchObject({ exact: true, secondPreserved: true, firstRemoved: true,
    oldPreserved: true, fallbackExact: true, distinctLocations: true });
  expect(result.expires).toBeGreaterThan(Date.now());
});
