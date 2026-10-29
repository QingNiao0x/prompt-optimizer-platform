import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..');
const bytes = name => readFile(resolve(evidence, name));
const sha = value => createHash('sha256').update(value).digest('hex');
const jobsBytes = await bytes('registry50-development/jobs.json');
const protocolBytes = await bytes('registry50-development/protocol.json');
const originalJobs = JSON.parse(jobsBytes), protocol = JSON.parse(protocolBytes);
const cases = JSON.parse(await bytes('registry50-development/cases.json')).cases;
assert.equal(protocol.modelId, 'deepseek:deepseek-flash');
assert.deepEqual(originalJobs.map(job => job.caseId), ['Q01', 'U02', 'U03']);
const jobs = originalJobs.map(original => {
  const job = structuredClone(original);
  assert.equal(job.version, '50');
  job.id = `${job.caseId}_V50_PRO_GEN`;
  job.purpose = 'NATIVE_MODEL_SELECTION_ABLATION_SAME_PRODUCT50';
  job.approvedEnhancementInput.modelId = 'deepseek:deepseek-v4-pro';
  const reverted = structuredClone(job.approvedEnhancementInput); reverted.modelId = 'deepseek:deepseek-flash';
  assert.equal(JSON.stringify(reverted), JSON.stringify(original.approvedEnhancementInput));
  return job;
});
const bundle = { syntheticOnly: true,
  description: 'Prepared native catalog-model single-variable development comparison. Same frozen product50 runtime/system and complete inputs; only public DTO.modelId changes from Flash to Pro. Normal scope+writer six calls, maximum12 including failures/repairs. No new material, Plan, actual works, unseen/adoption claim or quality retries.',
  modelSelectionProfile: { name: 'OFFICIAL_MODEL_SELECTION_ABLATION_V1', modelId: 'deepseek:deepseek-v4-pro',
    baselineStage: 'registry50-development', baselineJobsSha256: sha(jobsBytes), baselineProtocolSha256: sha(protocolBytes) },
  parameters: { generationMaxTokens: 8192, executionMaxTokens: 8192, temperature: 0.2, thinking: 'disabled', stream: false },
  phaseLimits: { GENERATION: 12, PLAN: 0, EXECUTION: 0, REVIEW: 0 }, cases, jobs,
  preFreezeRequirements: ['Root approves the future finite model profile and its zero-call guards; do not freeze/start before approval',
    'Use the closed Flash50 runtime/classes and dependency classpath, not current target or candidate51',
    'After fixed-account LOGIN, zero-upstream GET must resolve the real published Pro catalog model and official existing DeepSeek route before any generation',
    'Keep .2/8192/JSON/disabled/streamfalse and strict source/QA/raw/assembled protection; count every actual attempt',
    'Global default, administrator model catalog, user preference and all old closed evidence stay unchanged'],
  comparisonBoundary: 'Changes the native model for both existing prepare and writer calls; it is not a writer-only effect or proof of stable quality from three single samples.' };
const output = Buffer.from(JSON.stringify(bundle, null, 2) + '\n');
await writeFile(resolve(here, 'development-bundle.json'), output, { flag: 'wx' });
await writeFile(resolve(here, 'input-equivalence.json'), JSON.stringify({ status: 'PREPARED_NOT_FROZEN', actualModelCalls: 0,
  bundleSha256: sha(output), baselineProtocolSha256: sha(protocolBytes), baselineJobsSha256: sha(jobsBytes),
  comparisons: jobs.map((job, index) => ({ caseId: job.caseId, originalJobId: originalJobs[index].id, newJobId: job.id,
    onlyPublicModelIdChanged: true, sourceFlashDtoSha256: sha(JSON.stringify(originalJobs[index].approvedEnhancementInput)),
    candidateProDtoSha256: sha(JSON.stringify(job.approvedEnhancementInput)), version: '50',
    contextSnippetCount: job.approvedEnhancementInput.context.files.length })) }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ event: 'PRO50_BUNDLE_PREPARED_NOT_FROZEN', sha256: sha(output), jobs: jobs.length,
  normalActualCalls: 6, maximumActualCalls: 12, actualModelCalls: 0 }));
