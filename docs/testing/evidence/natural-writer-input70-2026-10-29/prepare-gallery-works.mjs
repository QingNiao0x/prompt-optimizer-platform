/** 旧资源分配失败仅作回归；70仍是首次正文及事先编号的两份作品，不改指导或挑选首稿。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const old = resolve(evidence, '../natural-useful-delivery-2026-10-28');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const stage = resolve(evidence, 'final-regression');
const closure = await json(resolve(stage, 'closure.json')); assert.equal(closure.status, 'CLOSED');
const path = resolve(stage, 'results/R52-HO-A_V70_GEN.json'), bytes = await readFile(path);
assert.equal(closure.files.find(file => resolve(repo, file.path) === path)?.sha256, sha(bytes));
const generation = JSON.parse(bytes); assert.equal(generation.status, 200);
assert.ok(generation.body.data.processing.completedStages.includes('BODY_GENERATION'));
const sample = (await json(resolve(old, 'sealed-registry52-holdout/cases.json'))).cases.find(item => item.id === 'R52-HO-A');
const oldWorks = await json(resolve(old, 'registry68-holdout-works/jobs.json'));
const template = oldWorks.find(item => item.caseId === sample.id).approvedModelRequest;
assert.equal(template.max_tokens, 8192);
const materialMessage = template.messages[2];
const expected = '本次共同材料（所有作品臂完全相同）：\n' + sample.materials.map(file => `--- ${file.path} ---\n${file.content}`).join('\n\n');
assert.equal(materialMessage.content, expected);
const jobs = [1, 2].map(repeat => ({ id: `R52-HO-A_V70_EXEC_R${repeat}`, caseId: sample.id,
  version: '70', phase: 'EXECUTION', repeat, approvedModelRequest: { ...structuredClone(template),
    messages: [structuredClone(template.messages[0]), { role: 'user', content: generation.body.data.optimizedPrompt }, structuredClone(materialMessage)] } }));
await save(resolve(evidence, 'gallery-works-bundle.json'), { syntheticOnly: true, cases: [sample], jobs,
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 2, REVIEW: 0 } });
await save(resolve(evidence, 'gallery-works-provenance.json'), { knownRegression: true, unseenClaim: false,
  generationSource: 'final-regression/results/R52-HO-A_V70_GEN.json', generationSha256: sha(bytes),
  commonMaterialSha256: sha(expected), sameSystemModelAndParametersAsHistoricalWorks: true,
  historicalSource: 'natural-useful-delivery-2026-10-28/registry68-holdout-works',
  historicalWorkSamplesNotResampled: true, onlyFirstTwo70Works: true, sourceGuidanceChangedAfterOpening: false,
  paidReviewCalls: 0, qualityRetries: 0 });
console.log(JSON.stringify({ prepared: true, knownRegression: true, newWorks: 2, modelCalls: 0 }));
