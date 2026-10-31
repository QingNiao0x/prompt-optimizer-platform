/** 核对已冻结原件及全部实耗后闭批；不调用模型，不改写既有响应。 */
import {readFile, readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {dir, repo, json, save, checkFrozen, names} from './run.mjs';

const protocol = await checkFrozen();
const traceNames = await names();
const traces = await Promise.all(traceNames.map(name => json(resolve(dir, 'traces', name))));
const byPhase = {};
let promptTokens = 0, completionTokens = 0;
for (const trace of traces) {
  byPhase[trace.phase] = (byPhase[trace.phase] ?? 0) + 1;
  assert.equal(trace.httpStatus, 200);
  promptTokens += trace.response.usage.prompt_tokens;
  completionTokens += trace.response.usage.completion_tokens;
}
assert.deepEqual(byPhase, {enhance: 13, product: 11, execute: 8});
assert.equal(traces.length, 32);
const regression = [];
for (const name of (await readdir(resolve(dir, 'results'))).filter(n => n.endsWith('.json'))) {
  const result = await json(resolve(dir, 'results', name));
  assert.equal(result.success, true);
  assert(result.completedStages.includes('BODY_GENERATION'));
  regression.push({file: name, success: true, completedStages: result.completedStages});
}
assert.equal(regression.length, 6);
const product = [];
for (const id of ['D_NOTICE', 'D_PLAN']) {
  const verdict = await json(resolve(dir, 'product', `${id}-verdict.json`));
  assert.equal(verdict.copyHistoryEqual, true);
  assert.equal(verdict.originalHistoryUnchanged, true);
  assert.equal(verdict.bodyGenerated, true);
  product.push({id, ...verdict});
}
const answers = await json(resolve(dir, 'product/D_PLAN-answer-preservation.json'));
assert.equal(answers.answersFromSavedHistory, true);
assert.equal(answers.questionsAndAnswersExact, true);
const suites = [
  'enhancement.service.impl.NaturalEnhancementSettingsTest',
  'enhancement.service.impl.NaturalEnhancementServiceTest',
  'history.service.impl.MybatisOptimizationHistoryServiceTest',
  'provider.domain.NaturalTaskReviewTest',
  'provider.domain.NaturalTaskScopeTest',
  'provider.infrastructure.openai.NaturalCompletionBasisProviderTest',
  'provider.infrastructure.openai.NaturalPlanContextProviderTest',
  'provider.infrastructure.openai.NaturalSimplifiedProviderTest',
];
const tests = [];
for (const suffix of suites) {
  const name = 'com.promptoptimizer.' + suffix;
  const xml = await readFile(resolve(repo, 'services/api/target/surefire-reports', `TEST-${name}.xml`), 'utf8');
  const tag = xml.match(/<testsuite\s[^>]+>/)[0];
  const attr = key => tag.match(new RegExp(`\\b${key}="([^"]+)"`))?.[1];
  const row = {suite: name, tests: Number(attr('tests')), failures: Number(attr('failures')), errors: Number(attr('errors')), skipped: Number(attr('skipped')), seconds: Number(attr('time'))};
  assert.equal(row.failures + row.errors + row.skipped, 0);
  tests.push(row);
}
assert.equal(tests.reduce((sum, t) => sum + t.tests, 0), 154);
const hashes = [];
for (const folder of ['traces', 'results', 'product', 'works']) {
  for (const name of (await readdir(resolve(dir, folder))).sort()) {
    const path = `${folder}/${name}`;
    hashes.push({path, sha256: createHash('sha256').update(await readFile(resolve(dir, path))).digest('hex')});
  }
}
await save(resolve(dir, 'acceptance.json'), {
  hypothesis: protocol.hypothesis, regression, product, answerPreservation: answers, tests,
  actualWorks: 'work-checks.json', engineeringAdoption: true,
  broadQualityImprovementProven: false, independentHumanReview: false,
  sourceDefault: 'PLAN_CONTEXT_REPAIR -> core-repair-v4',
  runtime: 'Isolated 9047 API verified with explicit v4. Existing 9000 process version not verified; see runtime-observation.json. No production deployment.',
  retryCauses: [
    {failed: 'traces/0006.json', repaired: 'traces/0007.json', cause: 'Unexpected task.kind field; rejected by strict phase DTO reader.'},
    {failed: 'traces/0022.json', repaired: 'traces/0023.json', cause: 'Assistant previous prompt placed in userEvidence; rejected by source-kind check.'},
  ],
});
await save(resolve(dir, 'closure.json'), {
  status: 'CLOSED', closedAt: new Date().toISOString(), totalMaximum: 200, actualCalls: traces.length,
  unusedTotal: 200 - traces.length, stageMaximum: 96, releasedUnusedReservation: 96 - traces.length,
  byPhase, protocolRepairsIncluded: 2, qualityRetries: 0, paidReviews: 0,
  promptTokens, completionTokens, totalTokens: promptTokens + completionTokens,
  previousClosedBatchesReopened: false, allOriginals: hashes,
});
console.log(JSON.stringify({calls: traces.length, byPhase, tests: tests.reduce((sum, t) => sum + t.tests, 0), promptTokens, completionTokens, status: 'CLOSED'}));
