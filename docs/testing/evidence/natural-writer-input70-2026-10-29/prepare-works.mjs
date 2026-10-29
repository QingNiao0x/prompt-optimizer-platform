/** 冻结所有原始/31/69/70作品的相同资料、模型、参数和交付范围，不按增强效果选取首稿。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const oldRoot = resolve(evidence, '../natural-useful-delivery-2026-10-28');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const samples = (await json(resolve(oldRoot, 'writer70-offline/future-cases.json'))).cases;
const template = (await json(resolve(oldRoot, 'registry68-holdout-works/jobs.json')))[0].approvedModelRequest;
assert.equal(template.max_tokens, 8192);
assert.deepEqual(template.thinking, { type: 'disabled' });
const jobs = [], provenance = [], unavailableArms = [];
for (const sample of samples) {
  const prompts = { ORIGINAL: sample.rawPrompt };
  const sources = [];
  for (const version of ['31', '69', '70']) {
    const stageName = version === '70' ? 'new-70-allocated' : `new-${version}`;
    const stageRoot = resolve(evidence, stageName);
    const closure = await json(resolve(stageRoot, 'closure.json'));
    assert.equal(closure.status, 'CLOSED');
    const path = resolve(stageRoot, `results/${sample.id}_V${version}_GEN.json`);
    const bytes = await readFile(path), result = JSON.parse(bytes);
    assert.equal(closure.files.find(file => resolve(repo, file.path) === path)?.sha256, sha(bytes));
    if (result.status !== 200) {
      // 无正文的真实失败保留为交付失败，不把原文透传或离线修稿伪装成该版本的作品输入。
      unavailableArms.push({ caseId: sample.id, version, status: result.status,
        errorCode: result.body?.error?.code, sourceSha256: sha(bytes), downstreamWorks: 0 });
      sources.push({ version, unavailable: true, status: result.status, sha256: sha(bytes) });
      continue;
    }
    if (version !== '31') assert.ok(result.body.data.processing.completedStages.includes('BODY_GENERATION'));
    prompts[version] = result.body.data.optimizedPrompt;
    sources.push({ version, path: `${stageName}/results/${sample.id}_V${version}_GEN.json`, sha256: sha(bytes),
      firstDeliveredBodyUsed: true, qualitySelection: false, processing: result.body.data.processing });
  }
  const shared = '本次共同材料（所有作品臂完全相同）：\n' + sample.materials.map(file => `--- ${file.path} ---\n${file.content}`).join('\n\n');
  for (const version of ['ORIGINAL', '31', '69', '70'].filter(version => prompts[version] !== undefined)) for (const repeat of [1, 2]) {
    const request = structuredClone(template);
    request.messages = [structuredClone(template.messages[0]), { role: 'user', content: prompts[version] }, { role: 'user', content: shared }];
    jobs.push({ id: `${sample.id}_V${version}_EXEC_R${repeat}`, caseId: sample.id, version, repeat,
      phase: 'EXECUTION', promptSha256: sha(prompts[version]), approvedModelRequest: request });
  }
  provenance.push({ caseId: sample.id, sources, commonMaterialSha256: sha(shared), commonSystemSha256: sha(template.messages[0].content),
    onlyPromptDiffersWithinCase: true, reviewerCriteriaInModelInput: false });
}
const seed = 'writer70-first-two-repeats-frozen-before-any-work';
jobs.sort((a, b) => sha(seed + '\0' + a.id).localeCompare(sha(seed + '\0' + b.id)));
const mapping = [...jobs].sort((a, b) => sha(seed + '\0blind\0' + a.id).localeCompare(sha(seed + '\0blind\0' + b.id)))
  .map((job, index) => ({ workId: `W${String(index + 1).padStart(2, '0')}`, caseId: job.caseId, arm: job.version, repeat: job.repeat,
    source: `works/results/${job.id}.json` }));
const privateRoot = resolve(repo, 'tmp/natural-writer-input70-2026-10-29');
await mkdir(privateRoot, { recursive: true });
await save(resolve(privateRoot, 'works-coordinator.json'), { mapping, rootHasSeenEnhancementPrompts: true,
  rootHasSeenTheseWorksBeforeReview: false, rootIsNotExternalExpert: true });
await save(resolve(evidence, 'works-provenance.json'), { provenance, unavailableArms,
  fullFourArmComparisonAvailable: unavailableArms.length === 0, allAvailableFirstTwoRepetitionsIncluded: true, qualityRetries: 0 });
await save(resolve(evidence, 'works-bundle.json'), { syntheticOnly: true, cases: samples, jobs,
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 16, REVIEW: 0 }, parameters: { executionMaxTokens: 8192 } });
console.log(JSON.stringify({ prepared: true, works: jobs.length, modelCalls: 0 }));
