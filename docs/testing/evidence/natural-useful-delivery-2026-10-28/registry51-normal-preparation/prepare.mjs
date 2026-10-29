import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const sourceBytes = await readFile(resolve(evidence, 'registry50-normal-preparation/generation-bundle.json'));
const source = JSON.parse(sourceBytes); assert.equal(source.jobs.length, 5);
const jobs = source.jobs.map(item => ({ ...structuredClone(item), id: `${item.caseId}_V51_GEN`, version: '51', purpose: 'FLASH51_NORMAL_REGRESSION' }));
const equality = jobs.map(job => {
  const original = source.jobs.find(item => item.caseId === job.caseId).approvedEnhancementInput;
  assert.ok(isDeepStrictEqual(original, job.approvedEnhancementInput));
  assert.equal(original.modelId, 'deepseek:deepseek-flash');
  return { caseId: job.caseId, completeDtoUnchanged: true, dtoSha256: sha(JSON.stringify(original)), contextSnippetCount: original.context.files.length };
});
const budget = { maximumActualAcrossNormalAndPlanStages: 36, allocatedGenerationDirect: 20, allocatedPlan: 8,
  allocatedGenerationAfterActualPlan: 8, unallocated: 0, normalEstimatedActual: 18, qualityRetriesAllowed: false };
const bundle = { syntheticOnly: true, description: 'Existing five Flash inputs unchanged; controlled candidate51 normal regression preparation only.',
  parameters: source.parameters, phaseLimits: { GENERATION: 20, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  cases: source.cases, jobs, relatedNormalFlowBudget: budget,
  preparationProvenance: { source: 'registry50-normal-preparation/generation-bundle.json', sourceSha256: sha(sourceBytes),
    completeDtosIncludingModelUnchanged: true, equality, liveUiRecaptured: false, rootFreezeRequired: true } };
const c01 = source.cases.find(item => item.id === 'C01');
const plans = { syntheticOnly: true, status: 'REQUIRES_NEW_REAL_CONTEXT_PLAN_AND_OPERATOR_SELECTION',
  modelId: 'deepseek:deepseek-flash', strategyVersion: '51', relatedNormalFlowBudget: budget,
  cases: [
    { id: 'P01', rawPrompt: c01.rawPrompt, expectedPath: 'ACTUAL_ZERO_QUESTIONS_THEN_GENERATION',
      futurePlanJobId: 'P01_V51_PLAN', futureGenerationJobId: 'P01_V51_CONFIRMED_GEN' },
    { id: 'P02', rawPrompt: `请在重点记录模块@synthetic-survey-workspace/src/views/records 的新增和导入功能增加调查时间限制：只能填写当日之前的日期，同时又不能超过今日。今天是否允许填写由我确认，不要代选。\n\n${c01.contract}`,
      expectedPath: 'IMPORTANT_RESERVED_UNKNOWN_THEN_EXPLICIT_REAL_ANSWER', futurePlanJobId: 'P02_V51_PLAN', futureGenerationJobId: 'P02_V51_CONFIRMED_GEN' },
  ], planningContext: 'Must come from fresh real owned UI preparation; no placeholder contextId or planId is executable.' };
for (const [file, data] of Object.entries({ 'generation-bundle.json': bundle, 'plan-cases.json': plans,
  'input-equivalence.json': { sourceSha256: sha(sourceBytes), equality, actualModelCalls: 0 } })) {
  await writeFile(resolve(here, file), JSON.stringify(data, null, 2) + '\n', { flag: 'wx' });
}
console.log(JSON.stringify({ version: '51', unchangedFlashInputs: 5, maximumFutureCalls: 36, actualModelCalls: 0, stageFrozen: false }));
