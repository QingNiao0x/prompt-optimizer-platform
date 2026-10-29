import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const target = resolve(evidence, 'registry68-holdout-works-preparation');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const closure = await json(resolve(evidence, 'registry68-holdout-gen/closure.json'));
assert.equal(closure.status, 'CLOSED');
const samples = (await json(resolve(evidence, 'sealed-registry52-holdout/cases.json'))).cases;
const frozenJobs = await json(resolve(evidence, 'registry68-holdout-gen/jobs.json'));
const template = (await json(resolve(evidence, 'registry58-works/jobs.json')))[0].approvedModelRequest;
assert.equal(template.max_tokens, 8192);
const jobs = [], cases = [], provenance = [];
for (const sample of samples) {
  const prompts = { ORIGINAL: sample.rawPrompt }, sources = [];
  for (const version of ['31', '68']) {
    const job = frozenJobs.find(job => job.caseId === sample.id && job.version === version);
    let path = resolve(evidence, `registry68-holdout-gen/results/${job.id}.json`), bytes = await readFile(path);
    assert.equal(closure.files.find(file => resolve(repo, file.path) === path)?.sha256, sha(bytes));
    const result = JSON.parse(bytes);
    if (sample.id === 'R52-HO-B' && version === '68' && result.status === 502) {
      // 按时间取最早的真实68固定正文，69仅改诊断标签；绝不把原502伪造成HTTP成功。
      const replayBytes = await readFile(resolve(evidence, 'registry69-diagnostic-replay.json'));
      const replay = JSON.parse(replayBytes);
      assert.equal(replay.kind, 'OFFLINE_CANONICAL_REPLAY_OF_ACTUAL_RESPONSE');
      assert.equal(replay.actualModelCalls, 0); assert.equal(replay.originalApiStatus, 502);
      assert.equal(replay.fullProductionValidationPassed, true); assert.equal(replay.bodyUnchanged, true);
      const actualBodyTrace = await json(resolve(evidence, 'registry68-holdout-gen/traces/011.json'));
      assert.equal(replay.validatedDraft.optimizedPrompt, JSON.parse(actualBodyTrace.response.choices[0].message.content).optimizedPrompt);
      prompts[version] = replay.validatedDraft.optimizedPrompt;
      sources.push({ version, writerVersion: '68', protocolFixVersion: '69', sourceKind: replay.kind,
        path: 'registry69-diagnostic-replay.json', sha256: sha(replayBytes), originalFailureRetained: true,
        actual69HttpDeliveryNotYetVerified: true, selectionBasedOnQuality: false });
      continue;
    }
    assert.equal(result.status, 200);
    assert.ok(result.body.data.processing.completedStages.includes('BODY_GENERATION'));
    prompts[version] = result.body.data.optimizedPrompt;
    sources.push({ version, path: `registry68-holdout-gen/results/${job.id}.json`, sha256: sha(bytes), selectionBasedOnQuality: false });
  }
  const materials = sample.materials.map(material => ({ ...material }));
  const shared = '本次共同材料（所有作品臂完全相同）：\n' + materials.map(material => `--- ${material.path} ---\n${material.content}`).join('\n\n');
  cases.push({ ...sample, executorMaterials: materials, eligibleArms: ['ORIGINAL', '31', '68'] });
  for (const version of ['ORIGINAL', '31', '68']) for (const repeat of [1, 2]) {
    const request = structuredClone(template);
    request.messages = [structuredClone(template.messages[0]), { role: 'user', content: prompts[version] }, { role: 'user', content: shared }];
    jobs.push({ id: `${sample.id}_V${version}_EXEC_R${repeat}`, caseId: sample.id, version, phase: 'EXECUTION', repeat,
      promptSha256: sha(prompts[version]), approvedModelRequest: request });
  }
  provenance.push({ caseId: sample.id, generation: sources, completeSharedMaterialMessageSha256: sha(shared),
    commonSystemSha256: sha(template.messages[0].content), reviewNotesInAnyModelRequest: false });
}
const seed = '68-first-opened52-works-before-any-work';
jobs.sort((a, b) => sha(seed + '\0' + a.id).localeCompare(sha(seed + '\0' + b.id)));
const mapping = [...jobs].sort((a, b) => sha(seed + '\0blind\0' + a.id).localeCompare(sha(seed + '\0blind\0' + b.id)))
  .map((job, i) => ({ workId: `W${String(i + 1).padStart(2, '0')}`, caseId: job.caseId, arm: job.version, repeat: job.repeat,
    source: `registry68-holdout-works/results/${job.id}.json` }));
await mkdir(target, { recursive: true });
await save(resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry68-holdout-works-coordinator.json'),
  { rootHasSeenTheseWorksBeforeReview: false, rootHasSeenEnhancementPrompts: true, reviewerVersionBlind: true, mapping });
await save(resolve(target, 'provenance.json'), { provenance, newWorks: jobs.length, allTwoRepeatsIncluded: true,
  onlyPromptDiffersWithinEachCase: true, qualityRetries: 0, noTopicTuningAfterOpening: true });
await save(resolve(target, 'bundle.json'), { syntheticOnly: true, cases, jobs,
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: jobs.length, REVIEW: 0 }, parameters: { executionMaxTokens: 8192 },
  preparationArtifacts: ['registry68-holdout-opening.json', 'registry68-holdout-works-preparation/provenance.json',
    'sealed-registry52-holdout/review-notes.json'],
  description: 'All first two repetitions of ORIGINAL/31/68 on first-opened52 holdout; same original materials/system/model/parameters, no answer cards. All failures retained.' });
console.log(JSON.stringify({ prepared: true, newWorks: jobs.length, actualCalls: 0, defaultVersion: '31' }));
