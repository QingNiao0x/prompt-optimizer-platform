import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { cases, loadSyntheticProject } from '../fixtures/project.mjs';

const evidence = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const stage = process.argv[2], candidate = process.argv[3] ?? '46';
const captures = process.argv[4] ?? 'captures-r2';
assert.match(captures, /^captures(?:-r\d+)?$/);
if (!/^[a-z0-9-]+$/.test(stage ?? '') || !/^\d+$/.test(candidate)) throw Error('用法：node build-code-work-bundle.mjs <已完成增强阶段> [候选版本]');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const fixture = await loadSyntheticProject();
const materials = fixture.files.filter(file => !file.path.startsWith('src/views/records-archive'));
const suffix = '\n\n本次执行使用的同一合成项目材料（已有文件完整正文）：\n'
  + materials.map(file => `--- ${file.path} ---\n${file.content}`).join('\n\n');
const jobs = [], samples = [];
for (const sample of cases) {
  const actualInput = await json(resolve(evidence, 'browser', captures, sample.id, 'input.json'));
  assert.equal(actualInput.rawPrompt, sample.rawPrompt);
  const prompts = { ORIGINAL: actualInput.rawPrompt };
  const blockedArms = [];
  for (const version of ['31', candidate]) {
    const result = await json(resolve(evidence, stage, 'results', `${sample.id}_V${version}_GEN.json`));
    assert.equal(result.status, 200); assert.equal(result.body.data.chainVersion, version);
    assert.ok(result.body.data.optimizedPrompt?.trim());
    if (!result.body.data.processing?.completedStages?.includes('BODY_GENERATION')) {
      if (version === candidate) throw Error('候选未完成正文生成，不应进入付费作品阶段');
      blockedArms.push({ version, reason: '未完成BODY_GENERATION，先判产品门失败，不为原文加源码作品继续付费。' });
    } else prompts[version] = result.body.data.optimizedPrompt;
  }
  const system = '完成用户当前代码修改需求。只能使用所给项目材料，不能访问外部工具或声称已经运行。'
    + '以JSON对象返回需要修改文件的完整内容，格式为{"files":[{"path":"相对路径","content":"完整文件正文"}],"summary":"可选简短说明"}；files必填，summary可省略。不要在JSON外附Markdown或解释，不需修改的文件可省略。没有执行工具时，不得声称已运行测试。'
    + sample.contract;
  for (const arm of Object.keys(prompts)) for (let repeat = 1; repeat <= 2; repeat += 1) {
    jobs.push({ id: `${sample.id}_${arm === 'ORIGINAL' ? arm : 'V' + arm}_EXEC_R${repeat}`,
      phase: 'EXECUTION', caseId: sample.id, version: arm,
      approvedModelRequest: { model: 'deepseek-flash', stream: false, temperature: 0.2, max_tokens: 8192,
        thinking: { type: 'disabled' }, messages: [{ role: 'system', content: system }, { role: 'user', content: prompts[arm] + suffix }] } });
  }
  samples.push({ ...sample, blockedArms, executorMaterials: materials.map(file => ({ path: file.path, content: file.content })),
    workContract: { contractInRawAndExecutionSystemIdentical: true, allowedWritePaths: sample.allowedWritePaths,
      repeatsPerArm: 2, model: 'deepseek-flash', temperature: 0.2, paidReviews: 0,
      commonMaterialsSha256: createHash('sha256').update(suffix).digest('hex'),
      onlyPromptChanges: true, testChangesOptional: sample.id === 'C01',
      output: { required: ['files'], optional: ['summary'], summaryPresenceIsNotQualityScore: true,
        noToolExecutionClaims: true },
      testsMustNotBeReturned: sample.id === 'C02' } });
}
jobs.sort((left, right) => createHash('sha256').update('2026-10-28-code-work-order:' + left.id).digest('hex')
  .localeCompare(createHash('sha256').update('2026-10-28-code-work-order:' + right.id).digest('hex')));
await mkdir(resolve(evidence, 'browser/works-preparation'), { recursive: true });
const output = resolve(evidence, 'browser/works-preparation/bundle.json');
await writeFile(output, JSON.stringify({ syntheticOnly: true, cases: samples, jobs }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output, cases: samples.length, jobs: jobs.length, actualNewModelCalls: 0 }));
