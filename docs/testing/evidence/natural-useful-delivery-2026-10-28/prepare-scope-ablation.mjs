import { readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

// 一次性机制实验：保持已执行 writer 的全部指导和协议，仅移除派生 taskScope。
const root = dirname(fileURLToPath(import.meta.url));
const traces = resolve(root, '../../../../tmp/natural-useful-delivery-2026-10-28/development-r2/traces');
const readJson = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const cases = [], jobs = [];
for (const caseId of ['Q01', 'V03']) {
  let writer;
  for (const name of await readdir(traces)) {
    if (!name.endsWith('.json')) continue;
    const trace = await readJson(resolve(traces, name));
    if (trace.jobId !== `${caseId}_V46_GEN`) continue;
    const content = trace.request?.messages?.[1]?.content;
    if (!content) continue;
    const start = content.indexOf('\n{');
    if (start < 0) continue;
    const input = JSON.parse(content.slice(start + 1));
    if (input.taskScope) writer = { trace, input, start };
  }
  if (!writer) throw Error(`Completed body-generation trace required: ${caseId}`);
  const request = structuredClone(writer.trace.request);
  const originalUser = request.messages[1].content;
  delete writer.input.taskScope;
  request.messages[1].content = originalUser.slice(0, writer.start + 1) + JSON.stringify(writer.input);
  cases.push({ id: `AS${caseId}`, rawPrompt: writer.input.rawPrompt,
    purpose: '开发题的派生范围摘要锚定消融；不是下游作品或独立留出题',
    baselineJob: writer.trace.jobId,
    changedVariable: '仅移除 writer user JSON 的 taskScope；原始来源、system、内部记录协议、参数不变',
    baselineSystemSha256: createHash('sha256').update(request.messages[0].content).digest('hex'),
    baselineUserSha256: createHash('sha256').update(originalUser).digest('hex'),
    preregisteredDecision: 'Q01须出现有用的对象相关工作展开，且V03不损失原动作与边界；若仍仅核查要求，停止此假设，不重复采样。' });
  jobs.push({ id: `AS${caseId}_NO_SCOPE`, phase: 'EXECUTION', caseId: `AS${caseId}`, version: '46', approvedModelRequest: request });
}
await writeFile(resolve(root, 'scope-ablation-bundle.json'), JSON.stringify({ syntheticOnly: true,
  phaseLimits: { EXECUTION: 2 }, cases, jobs }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ jobs: jobs.length, maximumCalls: 2, actualCalls: 0 }));
