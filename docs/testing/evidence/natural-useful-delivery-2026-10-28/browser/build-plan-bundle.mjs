import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const attempt = process.argv[2] ?? 'r1', version = process.argv[3] ?? '47';
assert.match(attempt, /^r\d+$/); assert.match(version, /^\d+$/);
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const cases = [], jobs = [];
for (const id of ['P01', 'P02']) {
  const directory = resolve(here, 'plan-captures', attempt, id);
  const [input, preparationInput, preparation, verdict] = await Promise.all([
    json(resolve(directory, 'plan-input.json')), json(resolve(directory, 'prepare-input.json')),
    json(resolve(directory, 'prepare-response.json')), json(resolve(directory, 'verdict.json')),
  ]);
  assert.equal(preparation.status, 200); assert.equal(verdict.actualModelCalls, 0);
  assert.equal(input.rawPrompt, preparationInput.rawPrompt);
  assert.equal(input.planningContext.contextId, preparation.body.data.contextId);
  assert.equal(input.planningContext.version, preparation.body.data.version);
  assert.ok(Date.parse(preparation.body.data.expiresAt) > Date.now(), '实际上下文已过期；不得静默重新绑定');
  cases.push({ id, rawPrompt: input.rawPrompt, origin: '真实工作台选择完整项目，真实确定性上下文准备，Plan请求被本机捕获未发模型',
    originalPreparationRequest: preparationInput, actualContextDigest: preparation.body.data.digest,
    actualContextReference: input.planningContext, actualContextExpiresAt: preparation.body.data.expiresAt,
    ownerBinding: '固定合成账号，由真实服务端CurrentActor确定；未由脚本指定用户',
    expectation: id === 'P01' ? '清楚规则不凑题' : '只询问用户保留且会改变今天边界的重要决定' });
  jobs.push({ id: `${id}_V${version}_PLAN`, phase: 'PLAN', caseId: id, version,
    approvedEnhancementInput: input });
}
const output = resolve(here, 'plan-preparation', `bundle-v${version}-${attempt}.json`);
await mkdir(dirname(output), { recursive: true });
await writeFile(output, JSON.stringify({ syntheticOnly: true, cases, jobs,
  phaseLimits: { GENERATION: 0, PLAN: 8, EXECUTION: 0, REVIEW: 0 } }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output, jobs: jobs.length, actualNewModelCalls: 0,
  expiresAt: cases.map(sample => ({ id: sample.id, expiresAt: sample.actualContextExpiresAt })) }));
