import assert from 'node:assert/strict';
import { readFile, realpath, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

// Preparation only. No HTTP, account access, model call, fixture execution or closed-stage write.
const here = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const readJson = async file => JSON.parse(await readFile(file, 'utf8'));
const planned = await readJson(resolve(here, 'code-planned-bundle.json'));
const proof = await readJson(resolve(here, 'code-reuse-provenance.json'));
assert.equal(sha(await readFile(resolve(here, 'code-planned-bundle.json'))), proof.plannedBundleSha256);
const stageName = process.argv[2] ?? planned.sourceNormalStage;
assert.match(stageName, /^[a-z0-9-]+$/);
assert.equal(stageName, planned.sourceNormalStage, 'Only the planned normal51 source stage is allowed');

// Reuse the exact old executor fixture/system/profile. A drift requires a new proposal, not a replacement baseline call.
for (const source of [...proof.sourceArtifacts, planned.contentProposal]) {
  const file = resolve(repo, source.path);
  assert.ok(file.startsWith(`${repo}${sep}`) && !file.split(/[\\/]/).some(part => part.startsWith('sealed-')));
  assert.ok((await realpath(file)).startsWith(`${await realpath(repo)}${sep}`));
  assert.equal(sha(await readFile(file)), source.sha256, `Prepared source drift: ${source.path}`);
}
const stage = resolve(evidence, stageName), closure = await readJson(resolve(stage, 'closure.json'));
assert.equal(closure.status, 'CLOSED'); assert.equal(closure.entryLockedByClosure, true);
const sourceJobs = await readJson(resolve(stage, 'jobs.json'));
const oldJobs = await readJson(resolve(evidence, 'code-works46/jobs.json'));
const oldBundle = await readJson(resolve(evidence, 'code-works46-bundle.json'));
const jobs = [], cases = [], sourceResults = [];

for (const sample of planned.cases) {
  const reference = sample.plannedGenerationRef;
  const sourceJob = sourceJobs.find(item => item.id === reference.jobId);
  assert.equal(sourceJob?.version, '51'); assert.equal(sourceJob?.caseId, sample.caseId);
  assert.equal(sha(JSON.stringify(sourceJob.approvedEnhancementInput)), reference.expectedSerializedDtoSha256);
  const resultFile = resolve(stage, reference.resultRelativePath);
  assert.ok(resultFile.startsWith(`${stage}${sep}`));
  const original = closure.files.find(item => resolve(repo, item.path) === resultFile);
  assert.ok(original, 'Final prompt result is not a declared locked original');
  const bytes = await readFile(resultFile); assert.equal(sha(bytes), original.sha256);
  const result = JSON.parse(bytes), prompt = result.body?.data?.optimizedPrompt;
  assert.equal(result.status, 200); assert.equal(result.body.data.chainVersion, '51');
  assert.ok(result.body.data.processing?.completedStages?.includes('BODY_GENERATION'));
  assert.equal(result.body.data.processing.originalPreserved, false);
  assert.equal(typeof prompt, 'string'); assert.ok(prompt.trim());
  const source = oldJobs.find(item => item.id === sample.originalRequestRef.jobId);
  assert.ok(source && source.version === 'ORIGINAL' && source.caseId === sample.caseId);
  assert.equal(sha(sample.materialSuffix), sample.materialSuffixSha256);
  assert.equal(sha(source.approvedModelRequest.messages[0].content), sample.systemSha256);
  assert.equal(source.approvedModelRequest.messages[1].content, sample.rawPrompt + sample.materialSuffix);
  for (const repeat of [1, 2]) {
    const request = structuredClone(source.approvedModelRequest);
    request.messages[1].content = prompt + sample.materialSuffix;
    for (const material of sample.executorMaterials) assert.ok(request.messages[1].content.includes(material.content));
    jobs.push({ id: `${sample.caseId}_V51_EXEC_R${repeat}`, phase: 'EXECUTION', caseId: sample.caseId,
      version: '51', repeat, approvedModelRequest: request });
  }
  cases.push({ ...structuredClone(oldBundle.cases.find(item => item.id === sample.caseId)),
    sourceGenerationRef: reference, actualFinalPromptSha256: sha(prompt), rootPromptScopeReviewStillRequired: true });
  sourceResults.push({ jobId: sourceJob.id, resultPath: original.path, resultSha256: original.sha256, promptSha256: sha(prompt) });
}
assert.equal(jobs.length, 4);
const seed = 'registry51-code-works-fixed-before-execution';
jobs.sort((a, b) => sha(`${seed}\0${a.id}`).localeCompare(sha(`${seed}\0${b.id}`)));
const bundle = { syntheticOnly: true, status: 'PREPARED_AWAITING_ROOT_NORMAL_GATE_AND_FREEZE', cases, jobs,
  phaseLimits: { EXECUTION: 4 }, reusedWorkResults: planned.reusedOriginalWorks,
  executionOrderSeed: seed, qualityRetriesAllowed: false, accountCreationAllowed: false,
  rootApprovalRequiredBeforeFreezeAndLive: true, rootMustCheckEnhancedAndExecutionScopeAgree: true,
  preparationProvenance: { sourceStage: stageName, closureSha256: sha(await readFile(resolve(stage, 'closure.json'))),
    sourceResults, onlyExactFinalDeliveredPromptsSubstituted: true, newModelCallsMade: 0,
    existingCheckerEntry: planned.checkerReuse.entry, historical45AndBlocked31Unchanged: true } };
const text = JSON.stringify(bundle, null, 2) + '\n';
await writeFile(resolve(here, 'code-bundle.json'), text, { flag: 'wx' });
console.log(JSON.stringify({ preparedOnly: true, newModelCallsMade: 0, newExecutionCallsProposed: 4,
  sourceStage: stageName, bundleSha256: sha(text), output: resolve(here, 'code-bundle.json') }));
