/** 仅遮住版本标签，不声称双盲或外部专家评审；失败也留在完整作业清单中。 */
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const coordination = await json(resolve(repo, 'tmp/natural-writer-input70-2026-10-29/works-coordinator.json'));
const works = [];
for (const item of coordination.mapping) {
  const result = await json(resolve(evidence, item.source));
  const content = result.body?.data?.content ?? '';
  works.push({ workId: item.workId, caseId: item.caseId, status: result.status,
    content, hanCharacters: (content.match(/\p{Script=Han}/gu) ?? []).length,
    error: result.body?.error?.code ?? null });
}
await writeFile(resolve(evidence, 'anonymous-works.json'), JSON.stringify({ works,
  versionLabelsMasked: true, rootHasSeenGenerationPrompts: true, externallyBlindedReview: false }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ prepared: true, works: works.length, modelCalls: 0 }));
