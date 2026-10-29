import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url));
const json = async path => JSON.parse(await readFile(resolve(evidence, path), 'utf8'));
const cases = [], jobs = [], provenance = [];
for (const stage of ['registry51-development', 'registry51-normal']) {
  const samples = (await json(stage + '/cases.json')).cases, original = await json(stage + '/jobs.json');
  for (const job of original) {
    assert.equal(job.phase, 'GENERATION'); assert.ok(job.approvedEnhancementInput && !job.approvedEnhancementInput.planConfirmation);
    const sample = samples.find(item => item.id === job.caseId); assert.ok(sample);
    const input = structuredClone(job.approvedEnhancementInput); assert.equal(input.modelId, 'deepseek:deepseek-flash');
    cases.push(structuredClone(sample));
    jobs.push({ id: job.id.replace('_V51_', '_V55_'), caseId: job.caseId, phase: 'GENERATION', version: '55',
      approvedEnhancementInput: input, purpose: 'FROZEN55_REGRESSION_UNCHANGED_FULL_INPUT' });
    provenance.push({ caseId: job.caseId, originalStage: stage, originalJobId: job.id,
      approvedInputSha256: createHash('sha256').update(JSON.stringify(input)).digest('hex'),
      priorResponseRelabeled: false, newActualCallRequired: true });
  }
}
assert.equal(jobs.length, 8); const target = resolve(evidence, 'registry55-regression-preparation'); await mkdir(target, { recursive: true });
for (const [name, value] of Object.entries({ 'bundle.json': { syntheticOnly: true, cases, jobs,
  phaseLimits: { GENERATION: 16, PLAN: 0, EXECUTION: 0, REVIEW: 0 } }, 'provenance.json': provenance }))
  await writeFile(resolve(target, name), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
await writeFile(resolve(target, 'protocol.md'), '# 55 regression\n\nReuse eight known development/normal inputs verbatim. No unseen topics are opened. One generation per case with the existing single protocol repair only; all attempts retained. Inspect prompt, task and history. Stop on severe intent/fact/permission/required-format regression; no quality retry. Content additions must help the original task. Allow sufficient inputs to tie. Further unseen questions and works require this semantic gate. Default31 is unchanged.\n', { flag: 'wx' });
console.log(JSON.stringify({ jobs: jobs.length, maximumCalls: 16, actualCalls: 0 }));
