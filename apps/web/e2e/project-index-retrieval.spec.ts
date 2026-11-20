import { expect, test } from '@playwright/test';

test('长通知中的 API 能找到 SDK 调用，解释通知时保留文档，在旧库与独立库中均可检索', async ({ page }) => {
  await page.route('**/__notice_lookup', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html>' }));
  await page.goto('/__notice_lookup');
  const results = await page.evaluate(async () => {
    const repositoryModule = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const indexerModule = '/src/features/project-index/projectIndexer.ts';
    const { RoutedProjectIndexRepository } = await import(repositoryModule) as typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    const { indexProject, retrieveProjectContextWithReport } = await import(indexerModule) as typeof import('../src/features/project-index/projectIndexer');
    const repository = new RoutedProjectIndexRepository();
    const notice = '通知：服务端将在约定日期停用旧入参。请核对业务调用 SendSmsVerifyCode 使用的签名，已经迁移的可忽略。';
    async function* files() {
      // 文档含原样接口名，实际 SDK 则使用小写开头与后缀。按精确词召回会丢失唯一的业务调用。
      for (let index = 0; index < 40; index++) {
        const path = `docs/archive/${index}.md`;
        yield { kind: 'file' as const, path, file: new File([notice], path) };
      }
      const path = 'app/SmsGateway.java';
      yield { kind: 'file' as const, path, file: new File(['client.sendSmsVerifyCodeWithOptions(request, options);'], path) };
    }
    const hits = [];
    for (const projectId of ['legacy-notice', 'pi-d1-notice']) {
      try {
        await indexProject({ projectId, rootName: 'demo', entries: files(), repository });
        for (const query of [`请看以下短信，我是否需要修改？\n${notice}`,
          `下面通知只需用两句大白话解释给同事。\n${notice}`, '请阅读 @docs/archive/6.md 中的 SendSmsVerifyCode 通知']) {
          const result = await retrieveProjectContextWithReport(repository, { projectId, query, maxChunks: 1 });
          hits.push(result.selections.map(selection => selection.path));
        }
      } finally {
        await repository.deleteProject(projectId);
      }
    }
    return hits;
  });
  const expected = [['app/SmsGateway.java'], [expect.stringMatching(/^docs\/archive\//)], ['docs/archive/6.md']];
  expect(results).toEqual([...expected, ...expected]);
});

test('根目录文件名在大量同名正文中仍精确召回，未知文件不收紧普通检索', async ({ page }) => {
  await page.route('**/__root_reference', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html>' }));
  await page.goto('/__root_reference');
  const result = await page.evaluate(async () => {
    const repositoryPath = '/src/features/project-index/indexedDbProjectIndexRepository.ts';
    const indexerPath = '/src/features/project-index/projectIndexer.ts';
    const { IndexedDbProjectIndexRepository } = await import(repositoryPath) as typeof import('../src/features/project-index/indexedDbProjectIndexRepository');
    const { indexProject, retrieveProjectContextWithReport } = await import(indexerPath) as typeof import('../src/features/project-index/projectIndexer');
    const repository = new IndexedDbProjectIndexRepository(), projectId = 'root-file-reference';
    async function* files() {
      for (let i = 0; i < 450; i++) {
        const path = `apps/examples/${i}.ts`;
        yield { kind: 'file' as const, path, file: new File(['const readme = "README.md 普通业务依据";'], path) };
      }
      yield { kind: 'file' as const, path: 'README.md', file: new File(['本项目说明正文：退款按原规则执行。'], 'README.md') };
    }
    try {
      const summary = await indexProject({ projectId, rootName: 'sample', repository, entries: files() });
      const started = performance.now();
      const root = await retrieveProjectContextWithReport(repository, { projectId, query: 'README.md', maxChunks: 1 });
      const elapsedMs = performance.now() - started;
      const unknown = await retrieveProjectContextWithReport(repository, { projectId, query: '不存在.md 普通业务依据', maxChunks: 2 });
      const other = await retrieveProjectContextWithReport(repository, { projectId: 'other', query: 'README.md', maxChunks: 1 });
      return { count: summary.indexedFiles, root, unknown, other, elapsedMs };
    } finally { await repository.deleteProject(projectId); }
  });
  expect(result.count).toBe(451);
  expect(result.root.selections.map(item => item.path)).toEqual(['README.md']);
  expect(result.root.files[0]?.content).toContain('退款按原规则执行');
  expect(result.unknown.files).not.toHaveLength(0);
  expect(result.unknown.references ?? []).toEqual([]);
  expect(result.other.files).toEqual([]);
  console.log('ROOT_FILE_RETRIEVAL', JSON.stringify({ count: result.count, elapsedMs: result.elapsedMs }));
});

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
