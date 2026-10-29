import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const json = async path => JSON.parse((await readFile(resolve(root, path), 'utf8')).replace(/^\uFEFF/, ''));
const completion = await json('plan-completion-bundle-v2.json'), development = await json('development-r2-bundle.json');
const cases = [], jobs = [];
// 先跑实际回答路径，避免把会话失效误判成模型质量。过期必须重新准备并留存新绑定。
for (const prefix of ['P02', 'P01']) {
  const old = completion.jobs.find(job => job.id.startsWith(prefix));
  const oldCase = completion.cases.find(item => item.id === old.caseId);
  const actualPlan = await json(`plan46/results/${prefix}_V46_PLAN.json`);
  if (Date.parse(actualPlan.body.data.expiresAt) < Date.now()) throw Error('Actual Plan expired; cannot silently rebind');
  cases.push({ ...oldCase, comparisonBaseline: `plan-completion46/results/${old.id}.json`,
    requiredChecks: [...oldCase.requiredChecks, '真实确认更新旧未决条件，不能在正文并列生效；内部仍完整追溯'] });
  jobs.push({ ...old, id: `${prefix}_V47_CONFIRMED_GEN`, version: '47' });
}
for (const id of ['C01', 'Q01', 'V01', 'V02']) {
  const old = development.jobs.find(job => job.id === `${id}_V46_GEN`);
  cases.push({ ...development.cases.find(item => item.id === id),
    controlPurpose: '确认优先级修复不得改变无回答任务的原意；生成波动不当作新补全收益' });
  jobs.push({ ...old, id: `${id}_V47_GEN`, version: '47' });
}
await writeFile(resolve(root, 'effective47-bundle.json'), JSON.stringify({ syntheticOnly: true,
  phaseLimits: { GENERATION: 24 }, cases, jobs }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ jobs: jobs.length, maximumCalls: 24, actualCalls: 0, version: '47' }));
