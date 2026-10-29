import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url)), repo = resolve(here, '../../../../..');
const attempt = process.argv[2] ?? 'r1'; assert.match(attempt, /^r\d+$/);
const { createServer } = await import(new URL('../../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
const server = await createServer({ root: resolve(repo, 'apps/web'), logLevel: 'warn' });
try {
  const { indexProject, retrieveProjectContextWithReport } = await server.ssrLoadModule('/src/features/project-index/projectIndexer.ts');
  const { matchesReferencedPath, referencedPathVariants } = await server.ssrLoadModule('/src/features/project-index/projectPathReferences.ts');
  class Repository {
    files = []; chunks = []; summary;
    async beginProject(summary) { this.summary = summary; }
    async findFiles(projectId, paths) { return this.files.filter(file => file.projectId === projectId && paths.includes(file.path)); }
    async writeBatch(files, chunks) { this.files.push(...files); this.chunks.push(...chunks); }
    async pauseProject(summary) { this.summary = summary; }
    async completeProject(summary) { this.summary = summary; }
    async removeUnseenFiles() { return 0; }
    async failProject() {}
    async deleteProject() { this.files = []; this.chunks = []; }
    async findCandidateChunks(projectId, terms, limit) {
      const keys = terms.map(term => `${projectId}:${term}`);
      return this.chunks.filter(chunk => chunk.projectId === projectId && (chunk.priority === 0 || !keys.length || keys.some(key => chunk.searchTerms.includes(key)))).slice(0, limit);
    }
    async findChunksByPaths(projectId, paths, limit) {
      const accepted = paths.flatMap(path => referencedPathVariants(path, this.summary?.rootName));
      return this.chunks.filter(chunk => chunk.projectId === projectId && accepted.some(path => matchesReferencedPath(chunk.path, path))).slice(0, limit);
    }
  }
  async function retrieve(id, files, query) {
    const repository = new Repository();
    const entries = async function* () {
      for (const file of files) yield { kind: 'file', path: file.path, file: new File([file.content], file.path.split('/').at(-1), { type: 'text/plain', lastModified: 1 }) };
    };
    await indexProject({ projectId: id, rootName: 'sample', repository, entries: entries() });
    return retrieveProjectContextWithReport(repository, { projectId: id, query });
  }
  const sameNameFiles = [
    { path: 'src/current/create.js', content: 'export function createSurvey() { return "current"; }' },
    { path: 'src/legacy/create.js', content: 'export function createSurvey() { return "legacy"; }' },
  ];
  const shadow = await retrieve('same-name-shadow', sameNameFiles, '修改 @sample/src/current 的 createSurvey，限制未来日期');
  const semanticFiles = [{ path: 'src/shared/retry.ts', content: 'export function retryPayment() { return true; }' }];
  const unmatched = await retrieve('missing-bare-file', semanticFiles, '解释 src/missing/retryPayment.ts 的实现');
  const symbolOnly = await retrieve('symbol-only-control', semanticFiles, '解释 retryPayment 的实现');
  const shadowPaths = shadow.selections.map(item => item.path), unmatchedPaths = unmatched.selections.map(item => item.path);
  const report = { actualModelCalls: 0, method: '实际indexProject与retrieveProjectContextWithReport，仓库使用原有单测等价内存实现；无生成指导改动',
    sourceSha256: createHash('sha256').update(await readFile(resolve(repo, 'apps/web/src/features/project-index/projectIndexer.ts'))).digest('hex'),
    completeSymbolShadow: { query: '修改 @sample/src/current 的 createSurvey，限制未来日期', actual: shadow,
      oldModuleIncluded: shadowPaths.includes('src/legacy/create.js'), expectedPaths: ['src/current/create.js'] },
    unmatchedBarePath: { query: '解释 src/missing/retryPayment.ts 的实现', actual: unmatched,
      control: symbolOnly, symbolQueryFindsSource: symbolOnly.files.length > 0,
      fileNameSemanticTermLost: unmatchedPaths.length === 0 && symbolOnly.files.length > 0,
      interpretation: '非@普通路径未命中时不应假称该路径已找到，但移除整个词也会失去原本可用于常规定位的符号线索。' } };
  assert.ok(shadowPaths.includes('src/current/create.js'));
  if (attempt !== 'r1') {
    assert.equal(report.completeSymbolShadow.oldModuleIncluded, false);
    assert.equal(report.unmatchedBarePath.fileNameSemanticTermLost, false);
  }
  await writeFile(new URL(`./retrieval-review-${attempt}.json`, import.meta.url), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ actualModelCalls: 0, sameNameOldModuleIncluded: report.completeSymbolShadow.oldModuleIncluded,
    missingBarePathLosesSymbol: report.unmatchedBarePath.fileNameSemanticTermLost }));
} finally { await server.close(); }
