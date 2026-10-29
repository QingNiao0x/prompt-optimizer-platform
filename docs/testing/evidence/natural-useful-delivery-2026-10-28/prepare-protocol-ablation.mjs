import { readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = dirname(fileURLToPath(import.meta.url));
const repo = resolve(root, '../../../..');
const traces = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/development-r2/traces');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
let writer;
for (const path of await readdir(traces)) {
  if (!path.endsWith('.json')) continue;
  const trace = await json(resolve(traces, path));
  if (trace.jobId === 'Q01_V46_GEN' && trace.request?.messages?.[0]?.content.includes('只返回JSON：')) writer = trace;
}
if (!writer) throw Error('Completed actual writer request required');
const request = structuredClone(writer.request);
const original = request.messages[0].content;
const marker = '只返回JSON：';
const index = original.indexOf(marker);
if (index < 0 || index !== original.lastIndexOf(marker)) throw Error('Frozen output protocol boundary must be unique');
request.messages[0].content = original.slice(0, index)
  .replace('修正后再填写真实的来源与正文覆盖引文；', '修正正文后再返回；')
  + '只返回JSON：{"optimizedPrompt":"完整自然正文","framework":"参考标记"}。\n';
const rawPrompt = '请帮我查询全球使用AI最多的职业/行业';
const bundle = { syntheticOnly: true, phaseLimits: { EXECUTION: 1 },
  cases: [{ id: 'ABQ01', rawPrompt, purpose: 'Output-protocol ablation on the existing actual Q01 writer input; not a downstream work or independent holdout',
    baselineJob: 'Q01_V46_GEN', changedVariable: 'Remove internal task record from writer response; business guidance and full user message unchanged',
    baselineSystemSha256: createHash('sha256').update(original).digest('hex'),
    commonUserMessageSha256: createHash('sha256').update(request.messages[1].content).digest('hex') }],
  jobs: [{ id: 'ABQ01_BODY_ONLY', phase: 'EXECUTION', caseId: 'ABQ01', version: '46', approvedModelRequest: request }] };
await writeFile(resolve(root, 'protocol-ablation-bundle.json'), JSON.stringify(bundle, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ jobs: 1, maximumCalls: 1, actualCalls: 0, changedVariable: bundle.cases[0].changedVariable }));
