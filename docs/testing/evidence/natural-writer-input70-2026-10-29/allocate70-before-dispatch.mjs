/** 首次请求前调整阶段预留，保留未执行的原冻结；不增加18次新题或48次任务上限。 */
import assert from 'node:assert/strict';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const actual31 = (await json(resolve(evidence, 'new-31/closure.json'))).actualCalls;
const actual69 = (await json(resolve(evidence, 'new-69/closure.json'))).actualCalls;
const limit = Math.min(8, 18 - actual31 - actual69);
assert.ok(limit >= 6 && limit <= 8);
const runtime = resolve(repo, 'tmp/natural-writer-input70-2026-10-29/new-70');
try { assert.equal((await readdir(resolve(runtime, 'traces'))).length, 0); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
await mkdir(resolve(runtime, 'traces'), { recursive: true });
await writeFile(resolve(evidence, 'new-70/cancellation.json'), JSON.stringify({
  reason: 'Unstarted stage withdrawn before any request to allocate the remaining frozen new-case allowance to protocol repair.',
  actualCalls: 0, qualityRetries: 0, originalCasesAndInputsUnchanged: true,
  replacementStage: 'new-70-allocated', newCaseGroupMaximum: 18, previousNewCaseActual: actual31 + actual69,
  replacementMaximum: limit, taskMaximum: 48,
}, null, 2) + '\n', { flag: 'wx' });
const bundle = await json(resolve(evidence, 'new-70-bundle.json'));
bundle.phaseLimits.GENERATION = limit;
await writeFile(resolve(evidence, 'new-70-allocated-bundle.json'), JSON.stringify(bundle, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ originalStageNeverDispatched: true, newCaseGroupMaximum: 18,
  previousNewCaseActual: actual31 + actual69, remainingAllocated: limit, actualCalls: 0 }));
