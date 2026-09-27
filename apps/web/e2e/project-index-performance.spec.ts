import { expect, test } from '@playwright/test';

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
