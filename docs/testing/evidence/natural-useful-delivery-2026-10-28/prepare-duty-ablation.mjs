import { readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = dirname(fileURLToPath(import.meta.url));
const traces = resolve(root, '../../../../tmp/natural-useful-delivery-2026-10-28/development-r2/traces');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const cases = [], jobs = [];
for (const caseId of ['Q01', 'V03']) {
  let writer;
  for (const name of await readdir(traces)) {
    if (!name.endsWith('.json')) continue;
    const trace = await json(resolve(traces, name));
    if (trace.jobId !== `${caseId}_V46_GEN`) continue;
    const user = trace.request?.messages?.[1]?.content;
    if (user && JSON.parse(user.slice(user.indexOf('\n{') + 1)).taskScope) writer = trace;
  }
  if (!writer) throw Error(`Actual body trace required: ${caseId}`);
  const request = structuredClone(writer.request), original = request.messages[0].content;
  const body = '把rawPrompt整理为用户可以直接复制给执行者的自然请求。';
  const protocol = '与本次结果一起返回简短taskUnderstanding';
  if (!original.includes(body) || !original.includes(protocol)) throw Error('Expected exact46 instruction boundaries');
  request.messages[0].content = '【增强器的编辑职责】\n'
    + original.replace(body, '【优化正文的写作职责】\n上述编辑职责由增强器在处理输入时履行；只将原任务实际需要的内容写为下游要求，不将编辑检查清单转述成用户的新任务。\n' + body)
      .replace(protocol, '【内部结果协议，不进入优化正文】\n' + protocol);
  const input = JSON.parse(request.messages[1].content.slice(request.messages[1].content.indexOf('\n{') + 1));
  cases.push({ id: `AD${caseId}`, rawPrompt: input.rawPrompt,
    purpose: '编辑责任与下游正文职责分域的一次机制实验，不是实际作品或留出',
    baselineJob: writer.jobId, changedVariable: '保留原system全部规则、输入和响应协议，仅机械标记职责作用域并澄清编辑检查不自动成为下游任务',
    baselineSystemSha256: createHash('sha256').update(original).digest('hex'),
    identicalUserSha256: createHash('sha256').update(request.messages[1].content).digest('hex'),
    preregisteredDecision: '只有对象相关实际内容更有帮助且保留必要查询依据才支持此假设；删核查句、篇幅增加或换词不算收益。Q01仍仅核查则停止职责标签假设，不换措辞重采。' });
  jobs.push({ id: `AD${caseId}_SCOPED_DUTIES`, phase: 'EXECUTION', caseId: `AD${caseId}`, version: '46', approvedModelRequest: request });
}
await writeFile(resolve(root, 'duty-ablation-bundle.json'), JSON.stringify({ syntheticOnly: true,
  phaseLimits: { EXECUTION: 2 }, cases, jobs }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ jobs: 2, maximumCalls: 2, actualCalls: 0 }));
