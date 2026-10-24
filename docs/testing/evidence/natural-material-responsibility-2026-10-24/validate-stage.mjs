import { readFile, stat } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { verifiedReuse } from './reuse-evidence.mjs';
import { enhancementInput, executionMaterialSuffix } from './evaluation-inputs.mjs';

const base = dirname(fileURLToPath(import.meta.url));
const repo = resolve(base, '../../../..');
const stage = process.argv[2];
assert.match(stage ?? '', /^[a-z0-9-]+$/, 'Explicit safe stage required');
const evidence = resolve(base, stage);
const privateTask = resolve(repo, 'tmp/natural-material-responsibility-2026-10-24');
const draftOnly = process.argv[3] === '--draft';
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const task = await json(resolve(base, 'task-protocol.json'));
const protocol = await json(resolve(evidence, draftOnly ? 'protocol-draft.json' : 'protocol.json'));
const cases = (await json(resolve(evidence, 'cases.json'))).cases;

/** 只检查冻结结构和文件存在性，不解密凭据、不登录、不访问网络。 */
for (const root of [base, evidence, privateTask, resolve(privateTask, stage)]) {
  let closed = false;
  try { await stat(resolve(root, 'closure.json')); closed = true; }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  assert.equal(closed, false, 'Closed task/stage cannot execute');
}
assert.ok(draftOnly ? ['DRAFT','FROZEN'].includes(task.status) : task.status === 'FROZEN');
assert.equal(task.selectedMaximumActualCalls,48);
assert.equal(task.oldReleasedBalanceTransferred,0);
assert.ok(task.stages.reduce((sum,s)=>sum+s.maximum,0)<=48);
assert.equal(protocol.maximumNewActualCalls,task.stages.find(s=>s.name===stage)?.maximum);
assert.equal(protocol.status, draftOnly ? 'DRAFT' : 'FROZEN');
assert.ok(Number.isSafeInteger(task.selectedMaximumActualCalls) && task.selectedMaximumActualCalls > 0);
assert.equal(protocol.taskMaximumActualCalls, task.selectedMaximumActualCalls);
assert.ok(Number.isSafeInteger(protocol.maximumNewActualCalls) && protocol.maximumNewActualCalls > 0
  && protocol.maximumNewActualCalls <= task.selectedMaximumActualCalls);
assert.equal(protocol.accountCreationAllowed, false);
assert.equal(protocol.qualityRetriesAllowed, false);
assert.equal(protocol.baselineVersion, '31');
assert.ok(Array.isArray(protocol.candidateVersions) && protocol.candidateVersions.length === 1);
assert.ok(protocol.candidateVersions.every(version => typeof version === 'string' && /^\d+$/.test(version) && version !== '31'));
assert.deepEqual(protocol.versions, ['31', ...protocol.candidateVersions]);
const generationVersions = protocol.generationVersions ?? protocol.versions;
assert.ok(Array.isArray(generationVersions) && generationVersions.length
  && generationVersions.every(version => protocol.versions.includes(version)));
assert.equal(new Set(generationVersions).size, generationVersions.length);
const allArms = ['ORIGINAL', ...protocol.versions.map(version => 'V' + version)];
const executionArms = protocol.executionArms ?? allArms;
assert.ok(Array.isArray(executionArms) && executionArms.every(arm => allArms.includes(arm)));
assert.equal(new Set(executionArms).size, executionArms.length);
assert.equal(protocol.confirmationMode, 'PER_VERSION', 'Shared invented answer bundles are not supported');
assert.equal(protocol.modelId, 'deepseek:deepseek-flash');
assert.equal(protocol.upstreamModel, 'deepseek-flash');
assert.equal(protocol.endpoint, 'https://api.deepseek.com/chat/completions');
assert.equal(protocol.parameters.thinking, 'disabled');
assert.equal(protocol.parameters.stream, false);
assert.ok(typeof protocol.parameters.temperature === 'number' && protocol.parameters.temperature >= 0);
for (const name of ['generationMaxTokens', 'executionMaxTokens', 'reviewMaxTokens']) {
  assert.ok(Number.isSafeInteger(protocol.parameters[name]) && protocol.parameters[name] > 0);
}
assert.ok(Array.isArray(cases) && cases.length > 0);
const ids = new Set(cases.map(sample => sample.id));
assert.equal(ids.size, cases.length, 'Case IDs must be unique');
for (const sample of cases) {
  assert.match(sample.id, /^[A-Z0-9_-]+$/);
  assert.ok(typeof sample.rawPrompt === 'string' && sample.rawPrompt.trim());
  assert.ok(Array.isArray(sample.executorMaterials),
    'Executor materials are an explicit array; sufficient requests may need none');
  const enhancement = enhancementInput(sample, protocol);
  assert.equal(Object.hasOwn(enhancement, 'executorMaterials'), false);
  for (const file of sample.executorMaterials) {
    assert.deepEqual(Object.keys(file).sort(), ['content', 'name'], 'Executor material schema is {name,content}');
    assert.ok(typeof file.name === 'string' && file.name.trim());
    assert.ok(typeof file.content === 'string' && file.content.trim());
    assert.ok(file.content.length >= 20, 'Use actual readable input material, not a label or answer hint');
    assert.equal(JSON.stringify(enhancement).includes(file.content), false, 'Executor materials leaked to enhancement');
    assert.ok(executionMaterialSuffix(sample).includes(file.content));
  }
}
assert.equal(new Set(protocol.allowedJobs).size, protocol.allowedJobs.length, 'Job IDs must be unique');
const allowed = new Set(protocol.allowedJobs);
const reused = await verifiedReuse(repo, evidence, resolve(privateTask, stage), cases);
const reusedIds = new Set(reused.results.map(result => result.id));
assert.ok([...reusedIds].every(id => !allowed.has(id)), 'Reused jobs cannot be authorized as new model calls');
assert.ok(protocol.allowedJobs.every(id => /^[A-Z0-9_-]+$/.test(id)));
for (const phase of ['GENERATION', 'PLAN', 'EXECUTION', 'REVIEW']) {
  assert.ok(Number.isSafeInteger(protocol.phaseLimits[phase]) && protocol.phaseLimits[phase] >= 0);
}
assert.ok(Object.values(protocol.phaseLimits).reduce((a, b) => a + b, 0) <= protocol.maximumNewActualCalls);
for (const list of ['generation', 'plan', 'confirmedPlanGeneration', 'execution']) {
  assert.ok(Array.isArray(protocol[list]) && protocol[list].every(id => ids.has(id)));
  assert.equal(new Set(protocol[list]).size, protocol[list].length);
}
for (const id of protocol.confirmedPlanGeneration) assert.ok(protocol.plan.includes(id));
for (const id of protocol.generation) {
  for (const version of protocol.versions) {
    const job = `${id}_V${version}_GEN`;
    assert.ok(generationVersions.includes(version) ? allowed.has(job) : reusedIds.has(job), 'Missing generation or closed baseline: ' + job);
  }
}
const planVersions = protocol.planVersions ?? protocol.versions;
assert.ok(planVersions.length && planVersions.every(v=>protocol.versions.includes(v)));
for (const id of protocol.regressionGeneration ?? []) assert.ok(ids.has(id) && allowed.has(`${id}_V${protocol.candidateVersions[0]}_GEN`));
for (const id of protocol.plan) {
  for (const version of planVersions) assert.ok(allowed.has(`${id}_V${version}_PLAN`));
}
for (const id of protocol.confirmedPlanGeneration) {
  for (const version of planVersions) assert.ok(allowed.has(`${id}_V${version}_PLAN_GEN`));
}
if (protocol.execution.length) {
  assert.equal(protocol.executionRepeats, 2);
  assert.notEqual(protocol.executionIncludeOriginal, false, 'All three arms are required');
  for (const id of protocol.execution) {
    assert.ok(protocol.generation.includes(id) || protocol.confirmedPlanGeneration.includes(id));
    for (const arm of allArms) {
      for (let repeat = 1; repeat <= 2; repeat++) {
        const job = `${id}_${arm}_EXEC_R${repeat}`;
        assert.ok(executionArms.includes(arm) ? allowed.has(job) : reusedIds.has(job), 'Missing execution or closed work: ' + job);
      }
    }
  }
}
for (const review of protocol.reviews ?? []) {
  assert.ok(allowed.has(review.id));
  assert.ok(Array.isArray(review.caseIds) && review.caseIds.every(id => protocol.execution.includes(id)));
  assert.equal(typeof review.reverse, 'boolean');
}
if (protocol.reviews?.length) {
  assert.equal(protocol.reviews.length, 2, 'Exactly two independent positional reviews');
  assert.equal(protocol.reviews[0].reverse, false);
  assert.equal(protocol.reviews[1].reverse, true);
  assert.deepEqual(protocol.reviews[0].caseIds, protocol.reviews[1].caseIds);
  assert.ok(typeof protocol.reviewSystem === 'string' && protocol.reviewSystem.includes('center'));
}
for (const version of protocol.versions) {
  assert.ok(Array.isArray(protocol.generationPayloadVersions?.[version])
    && protocol.generationPayloadVersions[version].includes(version));
}
await stat(resolve(repo, 'tmp/local-acceptance/account.json'));
await stat(resolve(repo, 'tmp/local-acceptance/credential.dpapi'));
console.log(JSON.stringify({event:draftOnly?'DRAFT_STAGE_VALIDATED_OFFLINE':'FROZEN_STAGE_VALIDATED_OFFLINE',stage,cases:cases.length,
  jobs:allowed.size,reusedLogicalResults:reused.results.length,maximumNewActualCalls:protocol.maximumNewActualCalls,modelCalls:0}));
