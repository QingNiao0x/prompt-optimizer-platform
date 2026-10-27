import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadSyntheticProject } from './synthetic-project.mjs';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const closure = await json(resolve(evidence, 'live/closure.json'));
if (closure.status !== 'CLOSED' || !closure.entryLockedByClosure || closure.actualCalls !== 6 || !closure.allJobsDelivered) throw Error('Works require the completed, locked six-call generation stage');
const fixture = await loadSyntheticProject();
const liveCase = (await json(resolve(evidence, 'live/cases.json'))).cases.find(sample => sample.id === 'C01');
if (liveCase.rawPrompt !== fixture.rawPrompt) throw Error('Raw synthetic task changed');
for (const file of fixture.files) {
  const expected = liveCase.projectFileHashes.find(item => item.path === file.path);
  if (!expected || expected.sha256 !== createHash('sha256').update(file.content).digest('hex')) throw Error('Frozen project source changed');
}
const prompts = { ORIGINAL: fixture.rawPrompt };
for (const version of ['31', '45']) {
  const generated = await json(resolve(evidence, `live/results/C01_V${version}_GEN.json`));
  if (generated.status !== 200 || generated.body?.data?.chainVersion !== version || !generated.body.data.optimizedPrompt?.trim()) throw Error('Missing exact actual generation result');
  prompts[version] = generated.body.data.optimizedPrompt;
}
// 同一完整的可执行模块及既有测试契约提供给三臂；72份只用于检索压力的噪声不加入执行输入。
// 独立新增需求验收留在项目外，不将答案卡、期望未来错误码或算法实现发给模型。
const materials = fixture.files.filter(file => !file.path.startsWith('src/views/records-archive'));
const suffix = '\n\n本次执行使用的同一合成项目材料（已有文件完整正文）：\n'
  + materials.map(file => `--- ${file.path} ---\n${file.content}`).join('\n\n');
const system = '完成用户当前代码修改需求。只能使用所给项目材料，不能访问外部工具或声称已经运行。以JSON对象返回需要修改文件的完整内容，格式为{"files":[{"path":"相对路径","content":"完整文件正文"}]}，不附Markdown或解释。只允许修改以下项目文件：'
  + fixture.targetPaths.join('、') + '。不需修改的文件可省略，其他文件不能返回。';
const jobs = [];
for (const arm of ['ORIGINAL', '31', '45']) {
  for (let repeat = 1; repeat <= 2; repeat++) {
    jobs.push({ id: `C01_${arm === 'ORIGINAL' ? arm : 'V' + arm}_EXEC_R${repeat}`, phase: 'EXECUTION', caseId: 'C01', version: arm,
      approvedModelRequest: { model: 'deepseek-flash', stream: false, temperature: 0.2, max_tokens: 8192, thinking: { type: 'disabled' },
        messages: [{ role: 'system', content: system }, { role: 'user', content: prompts[arm] + suffix }] } });
  }
}
const sample = { ...liveCase, executorMaterials: materials.map(file => ({ name: file.path, content: file.content })),
  workContract: { allowedWritePaths: fixture.targetPaths, commonMaterialSha256: createHash('sha256').update(suffix).digest('hex'),
    output: '{"files":[{"path":"relative path","content":"full source"}]}', repeats: 2, paidReview: false,
    statement: 'Only the actual raw/31/45 prompt changes; model, parameters, common materials and transport format are identical.' } };
await mkdir(resolve(evidence, 'works-preparation'), { recursive: true });
const output = resolve(evidence, 'works-preparation/c01-bundle.json');
await writeFile(output, JSON.stringify({ syntheticOnly: true, cases: [sample], jobs }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output, cases: 1, jobs: jobs.length, repeatsPerArm: 2, commonMaterialFiles: materials.length,
  allowedWritePaths: fixture.targetPaths, commonMaterialSha256: sample.workContract.commonMaterialSha256, callsMade: 0 }));
