import { expect, test } from '@playwright/test';

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
