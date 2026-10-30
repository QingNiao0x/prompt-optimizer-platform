import { expect, test } from '@playwright/test';
import { mockAuthentication } from './authFixture';
import { openWorkbenchPane } from './workbenchPanes';

/** 注入原生事务写入异常，验证真实 IndexedDB 的回滚、保留和继续；不冒充磁盘实际耗尽。 */
test('存储失败保留已提交批次，重试不会清空索引或复用残缺内容', async ({ page }) => {
  await page.route('**/__recovery', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>恢复回归</title>' }));
  await page.goto('/__recovery');
  const result = await page.evaluate(async () => {
    const repositoryPath = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const enginePath = '/src/features/project-index/projectIndexer.ts';
    const { IndexedDbProjectIndexRepository } = await import(repositoryPath) as typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    const { indexProject, retrieveProjectContext } = await import(enginePath) as typeof import('../src/features/project-index/projectIndexer');
    const repository = new IndexedDbProjectIndexRepository();
    const entries = Array.from({ length: 450 }, (_, i) => ({ kind: 'file' as const,
      path: `src/file-${i}.ts`, file: new File([`export const unique${i} = '尾部规则${i}';`], `${i}.ts`, { lastModified: 1 }) }));
    const source = async function* () { yield* entries; };
    const options = { projectId: 'recovery', rootName: 'fixture', repository, expiresAt: '2099-01-01T00:00:00Z' };
    const put = IDBObjectStore.prototype.put;
    let fileWrites = 0;
    let message = '';
    try {
      IDBObjectStore.prototype.put = function(value: unknown, key?: IDBValidKey) {
        if (this.name === 'files' && ++fileWrites === 201) throw new DOMException('', 'QuotaExceededError');
        return key === undefined ? put.call(this, value) : put.call(this, value, key);
      };
      await indexProject({ ...options, entries: source() });
    } catch (error) { message = (error as Error).message; }
    finally { IDBObjectStore.prototype.put = put; }
    const failed = await repository.findProject(options.projectId);
    const cleanup = await repository.cleanupProjects();
    const kept = await repository.findProject(options.projectId);
    const resumed = await indexProject({ ...options, mode: 'RESUME', entries: source() });
    const tail = await retrieveProjectContext(repository, { projectId: options.projectId, query: 'src/file-449.ts', maxChunks: 2 });
    const files = await repository.findFiles(options.projectId, entries.map(entry => entry.path));
    await repository.deleteProject(options.projectId);
    return { message, failed, cleanup, kept, resumed, records: files.length,
      tail: tail.some(file => file.path.startsWith('src/file-449.ts#chunk-') && file.content.includes('尾部规则449')) };
  });
  expect(result.message).toContain('存储空间');
  expect(result.failed).toMatchObject({ status: 'FAILED', indexedFiles: 200 });
  expect(result.cleanup).toBe(0);
  expect(result.kept?.indexedFiles).toBe(200);
  expect(result.resumed).toMatchObject({ status: 'READY', indexedFiles: 450, unchangedFiles: 200, failedFiles: 0 });
  expect(result.records).toBe(450);
  expect(result.tail).toBe(true);
});

test('真实 Worker 的空配额异常有可读提示，点击继续后完成剩余文件', async ({ page }, testInfo) => {
  await mockAuthentication(page);
  await page.route('**/api/v1/me/settings', route => route.fulfill({ status: 503, json: { error: { message: '受控偏好不可用' } } }));
  await page.route('**/api/v1/analytics/events', route => route.fulfill({ status: 204 }));
  await page.route('**/api/v1/models', route => route.fulfill({ json: { data: [
    { id: 'mock:default', displayName: '测试模型', provider: 'Mock', defaultModel: true },
  ] } }));
  let workerLoads = 0;
  await page.route(url => url.pathname.endsWith('/projectIndex.worker.ts'), async route => {
    const response = await route.fetch();
    if (++workerLoads !== 1) return route.fulfill({ response });
    // 只在首次真实 Worker 的文件事务中注入一次失败；恢复 Worker 使用未修改的生产模块。
    const injection = `const savedPut=IDBObjectStore.prototype.put;let n=0;
      IDBObjectStore.prototype.put=function(value,key){if(this.name==='files'&&++n===201)throw new DOMException('','QuotaExceededError');
      return key===undefined?savedPut.call(this,value):savedPut.call(this,value,key)};`;
    await route.fulfill({ response, body: `${injection}\n${await response.text()}` });
  });
  await page.goto('/workbench');
  await openWorkbenchPane(page, 'context');
  await page.evaluate(async () => {
    const root = await (await navigator.storage.getDirectory()).getDirectoryHandle('recovery-ui', { create: true });
    for (let offset = 0; offset < 450; offset += 10) await Promise.all(Array.from({ length: 10 }, async (_, i) => {
      const writer = await (await root.getFileHandle(`file-${offset + i}.ts`, { create: true })).createWritable();
      await writer.write(`export const value = ${offset + i};`); await writer.close();
    }));
    Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
  });
  await page.getByRole('button', { name: '添加上下文' }).click();
  await page.getByRole('menuitem', { name: '选择文件夹' }).click();
  await expect(page.locator('.warning-list')).toContainText('浏览器存储空间不足', { timeout: 30_000 });
  await expect(page.getByTestId('resume-index')).toBeVisible();
  await expect(page.getByText('索引已中断，以下为最后成功提交的文件检查点；继续时会校验并补建。')).toBeVisible();
  await page.locator('.warning-list').scrollIntoViewIfNeeded();
  await expect(page.locator('.warning-list')).toBeInViewport();
  await page.locator('.warning-list').screenshot({ path: testInfo.outputPath('failure-message.png') });
  await page.getByTestId('resume-index').scrollIntoViewIfNeeded();
  await expect(page.getByTestId('resume-index')).toBeInViewport();
  await page.screenshot({ path: testInfo.outputPath('recoverable-failure.png') });
  await page.getByTestId('resume-index').click();
  await expect(page.getByText('450 个文本文件已完整建立本地索引')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/本轮新增 250 · 更新 0 · 未变化 200 · 删除 0/)).toBeVisible();
  await expect(page.locator('.warning-list')).not.toBeVisible();
});
