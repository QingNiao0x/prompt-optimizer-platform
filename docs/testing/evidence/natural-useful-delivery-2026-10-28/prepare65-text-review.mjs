import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = value => createHash('sha256').update(value).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const target = resolve(evidence, 'registry65-text-review-preparation');
const key = (await json(resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry65-text-works-coordinator.json'))).mapping;
const cases = (await json(resolve(evidence, 'registry65-text-works/cases.json'))).cases;
const criteria = await json(resolve(evidence, 'registry65-text-works-preparation/criteria.json'));
const system = '你是内部作品审阅助手，只依据原始需求、共同材料和实际作品判断。作品顺序与编号不表示优劣，不推测生成版本。不把更多字数、标题、表格或引用当作收益。合理设计变体允许；必要修改须有原句和依据。先逐份判断，再比较预设配对；允许持平、双方失败及无法确定。不要为了评测凑错或凑胜。只返回JSON：{"works":[{"workId":"编号","outcome":"DIRECTLY_USABLE或NEEDS_EDIT或SERIOUS_FAILURE或UNRESOLVED","findings":[{"severity":"serious或materialEdit或minorPreference","quote":"作品原句","source":"材料/需求依据","reason":"具体问题","necessaryEdit":"交付前必须改什么"}],"usefulContent":"具体可用之处"}],"comparisons":[{"id":"配对编号","judgment":"LEFT_BETTER或TIE或RIGHT_BETTER或BOTH_FAIL或UNRESOLVED","reason":"具体交付区别","leftQuote":"左作品原句","rightQuote":"右作品原句","editDifference":"用户实际少改/多改什么；只是文风须写持平"}]}。不输出推理过程，不使用外部工具，不把未经核实的猜测当错误。';
const packets = [], jobs = [], provenance = [];
for (const sample of cases) {
  const mapping = key.filter(item => item.caseId === sample.id), works = [];
  assert.equal(mapping.length, 6);
  for (const item of mapping) {
    const path = resolve(evidence, item.source.path), bytes = await readFile(path);
    const stage = item.source.path.split('/')[0];
    const closure = await json(resolve(evidence, stage, 'closure.json')); assert.equal(closure.status, 'CLOSED');
    assert.equal(closure.files.find(file => resolve(repo, file.path) === path)?.sha256, sha(bytes));
    if (item.source.sha256) assert.equal(item.source.sha256, sha(bytes));
    const result = JSON.parse(bytes);
    works.push({ workId: item.workId, delivered: result.status === 200 && result.body.data.finishReason === 'stop', content: result.body?.data?.content ?? '' });
    provenance.push({ workId: item.workId, resultSha256: sha(bytes), contentSha256: sha(result.body?.data?.content ?? '') });
  }
  const comparisons = []; let count = 0;
  for (const repeat of [1, 2]) for (const [a, b] of [['ORIGINAL', '31'], ['ORIGINAL', '65'], ['31', '65']]) {
    const left = mapping.find(item => item.arm === a && item.repeat === repeat), right = mapping.find(item => item.arm === b && item.repeat === repeat);
    assert.ok(left && right);
    const swap = parseInt(sha(`${sample.id}:${repeat}:${a}:${b}`).slice(0, 2), 16) % 2;
    comparisons.push({ id: `${sample.id}-J${++count}`, left: swap ? right.workId : left.workId, right: swap ? left.workId : right.workId });
  }
  const packet = { rawPrompt: sample.rawPrompt, materials: sample.executorMaterials, criteria: criteria.commonCriteria, taskCriteria: criteria[sample.id], works, comparisons };
  packets.push({ caseId: sample.id, ...packet });
  jobs.push({ id: `${sample.id}_ANONYMOUS_REVIEW`, caseId: sample.id, phase: 'REVIEW', version: '65', approvedModelRequest: {
    model: 'deepseek-flash', temperature: 0.2, max_tokens: 8192, thinking: { type: 'disabled' }, stream: false, response_format: { type: 'json_object' },
    messages: [{ role: 'system', content: system }, { role: 'user', content: JSON.stringify(packet) }],
  } });
}
await mkdir(target, { recursive: true });
await save(resolve(target, 'anonymous-works.json'), packets);
await save(resolve(target, 'provenance.json'), { actualCalls: 0, provenance, substantiveCriteriaUnchangedFrom63: true, rootAlreadySawBaselines: true, onlyReviewerModelIsVersionBlind: true, realHumanOrExpertReview: false });
await save(resolve(target, 'bundle.json'), { syntheticOnly: true, cases, jobs, phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 0, REVIEW: jobs.length }, parameters: { reviewMaxTokens: 8192 },
  preparationArtifacts: ['registry65-text-works-preparation/criteria.json', 'registry65-text-review-preparation/anonymous-works.json', 'registry65-text-review-preparation/provenance.json'],
  description: 'Version-blind internal AI review; root has old baseline knowledge and separately checks all original source evidence before unblinding. No automated adoption from model ratings.' });
console.log(JSON.stringify({ prepared: true, reviewJobs: jobs.length, anonymousWorks: provenance.length, actualCalls: 0 }));
