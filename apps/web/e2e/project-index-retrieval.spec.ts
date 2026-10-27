import { expect, test } from '@playwright/test';

test('明确目录在真实IndexedDB中优先召回，未命中不会冒充相似旧目录', async ({ page }) => {
  await page.route('**/__explicit_path_fixture', (route) => route.fulfill({
    contentType: 'text/html', body: '<!doctype html><html lang="zh-CN"><title>目录召回</title></html>',
  }));
  await page.goto('/__explicit_path_fixture');
  const result = await page.evaluate(async () => {
    const repositoryModule = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const indexerModule = '/src/features/project-index/projectIndexer.ts';
    const { IndexedDbProjectIndexRepository } = await import(repositoryModule) as
      typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    const { indexProject, retrieveProjectContextWithReport } = await import(indexerModule) as
      typeof import('../src/features/project-index/projectIndexer');
    const repository = new IndexedDbProjectIndexRepository();
    const projectId = 'explicit-reference-retrieval';
    async function* files() {
      for (let index = 0; index < 450; index += 1) {
        const path = `src/utils/noise${index}.ts`;
        yield { kind: 'file' as const, path, file: new File(['const shared = "日期 新增 导入";'], path) };
      }
      for (const path of ['src/views/records/Entry.vue', 'src/views/records/Import.vue', 'src/views/records-old/Old.vue']) {
        yield { kind: 'file' as const, path, file: new File(['const field = "录入时间";'], path) };
      }
    }
    try {
      const summary = await indexProject({ projectId, rootName: 'sample-project', repository, entries: files() });
      const target = await retrieveProjectContextWithReport(repository, {
        projectId, query: '模块@sample-project/src/views/records 新增和导入限制日期', maxChunks: 2,
      });
      const missing = await retrieveProjectContextWithReport(repository, {
        projectId, query: '修改 @sample-project/src/views/unavailable 新增和导入的日期限制',
        changedPaths: summary.changedPaths, maxChunks: 40,
      });
      const windowsPath = await retrieveProjectContextWithReport(repository, {
        projectId, query: '检查 @sample-project\\src\\views\\records\\Import.vue', maxChunks: 1,
      });
      return { indexedFiles: summary.indexedFiles, target, missing, windowsPath };
    } finally { await repository.deleteProject(projectId); }
  });
  expect(result.indexedFiles).toBe(453);
  expect(result.target.selections.map((item) => item.path)).toEqual([
    'src/views/records/Entry.vue', 'src/views/records/Import.vue',
  ]);
  expect(result.target.selections.every((item) => item.reasons.includes('用户明确引用路径'))).toBe(true);
  expect(result.missing.files).toEqual([]);
  expect(result.missing.references?.[0]?.status).toBe('NO_MATCHING_CHUNKS');
  expect(result.windowsPath.selections.map((item) => item.path)).toEqual(['src/views/records/Import.vue']);
});

/** 使用浏览器真实 IndexedDB 验证长需求和确认答案的召回，不依赖内存仓库替身。 */
test('长需求末尾的确认答案能召回中文方案正文，且索引按项目隔离', async ({ page }) => {
  await page.route('**/__index_retrieval_fixture', (route) => route.fulfill({
    contentType: 'text/html',
    body: '<!doctype html><html lang="zh-CN"><title>索引检索测试</title></html>',
  }));
  await page.goto('/__index_retrieval_fixture');

  const result = await page.evaluate(async () => {
    const repositoryModule = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const indexerModule = '/src/features/project-index/projectIndexer.ts';
    const { IndexedDbProjectIndexRepository } = await import(repositoryModule) as
      typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    const { indexProject, retrieveProjectContext } = await import(indexerModule) as
      typeof import('../src/features/project-index/projectIndexer');
    const repository = new IndexedDbProjectIndexRepository();
    const projectId = 'long-prompt-project';
    const businessRule = '跨地区退款必须按订单所属地区计算退款比例，超过三十天进入人工复核。';
    const planAnswer = '跨地区退款 三十天 人工复核';

    async function* projectFiles() {
      // 高频词能填满旧实现的候选预算，业务文档排在目录后部。
      for (let index = 0; index < 240; index += 1) {
        const path = `docs/${String(index).padStart(3, '0')}.md`;
        yield { kind: 'file' as const, path, file: new File(['topic0 常规项目说明'], path) };
      }
      const path = 'docs/zz-refund-policy.txt';
      const intro = Array.from({ length: 180 }, (_, index) => `identifier${index}`).join(' ');
      yield { kind: 'file' as const, path, file: new File([intro, '\n', businessRule], path) };
    }

    try {
      const summary = await indexProject({ projectId, rootName: 'mixed-project',
        repository, entries: projectFiles() });
      const query = `${Array.from({ length: 80 }, (_, index) => `topic${index}`).join(' ')}\n已确认：${planAnswer}`;
      const files = await retrieveProjectContext(repository, {
        projectId, query, maxChunks: 4, maxCharacters: 12_000,
      });
      const otherProjectFiles = await retrieveProjectContext(repository, {
        projectId: 'another-project', query, maxChunks: 4,
      });
      return { indexedFiles: summary.indexedFiles, files, otherProjectFiles, businessRule };
    } finally {
      await repository.deleteProject(projectId);
    }
  });

  expect(result.indexedFiles).toBe(241);
  expect(result.files.some((file) => file.path.startsWith('docs/zz-refund-policy.txt')
    && file.content?.includes(result.businessRule))).toBe(true);
  expect(result.otherProjectFiles).toEqual([]);
});
