import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
if (process.argv.includes('--help')) {
  console.log('Offline only: prepare the existing five normal inputs for explicitly selected native Pro50, plus fresh-Plan templates. Does not freeze, start or call anything.'); process.exit(0);
}
const sourcePath = resolve(evidence, 'registry50-normal-preparation/generation-bundle.json');
const sourceBytes = await readFile(sourcePath), baseline = JSON.parse(sourceBytes);
const ids = ['C01', 'C02', 'V01', 'V02', 'V03'];
assert.deepEqual(baseline.cases.map(item => item.id), ids);
assert.equal(baseline.jobs.length, 5);
const jobs = baseline.jobs.map(job => {
  assert.equal(job.version, '50'); assert.equal(job.phase, 'GENERATION');
  assert.equal(job.approvedEnhancementInput.modelId, 'deepseek:deepseek-flash');
  return { ...structuredClone(job), id: `${job.caseId}_V50_PRO_GEN`, purpose: 'NATIVE_PRO50_NORMAL_REGRESSION',
    approvedEnhancementInput: { ...structuredClone(job.approvedEnhancementInput), modelId: 'deepseek:deepseek-v4-pro' } };
});
const equivalence = jobs.map(job => {
  const original = baseline.jobs.find(item => item.caseId === job.caseId).approvedEnhancementInput;
  const restored = structuredClone(job.approvedEnhancementInput); restored.modelId = original.modelId;
  assert.ok(isDeepStrictEqual(original, restored));
  return { caseId: job.caseId, onlyModelIdChanged: true, originalDtoSha256: sha(JSON.stringify(original)),
    proposedDtoSha256: sha(JSON.stringify(job.approvedEnhancementInput)), contextSnippetCount: original.context.files.length };
});
const budget = { maximumActualAcrossNormalAndPlanStages: 36, allocatedGenerationDirect: 20,
  allocatedPlan: 8, allocatedGenerationAfterActualPlan: 8, unallocated: 0, normalEstimatedActual: 18,
  failedAndProtocolRepairAttemptsCount: true, qualityRetriesAllowed: false };
const generation = { syntheticOnly: true, description: 'Existing five normal cases, explicitly selected native Pro50; offline draft, not a registered live profile.',
  plannedProfileName: 'NATIVE_PRO50_NORMAL_GEN_V1', finiteProfileRegistered: false,
  parameters: structuredClone(baseline.parameters), phaseLimits: { GENERATION: 20, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  cases: structuredClone(baseline.cases), jobs, relatedNormalFlowBudget: budget,
  preparationProvenance: { source: 'registry50-normal-preparation/generation-bundle.json', sourceSha256: sha(sourceBytes),
    inputsRecapturedWithPro: false, onlyDtoChange: 'modelId', equivalence,
    freshFiniteProfileAndRootAuthorizationRequired: true } };
const c01 = baseline.cases.find(item => item.id === 'C01');
const plans = { syntheticOnly: true, status: 'TEMPLATES_REQUIRE_FRESH_REAL_CONTEXT_AND_PLAN',
  modelId: 'deepseek:deepseek-v4-pro', strategyVersion: '50',
  plannedPlanProfileName: 'NATIVE_PRO50_PLAN_V1', plannedCompletionProfileName: 'NATIVE_PRO50_PLAN_COMPLETION_V1',
  finiteProfilesRegistered: false, relatedNormalFlowBudget: budget,
  cases: [
    { id: 'P01', rawPrompt: c01.rawPrompt, expectedPath: 'ZERO_QUESTIONS_THEN_GENERATION',
      futurePlanJobId: 'P01_V50_PRO_PLAN', futureGenerationJobId: 'P01_V50_PRO_CONFIRMED_GEN' },
    { id: 'P02', rawPrompt: `请在重点记录模块@synthetic-survey-workspace/src/views/records 的新增和导入功能增加调查时间限制：只能填写当日之前的日期，同时又不能超过今日。今天是否允许填写由我确认，不要代选。\n\n${c01.contract}`,
      expectedPath: 'IMPORTANT_USER_RESERVED_UNKNOWN_THEN_ACTUAL_ANSWER_THEN_GENERATION',
      futurePlanJobId: 'P02_V50_PRO_PLAN', futureGenerationJobId: 'P02_V50_PRO_CONFIRMED_GEN' },
  ],
  materialContract: 'Complete 79-file project, real UI context preparation; keep all actually prepared material in the final service request and its original planningContext reference.',
  prohibited: ['Old planId/contextId', 'Invented actualAnswers', 'Automatic recommendation treated as selection', 'Silent expiry rebinding', 'Using old Flash31 as a same-model strategy comparison'],
};
for (const [name, value] of Object.entries({ 'generation-bundle.json': generation, 'plan-cases.json': plans,
  'input-equivalence.json': { sourceSha256: sha(sourceBytes), equivalence, actualModelCalls: 0 } })) {
  await writeFile(resolve(here, name), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
}
console.log(JSON.stringify({ preparedInputs: 5, planTemplates: 2, maximumActualCalls: 36,
  actualModelCalls: 0, finiteProfilesRegistered: false, stageFrozen: false }));
