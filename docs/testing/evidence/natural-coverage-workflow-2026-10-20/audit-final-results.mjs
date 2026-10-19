import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

// 闭批后的离线推导，只读取原件；不登录、不调用模型、不改变冻结输入或首次汇总。
const evidence = resolve('docs/testing/evidence/natural-coverage-workflow-2026-10-20');
const read = async name => JSON.parse((await readFile(resolve(evidence, name), 'utf8')).replace(/^\uFEFF/, ''));
const save = async (name, value) => {
  const text = JSON.stringify(value, null, 2) + '\n';
  try { await writeFile(resolve(evidence, name), text, { flag: 'wx' }); }
  catch (error) {
    if (error.code !== 'EEXIST') throw error;
    assert.equal(await readFile(resolve(evidence, name), 'utf8'), text, '已有推导不同，不能覆盖');
  }
};
const protocol = await read('protocol-candidate2.json');
const closure = await read('budget-closure.json');
assert.equal(closure.status, 'CLOSED');
assert.equal(closure.actualNewCalls, 43);
const traces = (await readFile(resolve(evidence, 'transports.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
const results = await read('http-results.json');
assert.equal(traces.length, closure.actualNewCalls);
const currentId = id => protocol.currentJobs[id] ?? id;
const byId = id => results.find(row => row.id === currentId(id));

const costsOf = selected => selected.reduce((sum, trace) => {
  const usage = trace.response?.usage;
  sum.calls++;
  if (!usage) sum.usageMissing++;
  sum.inputTokens += usage?.prompt_tokens ?? usage?.input_tokens ?? 0;
  sum.outputTokens += usage?.completion_tokens ?? usage?.output_tokens ?? 0;
  sum.cachedInputTokens += usage?.prompt_tokens_details?.cached_tokens ?? usage?.prompt_cache_hit_tokens ?? 0;
  sum.upstreamElapsedMsSum += trace.elapsedMs ?? 0;
  sum.totalTokens = sum.inputTokens + sum.outputTokens;
  return sum;
}, { calls: 0, usageMissing: 0, inputTokens: 0, outputTokens: 0, cachedInputTokens: 0, totalTokens: 0, upstreamElapsedMsSum: 0 });
const selected = ids => {
  for (const id of ids) assert.equal(byId(id)?.status, 200, '当前对照必须有实际成功原件');
  const actualIds = ids.map(currentId);
  return traces.filter(trace => actualIds.includes(trace.jobId));
};
const pairedGeneration = protocol.generation.map(caseId => ({
  caseId,
  V3: costsOf(selected([caseId + '_V3_GEN'])),
  V4: costsOf(selected([caseId + '_V4_GEN'])),
  candidateJobId: currentId(caseId + '_V4_GEN'),
}));
const directTotals = Object.fromEntries(['V3', 'V4'].map(arm => [arm,
  costsOf(selected(protocol.generation.map(id => id + '_' + arm + '_GEN')))]));
const executionTotals = Object.fromEntries(['RAW', 'V3', 'V4'].map(arm => [arm,
  costsOf(selected(protocol.execution.map(id => id + '_' + arm + '_EXEC')))]));
const endToEnd = { RAW: executionTotals.RAW };
for (const arm of ['V3', 'V4']) endToEnd[arm] = costsOf(selected(protocol.execution.flatMap(id => [id + '_' + arm + '_GEN', id + '_' + arm + '_EXEC'])));
await save('paired-cost-audit.json', {
  fullBatchIncludingFailedAttemptsAndReviews: costsOf(traces),
  pairedGeneration, directTotals, executionTotals,
  fourArtifactGenerationPlusExecution: endToEnd,
  note: '完整批次包含首次失败和定向修正；配对成本仅列当前成功原件，不代表从完整批次扣除旧失败。第三版与第四版六组原始输入相同；第四版报告和交接说明使用C2，其余沿用C1，生成指导未改变。耗时是上游请求耗时之和，非页面端到端耗时；不换算货币，也不外推平均收益。',
});

const mapping = await read('review-mapping.json');
const reviewAudits = [];
for (const jobId of protocol.reviewJobs) {
  const result = byId(jobId);
  assert.equal(result.status, 200);
  let text = result.body.data.content.trim();
  if (text.startsWith('```json') && text.endsWith('```')) text = text.slice(7, -3).trim();
  const body = JSON.parse(text);
  assert.deepEqual(body.cases.map(row => row.caseId).sort(), [...protocol.execution].sort());
  const invalidQuotes = [];
  for (const sample of body.cases) {
    assert.deepEqual(sample.artifacts.map(row => row.label).sort(), ['A', 'B', 'C']);
    assert.equal(new Set(sample.preferredLabels).size, sample.preferredLabels.length);
    assert(sample.preferredLabels.every(label => ['A', 'B', 'C'].includes(label)));
    // 用户视角允许全不合格；首次汇总强制至少一个赢家，超出了冻结评审要求。
    if (!sample.preferredLabels.length) {
      assert(sample.artifacts.every(artifact => artifact.directlyUsable === false));
      assert.equal(typeof sample.reason, 'string');
      assert(sample.reason.trim());
    }
    for (const artifact of sample.artifacts) {
      assert.equal(typeof artifact.directlyUsable, 'boolean');
      const arm = mapping.find(row => row.reviewer === jobId && row.caseId === sample.caseId && row.label === artifact.label)?.arm;
      assert(arm);
      const actual = byId(sample.caseId + '_' + arm + '_EXEC').body.data.content;
      for (const key of ['hardRequirementFailures', 'usefulContent', 'unnecessaryWork']) {
        assert(Array.isArray(artifact[key]));
        for (const finding of artifact[key]) {
          assert.equal(typeof finding.claim, 'string');
          assert.equal(typeof finding.quote, 'string');
          if (finding.quote && !actual.includes(finding.quote)) invalidQuotes.push({ caseId: sample.caseId, arm, key, ...finding });
        }
      }
      artifact.arm = arm;
      artifact.exactQuotesValid = !invalidQuotes.some(row => row.caseId === sample.caseId && row.arm === arm);
    }
    sample.preferredArms = sample.preferredLabels.map(label => mapping.find(row => row.reviewer === jobId && row.caseId === sample.caseId && row.label === label).arm);
  }
  reviewAudits.push({ jobId, structureValidAfterToolCorrection: true, invalidQuotes, caseAssessments: body.cases,
    usableAsUnqualifiedAcceptance: false,
    limitation: '逐字引文核对通过，但两次均为同供应商AI辅助评审，仍漏看RAW报告的基线分子混淆，不能代替实际作品核对或专业人员验收。' });
}
await save('user-view-review-audit.json', {
  supersedesInterpretationOf: 'user-view-reviews.json',
  cause: '首次汇总将报告preferredLabels=[]误判为非法结构；冻结评审允许并列，未要求必须推荐作品。离线更正工具解释，不修改模型响应、产品策略或预算。',
  actualModelCallsAdded: 0, reviews: reviewAudits,
});

const artifactLengths = protocol.execution.flatMap(caseId => ['RAW', 'V3', 'V4'].map(arm => {
  const content = byId(caseId + '_' + arm + '_EXEC').body.data.content;
  return { caseId, arm, fullNonWhitespace: content.replace(/\s/g, '').length,
    hanCharacters: [...content].filter(character => /\p{Script=Han}/u.test(character)).length,
    nonWhitespaceWithoutMarkdownAsterisks: content.replace(/[\s*]/g, '').length };
}));
await save('artifact-length-audit.json', {
  counts: artifactLengths,
  note: '冻结报告检查使用完整非空白字符，上述辅助计数不改变冻结检查，也不等于所有中文文字处理工具的字数口径。RAW报告汉字374而完整非空白450；V3为427/504，V4为444/523。不得称三组都超过400汉字。',
});
console.log(JSON.stringify({ fullBatch: costsOf(traces), directTotals, executionTotals,
  reviewQuoteErrors: reviewAudits.map(row => ({ jobId: row.jobId, errors: row.invalidQuotes.length })),
  callsAdded: 0 }));
