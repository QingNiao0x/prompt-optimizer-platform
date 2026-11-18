import { expect, test } from '@playwright/test';

/** 新旧词表混合、归属校验和覆盖均使用真实 IndexedDB 事务。 */
test('独立库紧凑词表保留全部词项并兼容旧记录', async ({ page }) => {
  await page.route('**/__compact_index', route => route.fulfill({ contentType: 'text/html', body: '<title>索引验证</title>' }));
  await page.goto('/__compact_index');
  const result = await page.evaluate(async () => {
    const repositoryModule = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const storageModule = '/src/features/project-index/projectIndexStorage.ts';
    const { IndexedDbProjectIndexRepository } = await import(repositoryModule);
    const { projectDatabaseLocation } = await import(storageModule);
    const id = 'pi-d1-compact-test';
    const location = projectDatabaseLocation(id)!;
    const legacy = new IndexedDbProjectIndexRepository({ name: location.name, factory: location.factory });
    const repository = new IndexedDbProjectIndexRepository(location);
    const chunk = (path: string) => ({ id: `${id}\0${path}\0${0}`, projectId: id, path, chunkIndex: 0,
      content: '完整正文与尾部', language: 'text', priority: 100, symbols: ['symbol'], imports: ['reference'],
      searchTerms: [`${id}:uniquemarker`, `${id}:尾部`] });
    try {
      await legacy.writeBatch([], [chunk('old.ts')]);
      await repository.writeBatch([], [chunk('new.ts')]);
      let rejected = false;
      try { await repository.writeBatch([], [{ ...chunk('bad.ts'), projectId: 'other' }]); }
      catch { rejected = true; }
      const hits = await repository.findCandidateChunks(id, ['uniquemarker'], 10);
      const request = (value: IDBRequest): Promise<any> => new Promise((resolve, reject) => {
        value.onsuccess = () => resolve(value.result); value.onerror = () => reject(value.error);
      });
      const factory = await location.factory();
      const db = await request(factory.open(location.name));
      const rows = await request(db.transaction('chunkSearch').objectStore('chunkSearch').getAll());
      db.close();
      await repository.writeBatch([], [chunk('old.ts')], ['old.ts']);
      const updated = await repository.findCandidateChunks(id, ['uniquemarker'], 10);
      return { rejected, rows, hits, updated };
    } finally { legacy.close(); await repository.deleteProject(id); repository.close(); }
  });
  expect(result.rejected).toBe(true);
  expect(result.rows.find((row: any) => row.formatVersion === 2).searchTerms).toEqual(['uniquemarker', '尾部']);
  expect(result.rows.find((row: any) => !row.formatVersion).searchTerms).toEqual(['pi-d1-compact-test:uniquemarker', 'pi-d1-compact-test:尾部']);
  expect(result.hits.map((chunk: any) => chunk.path).sort()).toEqual(['new.ts', 'old.ts']);
  expect(result.updated).toHaveLength(2);
  for (const chunk of result.updated) {
    expect(chunk.content).toBe('完整正文与尾部');
    expect(chunk.searchTerms).toEqual(['pi-d1-compact-test:uniquemarker', 'pi-d1-compact-test:尾部']);
  }
});
