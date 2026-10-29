import { readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = dirname(fileURLToPath(import.meta.url));
const traces = resolve(root, '../../../../tmp/natural-useful-delivery-2026-10-28/development-r2/traces');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const before = '普通的内容组织、检索、阅读、方法和实现设计由执行者完成；可提出与原任务相称的做法并允许结合实际调整。';
const after = '增强器负责将简略目标设计为具体工作要求：展开与目标相关的内容重点、必要处理和应交付的内容；这种要求设计服务于原任务，不是执行原任务或增加另一个目标。\n'
  + '下游执行者负责按这些要求检索、阅读、计算、实现和写作，并结合现场选择具体手段；增强器未见的实际情况仍不写成事实。';
const cases = [], jobs = [];
for (const caseId of ['Q01', 'C01', 'V02', 'V03']) {
  let writer;
  for (const name of await readdir(traces)) {
    if (!name.endsWith('.json')) continue;
    const trace = await json(resolve(traces, name));
    if (trace.jobId !== `${caseId}_V46_GEN`) continue;
    const user = trace.request?.messages?.[1]?.content;
    if (user && JSON.parse(user.slice(user.indexOf('\n{') + 1)).taskScope) writer = trace;
  }
  if (!writer) throw Error(`Actual completed writer trace required: ${caseId}`);
  const request = structuredClone(writer.request), system = request.messages[0].content;
  if (!system.includes(before) || system.indexOf(before) !== system.lastIndexOf(before)) throw Error('Exact delegated-design sentence must occur once');
  request.messages[0].content = system.replace(before, after);
  const input = JSON.parse(request.messages[1].content.slice(request.messages[1].content.indexOf('\n{') + 1));
  cases.push({ id: `AT${caseId}`, rawPrompt: input.rawPrompt,
    purpose: '一次共同职责冲突实验；不是作品或留出题，不为某题增加模板', baselineJob: writer.jobId,
    changedVariable: '仅替换将普通内容组织和设计整体交给下游的一句，区分增强器设计工作要求与下游实际执行；全部其他业务指导、输入和协议不变',
    baselineSystemSha256: createHash('sha256').update(system).digest('hex'),
    identicalUserSha256: createHash('sha256').update(request.messages[1].content).digest('hex'),
    preregisteredDecision: 'Q01须有服务原查询的具体工作内容，不能只增加核查/长度；C01文件范围、V02只阅读等待、V03无资料与普通用户排查均不退步。失败停止此职责改写，不通过同义指导或重采寻找成功。即使满足仍需实际作品和留出验证。' });
  jobs.push({ id: `AT${caseId}_TASK_DESIGN`, phase: 'EXECUTION', caseId: `AT${caseId}`, version: '46', approvedModelRequest: request });
}
await writeFile(resolve(root, 'task-design-ablation-bundle.json'), JSON.stringify({ syntheticOnly: true,
  phaseLimits: { EXECUTION: 4 }, cases, jobs }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ jobs: 4, maximumCalls: 4, actualCalls: 0 }));
