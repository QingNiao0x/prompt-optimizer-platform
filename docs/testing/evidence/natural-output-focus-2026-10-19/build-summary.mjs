import { readFile, readdir, writeFile, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

const evidence = resolve('docs/testing/evidence/natural-output-focus-2026-10-19');
const root = resolve('tmp/natural-output-focus-20261019');
const read = async path => JSON.parse(await readFile(path, 'utf8'));
const save = async (name, value) => writeFile(resolve(evidence, name), JSON.stringify(value, null, 2) + '\n');
const protocol = await read(resolve(evidence, 'protocol.json'));
const closure = await read(resolve(evidence, 'budget-closure.json'));
const manifest = await read(resolve(evidence, 'source-manifest.json'));
const samples = (await read(resolve(evidence, 'cases.json'))).cases;
const traces = [];
for (const name of (await readdir(resolve(root, 'traces'))).filter(name => name.endsWith('.json')).sort()) {
  traces.push(await read(resolve(root, 'traces', name)));
}
assert.equal(closure.status, 'CLOSED');
assert.equal(traces.length, 16);
assert.equal(new Set(traces.map(row => row.jobId)).size, 16);
const phases = {};
for (const row of traces) {
  assert(protocol.allowedJobs.includes(row.jobId));
  assert.equal(row.httpStatus, 200);
  assert.equal(row.response.choices[0].finish_reason, 'stop');
  phases[row.phase] = (phases[row.phase] ?? 0) + 1;
}
assert.deepEqual(phases, { GENERATION: 8, PLAN: 4, EXECUTION: 4 });
for (const file of manifest.files) {
  assert.equal(createHash('sha256').update(await readFile(resolve(file.path))).digest('hex'), file.sha256,
    'Frozen source changed: ' + file.path);
}
await writeFile(resolve(evidence, 'transports.jsonl'), traces.map(row => JSON.stringify(row)).join('\n') + '\n');

const generated = async id => read(resolve(root, 'results', id + '.json'));
const comparablePayload = row => {
  const message = row.request.messages[1].content;
  const payload = JSON.parse(message.slice(message.indexOf('{')));
  delete payload.strategyVersion;
  return payload;
};
const comparisons = [];
for (const id of protocol.generation) {
  const sample = samples.find(row => row.id === id);
  const previous = await generated(id + '_V2_GEN');
  const current = await generated(id + '_V3_GEN');
  assert.deepEqual(comparablePayload(traces.find(row => row.jobId === id + '_V2_GEN')),
    comparablePayload(traces.find(row => row.jobId === id + '_V3_GEN')), 'Unequal task input: ' + id);
  comparisons.push({ caseId: id, rawPrompt: sample.rawPrompt,
    previousPrompt: previous.body.data.optimizedPrompt, currentPrompt: current.body.data.optimizedPrompt,
    previousCharacters: [...previous.body.data.optimizedPrompt].length,
    currentCharacters: [...current.body.data.optimizedPrompt].length,
    previousRuleCount: previous.body.data.appliedConstraints.length,
    currentRuleCount: current.body.data.appliedConstraints.length });
}
await save('prompt-comparisons.json', comparisons);

const plans = [];
for (const id of protocol.plan) {
  for (const version of ['2', '3']) {
    const result = await generated(id + '_V' + version + '_PLAN');
    plans.push({ caseId: id, version, summary: result.body.data.summary, questions: result.body.data.questions,
      questionCount: result.body.data.questions.length,
      recommendedOptions: result.body.data.questions.flatMap(question => question.options).filter(option => option.recommended).length });
  }
  assert.deepEqual(comparablePayload(traces.find(row => row.jobId === id + '_V2_PLAN')),
    comparablePayload(traces.find(row => row.jobId === id + '_V3_PLAN')), 'Unequal Plan input: ' + id);
}
await save('plan-results.json', plans);

const messages = [];
for (const version of ['2', '3']) {
  const result = await generated('MESSAGE_V' + version + '_EXEC');
  const content = result.body.data.content;
  messages.push({ version, content, nonWhitespaceCharacters: [...content.replace(/\s/gu, '')].length,
    limit: 50, withinLimit: [...content.replace(/\s/gu, '')].length <= 50,
    scope: '完整回复计数；是否保留行动、期限及无额外说明另外逐字审阅，不靠长度证明全部正确。' });
}
await save('message-results.json', messages);
const costs = {};
for (const version of ['2', '3']) {
  const selected = traces.filter(row => protocol.generation.some(id => row.jobId === id + '_V' + version + '_GEN'));
  costs[version] = { samples: selected.length,
    inputTokens: selected.reduce((sum, row) => sum + row.response.usage.prompt_tokens, 0),
    outputTokens: selected.reduce((sum, row) => sum + row.response.usage.completion_tokens, 0),
    meanUpstreamMs: selected.reduce((sum, row) => sum + row.elapsedMs, 0) / selected.length };
}
await save('cost-observations.json', { scope: '仅本轮4对增强，不能推断全部任务、模型或同质量成本。', versions: costs });

const suites = ['TaskDeliveryFocusStrategyTest', 'OpenAiCompatibleNaturalPromptProviderTest',
  'NaturalEnhancementServiceTest', 'MybatisOptimizationHistoryServiceTest', 'ResearchEvaluationGuidanceTest',
  'TaskTemplateAdaptationTest', 'TaskCompletionRegressionTest', 'TaskMeaningRegressionTest',
  'OpenAiCompatiblePromptEnhancementProviderTest', 'DocumentOptimizationControllerTest', 'PromptFrameworkSelectorTest'];
const reports = resolve('services/api/target/surefire-reports');
const results = [];
for (const name of suites) {
  const file = (await readdir(reports)).find(row => row.startsWith('TEST-') && row.endsWith('.' + name + '.xml'));
  const source = await readFile(resolve(reports, file), 'utf8');
  const opening = source.match(/<testsuite\b[^>]*>/)[0];
  const number = key => Number(opening.match(new RegExp('\\b' + key + '="(\\d+)"'))[1]);
  const result = { name, tests: number('tests'), failures: number('failures'), errors: number('errors'),
    skipped: number('skipped'), reportUpdatedAt: (await stat(resolve(reports, file))).mtime.toISOString() };
  assert.equal(result.failures + result.errors + result.skipped, 0);
  results.push(result);
}
assert.equal(results.reduce((sum, row) => sum + row.tests, 0), 223);
await save('validation-summary.json', { productGitHead: manifest.gitHead, actualCalls: closure,
  java: { tests: 223, suites: results, scope: '本轮受影响报告去重合并；不是全仓库回归。' },
  comparativeResult: 'MIXED_RESULTS', frontendSourceChanged: false, browserAcceptance: false,
  newAccounts: 0, newTenants: 0, newWorkspaces: 0, flywayApplied: false,
  localRedisVersion: '3.2.100', redis7Acceptance: false, dockerUsed: false,
  scope: '单一公开模型别名、4对增强及2对Plan与2对作品；没有AI裁判或专业人员验收。' });
console.log(JSON.stringify({ actualCalls: traces.length, phases, java: 223, costs,
  promptCharacters: comparisons.map(row => [row.caseId, row.previousCharacters, row.currentCharacters]),
  messageCharacters: messages.map(row => [row.version, row.nonWhitespaceCharacters]),
  planCounts: plans.map(row => [row.caseId, row.version, row.questionCount]) }));
