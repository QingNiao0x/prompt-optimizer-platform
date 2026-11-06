/** 仅对确定性检查不能回答的六项开放任务做匿名辅助评阅，结果不冒称人工评审。 */
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { directory, readJson, save, exists } from './evaluation.mjs';

const protocol = await readJson(resolve(directory, 'protocol.json'));
const plan = await readJson(resolve(directory, 'works-protocol.json'));
const samples = [...(await readJson(resolve(directory, 'cases.json'))).cases,
  ...(await readJson(resolve(directory, 'holdout.json'))).cases];
await mkdir(resolve(directory, 'reviews'), { recursive: true });
const assignments = [];
const items = [];
for (const sample of samples.filter(item => item.work && !['W_QUERY', 'W_CODE', 'R_SUFFICIENT'].includes(item.id))) {
  const arms = sample.id.length % 2 ? ['candidate', 'raw', 'current'] : ['current', 'candidate', 'raw'];
  const works = [];
  for (const job of plan.jobs.filter(job => job.caseId === sample.id)) {
    const value = await readJson(resolve(directory, 'works', `${job.id}.json`));
    const content = value.response.body?.choices?.[0]?.message?.content;
    if (value.response.status !== 200 || !content) continue;
    const label = `${String.fromCharCode(65 + arms.indexOf(job.arm))}${job.round}`;
    works.push({ label, content });
    assignments.push({ caseId: sample.id, label, job: job.id, arm: job.arm });
  }
  items.push({ sample, works });
}
await save(resolve(directory, 'blind-map.json'), { assignments, hiddenFromReviewers: true });
await save(resolve(directory, 'review-protocol.json'), { frozenAt: new Date().toISOString(),
  sampleIds: items.map(item => item.sample.id), plannedCalls: items.length * 2,
  deterministicOnly: ['W_CODE', 'R_SUFFICIENT'], skipped: ['W_QUERY'],
  reason: 'Code delivery failures and the exact notice constraints are checked deterministically and internally. No paid review of the query with two undelivered enhancement arms.',
  assessment: 'Two separate deepseek-flash review calls per open task. Same model family, internal AI assessment, not independent humans.' });
for (const { sample, works } of items) {
  const context = { files: [...sample.files, ...(sample.executionFiles ?? [])],
    ...(sample.plan ? { actualUserAnswer: sample.answer } : {}) };
  const audit = sample.id === 'H1' ? await readJson(resolve(directory, 'holdout-audit.json')) : undefined;
  const arithmetic = sample.id === 'W_DATA' ? await readJson(resolve(directory, 'data-truth.json')) : undefined;
  for (const pass of [1, 2]) {
    const id = `${sample.id}_review_${pass}`;
    const output = resolve(directory, 'reviews', `${id}.json`);
    assert.equal(await exists(output), false, '已经评阅的样本不能重试挑选分数');
    const payload = { model: protocol.model, temperature: protocol.temperature, max_tokens: protocol.max_tokens,
      thinking: { type: 'disabled' }, stream: false, response_format: { type: 'json_object' }, messages: [
        { role: 'system', content: '评阅匿名实际作品。按用户原始目标、共同材料和真实答案判断可直接使用程度、具体错误和必要修改。合理方案允许不同。长度、标题和更专业的措辞本身不算收益。不要追加用户未要求的交付或把主观偏好说成硬性错误。只返回JSON，所有问题须引用实际原句。' },
        { role: 'user', content: JSON.stringify({ rawPrompt: sample.rawPrompt, context, rubric: sample.rubric,
          audit, arithmetic, works: pass === 1 ? works : [...works].reverse(),
          responseContract: { assessments: [{ label: '匿名编号', goalMet: true,
            severeIssues: [{ quote: '具体原句', reason: '违反哪项原始要求', requiredEdit: '必须怎样改' }],
            necessaryEdits: [{ quote: '必须修改的原句', reason: '实际问题' }],
            usefulFeatures: ['对目标有帮助的具体内容'], usableWithoutSubstantiveEdit: true }],
            preferenceGroups: [['持平的匿名编号'], ['次选编号']], uncertainties: ['证据不足或主观取舍'] } }) },
      ] };
    let response = await fetch('http://127.0.0.1:9078/job', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ phase: 'review', job: id }) });
    assert.equal(response.status, 200);
    response = await fetch('http://127.0.0.1:9078/invoke', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload) });
    assert.equal(response.status, 200);
    const value = await response.json();
    await save(output, { caseId: sample.id, pass, payload, response: value });
    console.log(JSON.stringify({ event: 'REVIEW', id, status: value.status }));
  }
}
