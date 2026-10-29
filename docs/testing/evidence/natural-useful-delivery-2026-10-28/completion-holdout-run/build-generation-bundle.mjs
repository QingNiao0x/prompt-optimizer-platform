import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url)), seal = here;
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const verification = await json(resolve(here, 'seal-verification.json'));
assert.equal(verification.candidateVersion, '47'); assert.equal(verification.checks.length, 4);
const samples = (await json(resolve(seal, 'case-index.json'))).cases, cases = [], jobs = [];
for (const sample of samples) {
  const captureGroup = 'captures';
  const input = await json(resolve(here, captureGroup, sample.id, 'input.json'));
  const verdict = await json(resolve(here, captureGroup, sample.id, 'verdict.json'));
  const raw = await readFile(resolve(seal, sample.prompt), 'utf8');
  assert.equal(input.rawPrompt, raw.trim()); assert.equal(verdict.modelCalls, 0);
  assert.equal(input.context.files.length, sample.attachments.length);
  const attachments = await Promise.all(sample.attachments.map(async path => ({ path, content: await readFile(resolve(seal, path), 'utf8'),
    sha256: createHash('sha256').update(await readFile(resolve(seal, path))).digest('hex') })));
  assert.deepEqual(input.context.files.map(file => createHash('sha256').update(file.content).digest('hex')).sort(),
    attachments.map(file => file.sha256).sort(), '全部指定材料须逐字完整进入增强');
  cases.push({ id: sample.id, rawPrompt: input.rawPrompt, originalSealedPrompt: raw,
    sealedCaseId: sample.sealedCaseId, sourceCollectionId: sample.sourceCollectionId, kind: '简略委托补全', collection: 'candidate47冻结并正式解封后的内部留出',
    originalAttachments: attachments, capture: `completion-holdout-run/${captureGroup}/${sample.id}/input.json`,
    attachmentVisibility: 'all sealed attachments available to enhancer and executor; none execution-only',
    inputSha256: createHash('sha256').update(JSON.stringify(input)).digest('hex'),
    workContract: { maximumOutputTokens: 2600, officialExecutionRepeatsPerArm: 1,
      externalTools: false, internet: false, answerCardsExcludedFromInputs: true,
      outputRestrictions: '原封存prompt中的格式/字数/文件要求，模型与三臂共同执行材料一致' } });
  for (const version of ['31', '47']) jobs.push({ id: `${sample.id}_V${version}_GEN`, phase: 'GENERATION', caseId: sample.id,
    version, approvedEnhancementInput: input });
}
await writeFile(resolve(here, 'generation-bundle.json'), JSON.stringify({ syntheticOnly: true, cases, jobs,
  phaseLimits: { GENERATION: 24, PLAN: 0, EXECUTION: 0, REVIEW: 0 }, executionMaxTokens: 2600,
  prerequisiteStages: ['effective47'] }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output: resolve(here, 'generation-bundle.json'), cases: cases.length, jobs: jobs.length,
  actualNewModelCalls: 0, generationStageMaximum: 24, sealedFilesModified: false }));
