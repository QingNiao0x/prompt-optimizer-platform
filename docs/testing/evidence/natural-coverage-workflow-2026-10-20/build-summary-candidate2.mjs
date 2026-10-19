import { readFile, writeFile, readdir, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

const evidence = resolve('docs/testing/evidence/natural-coverage-workflow-2026-10-20');
const root = resolve('tmp/natural-coverage-workflow-20261020');
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const save = async (name, value) => writeFile(resolve(evidence, name), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const protocol = await json(resolve(evidence, 'protocol-candidate2.json'));
const manifest = await json(resolve(evidence, 'source-manifest-candidate2.json'));
for (const file of manifest.files) {
  assert.equal(createHash('sha256').update(await readFile(resolve(file.path))).digest('hex'), file.sha256, 'Frozen source drift');
}
const initialManifest = await json(resolve(evidence, 'source-manifest.json'));
const initialSourceChanges = [];
for (const file of initialManifest.files) {
  const hash = createHash('sha256').update(await readFile(resolve(file.path))).digest('hex');
  if (hash !== file.sha256) initialSourceChanges.push(file.path);
}
assert.deepEqual(initialSourceChanges.sort(), [
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalTaskReview.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
].sort());
const cases = (await json(resolve(evidence, 'cases.json'))).cases;
const names = (await readdir(resolve(root, 'traces'))).filter(name => name.endsWith('.json')).sort();
const traces = await Promise.all(names.map(name => json(resolve(root, 'traces', name))));
const phases = {}, perJob = {};
for (const trace of traces) {
  assert(protocol.allowedJobs.includes(trace.jobId));
  phases[trace.phase] = (phases[trace.phase] ?? 0) + 1;
  perJob[trace.jobId] = (perJob[trace.jobId] ?? 0) + 1;
}
assert(traces.length <= protocol.maximumNewActualCalls);
for (const [phase, count] of Object.entries(phases)) assert(count <= protocol.phaseLimits[phase]);
const resultNames = (await readdir(resolve(root, 'results'))).filter(name => name.endsWith('.json'));
const results = await Promise.all(resultNames.map(name => json(resolve(root, 'results', name))));
const missingJobs = protocol.allowedJobs.filter(id => !results.some(result => result.id === (protocol.supersededUnexecutedJobs?.[id] ?? id)));
if (missingJobs.length && !process.argv.includes('--stop')) throw Error('Missing frozen jobs; use explicit stop only after documenting the cause');
const closure = { status: 'CLOSED', actualNewCalls: traces.length, maximumNewCalls: protocol.maximumNewActualCalls,
  unusedCalls: protocol.maximumNewActualCalls - traces.length, previousClosedActualCalls: protocol.previousClosedActualCalls,
  cumulativeActualCalls: protocol.previousClosedActualCalls + traces.length, phaseCounts: phases,
  protocolRetries: Object.entries(perJob).filter(([id]) => id.endsWith('_GEN') || id.endsWith('_PLAN'))
    .reduce((sum, [, count]) => sum + Math.max(0, count - 1), 0), qualityRetries: 0, missingJobs,
  closedAt: new Date().toISOString(), reason: missingJobs.length ? '停止并保留失败/未完成作业，余额关闭不转移。' : '全部冻结作业已记录，余额关闭不转移。' };
await writeFile(resolve(root, 'closure.json'), JSON.stringify(closure, null, 2) + '\n', { flag: 'wx' });
await save('budget-closure.json', closure);
await writeFile(resolve(evidence, 'transports.jsonl'), traces.map(trace => JSON.stringify(trace)).join('\n') + '\n', { flag: 'wx' });
await save('http-results.json', results);

const byId = id => results.find(result => result.id === (protocol.currentJobs?.[id] ?? id));
const comparison = [];
for (const id of protocol.generation) {
  const sample = cases.find(row => row.id === id);
  const old = byId(id + '_V3_GEN'), candidate = byId(id + '_V4_GEN');
  const prompts = [old, candidate].map(result => result?.body.data?.optimizedPrompt ?? null);
  const inputOf = job => {
    const trace = traces.find(row => row.jobId === (protocol.currentJobs?.[job] ?? job));
    if (!trace) return null;
    const text = trace.request.messages[1].content;
    const input = JSON.parse(text.slice(text.indexOf('{')));
    return { rawPrompt: input.rawPrompt, context: { customDescription: input.context.customDescription,
      snippets: input.context.fileSnippets.map(file => ({ path: file.path, content: file.content, language: file.language })) },
      actualAnswers: input.actualAnswers, conversation: input.conversation, includeExamples: input.includeExamples,
      mandatoryRules: input.mandatoryRules, modelId: input.modelId };
  };
  const a = inputOf(id + '_V3_GEN'), b = inputOf(id + '_V4_GEN');
  const sameInformation = a && b && JSON.stringify(a) === JSON.stringify(b);
  comparison.push({ caseId: id, rawPrompt: sample.rawPrompt, status: [old?.status, candidate?.status], sameInformation,
    originalCandidateStatus: results.find(result => result.id === id + '_V4_GEN')?.status, candidateJobId: candidate?.id, baselinePrompt: prompts[0], candidatePrompt: prompts[1], promptCharacters: prompts.map(text => text?.length ?? null) });
}
await save('prompt-comparisons.json', comparison);
const plans = results.filter(result => result.phase === 'PLAN' || result.id.endsWith('_PLAN')).map(result => ({
  caseId: result.caseId, version: result.version, status: result.status,
  questionCount: result.body.data?.questions?.length ?? null, summary: result.body.data?.summary,
  questions: result.body.data?.questions ?? [],
}));
await save('plan-results.json', plans);

const costs = {};
for (const trace of traces) {
  const arm = trace.jobId.includes('_V3_') ? 'V3' : trace.jobId.includes('_V4_') ? 'V4'
    : trace.jobId.includes('_RAW_') ? 'RAW' : 'BLINDED';
  const group = costs[trace.phase + '_' + arm] ??= { calls: 0, usageMissing: 0, inputTokens: 0, outputTokens: 0, elapsedMs: 0 };
  group.calls++;
  const usage = trace.response?.usage;
  if (!usage) group.usageMissing++;
  group.inputTokens += usage?.prompt_tokens ?? usage?.input_tokens ?? 0;
  group.outputTokens += usage?.completion_tokens ?? usage?.output_tokens ?? 0;
  group.elapsedMs += trace.elapsedMs ?? 0;
}
await save('cost-observations.json', costs);

const mapping = await json(resolve(evidence, 'review-mapping.json')).catch(() => []);
const reviews = [];
for (const id of protocol.reviewJobs) {
  const result = byId(id);
  const audit = { jobId: id, status: result?.status ?? null, validStructure: false, invalidQuotes: [], caseAssessments: [] };
  try {
    let content = result.body.data.content.trim();
    if (content.startsWith('```json') && content.endsWith('```')) content = content.slice(7, -3).trim();
    const body = JSON.parse(content);
    assert.equal(body.cases.length, protocol.execution.length);
    assert.deepEqual([...body.cases.map(row => row.caseId)].sort(), [...protocol.execution].sort());
    for (const sample of body.cases) {
      assert.deepEqual(sample.artifacts.map(row => row.label).sort(), ['A', 'B', 'C']);
      assert(sample.preferredLabels.length > 0 && sample.preferredLabels.every(label => ['A', 'B', 'C'].includes(label)));
      for (const artifact of sample.artifacts) {
        const arm = mapping.find(row => row.reviewer === id && row.caseId === sample.caseId && row.label === artifact.label)?.arm;
        assert(arm);
        const actual = byId(sample.caseId + '_' + arm + '_EXEC')?.body.data?.content ?? '';
        for (const key of ['hardRequirementFailures', 'usefulContent', 'unnecessaryWork']) {
          assert(Array.isArray(artifact[key]));
          for (const finding of artifact[key]) {
            if (typeof finding.quote !== 'string' || (finding.quote && !actual.includes(finding.quote))) {
              audit.invalidQuotes.push({ caseId: sample.caseId, arm, key, finding });
            }
          }
        }
        artifact.arm = arm;
      }
      sample.preferredArms = sample.preferredLabels.map(label => mapping.find(row => row.reviewer === id
        && row.caseId === sample.caseId && row.label === label).arm);
    }
    audit.validStructure = true;
    audit.caseAssessments = body.cases;
  } catch (error) { audit.error = error.name; }
  audit.usableWithoutQuoteCorrection = audit.validStructure && audit.invalidQuotes.length === 0;
  reviews.push(audit);
}
await save('user-view-reviews.json', reviews);

const suiteNames = ['NaturalTaskReviewTest', 'NaturalTaskUnderstandingTest', 'NaturalEnhancementServiceTest',
  'OpenAiCompatibleNaturalPromptProviderTest', 'MybatisOptimizationHistoryServiceTest', 'DocumentOptimizationControllerTest',
  'DynamicPlatformConstraintTest', 'PromptDocumentTest'];
const reportDir = resolve('services/api/target/surefire-reports');
const reportNames = await readdir(reportDir);
const suites = [];
for (const name of suiteNames) {
  const report = reportNames.find(file => file.startsWith('TEST-') && file.endsWith('.' + name + '.xml'));
  const text = await readFile(resolve(reportDir, report), 'utf8');
  const start = text.match(/<testsuite\b[^>]*>/)[0];
  const number = key => Number(start.match(new RegExp('\\b' + key + '="(\\d+)"'))[1]);
  const row = { name, tests: number('tests'), failures: number('failures'), errors: number('errors'), skipped: number('skipped'),
    updatedAt: (await stat(resolve(reportDir, report))).mtime.toISOString() };
  assert.equal(row.failures + row.errors + row.skipped, 0);
  suites.push(row);
}
await save('validation-summary.json', { productGitHead: manifest.gitHead, actualCalls: closure, costs,
  initialProductGitHead: initialManifest.gitHead, initialSourceChanges,
  java: { tests: suites.reduce((sum, row) => sum + row.tests, 0), suites, scope: '受影响测试报告去重合并，非全仓库验收。' },
  toolChecks: await json(resolve(evidence, 'tool-verification.json')), originalComparisons: comparison.map(row => ({ caseId: row.caseId, sameInformation: row.sameInformation })),
  newAccounts: 0, newTenants: 0, newWorkspaces: 0, flywayApplied: false, browserAcceptance: false,
  productFrontendChanged: false, downstreamChecks: 'artifact-checks.json', userReviewAudit: 'user-view-reviews.json',
  limitations: '单一公开模型别名、少量冻结任务；覆盖锚点与辅助评审不证明任意语义等价、全场景收益或专业验收。' });
console.log(JSON.stringify({ actualCalls: traces.length, phases, closedUnused: closure.unusedCalls,
  java: suites.reduce((sum, row) => sum + row.tests, 0), missingJobs, reviewsWithValidQuotes: reviews.filter(row => row.usableWithoutQuoteCorrection).length }));
