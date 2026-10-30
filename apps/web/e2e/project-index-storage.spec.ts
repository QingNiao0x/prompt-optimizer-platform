import { expect, test } from '@playwright/test';

/** 使用真实浏览器存储量约束长路径 × 全文词表的放大，不通过减少词或正文通过检查。 */
for (const scenario of [{ unique: false, count: 500, maxMiB: 160 }, { unique: true, count: 200, maxMiB: 70 }]) {
test(`长路径${scenario.unique ? '不同词' : '共享词'}全文词表完整且存储不重复放大路径`, async ({ page }) => {
  test.setTimeout(120_000);
  await page.route('**/__storage_fixture', (route) => route.fulfill({
    contentType: 'text/html', body: '<!doctype html><title>本地索引容量回归</title>',
  }));
  await page.goto('/__storage_fixture');
  const result = await page.evaluate(async ({ unique, count }) => {
    const modulePath = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const { IndexedDbProjectIndexRepository } = await import(modulePath) as
      typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    type Chunk = import('../src/features/project-index/projectIndexer').IndexedProjectChunk;
    const repository = new IndexedDbProjectIndexRepository();
    const projectId = 'p-1f8293b4-20e6-47ae-aac7-81de68bb392e';
    const before = (await navigator.storage.estimate()).usage ?? 0;
    const started = performance.now();
    try {
      for (let start = 0; start < count; start += 100) {
        const chunks: Chunk[] = Array.from({ length: 100 }, (_, offset) => {
          const i = start + offset;
          const path = `docs/testing/evidence/research-professional-platform-acceptance-2026-10-30/deepseek/${String(i).padStart(4, '0')}-output-body.json`;
          return { id: `${projectId}\0${path}\0${i}`, projectId, path, chunkIndex: i,
            language: 'text', priority: 10, symbols: [], imports: [],
            searchTerms: [...Array.from({ length: 1000 }, (_, j) => `${projectId}:检索内容字段${unique ? `${i}_` : ''}${j}`), `${projectId}:tail_${i}`],
            content: '正文需保留。'.repeat(1000) + `tail_${i}` };
        });
        await repository.writeBatch([], chunks);
      }
      const indexedMs = performance.now() - started;
      const usage = ((await navigator.storage.estimate()).usage ?? 0) - before;
      const retrievalStarted = performance.now();
      const tail = await repository.findCandidateChunks(projectId, [`tail_${count - 1}`], 1);
      const retrievalMs = performance.now() - retrievalStarted;
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open('prompt-optimizer-project-index');
        request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
      });
      const chunks = await new Promise<Chunk[]>((resolve, reject) => {
        const request = db.transaction('chunks').objectStore('chunks').getAll();
        request.onsuccess = () => resolve(request.result as Chunk[]); request.onerror = () => reject(request.error);
      });
      const terms = await new Promise<{ searchTerms: string[] }[]>((resolve, reject) => {
        const request = db.transaction('chunkSearch').objectStore('chunkSearch').getAll();
        request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
      });
      db.close();
      return { usage, indexedMs, retrievalMs,
        count: chunks.length, terms: terms.reduce((sum, chunk) => sum + chunk.searchTerms.length, 0),
        complete: chunks.every((chunk) => chunk.content === '正文需保留。'.repeat(1000) + `tail_${chunk.chunkIndex}`),
        tailFound: tail.some((chunk) => chunk.content.endsWith(`tail_${count - 1}`)) };
    } finally { await repository.deleteProject(projectId); }
  }, scenario);
  console.log('INDEX_STORAGE', JSON.stringify(result));
  expect(result.count).toBe(scenario.count);
  expect(result.terms).toBe(scenario.count * 1001);
  expect(result.complete).toBe(true);
  expect(result.tailFound).toBe(true);
  // 旧长主键在两组中约 263 / 100 MB；保留浏览器差异余量，不依赖重复词比例通过。
  expect(result.usage).toBeLessThan(scenario.maxMiB * 1024 * 1024);
});
}

/** 旧词表迁移、跨批次覆盖和删除必须保持原子性，不能以容量优化换取旧词残留。 */
test('旧索引升级保留全部关联，更新删除与回滚不污染其他文件项目', async ({ page }) => {
  await page.route('**/__storage_upgrade', (route) => route.fulfill({
    contentType: 'text/html', body: '<!doctype html><title>索引升级回归</title>',
  }));
  await page.goto('/__storage_upgrade');
  const result = await page.evaluate(async () => {
    type Chunk = import('../src/features/project-index/projectIndexer').IndexedProjectChunk;
    type FileRecord = import('../src/features/project-index/projectIndexer').IndexedProjectFile;
    const dbName = 'prompt-optimizer-project-index';
    const request = <T>(value: IDBRequest<T>): Promise<T> => new Promise((resolve, reject) => {
      value.onsuccess = () => resolve(value.result); value.onerror = () => reject(value.error);
    });
    const done = (tx: IDBTransaction): Promise<void> => new Promise((resolve, reject) => {
      tx.oncomplete = () => resolve(); tx.onabort = () => reject(tx.error);
    });
    const chunk = (projectId: string, path: string, index: number, term = 'shared'): Chunk => ({
      id: `${projectId}\0${path}\0${index}`, projectId, path, chunkIndex: index, priority: 10,
      language: 'text', symbols: ['marker'], imports: ['./dependency'],
      content: `${path} 完整正文 ${index}`, searchTerms: [`${projectId}:${term}`, `${projectId}:tail_${index}`],
    });
    const file = (projectId: string, path: string, scan: string, chunkCount: number): FileRecord => ({
      id: `${projectId}\0${path}`, projectId, path, lastSeenScanId: scan, chunkCount,
      priority: 10, language: 'text', symbols: ['marker'], imports: ['./dependency'],
      size: 500, lastModified: 1, metadataOnly: false, fingerprint: 'f', indexedCharacters: 500, estimatedIndexBytes: 1000,
    });
    const original = [
      ...Array.from({ length: 12 }, (_, i) => chunk('p', 'a.txt', i)),
      ...Array.from({ length: 14 }, (_, i) => chunk('p', 'z.txt', i)),
      chunk('p', 'keep.txt', 0), chunk('q', 'a.txt', 0),
    ];
    const legacy = await new Promise<IDBDatabase>((resolve, reject) => {
      const r = indexedDB.open(dbName, 3);
      r.onupgradeneeded = () => {
        const db = r.result;
        db.createObjectStore('projects', { keyPath: 'id' });
        db.createObjectStore('sources', { keyPath: 'projectId' });
        const f = db.createObjectStore('files', { keyPath: 'id' });
        f.createIndex('projectId', 'projectId'); f.createIndex('projectPath', ['projectId', 'path'], { unique: true });
        f.createIndex('projectScan', ['projectId', 'lastSeenScanId']);
        const c = db.createObjectStore('chunks', { keyPath: 'id' });
        c.createIndex('projectId', 'projectId'); c.createIndex('projectPath', ['projectId', 'path']);
        c.createIndex('projectPriority', ['projectId', 'priority']); c.createIndex('searchTerms', 'searchTerms', { multiEntry: true });
      };
      r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error);
    });
    const tx = legacy.transaction(['files', 'chunks'], 'readwrite');
    const completion = done(tx);
    for (const c of original) tx.objectStore('chunks').put(c);
    for (const f of [file('p', 'a.txt', 'a', 12), file('p', 'z.txt', 'z', 14), file('p', 'keep.txt', 'm', 1), file('q', 'a.txt', 'a', 1)]) tx.objectStore('files').put(f);
    await completion;
    const expected = await request<Chunk[]>(legacy.transaction('chunks').objectStore('chunks').index('searchTerms').getAll('p:shared', 8));
    legacy.close();
    const modulePath = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const { IndexedDbProjectIndexRepository } = await import(modulePath) as
      typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    const repo = new IndexedDbProjectIndexRepository();
    const migrated = await repo.findCandidateChunks('p', ['shared'], 3);
    const db = await request(indexedDB.open(dbName));
    const preservedBodies = await request<Chunk[]>(db.transaction('chunks').objectStore('chunks').getAll());
    const preservedTerms = await request<{ id: string; searchTerms: string[] }[]>(db.transaction('chunkSearch').objectStore('chunkSearch').getAll());
    const termsById = new Map(preservedTerms.map(row => [row.id, row.searchTerms]));
    const preserved = preservedBodies.map(body => ({ ...body, searchTerms: termsById.get(body.id) }));
    await repo.writeBatch([file('p', '0-new.txt', 'm', 1)], [chunk('p', '0-new.txt', 0)]);
    const insertionOrderPreserved = (await repo.findCandidateChunks('p', ['shared'], 1))[0]?.path === '0-new.txt';
    const snapshot = async () => ({
      chunks: await request(db.transaction('chunks').objectStore('chunks').getAll()),
      postings: await request(db.transaction('chunkSearch').objectStore('chunkSearch').getAll()),
      blocks: await request(db.transaction('chunkSearchKeys').objectStore('chunkSearchKeys').getAll()),
    });
    const updated = chunk('p', 'a.txt', 0, 'newterm');
    await repo.writeBatch([], [updated]);
    const oldHits = await repo.findCandidateChunks('p', ['shared'], 100);
    const newHits = await repo.findCandidateChunks('p', ['newterm'], 1);
    const beforeFailure = await snapshot();
    let rolledBack = false;
    try { await repo.writeBatch([], [{ ...updated, content: (() => 'uncloneable') as unknown as string }], ['a.txt']); }
    catch { rolledBack = JSON.stringify(beforeFailure) === JSON.stringify(await snapshot()); }
    await repo.writeBatch([file('p', 'a.txt', 'a', 1)], [chunk('p', 'a.txt', 0, 'replacement')], ['a.txt']);
    const replacement = await repo.findChunksByPaths('p', ['a.txt'], 100);
    const removed = await repo.removeUnseenFiles('p', 'm');
    const cleaned = await snapshot();
    await repo.deleteProject('p');
    const final = await snapshot();
    const other = await repo.findCandidateChunks('q', ['shared'], 1);
    db.close();
    return {
      unchangedData: preserved.every(row => {
        const source = original.find(item => item.id === row.id)!;
        return Object.keys(source).every(key => JSON.stringify(row[key as keyof Chunk]) === JSON.stringify(source[key as keyof Chunk]));
      }) && preserved.length === original.length,
      sameOrder: JSON.stringify(migrated.map(c => c.id)) === JSON.stringify(expected.sort((a,b)=>a.id.localeCompare(b.id)).slice(0,3).map(c=>c.id)),
      insertionOrderPreserved,
      oldTermRemoved: !oldHits.some(c => c.id === updated.id), newTermFound: newHits[0]?.id === updated.id,
      rolledBack, replacementCount: replacement.length, removed,
      cleanedIds: cleaned.chunks.map(c => c.id),
      noStalePostings: cleaned.postings.every(r => !r.id.startsWith('p\0a.txt\0') && !r.id.startsWith('p\0z.txt\0')),
      isolated: other[0]?.projectId === 'q' && final.chunks.every(c => c.projectId === 'q')
        && final.postings.every(r => r.id.startsWith('q\0'))
        && final.blocks.every(r => Object.values(r.ids).every((id: string) => id.startsWith('q\0'))),
    };
  });
  expect(result).toEqual({ unchangedData: true, sameOrder: true, insertionOrderPreserved: true, oldTermRemoved: true,
    newTermFound: true, rolledBack: true, replacementCount: 1, removed: 2,
    cleanedIds: ['p\u00000-new.txt\u00000', 'p\0keep.txt\u00000', 'q\0a.txt\u00000'], noStalePostings: true, isolated: true });
});
