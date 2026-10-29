import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Only closed development originals and the shared public snapshot are read here.
// This preparation entry never invokes a model, the application, or a holdout.
const target = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(target, '..');
const closedStage = resolve(evidence, 'development-r2');
const read = (path) => readFileSync(path, 'utf8');
const parse = (path) => JSON.parse(read(path));
const hash = (value) => createHash('sha256').update(value).digest('hex');
const writeJson = (name, value) => writeFileSync(resolve(target, name), `${JSON.stringify(value, null, 2)}\n`, 'utf8');
if (existsSync(resolve(target, 'bundle.json'))) throw new Error('Prepared bundle already exists; do not replace a frozen input.');

const closurePath = resolve(closedStage, 'closure.json');
const closure = parse(closurePath);
if (closure.status !== 'CLOSED' || !closure.entryLockedByClosure) throw new Error('Development source is not closed.');
const priorJobsPath = resolve(closedStage, 'jobs.json');
const priorJobs = parse(priorJobsPath);
const original = (id) => {
  const prior = priorJobs.find((job) => job.id === id);
  if (!prior?.approvedEnhancementInput?.rawPrompt) throw new Error(`Missing raw input ${id}`);
  return prior.approvedEnhancementInput.rawPrompt;
};
const sourceResults = ['Q01_V31_GEN', 'Q01_V46_GEN', 'V03_V46_GEN'].map((id) => {
  const path = resolve(closedStage, 'results', `${id}.json`);
  const expected = closure.files.find((item) => item.path.endsWith(`/results/${id}.json`));
  const sha256 = hash(readFileSync(path));
  if (!expected || expected.sha256 !== sha256) throw new Error(`Closed source digest mismatch: ${id}`);
  const result = parse(path);
  if (result.status !== 200 || !result.body?.data?.optimizedPrompt) throw new Error(`Missing successful prompt: ${id}`);
  if (!result.body.data.processing?.completedStages?.includes('BODY_GENERATION')) throw new Error(`No writer stage: ${id}`);
  return { id, path, sha256, prompt: result.body.data.optimizedPrompt, chainVersion: result.body.data.chainVersion };
});
const generated = (id) => sourceResults.find((item) => item.id === id).prompt;
const sourcePackPath = resolve(evidence, 'query-source-pack.json');
const sourcePackText = read(sourcePackPath);
const sourcePack = JSON.parse(sourcePackText);
const material = { path: 'query-source-pack.json', content: sourcePackText };
const system = '请直接完成用户的任务，交付可阅读的答案。可使用自然段、标题或要点。';
const parameters = { model: 'deepseek-flash', temperature: 0.2, max_tokens: 8192, thinking: { type: 'disabled' }, stream: false };
const qRaw = original('Q01_V46_GEN');
const vRaw = original('V03_V46_GEN');
if (!vRaw.includes('没有相关资料') || !vRaw.includes('普通用户可操作的排查建议')) throw new Error('V03 original boundary is missing.');
const caseContexts = {
  Q01: `本次提供的公开材料快照（相同任务的所有作品使用同一份材料）：\n\n${sourcePackText}`,
  V03: '本次没有提供相关资料。',
};
const promptArms = {
  Q01: { ORIGINAL: qRaw, '31': generated('Q01_V31_GEN'), '46': generated('Q01_V46_GEN') },
  V03: { ORIGINAL: vRaw, '46': generated('V03_V46_GEN') },
};
const jobs = Object.entries(promptArms).flatMap(([caseId, arms]) => Object.entries(arms).flatMap(([version, prompt]) => [1, 2].map((repeat) => ({
  id: `${caseId}_${version === 'ORIGINAL' ? 'ORIGINAL' : `V${version}`}_EXEC_R${repeat}`,
  caseId,
  phase: 'EXECUTION',
  version,
  repeat,
  promptSha256: hash(prompt),
  approvedModelRequest: { ...parameters, messages: [{ role: 'system', content: system }, { role: 'user', content: prompt }, { role: 'user', content: caseContexts[caseId] }] },
}))));
const orderSeed = 'natural-content-works-2026-10-28-execution-r1';
jobs.sort((left, right) => hash(`${orderSeed}:${left.id}`).localeCompare(hash(`${orderSeed}:${right.id}`)));
const commonCase = { provenance: '公开任务的开发对照，非未见题；不包含真实用户资料。', repeatsPerArm: 2, onlyPromptChanges: true };
const bundle = {
  syntheticOnly: true,
  purpose: '同信息自然内容实际作品对照；仅准备，不代表已经执行或采用候选。',
  sourceStage: 'development-r2 CLOSED',
  publicMaterialsAreSynthetic: false,
  cases: [
    { id: 'Q01', rawPrompt: qRaw, contextDescription: '', ...commonCase, executorMaterials: [material], materialVisibility: 'EXECUTION_ONLY_ALL_ARMS_IDENTICAL', publicSourceSnapshotSha256: hash(sourcePackText), sourcePackCollectedOn: sourcePack.collectedOn },
    { id: 'V03', rawPrompt: vRaw, contextDescription: caseContexts.V03, ...commonCase, executorMaterials: [], explicitNoMaterials: true, commonExecutionContext: caseContexts.V03, absenceContextIsSharedNotAnEditToGeneratedPrompt: true },
  ],
  jobs,
  phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 10, REVIEW: 0 },
  executionOrderSeed: orderSeed,
  qualityRetriesAllowed: false,
  accountCreationAllowed: false,
};

const criteria = {
  status: 'FROZEN_BEFORE_EXECUTION',
  scope: '仅Q01和V03本轮10份作品；不外推到全部任务、职业或模型，不构成发布验收。',
  hypothesis: '46的自然正文能在同信息执行中改善或保持原任务的实际交付；更长提示词或更多核查句不自动转化为作品收益。',
  common: [
    { id: 'ANSWER', question: '是否实际回答原始用户问什么，而非主要给研究方法、资料清单、补充问卷或重复任务？', evidence: '摘录直接回答及遗漏；资料不足只限制对应结论，其余内容继续交付。' },
    { id: 'ACCURACY', question: '依据、数字、概念及事实状态是否准确；是否出现无依据的确定结论或伪称已检索/已操作？', evidence: '列出具体主张、作品原句、对应材料或需要核实的事实；不因有引用格式直接判准确。' },
    { id: 'FOCUS', question: '实际内容与必要局限是否保持合适比例，是否把主要篇幅转为口径、方法或缺口说明而没有回答？', evidence: '引用造成交付受阻的具体段落；不设置统一字数、段落或核查占比阈值。' },
    { id: 'READABLE', question: '用户能否直接阅读并理解结论或采取合理行动？', evidence: '指出术语、组织或步骤造成的具体理解困难；自然标题、要点、表格与短段均允许。' },
    { id: 'EDIT_BURDEN', question: '交付前还须删除、更正、补充或重组哪些内容？', evidence: '给出必要改动的原句与建议；区分实质修正和个人文风偏好，不靠字符数裁决。' },
  ],
  cases: {
    Q01: {
      required: [
        '给出哪些职业、哪些行业使用AI较多的实质回答；允许结合材料的不同层级给出有边界的结论。',
        '用共同材料正确支撑重要结论；平台对话分布、职业开发者调查、组织/行业采用调查不能无说明拼成统一全球排名。',
        '有限样本不能证明全球全职业或全行业唯一冠军；说明限制同时继续提供材料支持的回答。',
      ],
      notRequired: ['未要求的应用场景或用途扩展', '固定排名数量', '统一单一指标', '固定表格、章节或字数', '覆盖材料之外全部全球来源'],
      materialChecks: [
        'S1的职业类别占比是Claude平台对话/任务分布，不是相应职业从业者使用率。',
        'S2的84%包含正在使用或计划使用；51%对应职业开发者每日使用，不能写成全球所有从业者使用率。',
        'S3的88%是受访者报告其组织至少一项业务职能经常使用AI，不是行业员工使用率或全球行业排行。',
      ],
      reasonableAlternatives: '可按来源分别列领先类别，也可综合给出有依据的简短结论再说明不可直接比较之处；不要求虚构统一名次。',
    },
    V03: {
      required: [
        '解释同一照片在不同屏幕呈色不同的主要合理原因，语言面向普通用户。',
        '给普通用户可操作的排查建议，步骤与原因有关系，用户能据此比较或调整。',
        '本次没有相关资料，不得假定知道用户设备、照片配置或已经完成校准；不把索取全部材料当作开始解释的前提。',
      ],
      notRequired: ['固定原因数量', '覆盖所有专业色彩管理细节', '购买设备或专业校色作为必经步骤', '统一步骤顺序、固定章节或字数'],
      reasonableAlternatives: '允许从设置、应用、屏幕、照片或环境等不同合理路径组织；不凭单一参考答案的排列判优。',
    },
  },
  severity: {
    serious: '改变任务或交付类型、关键事实/依据误用、将不确定说成确定、必要回答缺失、越过用户资料边界，或普通用户照做存在实质误导。',
    materialEdit: '需要实质更正、补充或重组才能直接交付，但未达到严重失败。',
    minorPreference: '少量无语义影响的文风或组织偏好，不作为实质胜负。',
  },
  comparison: {
    judgments: ['LEFT_BETTER', 'TIE', 'RIGHT_BETTER', 'BOTH_FAIL', 'UNRESOLVED'],
    firstAssessEachWorkIndependently: true,
    explainWithQuotedEvidenceAndRequiredEdits: true,
    missingOrTruncatedWorkIsNotOpponentSemanticWin: true,
    sufficientInputTieIsFidelityPassNotImprovement: true,
    longerWorkOrMoreDimensionsDoNotWinAutomatically: true,
    repeatedDifferences: '每个预设配对分别报告两次结果；改善仅单次出现时标注不稳定。两次没有可核实实质收益时不宣称该题增强有效。',
    candidateClaimBoundary: '46出现新增严重退步即不以其他小收益抵消；全部作品仍须完成后再解盲汇总，禁止挑选较好重复。两题结果不能单独证明正式采用或发布。',
    reviewers: '内部匿名审阅；真实独立人工未参与时明确人工复核待完成，不冒称独立专家结论。',
  },
  prohibitedExecutorInputs: ['本判据', '材料校验答案卡', '匿名映射', '预期胜负', '候选版本标签'],
};

const blindSeed = 'natural-content-works-2026-10-28-review-r1';
const blindOrder = [...jobs].sort((left, right) => hash(`${blindSeed}:${left.id}`).localeCompare(hash(`${blindSeed}:${right.id}`)));
const map = blindOrder.map((job, index) => ({ workId: `W${String(index + 1).padStart(2, '0')}`, jobId: job.id, caseId: job.caseId, arm: job.version, repeat: job.repeat }));
const blind = (caseId, arm, repeat) => map.find((item) => item.caseId === caseId && item.arm === arm && item.repeat === repeat).workId;
const comparisons = [1, 2].flatMap((repeat) => [
  ['Q01', 'ORIGINAL', '31'], ['Q01', 'ORIGINAL', '46'], ['Q01', '31', '46'], ['V03', 'ORIGINAL', '46'],
].map(([caseId, leftArm, rightArm], index) => {
  const swap = parseInt(hash(`${blindSeed}:pair:${caseId}:${leftArm}:${rightArm}:${repeat}`).slice(0, 2), 16) % 2 === 1;
  const pair = [blind(caseId, leftArm, repeat), blind(caseId, rightArm, repeat)];
  if (swap) pair.reverse();
  return { comparisonId: `${caseId}-R${repeat}-${index + 1}`, caseId, left: pair[0], right: pair[1] };
}));
const reviewPlan = {
  status: 'PREPARED_BEFORE_EXECUTION',
  blindSeed,
  contentInput: '每份匿名作品只提供原始要求、该题共同资料/无资料状态，以及原样作品。隐藏生成提示词、版本、模型品牌、jobId、执行顺序、耗时、token和重复编号。',
  ordering: map.map(({ workId, caseId }) => ({ workId, caseId })),
  comparisons,
  procedure: [
    '全部10份首次作品完成或明确失败后，先核对原件、共同system/材料/参数和每臂重复次数；不按质量补采。',
    '协调者根据coordinator-key.json生成匿名作品；原样正文，不修正文风、事实、标题或重复。',
    '审阅者先逐份记录ANSWER、ACCURACY、FOCUS、READABLE、EDIT_BURDEN及原句证据，再完成预设比较。',
    '先保存带时间和哈希的匿名审阅结果，再由协调者解盲。分歧和不确定事实保留，不选更有利评分。',
    '报告分别说明首次交付/失败、作品语义差异、必要编辑负担与重复稳定性；仅给出本题集内部评估。',
  ],
  individualTemplate: { workId: '', caseId: '', outcome: 'DIRECTLY_USABLE|NEEDS_MATERIAL_EDIT|SERIOUS_FAILURE|MISSING|UNRESOLVED', findings: [{ criterionId: '', severity: 'serious|materialEdit|minorPreference', quote: '', reason: '', sourceReference: '', necessaryEdit: '' }], requiredEdits: [], unresolvedChecks: [] },
  pairTemplate: { comparisonId: '', judgment: 'LEFT_BETTER|TIE|RIGHT_BETTER|BOTH_FAIL|UNRESOLVED', concreteDifference: '', leftQuote: '', rightQuote: '', userEditDifference: '', personalStyleOnly: false },
  disclosure: '匿名不等于从未见过任务。参与过开发或读过提示词的AI审阅者须披露，不能称为独立人工审阅。',
};
writeFileSync(resolve(target, 'query-source-pack.json'), sourcePackText, 'utf8');
writeJson('bundle.json', bundle);
writeJson('criteria.json', criteria);
writeJson('anonymous-review-plan.json', reviewPlan);
writeJson('coordinator-key.json', { role: 'COORDINATOR_ONLY_NOT_EXECUTOR_OR_BLIND_REVIEWER_INPUT', map, unblindOnlyAfterReviewResultsLocked: true });
writeJson('prompt-provenance.json', {
  preparedAt: new Date().toISOString(),
  closedSource: { path: closurePath, sha256: hash(readFileSync(closurePath)), status: closure.status },
  originalInputSource: { path: priorJobsPath, sha256: hash(readFileSync(priorJobsPath)) },
  sourceResults: sourceResults.map(({ prompt, ...item }) => ({ ...item, promptSha256: hash(prompt) })),
  sourcePack: { path: sourcePackPath, sha256: hash(sourcePackText), copiedByteIdentically: true, executionOnly: true, publicResearchSnapshotNotSyntheticFacts: true },
  armPrompts: Object.entries(promptArms).flatMap(([caseId, arms]) => Object.entries(arms).map(([arm, prompt]) => ({ caseId, arm, prompt, sha256: hash(prompt) }))),
  system,
  v03AbsenceContext: '生成46正文按闭批原样，不修改；相同的无资料执行上下文提供给ORIGINAL和46，保留原始任务状态。',
  modelCallsMadeByPreparation: 0,
});
const manifestNames = ['build.mjs', 'bundle.json', 'criteria.json', 'anonymous-review-plan.json', 'coordinator-key.json', 'prompt-provenance.json', 'query-source-pack.json'];
writeJson('preparation-manifest.json', { status: 'PREPARED_NOT_EXECUTED', jobs: 10, paidReviews: 0, phaseLimits: bundle.phaseLimits, files: manifestNames.map((name) => ({ path: name, sha256: hash(readFileSync(resolve(target, name))) })) });
console.log(JSON.stringify({ status: 'PREPARED_NOT_EXECUTED', directory: target, jobs: jobs.length, comparisons: comparisons.length, modelCalls: 0 }, null, 2));
