import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, relative, dirname } from 'node:path';
import { createHash } from 'node:crypto';
import { checkResearchLength, BODY_HAN_POLICY } from '../../../../../scripts/me00a-work-checks.mjs';

// This finalizer performs no model requests. Original frozen manifests and pending proposals stay unchanged.
const root = resolve('tmp/me00a-20261012');
const live = resolve(root, 'candidate12-real');
const target = resolve('docs/testing/evidence/me00a-candidate12-2026-10-14');
const load = async file => JSON.parse(await readFile(file, 'utf8'));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const save = async (file, value) => {
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, typeof value === 'string' ? value : JSON.stringify(value, null, 2), { flag: 'wx' });
};
const account = await load(resolve('tmp/local-acceptance/account.json'));
const references = ['userId', 'tenantId', 'workspaceId', 'identifier'].map(key => account[key]).filter(Boolean);
const excludedKeys = /^(?:planId|planningContext|contextId|userId|tenantId|workspaceId|password|passwordHash|apiKey|authorization|cookie|set-cookie|sessionToken|accessToken|refreshToken)$/i;

/** Redact authentication/actor references, while retaining question/source IDs and exact synthetic task contents. */
function sanitize(value, key = '') {
  if (excludedKeys.test(key)) return '[private-reference-redacted]';
  if (Array.isArray(value)) return value.map(item => sanitize(item));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([name, item]) => [name, sanitize(item, name)]));
  if (typeof value === 'string') {
    for (const reference of references) value = value.replaceAll(reference, '[private-actor-reference-redacted]');
    return value.replaceAll('E:\\MyProject\\prompt-optimizer-platform\\tmp\\me00a-20261012\\traces\\', 'traces/');
  }
  return value;
}
const protocolPath = resolve(root, 'protocol.json');
const protocol = await load(protocolPath);
if (protocol.activeBatch.version !== 'candidate12' || protocol.activeBatch.authorizationStatus !== 'APPROVED_BY_USER'
    || protocol.maximumActualModelCalls !== 243 || protocol.phaseLimits.candidateValidation !== 163)
  throw new Error('Expected explicitly approved candidate12 batch');
const manifest = await load(resolve(target, 'candidate12-source.json'));
const groups = { sourceFiles: manifest.sourceFiles, classFiles: manifest.classFiles, policyFiles: manifest.policyFiles };
const freezeChecks = {};
for (const [name, files] of Object.entries(groups)) {
  for (const file of files) if (sha(await readFile(resolve(file.path))) !== file.sha256) throw new Error(`Frozen file changed: ${file.path}`);
  freezeChecks[name] = { checked: files.length, changed: 0 };
}
const allNames = (await readdir(resolve(root, 'traces'))).filter(name => /^\d+\.json$/.test(name)).sort();
const all = await Promise.all(allNames.map(name => load(resolve(root, 'traces', name))));
if (allNames.length !== 229 || all.filter(item => item.version === 'candidate12').length !== 10)
  throw new Error('Unexpected later attempts; re-audit actual ledger before closing');
const calls = all.map((trace, index) => ({ trace, name: allNames[index] })).filter(item => item.trace.version === 'candidate12');
const purposeCounts = Object.fromEntries(['PLATFORM_GENERATION', 'EXECUTION', 'REVIEW'].map(purpose =>
  [purpose, calls.filter(item => item.trace.purpose === purpose).length]));
if (purposeCounts.PLATFORM_GENERATION !== 2 || purposeCounts.EXECUTION !== 6 || purposeCounts.REVIEW !== 2
    || calls.some(item => item.trace.httpStatus !== 200 || item.trace.failure)) throw new Error('Unexpected purpose/status result');
const closedAt = new Date().toISOString();
const budget = {
  closedAt, authorization: 'protocol-amendment-09.json', totalActualCalls: all.length,
  actualByPhase: { baseline: all.filter(item => item.phase === 'baseline').length,
    candidateValidation: all.filter(item => item.phase === 'candidateValidation').length },
  formalLimits: { total: 243, baseline: 80, candidateValidation: 163 },
  batch: { before: 219, after: 229, actualNewCalls: 10, maximumNewCalls: 14, purposeCounts, planningCalls: 0,
    failedCalls: 0, protocolRepairCalls: 0, unusedBatchAllowance: 4,
    status: 'CLOSED_AFTER_AUTHORIZED_ONE_ROUND', unusedAllowanceTransferAllowed: false, secondRoundAuthorized: false },
  noPhaseBorrowing: true, stopNewRequestsForThisBatch: true,
};
if (budget.actualByPhase.baseline !== 70 || budget.actualByPhase.candidateValidation !== 159) throw new Error('Phase ledger differs');
await save(resolve(target, 'budget-final.json'), budget);
await save(resolve(target, 'post-real-freeze-verification.json'), { verifiedAt: closedAt, ...freezeChecks,
  noProductionOrGuidanceMutationAfterFreeze: true, previousManifestIsOfflineSnapshot: true });

// Publish exact work/copy Markdown; JSON loses only the explicitly described private identity/session references.
const publication = [];
async function publishFolder(folder) {
  for (const item of await readdir(folder, { withFileTypes: true })) {
    const source = resolve(folder, item.name);
    if (item.isDirectory()) await publishFolder(source);
    else {
      const name = relative(live, source).replaceAll('\\', '/');
      const bytes = await readFile(source);
      const exported = name.endsWith('.json') ? JSON.stringify(sanitize(JSON.parse(bytes.toString('utf8'))), null, 2) : bytes;
      await mkdir(dirname(resolve(target, 'live', name)), { recursive: true });
      await writeFile(resolve(target, 'live', name), exported, { flag: 'wx' });
      publication.push({ path: `live/${name}`, originalSha256: sha(bytes), publishedSha256: sha(exported),
        exactOriginalBytes: !name.endsWith('.json'), privateReferenceSanitizationAppliedToJson: name.endsWith('.json') });
    }
  }
}
await publishFolder(live);
const metrics = [];
for (const { trace, name } of calls) {
  await save(resolve(target, 'traces', name), sanitize(trace));
  const usage = trace.response?.usage;
  if (!usage) throw new Error(`Missing actual usage: ${name}`);
  metrics.push({ attempt: Number.parseInt(name), purpose: trace.purpose, label: trace.label, model: trace.request.model,
    providerHttpElapsedMs: trace.elapsedMs, usage, finishReason: trace.response.choices[0].finish_reason });
  if (trace.purpose === 'PLATFORM_GENERATION') {
    const arm = name === '220.json' ? 'plan' : 'direct';
    const content = trace.response.choices[0].message.content;
    await save(resolve(target, 'models', `${arm}-response.txt`), content);
    await save(resolve(target, 'models', `${arm}-response.json`), JSON.parse(content));
  }
}
for (const file of ['candidate12-q05-cases.json', 'candidate12-actual-answers.json', 'candidate12-plan-questions.json', 'candidate12-plan-input.json'])
  await save(resolve(target, 'fixtures', file), sanitize(await load(resolve(root, file))));
await save(resolve(target, 'publication-scope.json'), { privateJsonFields: excludedKeys.source,
  rawProviderBodiesPreserved: true, responseHeadersAndWireBytesNotCaptured: true, records: publication });
const tokenTotals = metrics.reduce((sum, item) => ({ prompt: sum.prompt + item.usage.prompt_tokens,
  completion: sum.completion + item.usage.completion_tokens, total: sum.total + item.usage.total_tokens }), { prompt: 0, completion: 0, total: 0 });
await save(resolve(target, 'provider-metrics.json'), { metrics, tokenTotals, samplePerModelArm: 1,
  scope: 'Provider HTTP times only, not end-to-end p50/p95 or invoice cost; cache differences retained',
  monetaryCost: 'UNKNOWN', performanceGatePassed: false });
const lock = await load(resolve(live, 'copies-lock.json'));
const hardChecks = {};
for (const model of ['flash', 'pro']) for (const arm of ['raw', 'direct', 'plan']) {
  const name = `${model}-${arm}`;
  const work = await readFile(resolve(live, 'works', `${name}.md`), 'utf8');
  const execution = calls.find(item => item.trace.label === name).trace;
  if (sha(execution.request.messages.at(-1).content) !== lock[arm].sha256) throw new Error(`Actual executor input differs: ${name}`);
  hardChecks[name] = { ...checkResearchLength(work), literalFrozenExecutionInput: true,
    noCodeFence: !/^\s*```/m.test(work), chineseMethodsDeliverable: true,
    syntheticStudyResultsAbsent: true, latterTwoChecks: 'Independent text audit, not a universal hallucination detector',
    strictJson: 'NOT_APPLICABLE', pureTranslation: 'NOT_APPLICABLE', examplesToggleMatrix: 'NOT_COVERED_THIS_BATCH' };
}
await save(resolve(target, 'independent-hard-checks.json'), { policy: BODY_HAN_POLICY, works: hardChecks,
  optimizedLengthPasses: 4, optimizedWorks: 4, allLengthPasses: 5, allWorks: 6,
  noSelfReportedWordCountAccepted: true, completeQualityGatePassed: false });
const mapping = await load(resolve(live, 'locked-mapping.json'));
const reviews = await Promise.all([1, 2].map(index => load(resolve(live, `reviewer-${index}.json`))));
if (reviews.some(item => !item.valid)) throw new Error('Invalid independent review retained; cannot mark the review stage complete');
const pairs = reviews[0].review.pairs.map((pair, index) => ({ left: mapping[pair.left], right: mapping[pair.right],
  reviewers: reviews.map(item => ({ model: item.model, ...item.review.pairs[index] })),
  verdictDisagreement: pair.verdict !== reviews[1].review.pairs[index].verdict }));
await save(resolve(target, 'review-comparison.json'), { reviewerType: 'AI_ASSISTED_NOT_HUMAN_EXPERT',
  independentFreshCalls: true, sharedAnonymousPack: true, packSha256: reviews[0].inputSha256,
  mapping, scores: reviews.map(item => ({ model: item.model, scores: item.review.scores })), pairs,
  disputedPairs: pairs.filter(item => item.verdictDisagreement).length,
  originalReviewsUnchanged: true, scoresCannotOverrideIndependentHardOrSevereFailures: true });
await save(resolve(target, 'content-audit.json'), {
  type: 'Independent source-and-work inspection by the development agent; not an additional human/AI expert review',
  workScope: 'One Q05 sample per model/arm; no inference of stable model capability or universal strategy benefit',
  findings: [
    { id: 'PLATFORM-220-STATE-DUPLICATE', layer: 'PLATFORM', evidence: ['models/plan-response.txt', 'live/plan/copied.md'],
      source: '当前仅为拟定研究，尚未实施，没有额外真实数据，不确认任何既有参数。',
      extra: '- 我没有额外真实数据', status: 'FAIL',
      reason: '同一研究的资料状态在增强背景已完整表达，平台仍补同状态；候选十二有限句法没有覆盖本轮复合句。四份旧原件修复不能证明新措辞全通过。' },
    { id: 'PLATFORM-221-CAUTIOUS-APPENDS', layer: 'PLATFORM_OR_UNRESOLVED_COVERAGE', evidence: ['models/direct-response.txt', 'live/direct/copied.md'],
      status: 'OPEN', reason: '读者表达及全局委托句仍保护性追加；部分推荐已展开，但可开展/适用条件是否全范围覆盖不能靠关键词确定。缺少真实数据与全称未确认状态在模型正文未完整表达，保留正确。' },
    { id: 'RAW-PLAN-SEMANTICS', layer: 'DOWNSTREAM_ORIGINAL_AMBIGUITY', evidence: ['live/works/flash-raw.md', 'live/works/pro-raw.md'],
      status: 'FAIL', reason: '原始两作品将Plan定义为先形成计划，Pro还把评价对象变为问答对；平台优化正文明确问答增强流程，四份优化作品保留该定义与提示词评价对象。这是本轮实际收益，不代表整体研究可用。' },
    { id: 'FLASH-DIRECT-RATER-CONTRADICTION', layer: 'DOWNSTREAM', evidence: ['live/works/flash-direct.md', 'live/direct/copied.md'],
      status: 'FAIL', source: '评审随机分配至不同组别材料，且每名评审不重复评价同一任务的同一模式。',
      reason: '与同篇每项任务由两名独立评审盲评的要求不一致；复制正文未要求破坏双评，确切违约始于下游作品。增强提示的评审分配随机例子是否诱发仍待最小策略对照。' },
    { id: 'FLASH-PLAN-INCOMPLETE-DESIGN', layer: 'ENHANCEMENT_AND_DOWNSTREAM_WITH_VALIDATION_BOUNDARY_OPEN', evidence: ['models/plan-response.json', 'live/works/flash-plan.md', 'live/plan/copied.md'],
      status: 'OPEN', source: '若某指标评分差异超过预设阈值，由第三人复核。',
      reason: '分类与来源已给拟议方案，但评分尺度/阈值仍未给具体建议，两公平对照的具体差异仍笼统。增强原件assessment的EXECUTION_STEP提到评分尺度建议，sections未落实，复制正文也未落实，不能全归下游；元数据不能自动当执行正文。保留当前校验，另评估声明与责任段一致性的边界，不机械追加所有gap。允许建议和参数未确认应并存。' },
    { id: 'PRO-DIRECT-COMPARISON-STRUCTURE', layer: 'DOWNSTREAM_WITH_UNRESOLVED_DESIGN_RISK', evidence: ['live/works/pro-direct.md', 'live/direct/copied.md'],
      status: 'OPEN', source: '直接增强无确认、直接增强有确认、Plan无确认、Plan有确认',
      reason: '2×2展开与Plan取得实际用户回答的定义之间操作关系未说清，评审对严重性意见相反；不可强判所有2×2都违法，但本作品未足以关闭原定公平对照可开展门禁。' },
    { id: 'PRO-PLAN-INFORMATION-FLOW', layer: 'DOWNSTREAM', evidence: ['live/works/pro-plan.md', 'live/plan/copied.md'],
      status: 'FAIL', source: '确认信息来源于Plan组实际问答中用户提供的回答，经整理后形成标准化确认信息文本',
      reason: '复制正文要求同一实际信息且不可修改回答；作品改为组后取得及整理，未保证内容/时点/使用范围一致。不能将任何自然Plan答复封存一概视为违法；当前受控同信息轨的违约单列。' },
    { id: 'LENGTH', layer: 'DOWNSTREAM', evidence: ['independent-hard-checks.json'], status: 'PARTIAL_PASS',
      reason: 'Flash原始1477超限，其他五份在800至1200；四份优化篇幅均通过，仅关闭本题本轮篇幅子项。' },
    { id: 'REVIEW-LIMITATIONS', layer: 'EVALUATION', evidence: ['live/reviewer-1.json', 'live/reviewer-2.json', 'live/review-pack.md'], status: 'OPEN',
      reason: '两评审未识别原始作品将Plan解释为先自行计划，Flash Plan高分遗漏具体尺度/阈值未补全。Flash评审称Pro Plan固定任务顺序即不充分随机化，原题只要求说明随机化，不能据此新增必须随机全部层级的硬门禁。两评审原始对两份均有失败的材料仍给胜/平；独立门禁另列双方失败，不覆盖原结论。' },
  ],
  acceptancePairs: [
    { left: 'flash-raw', right: 'flash-direct', verdict: 'BOTH_FAIL', reason: '原始篇幅超限/Plan语义错误；直接作品双评结构矛盾。原评审仍保留原始胜。' },
    { left: 'flash-raw', right: 'flash-plan', verdict: 'FAIL_VS_OPEN', reason: 'Plan有具体收益但专业补全及平台重复仍开放；不是上线胜率。' },
    { left: 'flash-direct', right: 'flash-plan', verdict: 'FAIL_VS_OPEN', reason: '两AI偏好Plan，不关闭Plan完整性缺项。' },
    { left: 'pro-raw', right: 'pro-direct', verdict: 'FAIL_VS_OPEN', reason: '原始目标/Plan定义错误；直接设计结构分歧未解决。' },
    { left: 'pro-raw', right: 'pro-plan', verdict: 'BOTH_FAIL', reason: '原始目标/Plan定义错误；Plan信息流程违约。' },
    { left: 'pro-direct', right: 'pro-plan', verdict: 'OPEN_VS_FAIL', reason: '评审原始平局保留，独立受控信息流失败不能作通过。' },
  ],
  strategyDecision: { newComplexInstructionsAdded: false, forcedStatisticalMethodAdded: false,
    automaticQualityRetryUsed: false, switchEnhancementModelDuringDiagnosis: false,
    next: '先离线回放220/221精确补资料状态及完整委托覆盖；纯下游问题评估去除有歧义的现有提示例子/重复，未证明收益前不一定修改提示词。',
    stableQualityOrPerformanceImprovementProven: false, examplesAndFrontendExpansionAllowedByCurrentGates: false },
});
await save(resolve(target, 'gate-status.json'), {
  G1: 'LOCAL_BOUNDARIES_PASSED_CURRENT_PROMPTS_NARROWLY_CHECKED_FULL_HOLDOUT_OPEN',
  G2: 'LOCAL_FILTER_PASSED_NEW_NATURAL_PLANNING_NOT_EVALUATED',
  G3: 'TWO_LIVE_COPY_HISTORY_MATCHES_EXAMPLE_UI_EDIT_UNDO_OPEN',
  G4: 'NOT_PASSED_ONE_SAMPLE_AND_REVIEW_DISAGREEMENTS', G5: 'NOT_PASSED_LENGTH_SUBITEM_ONLY_PASSED_FOR_FOUR_OPTIMIZED_WORKS',
  G6: 'SAME_ACCOUNT_NORMAL_LOGIN_AND_SCOPED_HISTORY_PASSED_FULL_BROWSER_LOOP_OPEN',
  G7: 'OPEN_ACTUAL_USAGE_AND_HTTP_TIMES_ONLY', G8: 'AFFECTED_CHECKS_PASSED_FULL_ENGINEERING_RELEASE_OPEN',
  overallReleased: false, examplesAndFrontendExpansionDeferredUntilDependenciesPass: true,
});
Object.assign(protocol.activeBatch, { status: budget.batch.status, authorizationStatusAtStart: 'APPROVED_BY_USER',
  authorizationStatus: 'APPROVED_AND_CLOSED', closedAt, callsAfter: 229,
  actualNewCalls: 10, actualPurposeCounts: purposeCounts, unusedBatchAllowance: 4 });
await save(resolve(target, 'protocol-final.json'), protocol);
await writeFile(protocolPath, JSON.stringify(protocol, null, 2));
console.log(JSON.stringify({ total: 229, baseline: 70, candidate: 159, newCalls: 10, tokenTotals,
  disputedPairs: pairs.filter(item => item.verdictDisagreement).length, sourceFiles: freezeChecks.sourceFiles.checked }));
