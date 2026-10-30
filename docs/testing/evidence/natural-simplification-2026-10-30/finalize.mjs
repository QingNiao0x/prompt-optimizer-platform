import { readFile, readdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { sha, readJson, saveNew, countAttempts } from './simple-baseline.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const repo = resolve(root, '../../../..');
const previous = resolve(repo, 'docs/testing/evidence/natural-writer-input70-2026-10-29');
const protocol = await readJson(resolve(root, 'protocol.json'));
const closure = await readJson(resolve(root, 'closure.json'));
const tests = await readJson(resolve(root, 'offline-test-results.json'));
const csv = await readJson(resolve(root, 'simple-csv-checks.json'));
const form = await readJson(resolve(root, 'human-review-form.json'));
const total = await countAttempts(resolve(repo, 'tmp/natural-writer-input70-2026-10-29'));
if (closure.status !== 'CLOSED' || closure.actualNewCalls !== 4 || total !== 48 || tests.totalTests !== 131
    || tests.suites.some(item => item.failures || item.errors || item.skipped) || csv.passed !== 16
    || form.completed !== false) throw Error('Final evidence does not match the finite implementation scope');
for (const source of protocol.sources) {
  if (sha(await readFile(resolve(repo, source.path), 'utf8')) !== source.sha256) throw Error('Frozen engineering source drift');
}
for (const entry of closure.files) {
  if (sha(await readFile(resolve(root, entry.path), 'utf8')) !== entry.sha256) throw Error('Closed actual response changed');
}
if (sha(await readFile(resolve(previous, 'final-status.json'), 'utf8')) !== protocol.historicalReportSha256) throw Error('Historical44 snapshot changed');
const metrics = [];
for (let i = 1; i <= 2; i++) {
  const old = [];
  for (const file of await readdir(resolve(previous, 'new-70-allocated/traces'))) {
    const trace = await readJson(resolve(previous, 'new-70-allocated/traces', file));
    if (trace.caseId === 'W70-N' + i) old.push(trace);
  }
  const simple = await readJson(resolve(root, 'traces', 'SIMPLE_N' + i + '_GEN.json'));
  metrics.push({ caseId: 'W70-N' + i, complex70: { calls: old.length,
    elapsedMs: old.reduce((n, item) => n + item.elapsedMs, 0), totalTokens: old.reduce((n, item) => n + item.response.usage.total_tokens, 0) },
    simple: { calls: 1, elapsedMs: simple.elapsedMs, totalTokens: simple.response.usage.total_tokens },
    generationInferenceParametersMatched: true, productProtectionResponsibilitiesEqual: false });
}
const notice = (await readJson(resolve(root, 'traces/SIMPLE_N1_EXEC.json'))).response.choices[0].message.content;
await saveNew(resolve(root, 'internal-quality-review.json'), {
  reviewerKind: 'INTERNAL_AI_ROOT_AGENT', humanReviewerParticipated: false, modelQualityRetries: false,
  cases: [
    { caseId: 'W70-N1', directlyUsableWithinInspectedScope: true, hanCharacters: (notice.match(/\p{Script=Han}/gu) ?? []).length,
      observed: ['保留维护日期时间、仅导出受影响、排队继续、有效下载链接、无需改设置或重复提交、紧急导出和支持入口', '无原因猜测、优惠券或逐一回访承诺', '只交付可外发通知'],
      humanPreference: 'PENDING' },
    { caseId: 'W70-N2', directlyUsableWithinInspectedScope: true, checks: csv.checks, passed: csv.passed,
      sourceReviewedByAgent: true, humanReviewerParticipated: false,
      limits: ['实际Python3.12与3.11语法检查', '只证明原16项预定行为', '改写中选择在内存汇总，不证明任意数据规模适用'] }
  ], metrics, conclusion: '两道已见合成题中简洁基线可行，不能证明普遍稳定收益或直接替换带来源/题答保护的产品。'
});
await saveNew(resolve(root, 'decision.json'), {
  recordedAt: new Date().toISOString(), status: 'ENGINEERING_READY_LIMITED_PILOT_COMPLETE_HUMAN_REVIEW_PENDING',
  engineeringImplemented: true, targetedJavaTests: tests.totalTests, wholeRepositoryTested: false,
  coreRepairLiveModelValidatedThisRound: false, freshPlanOrWorkbenchValidated: false,
  profileDefault: 'STABLE', sourceDefaultVersion: '31', optInCoreVersion: 'core-repair-v1',
  modelCallsThisRound: 4, sharedAuthorizedMaximum: 48, sharedActualCalls: total,
  sharedPhases: { GENERATION: 32, EXECUTION: 16, PLAN: 0, REVIEW: 0 }, previous500Reopened: false,
  simpleBaselineIntegratedIntoProduct: false, humanReviewCompleted: false, realUserTaskComparisonCompleted: false,
  overallStableQualityProven: false, productAdopted: false, dailyServiceUpdated: false, productionDeployed: false,
  broadCandidateIterationExpanded: false, furtherAutomaticSamplingPlanned: false,
  currentRequestRemaining: ['真人实际作品偏好及真实用户任务对照', '采用决定与启用后的普通入口核实'],
  recommendation: '停止扩大复杂链路；工程修复独立审阅。简洁基线保留作有限决策依据，后续采用必须保留必要产品保护并有用户收益证据。'
});
const report = await readFile(resolve(repo, 'docs/testing/自然增强工程修复独立采用与简洁基线对照-2026-10-30.md'), 'utf8');
let linksChecked = 0;
for (const match of report.matchAll(/\]\((\.\/[^)]+)\)/g)) {
  await readFile(resolve(repo, 'docs/testing', match[1]));
  linksChecked++;
}
const files = [];
for (const entry of await readdir(root, { withFileTypes: true })) {
  if (entry.isFile()) files.push({ path: entry.name, sha256: sha(await readFile(resolve(root, entry.name), 'utf8')) });
}
await saveNew(resolve(root, 'final-verification.json'), {
  recordedAt: new Date().toISOString(), sharedActualCalls: total, sharedMaximum: 48,
  historical44SnapshotUnchanged: true, closedResponsesVerified: closure.files.length,
  frozenProductSourcesVerified: protocol.sources.length, reportLinksChecked: linksChecked,
  humanReviewPending: true, defaultNotSwitched: true, files
});
console.log(JSON.stringify({ verified: true, targetedJavaTests: tests.totalTests, sharedActualCalls: total,
  simpleCsvChecks: csv.passed, humanReviewPending: true, reportLinksChecked: linksChecked }));
