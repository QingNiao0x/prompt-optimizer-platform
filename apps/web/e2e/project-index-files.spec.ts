import { mockAuthentication } from './authFixture';
import { expect, test } from '@playwright/test';

/** 文件选择与目录共用生产 Worker、持久化和全文分块。 */
test('多选源码突破旧数量及长度限制并恢复逻辑路径', async ({ page }) => {
  test.setTimeout(120000);
  await page.route('**/__file_index', route => route.fulfill({ contentType: 'text/html', body: '<title>全文索引</title>' }));
  await page.goto('/__file_index');
  const result = await page.evaluate(async () => {
    const selectionPath = '/src/features/project-index/projectDirectorySelection.ts';
    const storagePath = '/src/features/project-index/projectIndexStorage.ts';
    const repositoryPath = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const workerPath = '/src/workers/projectIndex.worker.ts?worker';
    const { createFileSelection } = await import(selectionPath);
    const { createIsolatedProjectId } = await import(storagePath);
    const { projectIndexRepository: repository } = await import(repositoryPath);
    const { default: IndexWorker } = await import(workerPath);
    const body = 'export const example = 1;\n'.repeat(5000) + 'TAIL_FULL_CONTENT';
    const files = Array.from({ length: 1005 }, (_, i) => new File([i === 0 ? body : `export const marker${i} = 1;`], `${i}.ts`));
    const nested = new File(['export const nested = 1;'], 'nested.ts');
    Object.defineProperty(nested, 'webkitRelativePath', { value: 'root/nested.ts' });
    const ignore = new File(['nested.ts'], '.gitignore');
    Object.defineProperty(ignore, 'webkitRelativePath', { value: 'root/.gitignore' });
    const id = createIsolatedProjectId();
    await repository.prepareProject(id, new Date(Date.now()+3600000).toISOString(), 'SESSION');
    const source = createFileSelection([...files, nested, ignore, new File(['secret'], '.env'), new File(['export const duplicate = 1;'], '1.ts')]);
    await repository.saveProjectSource(id, source);
    const worker = new IndexWorker();
    try {
      const summary: any = await new Promise((resolve, reject) => {
        worker.onmessage = (event: MessageEvent) => {
          if (event.data.type === 'done') resolve(event.data.summary);
          if (event.data.type === 'error') reject(Error(event.data.message));
        };
        worker.onerror = (event: ErrorEvent) => reject(Error(event.message));
        worker.postMessage({ type: 'start', projectId: id, rootHandle: source, mode: 'FULL', respectGitignore: true,
          limits: { maxScanFiles: 1000000, maxIndexBytes: 2147483648, maxIndexableFileBytes: 52428800 } });
      });
      const chunks = await repository.findChunksByPaths(id, ['0.ts'], 100);
      const restored = await repository.findProjectSource(id);
      const records = await repository.findFiles(id, ['root/nested.ts', '.env', '1.ts', '1.ts (2)']);
      return { summary, exact: chunks.sort((a: any,b: any)=>a.chunkIndex-b.chunkIndex).map((c: any)=>c.content).join('') === body,
        paths: records.map((f: any)=>f.path), restored: restored.kind };
    } finally { worker.terminate(); await repository.deleteProject(id); }
  });
  expect(result.exact).toBe(true);
  expect(result.summary.indexedFiles).toBeGreaterThan(1000);
  expect(result.summary.contentComplete).toBe(true);
  expect(result.paths.sort()).toEqual(['1.ts', '1.ts (2)']);
  expect(result.restored).toBe('file-selection');
});

/** 普通工作台选择入口也必须进入全文索引。 */
test('工作台选择文件建立本地全文索引', async ({ page }) => {
  await mockAuthentication(page);
  await page.route('**/api/v1/me/settings', route => route.fulfill({ status: 503, json: {} }));
  await page.route('**/api/v1/analytics/events', route => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [] } }));
  await page.goto('/workbench');
  await page.getByTestId('context-file-input').setInputFiles([
    { name: 'example.ts', mimeType: 'text/plain', buffer: Buffer.from('export const value = 1;\n'.repeat(5000)+'TAIL_UI') },
    { name: '.npmrc', mimeType: 'text/plain', buffer: Buffer.from('_authToken=synthetic-test') },
    { name: '.env', mimeType: 'text/plain', buffer: Buffer.from('synthetic-test') },
    { name: 'other.ts', mimeType: 'text/plain', buffer: Buffer.from('export const other = 2;') },
  ]);
  await expect(page.getByText('2 个文本文件已完整建立本地索引')).toBeVisible({ timeout: 30000 });
});
