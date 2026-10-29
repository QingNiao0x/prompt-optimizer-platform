import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = value => createHash('sha256').update(value).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const target = resolve(evidence, 'registry66-text-review-preparation');
const mapping = (await json(resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry66-text-works-coordinator.json'))).mapping;
const cases = (await json(resolve(evidence, 'registry66-text-works/cases.json'))).cases;
const criteria = await json(resolve(evidence, 'registry66-text-works-preparation/criteria.json'));
const oldReview = await json(resolve(evidence, 'registry65-text-review/jobs.json'));
const system = oldReview[0].approvedModelRequest.messages[0].content;
const jobs = [], packets = [], provenance = [];
for (const sample of cases) {
  const entries = mapping.filter(item => item.caseId === sample.id), works = [];
  assert.equal(entries.length, 8);
  for (const item of entries) {
    const path = resolve(evidence, item.source.path), bytes = await readFile(path);
    const closure = await json(resolve(evidence, item.source.path.split('/')[0], 'closure.json'));
    assert.equal(closure.status, 'CLOSED'); assert.equal(closure.files.find(file => resolve(repo, file.path) === path)?.sha256, sha(bytes));
    if (item.source.sha256) assert.equal(item.source.sha256, sha(bytes));
    const result = JSON.parse(bytes), content = result.body?.data?.content ?? '';
    works.push({ workId: item.workId, delivered: result.status === 200 && result.body.data.finishReason === 'stop', content });
    provenance.push({ workId: item.workId, resultSha256: sha(bytes), contentSha256: sha(content) });
  }
  const comparisons = [], arms = ['ORIGINAL', '31', '65', '66']; let count = 0;
  // Full pair matrix keeps anonymous works at equal comparison degree; no candidate is identified by extra pairings.
  for (const repeat of [1, 2]) for (let i = 0; i < arms.length; i++) for (let j = i + 1; j < arms.length; j++) {
    const left = entries.find(item => item.arm === arms[i] && item.repeat === repeat), right = entries.find(item => item.arm === arms[j] && item.repeat === repeat);
    assert.ok(left && right); const swap = parseInt(sha(`${sample.id}:${repeat}:${arms[i]}:${arms[j]}`).slice(0, 2), 16) % 2;
    comparisons.push({ id: `${sample.id}-K${++count}`, left: swap ? right.workId : left.workId, right: swap ? left.workId : right.workId });
  }
  const packet = { rawPrompt: sample.rawPrompt, materials: sample.executorMaterials, criteria: criteria.commonCriteria, taskCriteria: criteria[sample.id], works, comparisons };
  packets.push({ caseId: sample.id, ...packet });
  jobs.push({ id: `${sample.id}_ANONYMOUS_REVIEW`, caseId: sample.id, version: '66', phase: 'REVIEW', approvedModelRequest: {
    model: 'deepseek-flash', temperature: 0.2, max_tokens: 16384, thinking: { type: 'disabled' }, stream: false, response_format: { type: 'json_object' },
    messages: [{ role: 'system', content: system }, { role: 'user', content: JSON.stringify(packet) }],
  } });
}
await mkdir(target, { recursive: true });
await save(resolve(target, 'anonymous-works.json'), packets);
await save(resolve(target, 'provenance.json'), { provenance, actualCalls: 0, rootHasSeenPrior12Works: true, onlyReviewerIsVersionBlind: true,
  allFourArmsHaveTwoObservedWorks: true, fullBalancedPairMatrix: true, reviewMaxTokens: 16384, reviewBudgetFrozenBeforeFirstReview: true, actualHumanOrExpertReview: false });
await save(resolve(target, 'bundle.json'), { syntheticOnly: true, cases, jobs, phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 0, REVIEW: 2 }, parameters: { reviewMaxTokens: 16384 },
  preparationArtifacts: ['registry66-text-works-preparation/criteria.json', 'registry66-text-review-preparation/anonymous-works.json', 'registry66-text-review-preparation/provenance.json'],
  description: 'Version-blind internal AI comparison of RAW/31/65/66, all repeats retained. Same substantive criteria; larger reviewer output allowance declared before review to fit full balanced matrix. Root source audit still required.' });
console.log(JSON.stringify({ prepared: true, reviewerJobs: 2, anonymousWorks: provenance.length, reviewerMaxTokens: 16384, actualCalls: 0 }));
