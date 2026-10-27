# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-context-rewrite.spec.ts >> 自然增强从真实项目索引召回指定目录，显示复制与历史正文一致
- Location: e2e\natural-context-rewrite.spec.ts:92:1

# Error details

```
Error: expect(locator).toContainText(expected) failed

Locator: locator('.result-meta')
Expected substring: "已生成优化正文"
Received string:    "模型版本版本未记录fixtureTemplateGENERAL0 个结构段落Latency1 msMock 结果"
Timeout: 5000ms

Call log:
  - Expect "toContainText" with timeout 5000ms
  - waiting for locator('.result-meta')
    14 × locator resolved to <dl data-v-250dfbef="" data-v-aa10c3d5="" class="result-meta">…</dl>
       - unexpected value "模型版本版本未记录fixtureTemplateGENERAL0 个结构段落Latency1 msMock 结果"

```

```yaml
- term: 模型版本
- definition: 版本未记录
- text: fixture
- term: Template
- definition: GENERAL
- text: 0 个结构段落
- term: Latency
- definition: 1 ms
- text: Mock 结果
```

# Test source

```ts
  40  |   };
  41  |   await page.route('**/api/v2/optimizations', async (route) => {
  42  |     const request = route.request().postDataJSON() as OptimizationRequest;
  43  |     requests.push(request);
  44  |     result.contextReport.fileSnippets = request.context.files;
  45  |     await route.fulfill({ json: { requestId: 'controlled-context-rewrite', data: result } });
  46  |   });
  47  |   await page.route('**/api/v2/optimization-history**', async (route) => {
  48  |     const path = new URL(route.request().url()).pathname;
  49  |     const metadata = { resultKind: result.resultKind, generationPipeline: result.generationPipeline,
  50  |       chainVersion: result.chainVersion, processing: result.processing, framework: result.framework };
  51  |     if (path.endsWith('/optimization-history')) {
  52  |       await route.fulfill({ json: { data: { records: [{ id: recordId, rawPromptPreview: requests[0]?.rawPrompt,
  53  |         templateCode: 'GENERAL', providerName: 'fixture', modelName: 'controlled-browser-response', mock: true,
  54  |         latencyMs: 1, createdAt: '2026-10-27T00:00:00Z', ...metadata }], total: 1, current: 1, size: 10, pages: 1 } } });
  55  |       return;
  56  |     }
  57  |     await route.fulfill({ json: { data: { id: recordId, rawPrompt: requests[0]?.rawPrompt, optimizedPrompt: body,
  58  |       promptFormat: 'COMPACT', sections: [], contextSummary: {}, ambiguities: [], appliedConstraints: [],
  59  |       constraintGroups: result.constraintGroups, templateCode: 'GENERAL', providerName: 'fixture',
  60  |       modelName: 'controlled-browser-response', mock: true, latencyMs: 1, createdAt: '2026-10-27T00:00:00Z',
  61  |       includePermissionBoundaries: false, conversationHistory: [], permissionPolicy: {}, ...metadata } } });
  62  |   });
  63  |   return { requests, unexpected };
  64  | };
  65  | 
  66  | /** 通过文件夹选择器建立真实 Worker/IndexedDB 索引，不直接预填任务上下文。 */
  67  | const selectSyntheticDirectory = async (page: Page) => {
  68  |   const fixture = await loadSyntheticProject();
  69  |   await page.addInitScript(async ({ rootName, files }) => {
  70  |     localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
  71  |     const storage = await navigator.storage.getDirectory();
  72  |     const root = await storage.getDirectoryHandle(rootName, { create: true });
  73  |     for (const file of files) {
  74  |       const parts = file.path.split('/');
  75  |       let directory = root;
  76  |       for (const part of parts.slice(0, -1)) directory = await directory.getDirectoryHandle(part, { create: true });
  77  |       const handle = await directory.getFileHandle(parts.at(-1)!, { create: true });
  78  |       const writer = await handle.createWritable();
  79  |       await writer.write(file.content);
  80  |       await writer.close();
  81  |     }
  82  |     Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => root });
  83  |   }, fixture);
  84  |   await page.goto('/workbench');
  85  |   await openWorkbenchPane(page, 'context');
  86  |   await page.getByRole('button', { name: '添加上下文', exact: true }).click();
  87  |   await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
  88  |   await expect(page.getByText(`${fixture.files.length} 个源码文件已建立本地索引`, { exact: true })).toBeVisible();
  89  |   return fixture;
  90  | };
  91  | 
  92  | test('自然增强从真实项目索引召回指定目录，显示复制与历史正文一致', async ({ page }, testInfo) => {
  93  |   test.slow();
  94  |   const state = await setup(page);
  95  |   const fixture = await selectSyntheticDirectory(page);
  96  |   const indexedPaths = await page.evaluate(async () => {
  97  |     const request = indexedDB.open('prompt-optimizer-project-index');
  98  |     const database = await new Promise<IDBDatabase>((resolve, reject) => {
  99  |       request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
  100 |     });
  101 |     const transaction = database.transaction('files', 'readonly');
  102 |     const all = transaction.objectStore('files').getAll();
  103 |     const paths = await new Promise<string[]>((resolve, reject) => {
  104 |       all.onsuccess = () => resolve((all.result as Array<{ path: string }>).map(({ path }) => path));
  105 |       all.onerror = () => reject(all.error);
  106 |     });
  107 |     database.close();
  108 |     return paths.sort();
  109 |   });
  110 |   // 全部噪声仍被索引；目标优先不能靠删减索引范围取得。
  111 |   expect(indexedPaths).toEqual(fixture.files.map(({ path }) => path).sort());
  112 |   await openWorkbenchPane(page, 'intent');
  113 |   await page.getByLabel('原始提示词', { exact: true }).fill(fixture.rawPrompt);
  114 |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  115 |   const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  116 |   await expect(confirmation).toBeVisible();
  117 |   await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  118 |   await expect.poll(() => state.requests.length).toBe(1);
  119 |   const request = state.requests[0]!;
  120 |   const basePaths = request.context.files.map(({ path }) => path.split('#chunk-')[0]);
  121 |   await captureJson(testInfo, 'actual-browser-optimization-request.json', request);
  122 |   const retrieval = await page.evaluate(async () => {
  123 |     const modulePath = '/src/stores/optimization.ts';
  124 |     const { useOptimizationStore } = await import(modulePath) as typeof import('../src/stores/optimization');
  125 |     return useOptimizationStore().contextRetrieval;
  126 |   });
  127 |   await captureJson(testInfo, 'actual-browser-retrieval-report.json', retrieval);
  128 |   expect(request.rawPrompt).toBe(fixture.rawPrompt);
  129 |   expect(request.enhancement.promptFormat).toBe('COMPACT');
  130 |   expect(request.planConfirmation).toBeNull();
  131 |   for (const target of fixture.targetPaths) expect(basePaths).toContain(target);
  132 |   expect(basePaths.some((path) => path.startsWith('src/views/records-old/'))).toBe(false);
  133 |   expect(basePaths.some((path) => path.startsWith('src/views/records-archive'))).toBe(false);
  134 |   expect(request.context.files.length).toBeLessThan(fixture.files.length);
  135 |   const captureDirectory = new URL('../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/browser-capture/', import.meta.url);
  136 |   await mkdir(captureDirectory, { recursive: true });
  137 |   await writeFile(new URL('input.json', captureDirectory), `${JSON.stringify(request, null, 2)}\n`, 'utf8');
  138 |   await openWorkbenchPane(page, 'result');
  139 |   await expect(page.getByTestId('compact-prompt')).toHaveText(body);
> 140 |   await expect(page.locator('.result-meta')).toContainText('已生成优化正文');
      |                                              ^ Error: expect(locator).toContainText(expected) failed
  141 |   await page.bringToFront();
  142 |   await page.locator('.copy-main-button').click();
  143 |   await expect(page.locator('.copy-main-button')).toContainText('已复制');
  144 |   const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n');
  145 |   expect(copied).toBe(body);
  146 |   expect(copied).not.toContain('UNRELATED_SOURCE_');
  147 |   expect(copied).not.toContain('提供的材料：');
  148 |   await page.goto('/history');
  149 |   await page.getByRole('button', { name: '查看', exact: true }).filter({ visible: true }).click();
  150 |   await expect(page.getByRole('dialog', { name: '历史记录详情', exact: true }).locator('pre').nth(1)).toHaveText(copied);
  151 |   expect(state.unexpected).toEqual([]);
  152 |   await captureJson(testInfo, 'controlled-browser-verdict.json', {
  153 |     modelCalls: 0, controlledResponse: true, qualityEvidence: false, indexedFiles: indexedPaths.length,
  154 |     selectedPaths: basePaths, displayCopyHistoryEqual: true,
  155 |   });
  156 | });
  157 | 
  158 | test('冻结后的内部新题通过同一真实项目索引定位导入文件及必要依赖', async ({ page }, testInfo) => {
  159 |   test.slow();
  160 |   const state = await setup(page);
  161 |   await selectSyntheticDirectory(page);
  162 |   const newCase = JSON.parse(await readFile(new URL('../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/internal-new-case.json', import.meta.url), 'utf8')) as { rawPrompt: string };
  163 |   await openWorkbenchPane(page, 'intent');
  164 |   await page.getByLabel('原始提示词', { exact: true }).fill(newCase.rawPrompt);
  165 |   await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
  166 |   const confirmation = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
  167 |   await expect(confirmation).toBeVisible();
  168 |   await confirmation.getByRole('button', { name: '确认发送', exact: true }).click();
  169 |   await expect.poll(() => state.requests.length).toBe(1);
  170 |   const request = state.requests[0]!;
  171 |   const paths = request.context.files.map(({ path }) => path.split('#chunk-')[0]);
  172 |   await captureJson(testInfo, 'actual-browser-new-case-request.json', request);
  173 |   expect(request.rawPrompt).toBe(newCase.rawPrompt);
  174 |   expect(request.modelId).toBe('deepseek:deepseek-flash');
  175 |   expect(paths).toContain('src/views/records/import.js');
  176 |   expect(paths).toContain('src/views/records/dateRules.js');
  177 |   expect(paths.some((path) => path.startsWith('src/views/records-old/'))).toBe(false);
  178 |   expect(paths.some((path) => path.startsWith('src/views/records-archive'))).toBe(false);
  179 |   const captureDirectory = new URL('../../../docs/testing/evidence/natural-context-rewrite-2026-10-27/browser-capture/', import.meta.url);
  180 |   await mkdir(captureDirectory, { recursive: true });
  181 |   await writeFile(new URL('input-new-case.json', captureDirectory), `${JSON.stringify(request, null, 2)}\n`, 'utf8');
  182 |   expect(state.unexpected).toEqual([]);
  183 | });
  184 | 
```