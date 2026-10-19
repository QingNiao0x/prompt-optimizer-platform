import { readFile, writeFile, readdir, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const root = resolve('tmp/natural-task-eval-20261019');
const target = resolve('docs/testing/evidence/natural-task-generalization-2026-10-19');
const read = async path => JSON.parse(await readFile(path, 'utf8'));
const save = async (name, value) => writeFile(resolve(target, name), JSON.stringify(value, null, 2) + '\n');
const cases = (await read(resolve(target, 'cases.json'))).cases;
const protocol = await read(resolve(target, 'protocol.json'));
const closure = await read(resolve(target, 'budget-closure.json'));
const traces = [];
for (const name of (await readdir(resolve(root, 'traces'))).filter(name => name.endsWith('.json')).sort()) {
  traces.push(await read(resolve(root, 'traces', name)));
}
const assert = (value, message) => { if (!value) throw Error(message); };
assert(closure.status === 'CLOSED' && traces.length === 58 && traces.length <= protocol.maximumNewActualCalls, 'Actual budget mismatch');
assert(new Set(traces.map(row => row.jobId)).size === traces.length, 'Unexpected protocol retry or duplicated actual job');
const phaseCounts = {};
for (const row of traces) {
  phaseCounts[row.phase] = (phaseCounts[row.phase] ?? 0) + 1;
  assert(row.httpStatus === 200 && row.response.choices[0].finish_reason === 'stop', 'Actual response not complete');
}
for (const [phase, count] of Object.entries(phaseCounts)) assert(count <= protocol.phaseLimits[phase], 'Stage limit exceeded');

const suites = ['NaturalTaskUnderstandingTest', 'OpenAiCompatibleNaturalPromptProviderTest', 'NaturalEnhancementServiceTest',
  'MybatisOptimizationHistoryServiceTest', 'DocumentOptimizationControllerTest', 'OptimizationPlanningServiceTest',
  'PlanningSessionServiceTest', 'OpenAiCompatiblePromptEnhancementProviderTest', 'NaturalDocumentDatabaseAcceptanceTest',
  'ModelConcurrencyHttpIntegrationTest', 'CodeArtifactTaskIntentTest', 'DynamicPlatformConstraintTest',
  'TaskTemplateAdaptationTest', 'TaskCompletionRegressionTest', 'TaskConstraintAssemblyReplayTest', 'TaskConstraintWholeProjectionTest'];
const reports = resolve('services/api/target/surefire-reports');
const names = await readdir(reports);
const results = [];
for (const name of suites) {
  const file = names.find(value => value.startsWith('TEST-') && value.endsWith('.' + name + '.xml'));
  assert(file, 'Missing affected report ' + name);
  const text = await readFile(resolve(reports, file), 'utf8');
  const opening = text.match(/<testsuite\b[^>]*>/)[0];
  const number = key => Number(opening.match(new RegExp('\\b' + key + '="(\\d+)"'))[1]);
  const row = { name, tests: number('tests'), failures: number('failures'), errors: number('errors'), skipped: number('skipped'),
    updatedAt: (await stat(resolve(reports, file))).mtime.toISOString() };
  assert(row.failures + row.errors + row.skipped === 0, 'Affected checks are not green');
  results.push(row);
}
assert(results.reduce((sum, row) => sum + row.tests, 0) === 350, 'Affected test total drift');

const generated = async id => read(resolve(root, 'results', id + '.json'));
const comparisons = [];
for (const sample of cases) {
  const old = await generated(sample.id + '_V1');
  const current = await generated(sample.id + '_V2');
  const pair = { caseId: sample.id, rawPrompt: sample.rawPrompt, baselinePrompt: old.body.data.optimizedPrompt,
    initialNaturalPrompt: current.body.data.optimizedPrompt };
  if (['AI_GLOBAL', 'JAVA_COMPLETE', 'COPY_BRIEF'].includes(sample.id)) {
    pair.controlledRevisionPrompt = (await generated(sample.id + '_R2_GEN')).body.data.optimizedPrompt;
    pair.controlledRevisionNote = '该真实候选包含的附加范围说明随后撤回；终版仅做原始响应离线回放，不冒称有新的实际作品验收。';
  }
  comparisons.push(pair);
}
await save('prompt-comparisons.json', comparisons);

const mean = values => values.reduce((sum, value) => sum + value, 0) / values.length;
const arm = suffix => {
  const selected = traces.filter(row => cases.some(sample => row.jobId === sample.id + suffix));
  return { samples: selected.length, inputTokens: selected.reduce((sum, row) => sum + row.response.usage.prompt_tokens, 0),
    outputTokens: selected.reduce((sum, row) => sum + row.response.usage.completion_tokens, 0),
    meanUpstreamMs: mean(selected.map(row => row.elapsedMs)) };
};
const cost = { scope: '仅初轮同题8对，不能外推终版或其它模型；输入包含不同system指导与必要来源元数据。',
  baseline: arm('_V1'), initialNatural: arm('_V2'),
  baselineMeanBodyCharacters: mean(comparisons.map(row => row.baselinePrompt.length)),
  initialNaturalMeanBodyCharacters: mean(comparisons.map(row => row.initialNaturalPrompt.length)) };
await save('cost-observations.json', cost);

const modes = [];
for (const id of ['JAVA_COMPLETE', 'PPT_DESIGN']) {
  const natural = (await generated(id + '_V2')).body.data;
  const structured = (await generated(id + '_STRUCTURED')).body.data;
  assert(natural.resultKind === 'TEXT' && structured.sections.length === 5, 'Mode contract mismatch');
  modes.push({ caseId: id, naturalKind: natural.resultKind, naturalPipeline: natural.generationPipeline,
    structuredKind: structured.resultKind, structuredPipeline: structured.generationPipeline,
    structuredTitles: structured.sections.map(row => row.title), naturalPrompt: natural.optimizedPrompt,
    structuredPrompt: structured.optimizedPrompt, scope: '初轮相同原始提示词，两态格式实际响应，不代表专业质量通过。' });
}
await save('mode-comparison.json', modes);

const finalCode = await generated('JAVA_COMPLETE_R2_EXEC');
const finalCopy = await generated('COPY_BRIEF_R2_EXEC');
const finalPlan = await generated('PROCUREMENT_UNKNOWN_R2_PLAN');
const codeLog = await readFile(resolve(root, 'java/R2/run.log'), 'utf8');
assert(codeLog.includes('PASS cases=16'), 'Actual revised Java artifact failed');
const quality = { overall: 'NOT_PASSED', rawAuxiliaryPreferences: { initialNatural: 2, tie: 6, scope: '原始辅助意见，存在误判，不当作业务胜负。' },
  initialJava: await read(resolve(target, 'java-acceptance-initial.json')),
  revisedJava: { compiled: true, behaviorCases: 16, passed: true, source: finalCode.body.data.content },
  revisedCopy: { nonWhitespaceCharacters: finalCopy.body.data.content.replace(/\s/g, '').length,
    hanCharacters: (finalCopy.body.data.content.match(/\p{Script=Han}/gu) ?? []).length,
    hasExtraExplanation: true, passed: false, content: finalCopy.body.data.content },
  revisedPlan: { questions: finalPlan.body.data.questions.length,
    recommendedOptions: finalPlan.body.data.questions.flatMap(row => row.options).filter(row => row.recommended).length,
    questionsPreserved: finalPlan.body.data.questions },
  finalAssemblyReplay: { frozenDrafts: 3, liveModelCalls: 0, note: '仅验证最终组装保留正文、出处及固定边界；不等于终版真实作品通过。' },
  openItems: ['短文案两个候选仍追加说明及超长，按停止条件停止采样。', '查询类内容展开、口径与覆盖稳定性仍待质量验收。', '没有Qoder API对照、专业人员验收或全模型矩阵。'],
  withdrawn: '未证明收益的附加交付范围说明，保留所有原始模型响应与失败作品。' };
await save('quality-observations.json', quality);

const gitHead = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
const tracked = execFileSync('git', ['ls-files', 'services/api/src/main'], { encoding: 'utf8' }).trim().split(/\r?\n/);
const files = [];
for (const path of tracked) files.push({ path, sha256: createHash('sha256').update(await readFile(resolve(path))).digest('hex') });
await save('final-source-manifest.json', { gitHead, afterEvaluationChange: '撤回未证实收益的范围说明，三份原始响应已离线回放；严格核对自然历史版本的持久化类型，历史服务21项测试通过。两项终版修正均新增真实调用0。', files });
await save('validation-summary.json', { date: '2026-10-19', finalProductGitHead: gitHead, actualCalls: closure,
  java: { uniqueAffectedTests: 350, suites: results, note: '多批受影响验证的最后报告去重合并，并非一次命令或全仓库回归；首次失败另存。' },
  frontend: { files: 4, tests: 13, passed: true }, offlineBudgetChecks: 5, qualityStatus: 'NOT_PASSED',
  runtime: { modelId: protocol.modelId, observedUpstreamModels: [...new Set(traces.map(row => row.response.model))],
    localPostgres: true, localRedisVersion: '3.2.100', redis7Acceptance: false, dockerUsed: false,
    newAccounts: 0, newTenants: 0, newWorkspaces: 0, browserFullAcceptance: false, flywayApplied: false } });
console.log(JSON.stringify({ actualCalls: traces.length, phaseCounts, affectedJava: 350, frontend: 13, quality: 'NOT_PASSED', cost }));
