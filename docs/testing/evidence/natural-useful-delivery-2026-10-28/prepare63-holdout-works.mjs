import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const prepared = resolve(evidence, 'registry63-holdout-preparation');
const sealed = resolve(evidence, 'sealed-registry54-holdout2');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = value => createHash('sha256').update(value).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const samples = (await json(resolve(sealed, 'cases.json'))).cases;
const originalPlan = await json(resolve(sealed, 'execution-plan.json'));
const manifest = await json(resolve(sealed, 'manifest.json'));
const orderPath = resolve(repo, manifest.privateCoordinator.relativeToWorkspace);
assert.equal(sha(await readFile(orderPath)), manifest.privateCoordinator.sha256);
const order = await json(orderPath);
const closure = await json(resolve(evidence, 'registry63-holdout-gen/closure.json'));
assert.equal(closure.status, 'CLOSED');
const gates = await json(resolve(prepared, 'product-gates.json'));
assert.equal(gates.allEligible, true);
const generationJobs = await json(resolve(evidence, 'registry63-holdout-gen/jobs.json'));
const prompts = new Map();
const provenance = [];
for (const sample of samples) {
  prompts.set(`${sample.id}:ORIGINAL`, sample.rawPrompt);
  for (const version of ['31', '63']) {
    const id = `${sample.id}_V${version}_GEN`;
    assert.equal(gates.prompts.find(gate => gate.id === id)?.eligibleForWorks, true);
    const path = resolve(evidence, 'registry63-holdout-gen/results', id + '.json');
    const result = await json(path);
    assert.equal(result.status, 200);
    const data = result.body.data;
    assert.ok(data.processing.completedStages.includes('BODY_GENERATION'));
    assert.equal(typeof data.optimizedPrompt, 'string');
    prompts.set(`${sample.id}:${version}`, data.optimizedPrompt);
    provenance.push({ caseId: sample.id, version, generationResultSha256: sha(await readFile(path)), promptSha256: sha(data.optimizedPrompt) });
  }
}
const jobs = [], mapping = [];
for (const original of order.jobs) {
  const version = original.arm === '54' ? '63' : original.arm;
  const sample = samples.find(item => item.id === original.caseId);
  const input = generationJobs.find(job => job.caseId === sample.id).approvedEnhancementInput;
  const sharedMaterials = input.context.files.map(file => `【${file.path}】\n${file.content}`).join('\n\n');
  const prompt = prompts.get(`${sample.id}:${version}`);
  assert.ok(prompt);
  const id = `${sample.id}_${version}_R0${original.repeat}`;
  jobs.push({ id, caseId: sample.id, version, phase: 'EXECUTION', approvedModelRequest: {
    model: 'deepseek-flash', temperature: 0.2, max_tokens: 8192, stream: false, thinking: { type: 'disabled' },
    messages: [{ role: 'system', content: originalPlan.works.system }, { role: 'user', content: `【用户任务】\n${prompt}\n\n【共享背景】\n${sample.contextDescription}\n\n【完整共享资料】\n${sharedMaterials}` }],
  } });
  mapping.push({ ...original, originalSealedArm: original.arm, arm: version, jobId: id, promptSha256: sha(prompt), materialsSha256: sha(sharedMaterials) });
}
assert.equal(jobs.length, 12);
await save(resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry63-holdout-work-mapping.json'), { internalCoordinatorOnly: true, rootNotBlind: true, reviewerVersionBlind: true, originalOrderSha256: manifest.privateCoordinator.sha256, jobs: mapping });
await save(resolve(prepared, 'works-provenance.json'), { provenance, commonProfile: { model: 'deepseek-flash', temperature: 0.2, max_tokens: 8192, thinking: 'disabled' }, repeatedFirstExecutions: true, qualityRetries: 0, productGatesSha256: sha(await readFile(resolve(prepared, 'product-gates.json'))) });
await save(resolve(prepared, 'works-bundle.json'), {
  syntheticOnly: true, description: 'Original sealed holdout2 execution order, two first observations per admitted arm; same original full ordered material and execution permissions. Candidate metadata overlay54->63 was frozen before opening.',
  cases: samples, jobs, phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 12, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 8192, thinking: 'disabled', stream: false },
  preparationArtifacts: ['sealed-registry54-holdout2/review-criteria.json', 'registry63-holdout-preparation/product-gates.json', 'registry63-holdout-preparation/works-provenance.json'],
});
console.log(JSON.stringify({ prepared: true, workJobs: jobs.length, actualCalls: 0, allArmsSameMaterial: true, internalRootNotBlind: true }));
