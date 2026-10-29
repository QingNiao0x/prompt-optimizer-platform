import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, stat, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(here, '..');
const stage = process.argv[2];
const gatePath = process.argv[3];
if (!/^[a-z0-9-]+$/.test(stage ?? '') || !gatePath) {
  throw new Error('Usage: node build-works-bundle.mjs <generation-stage> <root-product-gate.json>');
}
const json = async (file) => JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));
const hash = (value) => createHash('sha256').update(value).digest('hex');
const sealed = resolve(evidence, 'sealed-engineering-holdout');
const manifestBytes = await readFile(resolve(sealed, 'manifest.json'));
assert.equal(hash(manifestBytes), 'a7f9c19244af011966e6558e2aeb7877e3d8d466b7a6f4f6a2fa3be2be4311af');
for (const file of JSON.parse(manifestBytes).files) {
  assert.equal(hash(await readFile(resolve(sealed, file.path))), file.sha256);
}
await stat(resolve(evidence, stage, 'closure.json'));
const generationBundle = await json(resolve(here, 'generation-bundle.json'));
const executionProject = await json(resolve(here, 'execution-project.json'));
const executionContract = await json(resolve(here, 'execution-contract.json'));
const gateBytes = await readFile(resolve(gatePath));
const gate = JSON.parse(gateBytes);
const decision = gate.cases.find((item) => item.id === 'E01');
assert.ok(decision, 'The root must review this exact case before work generation');
assert.equal(executionProject.files.length, 30);
assert.equal(executionContract.executionMaxTokens, 10000);
const sealedPrompt = await readFile(resolve(sealed, 'inputs/prompt.md'), 'utf8');
assert.equal(executionProject.rawPrompt, sealedPrompt);
for (const entry of executionProject.fileHashes) {
  assert.equal(hash(executionProject.files.find((file) => file.path === entry.path).content), entry.sha256);
  assert.equal(hash(await readFile(resolve(sealed, 'inputs/project', entry.path))), entry.sha256);
}

const prompts = { ORIGINAL: sealedPrompt };
const blocked = [];
const generationChecks = [];
for (const version of ['31', '47']) {
  const approved = decision.versions?.[version];
  assert.equal(typeof approved?.allow, 'boolean');
  assert.ok(typeof approved.reason === 'string' && approved.reason.trim());
  if (!approved.allow) { blocked.push({ version, reason: approved.reason }); continue; }
  const file = resolve(evidence, stage, 'results', `E01_V${version}_GEN.json`);
  const bytes = await readFile(file);
  const result = JSON.parse(bytes);
  const generated = result.body?.data;
  assert.equal(result.id, `E01_V${version}_GEN`);
  assert.equal(result.caseId, 'E01');
  assert.equal(result.phase, 'GENERATION');
  assert.equal(result.version, version);
  assert.equal(result.status, 200);
  assert.equal(generated?.provider?.mock, false);
  assert.equal(generated.chainVersion, version);
  if (!generated.processing?.completedStages?.includes('BODY_GENERATION')) {
    blocked.push({ version, reason: 'Actual BODY_GENERATION is absent; this arm cannot execute even when a product gate says allow.' });
    continue;
  }
  assert.ok(typeof generated.optimizedPrompt === 'string' && generated.optimizedPrompt.trim());
  prompts[version] = generated.optimizedPrompt;
  generationChecks.push({ version, resultSha256: hash(bytes), bodyGenerationCompleted: true,
    rootProductGateAllows: true, promptSha256: hash(generated.optimizedPrompt) });
}
assert.ok(Object.hasOwn(prompts, '47'), 'Candidate47 has no approved generated body; do not start paid works');
const browserVerdict = await json(resolve(here, 'live', stage, 'E01_V47_GEN/verdict.json'));
assert.equal(browserVerdict.version, '47');
assert.equal(browserVerdict.businessRequests, 1);
assert.equal(browserVerdict.providerMock, false);
assert.equal(browserVerdict.bodyGenerationCompleted, true);
assert.equal(browserVerdict.actualHistoryBodyVersionProcessingEqual, true);
const copied = await readFile(resolve(here, 'live', stage, 'E01_V47_GEN/copy.txt'), 'utf8');
assert.equal(copied.replace(/\r\n/g, '\n'), prompts['47'].replace(/\r\n/g, '\n'));

const attachmentSuffix = '\n\n以下是本次用户提供的完整工程文件（所有执行臂逐字一致）：\n'
  + executionProject.files.map((file) => `--- 工程文件：${file.path} ---\n${file.content}`).join('\n\n');
const system = '请执行用户要求，遵守原定范围和输出格式。只使用本次提供的信息和附件，不联网。当前没有可调用的执行工具，不要声称已经实际运行代码。';
const jobs = Object.entries(prompts).map(([arm, prompt]) => ({
  id: `E01_${arm === 'ORIGINAL' ? arm : `V${arm}`}_EXEC`, phase: 'EXECUTION', caseId: 'E01', version: arm,
  approvedModelRequest: { model: 'deepseek-flash', stream: false, temperature: 0.2, max_tokens: 10000,
    thinking: { type: 'disabled' }, messages: [{ role: 'system', content: system },
      { role: 'user', content: prompt + attachmentSuffix }] },
}));
jobs.sort((left, right) => hash(`sealed-engineering-order:${left.id}`).localeCompare(hash(`sealed-engineering-order:${right.id}`)));
for (const job of jobs) {
  assert.ok(job.approvedModelRequest.messages[1].content.endsWith(attachmentSuffix));
  for (const file of executionProject.files) assert.ok(job.approvedModelRequest.messages[1].content.includes(file.content));
}
const sample = generationBundle.cases.find((item) => item.id === 'E01');
const output = {
  syntheticOnly: true,
  cases: [{ ...sample, originalSealedPrompt: sealedPrompt, blocked,
    workStatus: 'Root product gate allows a single work attempt per admitted arm; no work quality pass yet',
    executionProjectPath: 'engineering-run/execution-project.json',
    executionProjectSha256: hash(await readFile(resolve(here, 'execution-project.json'))),
    commonAttachmentPayloadSha256: hash(attachmentSuffix),
    workContract: { ...executionContract, originalPromptBytesPreserved: true,
      sourceFileCount: 30, sourceBytesFromSealedProjectOnly: true,
      inputInformationIdenticalAcrossArms: true, sourceReconstructedFromChunks: false,
      productGateSha256: hash(gateBytes), repetitionsPerArm: 1 } }],
  jobs, parameters: { generationMaxTokens: 8192, executionMaxTokens: 10000 },
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: jobs.length, REVIEW: 0 }, prerequisiteStages: [stage],
};
const outputText = JSON.stringify(output, null, 2) + '\n';
await writeFile(resolve(here, 'works-bundle.json'), outputText, { flag: 'wx' });
await writeFile(resolve(here, 'works-preparation.json'), JSON.stringify({
  caseId: 'E01', generationStage: stage, sourceManifestSha256: hash(manifestBytes),
  productGateSha256: hash(gateBytes), generationChecks, blocked,
  workJobs: jobs.map((job) => job.id), wholeProjectFilesPerArm: 30, maxTokensPerArm: 10000,
  originalPromptPreserved: true, allSourceBytesIdentical: true, commonAttachmentPayloadSha256: hash(attachmentSuffix),
  answerCardsIncluded: false, productGateMeansWorkQualityPassed: false,
  modelCallsMadeByBuilder: 0, worksBundleSha256: hash(outputText),
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output: resolve(here, 'works-bundle.json'), jobs: jobs.map((job) => job.id),
  filesPerArm: 30, maxTokens: 10000, phaseLimit: jobs.length, blocked, modelCalls: 0, sha256: hash(outputText) }));
