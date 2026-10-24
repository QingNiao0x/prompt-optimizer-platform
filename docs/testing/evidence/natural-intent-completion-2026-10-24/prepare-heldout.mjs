import { readFile, writeFile, mkdir, stat } from 'node:fs/promises';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

// 此入口只在根代理明确开启留出后执行；此前不读取封存题面。
const base = dirname(fileURLToPath(import.meta.url));
const repo = resolve(base, '../../../..');
const read = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
assert.equal(process.argv[2], '--release-after-regression-pass');
const task = await read(resolve(base, 'task-protocol.json'));
const development = await read(resolve(base, 'regression/protocol.json'));
const closure = await read(resolve(base, 'regression/closure.json'));
const gate = await read(resolve(base, 'regression/regression-gate.json'));
assert.equal(task.status, 'FROZEN');
assert.equal(closure.status, 'CLOSED');
assert.equal(gate.status, 'PASS');
assert.equal(gate.candidateVersion, '37');
assert.equal(gate.unresolvedSevereRegressions, 0);
for (const path of [resolve(base, 'closure.json'), resolve(base, 'heldout/cases.json'), resolve(base, 'heldout/source-manifest.json')]) {
  let exists = false;
  try { await stat(path); exists = true; } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  assert.equal(exists, false, 'Task closed or holdout already released');
}
const sealedManifestPath = resolve(repo, 'tmp/intent37-holdout/manifest.json');
const adoption = await read(resolve(base, 'adoption-preregistration.json'));
assert.equal(adoption.status, 'FROZEN');
assert.equal(hash(await readFile(sealedManifestPath)), adoption.sealedHoldout.manifestSha256);
const sealed = await read(sealedManifestPath);
assert.equal(sealed.status, 'SEALED');
assert.equal(sealed.caseCount, 2);
assert.equal(sealed.files.length, 1);
assert.equal(sealed.files[0].path, 'tmp/intent37-holdout/cases.json');
const bytes = await readFile(resolve(repo, sealed.files[0].path));
assert.equal(hash(bytes), sealed.files[0].sha256, 'Sealed cases changed before release');
assert.equal(hash(bytes), adoption.sealedHoldout.casesSha256);
const value = JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
assert.ok(Array.isArray(value.cases) && value.cases.length === 2);
const ids = value.cases.map(sample => sample.id);
assert.equal(new Set(ids).size, 2);
const protocol = structuredClone(development);
protocol.batch = 'natural-intent-completion-heldout-2026-10-24';
protocol.status = 'DRAFT';
delete protocol.frozenAt;
protocol.authorizationQuote = '用户授权真实评测自行选择次数；本任务新增128内留出封顶40。';
protocol.taskPreviouslyActual = closure.taskActual;
protocol.maximumNewActualCalls = 40;
protocol.parameters.executionMaxTokens = 8192;
protocol.phaseLimits = {GENERATION:40,PLAN:0,EXECUTION:0,REVIEW:0};
protocol.plannedPrimaryCalls = {GENERATION:8,PLAN:0,EXECUTION:0,REVIEW:0};
protocol.generation = ids;
protocol.regressionGeneration = [];
protocol.plan = [];
protocol.confirmedPlanGeneration = [];
protocol.execution = [];
protocol.generationVersions = ['31','37'];
protocol.planVersions = ['31','37'];
protocol.executionArms = ['ORIGINAL','V31','V37'];
protocol.allowedJobs = ids.flatMap(id => [id+'_V31_GEN',id+'_V37_GEN']);
protocol.generationVersionsByCase = Object.fromEntries(ids.map(id => [id,['31','37']]));
protocol.requiredGenerationVersionsByCase = Object.fromEntries(ids.map(id => [id,['31','37']]));
protocol.prerequisiteStages = ['regression'];
protocol.order = '候选冻结并通过回归后开启内部独立封存两题；31/37同期增强，本阶段只检验增强；作品留到works阶段同信息三臂。';
protocol.criteria = ['三臂执行收到相同材料，不以准备分类自报或HTTP成功作为语义正确。',
 '比较目标/事实/要求保留、执行资料职责、是否存在整任务阻塞与用户必要修改；持平如实报告。',
 '候选无新增严重意图/事实/授权退步，且相对于原始与31存在可核查作品收益，才考虑默认切换。',
 '独立内部留出由本团队作者封存，不称外部专业认证；全部失败、正常变体与作品一并保留。'];
protocol.draftNotes = ['正常计划8次；上限40不指示用满。失败/协议修复计数，禁止质量重试。',
 '题面在开发关闭且回归PASS后才读取；候选不再根据留出结果继续同义调整。'];
const output = resolve(base, 'heldout');
await mkdir(output, {recursive:true});
await writeFile(resolve(output,'cases.json'),bytes,{flag:'wx'});
const encode = value => JSON.stringify(value,null,2)+'\n';
await writeFile(resolve(output,'protocol-draft.json'),encode(protocol),{flag:'wx'});
await writeFile(resolve(output,'release-manifest.json'),encode({releasedAt:new Date().toISOString(),
  candidateVersion:'37',developmentGate:'regression/regression-gate.json',source:sealed,
  sealedManifest:{path:relative(repo,sealedManifestPath).replaceAll('\\','/'),sha256:hash(await readFile(sealedManifestPath))},
  sourceSha256:hash(bytes),caseCount:2,modelCalls:0,caseTextsPrinted:false}),{flag:'wx'});
console.log(JSON.stringify({event:'INTERNAL_HOLDOUT_RELEASED_AFTER_REGRESSION_PASS',caseCount:2,modelCalls:0,caseTextsPrinted:false}));
