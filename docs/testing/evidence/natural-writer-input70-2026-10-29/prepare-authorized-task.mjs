/** 依据用户新增48次授权建立独立任务；旧500次批次和原始失败不回改、不恢复余额。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const oldName = 'natural-useful-delivery-2026-10-28';
const taskName = 'natural-writer-input70-2026-10-29';
const previous = resolve(evidence, '..', oldName);
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const proposalPath = resolve(previous, 'writer70-offline/next-evaluation-plan.json');
const proposal = await json(proposalPath);
assert.equal(proposal.requestedAdditionalMaximum, 48);
assert.equal(proposal.authorizedAdditionalMaximum, 0); // 保留提出草案时的事实，不事后改写。
const oldStatus = await json(resolve(previous, 'final-status.json'));
assert.equal(oldStatus.actualModelCalls, 500);
assert.equal(oldStatus.allExecutedStagesClosed, true);
const freeze = await json(resolve(previous, 'writer70-offline/engineering-freeze.json'));
for (const file of freeze.files) assert.equal(sha(await readFile(resolve(repo, file.path))), file.sha256, file.path);
for (const file of proposal.frozenPayloadFiles) assert.equal(sha(await readFile(resolve(previous, 'writer70-offline', file.path))), file.sha256, file.path);
await mkdir(evidence, { recursive: true });
await save(resolve(evidence, 'authorization.json'), {
  taskId: taskName, authorizedAdditionalMaximum: 48, authorizedOn: '2026-10-29',
  authorizationSource: '用户回复：授权新增最多48次实际调用；同时批准冻结合成材料及派生内容发送至DeepSeek官方端点。',
  endpoint: proposal.destination, oldTaskClosedActual: 500, oldTaskReopened: false,
  proposalPath: 'docs/testing/evidence/' + oldName + '/writer70-offline/next-evaluation-plan.json',
  proposalSha256: sha(await readFile(proposalPath)), phases: proposal.phases,
  paidReviewCalls: 0, qualityRetriesAllowed: false, failedAndProtocolRepairAttemptsCount: true,
  adoptionGateUnchanged: true, defaultVersion: '31', syntheticOnly: true,
});

// 只复制新任务运行工具；不修改已闭批文件或产品源码。网络前预留、完整源校验和身份隔离照旧。
const tools = ['freeze-live.mjs', 'run-live.mjs', 'close-stage.mjs', 'start-live.ps1', 'invoke-live.ps1',
  'stop-live.ps1', 'NaturalUsefulDeliveryEvaluationServer.java', 'NaturalRegistry49CallGuard.java',
  'writer-thinking-profile.mjs', 'official-model-selection-profile.mjs',
  'Body59GuardSelfTest.java', 'Prepared64GuardSelfTest.java', 'InfrastructureReservationSelfTest.java'];
const copied = [];
for (const name of tools) {
  const original = await readFile(resolve(previous, name), 'utf8');
  let content = original.replaceAll(oldName, taskName);
  if (['freeze-live.mjs', 'run-live.mjs', 'close-stage.mjs', 'start-live.ps1', 'NaturalUsefulDeliveryEvaluationServer.java', 'InfrastructureReservationSelfTest.java'].includes(name))
    content = content.replace(/\b500\b/g, '48');
  if (name === 'freeze-live.mjs') {
    content = content.replace('User explicitly authorized this automated implementation and at most 48 actual model calls on 2026-10-28; old closed tasks remain unchanged.',
      'User explicitly authorized 48 additional actual calls on 2026-10-29 for frozen synthetic ORIGINAL/31/69/70 input-projection evaluation; old 500-call task remains closed.');
    content = content.replace("const ids = new Set();", "if (bundle.jobs.some(job => !['31','69','70','ORIGINAL'].includes(job.version) || job.phase === 'PLAN' || job.phase === 'REVIEW') || bundle.experimentProfile || bundle.modelSelectionProfile) throw Error('Outside authorized writer70 task');\nconst ids = new Set();");
  }
  if (name === 'run-live.mjs') content = content.replace("['62','63','64','65','66'].includes", "['62','63','64','65','66','67','68','69','70'].includes");
  if (name === 'NaturalUsefulDeliveryEvaluationServer.java') content = content.replace(
    '// 本轮用户独立授权48次，从0计数；旧182/200闭批仅作历史，不转入或恢复余额。',
    '// 本轮用户新增授权48次，从0计数；旧500次任务保持闭批，不转入或恢复余额。');
  await writeFile(resolve(evidence, name), content, { flag: 'wx' });
  copied.push({ name, originalSha256: sha(original), copiedSha256: sha(content), source: oldName + '/' + name });
}
await save(resolve(evidence, 'tool-copy-manifest.json'), { files: copied, productSourceChanged: false,
  differences: ['独立任务路径、命名空间和48次总上限', '拒绝本轮范围之外的版本、Plan、评审及其他实验profile', '补齐67—70已有冻结截止时间在客户端的识别'], actualModelCalls: 0 });

const regression = (await json(resolve(previous, 'writer70-offline/regression-inputs.json'))).inputs;
const future = (await json(resolve(previous, 'writer70-offline/future-cases.json'))).cases;
const regressionJobs = regression.map(sample => ({ id: `${sample.caseId}_V70_GEN`, caseId: sample.caseId,
  version: '70', phase: 'GENERATION', approvedEnhancementInput: sample.approvedEnhancementInput }));
await save(resolve(evidence, 'regression-bundle.json'), { syntheticOnly: true,
  cases: regression.map(sample => ({ id: sample.caseId, rawPrompt: sample.approvedEnhancementInput.rawPrompt })),
  jobs: regressionJobs, phaseLimits: { GENERATION: 8, PLAN: 0, EXECUTION: 0, REVIEW: 0 } });
for (const version of ['31', '69', '70']) {
  const jobs = future.map(sample => {
    const input = structuredClone(regression.find(sample => sample.caseId === 'Q01').approvedEnhancementInput);
    input.rawPrompt = sample.rawPrompt;
    input.context.files = sample.materials.map(file => ({ path: file.path, content: file.content, language: 'markdown' }));
    return { id: `${sample.id}_V${version}_GEN`, caseId: sample.id, version, phase: 'GENERATION', approvedEnhancementInput: input };
  });
  await save(resolve(evidence, `new-${version}-bundle.json`), { syntheticOnly: true, cases: future, jobs,
    phaseLimits: { GENERATION: 6, PLAN: 0, EXECUTION: 0, REVIEW: 0 } });
}
console.log(JSON.stringify({ prepared: true, maximumNewCalls: 48, oldTaskActual: 500, modelCalls: 0, sourceFreezeVerified: freeze.files.length }));
