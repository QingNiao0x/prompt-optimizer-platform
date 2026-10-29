import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..');
const stage = process.argv[2], gatePath = process.argv[3];
if (!/^[a-z0-9-]+$/.test(stage ?? '') || !gatePath) throw Error('用法：node build-works-bundle.mjs <增强阶段> <产品门审阅JSON绝对路径>');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const input = await json(resolve(here, 'generation-bundle.json')), gate = await json(resolve(gatePath));
const cases = [], jobs = [];
for (const sample of input.cases) {
  const decision = gate.cases.find(item => item.id === sample.id); assert.ok(decision, '缺少逐题产品门审阅');
  const prompts = { ORIGINAL: sample.originalSealedPrompt }, blocked = [];
  for (const version of ['31', '47']) {
    const result = await json(resolve(evidence, stage, 'results', `${sample.id}_V${version}_GEN.json`));
    const approved = decision.versions?.[version]; assert.equal(typeof approved?.allow, 'boolean');
    assert.ok(typeof approved.reason === 'string' && approved.reason.trim(), '须记录真实门控理由，不能用HTTP200代替');
    if (!approved.allow) { blocked.push({ version, reason: approved.reason }); continue; }
    assert.equal(result.status, 200); assert.equal(result.body.data.provider.mock, false);
    assert.ok(result.body.data.processing.completedStages.includes('BODY_GENERATION'), '未生成正文不能进入付费作品');
    assert.ok(result.body.data.optimizedPrompt.trim()); prompts[version] = result.body.data.optimizedPrompt;
  }
  if (!Object.hasOwn(prompts, '47')) { cases.push({ ...sample, blocked, workStatus: '候选未过产品门，不继续付费作品' }); continue; }
  const attachmentSuffix = sample.originalAttachments.length ? '\n\n以下是本次用户提供的完整附件（所有执行臂逐字一致）：\n'
    + sample.originalAttachments.map(file => `--- 附件：${file.path.replace(/^inputs\/H\d+\//, '')} ---\n${file.content}`).join('\n\n') : '';
  const system = '请执行用户要求，遵守原定范围和输出格式。只使用本次提供的信息和附件，不联网。当前没有可调用的执行工具，不要声称已经实际运行代码。';
  for (const [arm, prompt] of Object.entries(prompts)) jobs.push({ id: `${sample.id}_${arm === 'ORIGINAL' ? arm : 'V' + arm}_EXEC`,
    phase: 'EXECUTION', caseId: sample.id, version: arm,
    approvedModelRequest: { model: 'deepseek-flash', stream: false, temperature: 0.2, max_tokens: 2600, thinking: { type: 'disabled' },
      messages: [{ role: 'system', content: system }, { role: 'user', content: prompt + attachmentSuffix }] } });
  cases.push({ ...sample, blocked, workStatus: '原始及通过产品门的增强臂各一次，不重采挑结果',
    commonAttachmentPayloadSha256: createHash('sha256').update(attachmentSuffix).digest('hex'),
    workContract: { ...sample.workContract, originalPromptBytesPreserved: true,
      inputInformationIdenticalAcrossArms: true, artifactFormatComesFromOriginalPrompt: true } });
}
jobs.sort((left, right) => createHash('sha256').update('sealed-holdout-order:' + left.id).digest('hex')
  .localeCompare(createHash('sha256').update('sealed-holdout-order:' + right.id).digest('hex')));
assert.ok(jobs.length > 0, '没有通过产品门的作品题，不应冻结空付费批次');
const output = resolve(here, 'works-bundle.json');
await writeFile(output, JSON.stringify({ syntheticOnly: true, cases, jobs, executionMaxTokens: 2600,
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: jobs.length, REVIEW: 0 },
  prerequisiteStages: [stage] }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output, works: jobs.length, maxTokens: 2600, actualModelCalls: 0, repetitionsPerArm: 1 }));
