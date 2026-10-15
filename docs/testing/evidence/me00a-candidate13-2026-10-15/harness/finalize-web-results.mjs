import { readFile, writeFile, readdir, mkdir } from 'node:fs/promises';
import { resolve, relative, dirname } from 'node:path';
import { createHash } from 'node:crypto';
import { assessmentCoverageRecord } from '../../../scripts/me00a-cross-model.mjs';
import { checkResearchLength, BODY_HAN_POLICY } from '../../../scripts/me00a-work-checks.mjs';
const root = resolve('tmp/me00a-20261012'), live = resolve(root, 'candidate13-real');
const target = resolve('docs/testing/evidence/me00a-candidate13-2026-10-15');
const load = async path => JSON.parse(await readFile(path, 'utf8'));
const hash = value => createHash('sha256').update(value).digest('hex');
const save = async (name, value) => {
  const path = resolve(target, name); await mkdir(dirname(path), { recursive: true });
  await writeFile(path, typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value, null, 2), { flag: 'wx' });
};
const protocol = await load(resolve(root, 'protocol.json'));
if (protocol.activeBatch.version !== 'candidate13' || protocol.activeBatch.status !== 'OPEN'
  || protocol.activeBatch.authorizationAmendment !== 'protocol-amendment-11.json'
  || protocol.maximumActualModelCalls !== 263 || protocol.phaseLimits.candidateValidation !== 183) throw new Error('Unexpected approved batch');
const manifest = await load(resolve(target, 'candidate13-source.json'));
const freezeChecks = {};
for (const key of ['sourceFiles', 'classFiles', 'policyFiles']) {
  for (const item of manifest[key]) if (hash(await readFile(resolve(item.path))) !== item.sha256) throw new Error('Frozen production drift');
  freezeChecks[key] = { checked: manifest[key].length, changed: 0 };
}
const names = (await readdir(resolve(root, 'traces'))).filter(name => /^\d+\.json$/.test(name)).sort();
const traces = await Promise.all(names.map(name => load(resolve(root, 'traces', name))));
const calls = traces.map((trace, i) => ({ trace, name: names[i] })).filter(({trace}) => trace.version === 'candidate13');
const counts = Object.fromEntries(['PLATFORM_GENERATION', 'EXECUTION', 'REVIEW'].map(purpose => [purpose, calls.filter(({trace}) => trace.purpose === purpose).length]));
if (traces.length !== 239 || traces.filter(t => t.phase === 'baseline').length !== 70 || traces.filter(t => t.phase === 'candidateValidation').length !== 169
  || counts.PLATFORM_GENERATION !== 2 || counts.EXECUTION !== 6 || counts.REVIEW !== 2
  || calls.some(({trace}) => trace.failure || (trace.surface === 'WEB' ? trace.status !== 'UI_COMPLETED' : trace.httpStatus !== 200))) throw new Error('Ledger or response state differs; inspect before closing');
const lock = await load(resolve(live, 'copies-lock.json'));
for (const {trace} of calls.filter(({trace}) => trace.purpose === 'EXECUTION')) {
  if (trace.request.inputSha256 !== lock[trace.arm].sha256 || hash(trace.request.input) !== lock[trace.arm].sha256
    || !trace.literalOutgoingPayloadMatches?.some(item => item.exact && item.sha256 === lock[trace.arm].sha256)) throw new Error('Actual web payload does not bind literal copy');
}
const originals = await Promise.all([230,231].map(n => load(resolve(root, 'traces', `${n}.json`))));
const models = originals.map(trace => JSON.parse(trace.response.choices[0].message.content));
const copies = Object.fromEntries(await Promise.all(['direct','plan'].map(async arm => [arm, await readFile(resolve(live, arm, 'copied.md'), 'utf8')])));
const workNames = ['doubao-raw','doubao-direct','doubao-plan','deepseek-raw','deepseek-direct','deepseek-plan'];
const works = Object.fromEntries(await Promise.all(workNames.map(async name => [name, await readFile(resolve(live, 'works', `${name}.md`), 'utf8')])));
const findings = {
  'deepseek-direct': [{component:'proposed-primary-rating-scale',status:'PARTIAL',quotes:['评分尺度拟采用分级行为锚定量表，先由研究者依据文献与任务样例拟定锚点'],reason:'Only names the future scale construction process; does not provide a current proposed range or primary anchor scheme. The copy already requires a proposed scale.'}],
  'deepseek-plan': [{component:'proposed-primary-rating-scale',status:'COVERED',quotes:['四指标均采用5点李克特量表，1=完全不符合，5=完全符合'],reason:'Provides a basic proposed range and endpoint anchors; full middle-level anchor details are still not supplied.'}],
  'doubao-direct': [{component:'proposed-primary-rating-scale',status:'COVERED',quotes:['拟议采用1‑5分李克特量表'],reason:'Basic proposed range and four dimension definitions are present; anchor details and threshold remain deferred.'}],
  'doubao-plan': [
    {component:'proposed-primary-rating-scale',status:'COVERED',quotes:['各维度采用李克特5级评分【拟议设计，可调整】'],reason:'Basic proposed scale and four dimension definitions are delivered; detailed anchors remain future work.'},
    {component:'four-task-category-primary-design',status:'MISSING',quotes:['4类任务分类框架、每类5项任务的构造来源为待确定参数'],reason:'Copy requires a proposed classification framework, but the work leaves it undetermined and supplies only possible material-source paths.'}
  ]
};
for (const [arm, model, gapIndex, quote] of [
  ['plan',models[0],3,'准确性、完整性、清晰度与可执行性的评分尺度与操作化定义'],
  ['direct',models[1],1,'四项评价维度的操作定义与评分尺度']]) {
  const records = ['doubao','deepseek'].map(vendor => ({ id:`${vendor}-${arm}`,text:works[`${vendor}-${arm}`],inputSha256:lock[arm].sha256,findings:findings[`${vendor}-${arm}`] }));
  await save(`assessment/live-${arm}-coverage.json`, assessmentCoverageRecord(model,copies[arm],[{
    gapIndex,component:'proposed-rating-scale-and-operational-definition-obligation',status:'COVERED',
    reason:'Unlike old candidate12 Plan 220, the new response TASK and literal copy both require a proposed primary scale and operational definitions.',
    evidence:[{sectionType:'TASK',quote}]
  }],records));
}
const hardChecks = Object.fromEntries(workNames.map(name => [name, {
  ...checkResearchLength(works[name]),literalFrozenExecutionInput:true, outgoingPayloadExactMatch:true,
  codeAbsent: !/^\s*```(?:java|python|js|ts|sql|c|cpp)?\s*$/m.test(works[name]),
  inventedObservedNumbersOrReferencesFound:false, observedFactAudit:'Manual inspection of these six saved synthetic-study methods texts, not a universal hallucination detector.',
  strictJson:'NOT_APPLICABLE',pureTranslation:'NOT_APPLICABLE',examplesToggleMatrix:'NOT_COVERED_THIS_BATCH',
  selfReportedCountAcceptedAsEvidence:false
}]));
await save('independent-hard-checks.json',{policy:BODY_HAN_POLICY,works:hardChecks,
  optimizedLengthPasses:4,optimizedWorks:4,allLengthPasses:4,allWorks:6,
  alternativeCountRisk:'deepseek-direct has 1200 body Han and 1239 with headings; primary policy remains fixed, alternative is not silently discarded.',
  completeQualityGatePassed:false});
await save('content-audit.json',{
  type:'Independent source-and-work inspection by the development agent; not a third expert or external AI review',
  sampleScope:'One Q05 work per vendor/arm. Unknown website revision and personalization prevent a pure model-capability conclusion.',
  findings:[
    {id:'CURRENT-MATERIAL-STATE',layer:'PLATFORM',status:'PASS_NARROW_SCOPE',evidence:['models/plan-response.txt','live/plan/copied.md','live/direct/copied.md'],
      reason:'New Plan response already states no additional real data; the copy does not append that state again. New direct response omits it, so the original-rule addition is necessary and appears once. Exact compound 220 replay remains the deterministic fix proof.'},
    {id:'REMAINING-STATE-AND-PENDING-APPENDS',layer:'PLATFORM_AND_ENHANCEMENT',status:'OPEN',evidence:['models/direct-response.txt','live/direct/copied.md','live/plan/copied.md'],
      reason:'Direct BACKGROUND already states current proposed/unimplemented study, yet platform user-rule block appends that status. Plan still appends four delegated question titles with unconfirmed status; some requirements overlap TASK. Model also repeats rules across sections. Keep source records and do not remove pending parameter identity merely because one keyword or global method phrase matches.'},
    {id:'RATING-OBLIGATION',layer:'ENHANCEMENT',status:'PASS_THIS_TWO_RESPONSES',evidence:['assessment/live-plan-coverage.json','assessment/live-direct-coverage.json'],
      reason:'Both new response TASK sections and copies deliver the proposed-scale obligation; prior 220 metadata/body omission did not recur. This is one sample, guidance unchanged, not proof that the prior failure is permanently solved.'},
    {id:'DS-DIRECT-RATING-DELIVERY',layer:'DOWNSTREAM_WITH_INPUT_REDUNDANCY_CAUSALITY_UNRESOLVED',status:'OPEN',evidence:['live/works/deepseek-direct.md','live/direct/copied.md'],
      quote:'评分尺度拟采用分级行为锚定量表，先由研究者依据文献与任务样例拟定锚点',
      reason:'No current primary scoring range/anchor proposal despite the copy requiring it. Failure first appears in the work; the effect of redundant prompting is unknown. Does not compel added instructions.'},
    {id:'DOUBAO-RAW-AND-DIRECT-ALLOCATION',layer:'DOWNSTREAM_WITH_DESIGN_AMBIGUITY',status:'NOT_PASSED',evidence:['live/works/doubao-raw.md','live/works/doubao-direct.md'],
      quote:'将全部20项任务‑重复单元随机打乱后分配至四组',
      reason:'Four-way allocation is not reconciled with every mode/every task/three repetitions. The work states both requirements but does not define a valid balanced generation scheme. Independent groups themselves are not forbidden; the unclear allocation cannot close executable-design gate.'},
    {id:'DS-PLAN-STRUCTURAL-CONTRADICTION',layer:'DOWNSTREAM',status:'FAIL',evidence:['live/works/deepseek-plan.md','live/plan/copied.md'],
      quotes:['增强方式为组间因素','每名被试在每模式下对每任务独立重复3次'],
      reason:'Same work describes a between-participant factor and each participant receiving every mode. The copied prompt does not demand this conflict. Do not diagnose every 2×2 or human-participant proposal as forbidden.'},
    {id:'DOUBAO-PLAN-DELEGATED-COMPLETION',layer:'DOWNSTREAM',status:'NOT_PASSED',evidence:['assessment/live-plan-coverage.json','live/works/doubao-plan.md'],
      quote:'4类任务分类框架、每类5项任务的构造来源为待确定参数',
      reason:'Current copy explicitly asks for a proposed category framework; the work supplies only source alternatives and postpones categories. Actual parameters unconfirmed is legitimate, but does not prevent a proposed primary design.'},
    {id:'INFORMATION-FLOW',layer:'DOWNSTREAM_WITH_INPUT_AND_EVALUATION_AMBIGUITY_OPEN',status:'PARTIAL',evidence:['live/works/doubao-plan.md','live/works/deepseek-plan.md','live/works/deepseek-direct.md'],
      reason:'Enhanced works improve sealing and literal same-information use. Some fail to specify the two fairness-control identities or who asks/answers across all conditions. Doubao Plan uses an external questioner, while platform copy says enhancer asks/user answers; exact actor mapping is under-specified. No evidence that actual answers were edited in the real test: all six outgoing prompts match locked inputs. Do not equate a missing design detail with observed answer tampering.'},
    {id:'BLIND-RATER-COVERAGE',layer:'DOWNSTREAM',status:'PARTIAL',evidence:['live/works/deepseek-direct.md','live/works/deepseek-plan.md','live/works/doubao-plan.md'],
      reason:'Two independent reviewers are retained in these works; candidate12 Flash split-rater sentence did not recur. Doubao direct/raw expose only evaluated prompt in primary blind view, leaving task/answer reference availability unclear; coverage and accurate rubric application need explicit design audit rather than universal new constraints.'},
    {id:'REVIEWER-QUALITY',layer:'EVALUATION',status:'NOT_A_FINAL_GATE',evidence:['live/review-comparison.json'],
      reason:'Both JSON schemas valid. One verdict-direction disagreement is a Doubao label/reason contradiction. Nonliteral/misbound evidence and overgeneralized participant/paired-design assertions remain. Preserve both original reviews; no automatic majority rule or corrected raw reviews.'},
    {id:'LENGTH',layer:'DOWNSTREAM_AND_EVALUATION_SCOPE',status:'PARTIAL_PASS',evidence:['independent-hard-checks.json'],
      reason:'Four optimized works pass frozen body-Han policy; raw Doubao723/raw DeepSeek1682 fail. DeepSeek direct1239 with headings signals counting-boundary sensitivity; neither web self-reported counts nor AI scores override fixed checks.'}
  ],
  acceptancePairs:[
    {left:'doubao-raw',right:'doubao-direct',verdict:'BOTH_FAIL',reason:'Raw length fails; both allocation descriptions do not close each-mode/task repetition self-consistency.'},
    {left:'doubao-raw',right:'doubao-plan',verdict:'BOTH_FAIL',reason:'Raw length fails; Plan does not supply delegated four-category primary design. Relative gains retained separately.'},
    {left:'doubao-direct',right:'doubao-plan',verdict:'BOTH_FAIL',reason:'Direct allocation open/failing and Plan missing primary classification; no compliant winner.'},
    {left:'deepseek-raw',right:'deepseek-direct',verdict:'BOTH_FAIL',reason:'Raw length fails; direct scoring-primary design remains future work, complete delivery gate not passed.'},
    {left:'deepseek-raw',right:'deepseek-plan',verdict:'BOTH_FAIL',reason:'Raw length fails; Plan between/within-mode statement contradicts itself.'},
    {left:'deepseek-direct',right:'deepseek-plan',verdict:'BOTH_FAIL',reason:'Direct primary scale incomplete; Plan structure contradictory. Original AI relative rankings remain untouched.'}
  ],
  strategyDecision:{productionOrGuidanceChangedDuringRealComparison:false,complexInstructionsAdded:false,modelSpecificTemplatesAdded:false,
    qualityRetryUsed:false,pureDownstreamProblemRequiresModification:false,stableBenefitOrPerformanceImprovementProven:false,
    next:'Offline replay 230/231 for residual same-study state and per-object pending append paths; separate target/condition/status source coverage. Evaluate simpler existing guidance only after deterministic platform issues are isolated. Keep independent hard checks and disputed reviews.',
    examplesAndFrontendExpansionAllowedByCurrentGates:false}
});
const account = await load(resolve('tmp/local-acceptance/account.json'));
const references = ['userId','tenantId','workspaceId','identifier'].map(key=>account[key]).filter(Boolean);
const privateKeys = /^(?:planId|planningContext|contextId|userId|tenantId|workspaceId|identifier|password|passwordHash|apiKey|authorization|cookie|set-cookie|sessionToken|accessToken|refreshToken|conversationPath|lateAssignedConversationPath)$/i;
function sanitize(value,key='') {
  if (privateKeys.test(key)) return '[private-reference-redacted]';
  if (Array.isArray(value)) return value.map(v=>sanitize(v));
  if (value && typeof value==='object') return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,sanitize(v,k)]));
  if (typeof value==='string') { for(const ref of references)value=value.replaceAll(ref,'[private-actor-reference-redacted]'); }
  return value;
}
const publication=[];
async function publishFolder(folder) {
  for (const item of await readdir(folder,{withFileTypes:true})) {
    const source=resolve(folder,item.name);
    if(item.isDirectory())await publishFolder(source);
    else {
      const name=relative(live,source).replaceAll('\\','/'),bytes=await readFile(source);
      const exported=name.endsWith('.json')?JSON.stringify(sanitize(JSON.parse(bytes.toString('utf8'))),null,2):bytes;
      await save(`live/${name}`,exported);
      publication.push({path:`live/${name}`,originalSha256:hash(bytes),publishedSha256:hash(exported),exactOriginalBytes:!name.endsWith('.json'),privateJsonSanitization:name.endsWith('.json')});
    }
  }
}
await publishFolder(live);
const metrics=[];
for(const {trace,name} of calls) {
  await save(`traces/${name}`,sanitize(trace));
  if(trace.purpose==='PLATFORM_GENERATION') {
    const arm=name==='230.json'?'plan':'direct';
    await save(`models/${arm}-response.txt`,trace.response.choices[0].message.content);
    await save(`models/${arm}-response.json`,JSON.parse(trace.response.choices[0].message.content));
    metrics.push({attempt:Number.parseInt(name),purpose:trace.purpose,requestedModel:trace.request.model,resolvedResponseModel:trace.response.model,
      parameters:{temperature:trace.request.temperature,max_tokens:trace.request.max_tokens,thinking:trace.request.thinking},providerHttpElapsedMs:trace.elapsedMs,usage:trace.response.usage});
  }else metrics.push({attempt:Number.parseInt(name),purpose:trace.purpose,vendor:trace.vendor,visibleProfile:trace.request.profile,
    observedCompletionUpperBoundMs:trace.observedCompletionUpperBoundMs,actualServerLatency:'UNKNOWN',tokens:'UNKNOWN',hiddenBackendRequests:'UNKNOWN'});
}
for(const file of ['candidate13-q05-cases.json','candidate13-actual-answers.json','candidate13-plan-questions.json','candidate13-plan-input.json'])await save(`fixtures/${file}`,sanitize(await load(resolve(root,file))));
await save('publication-scope.json',{privateJsonKeys:privateKeys.source,webCapture:'Official copied final answer plus rendered HTML/text and response-only screenshot; no login/sidebar/cookie profile exported.',
  wireHeadersAndFullWebResponseBytes:'NOT_CAPTURED',hiddenReasoning:'NOT_CAPTURED',sourceRecordsRetained:true,records:publication});
const apiTokens=metrics.filter(m=>m.usage).reduce((sum,m)=>({prompt:sum.prompt+m.usage.prompt_tokens,completion:sum.completion+m.usage.completion_tokens,total:sum.total+m.usage.total_tokens}),{prompt:0,completion:0,total:0});
await save('provider-metrics.json',{metrics,knownPlatformApiTokens:apiTokens,webTokens:'UNKNOWN',webServerLatency:'UNKNOWN',monetaryCost:'UNKNOWN',
  latencyCaveat:'UI completion observations include polling/development delay and are upper bounds, not measured generation latency. Do not compare as p50/p95.',performanceGatePassed:false});
await save('web-harness-corrections.json',{
  attribution:'EVALUATION_HARNESS',newModelCallsFromCorrections:0,existingModelSubmissionsNotRepeated:true,
  errors:[{kind:'PROSEMIRROR_INNERTEXT_DISPLAY_SPACING',correction:'Compare paragraph serialization and then verify exact outgoing body.'},
    {kind:'WORK_CHECK_IMPORT_PATH',invocations:2,correction:'Use three parent segments from private harness.'},
    {kind:'DOUBAO_TEMPORARY_ROUTE',correction:'Allow only temporary local route to durable route for one pending exact-payload attempt.'},
    {kind:'NEW_CHAT_SELECTOR_AND_ROUTE',correction:'Scope sidebar control; wait for empty /chat or /chat/ and zero response containers.'},
    {kind:'DOUBAO_COPY_IMPLEMENTATION',correction:'Capture text/plain ClipboardItem or legacy selected-text copy in addition to writeText. Failed first interception did not read OS clipboard; fallback write is unknown.'}],
  originalIncorrectZeroLedgerRecord:'pre-io-harness-corrections.json retained; its counter is corrected only in explicit addendum.'
});
await save('post-real-freeze-verification.json',{verifiedAt:new Date().toISOString(),...freezeChecks,noProductionOrGuidanceMutationAfterFreeze:true});
await save('harness-final-hashes.json',Object.fromEntries(await Promise.all(['web-evaluation.mjs','web-collect.mjs','freeze-web-review.mjs','audit-web-reviews.mjs','finalize-web-results.mjs']
  .map(async name=>[name,hash(await readFile(resolve('tmp/me00a-candidate13-20261014/harness',name)))]))));
const closedAt=new Date().toISOString();
const budget={closedAt,authorization:'protocol-amendment-10.json + protocol-amendment-11.json',totalBudgetUnits:239,
  actualByPhase:{baseline:70,candidateValidation:169},formalLimits:{total:263,baseline:80,candidateValidation:183},
  accounting:{apiRequestUnit:'Actual provider request including failure',webUnit:'UI submission attempt including failure',webHiddenBackendCalls:'UNKNOWN',aggregateIsBudgetUnitsNotBackendInferenceCount:true},
  batch:{before:229,after:239,actualNewBudgetUnits:10,maximumNewCalls:14,purposeCounts:counts,apiGenerationRequests:2,webExecutionSubmissions:6,webReviewSubmissions:2,
    planningCalls:0,failedProviderOrUiSubmissions:0,protocolRepairCalls:0,localHarnessErrorsRecordedSeparately:true,unusedBatchAllowance:4,
    unusedClosedFourVendorAllocationTransferred:0,unusedAllowanceTransferAllowed:false,secondRoundAuthorized:false,status:'CLOSED_AFTER_USER_NARROWED_TWO_VENDOR_ONE_ROUND'},
  noPhaseBorrowing:true,stopNewRequestsForThisBatch:true};
await save('budget-final.json',budget);
const oldGate=await load(resolve(target,'gate-status.json'));
await save('gate-status-final.json',{...oldGate,newModelCalls:2,newWebSubmissions:8,newBudgetUnits:10,realEnhancement:'TWO_RESPONSES_SAVED_NO_REPAIR',
  newDatabaseHistory:'TWO_MATCHING_CANONICAL_ACCOUNT_RECORDS',crossModelWorks:'TWO_WEB_VENDORS_SIX_SINGLE_SAMPLES_NOT_FOUR_VENDORS',
  twoIndependentReviews:'TWO_SCHEMA_VALID_RAW_REVIEWS_RETAINED_WITH_EVIDENCE_AND_VERDICT_ERRORS',
  realMaterialState:'PASS_FOR_NEW_PLAN_AND_NECESSARY_DIRECT_FALLBACK_ONLY',ratingObligation:'PRESENT_IN_BOTH_NEW_COPIES_NOT_UNIVERSALLY_PROVEN',
  length:'FOUR_OPTIMIZED_BODY_POLICY_PASS_TWO_RAW_FAIL_ALTERNATIVE_COUNT_RISK_RETAINED',professionalCompleteness:'NOT_PASSED',
  structureAndInformationFlow:'NOT_PASSED',performance:'OPEN_WEB_METRICS_UNKNOWN',allNaturalWordingFusion:'OPEN',examplesAndFrontend:'NOT_EXPANDED',
  G1:'LOCAL_NARROW_BOUNDARIES_PASS_WIDER_WORDING_AND_REPEAT_APPENDS_OPEN',G2:'NEW_NATURAL_PLAN_QUESTIONS_NOT_EVALUATED',
  G3:'TWO_REAL_COPY_HISTORY_MATCHES_WEB_PAYLOADS_MATCH_EDIT_UNDO_LOOP_OPEN',G4:'NOT_PASSED_DESIGN_FAILURES_AND_REVIEW_LIMITATIONS',
  G5:'BODY_LENGTH_SUBITEM_FOUR_OPTIMIZED_PASS_REMAINING_HARD_AND_CONTENT_GATES_OPEN',G6:'CANONICAL_ACCOUNT_REUSED_NO_CREATION_FULL_FRONTEND_OPEN',
  G7:'OPEN_WEB_TOKENS_VERSION_AND_TRUE_LATENCY_UNKNOWN',G8:'LOCAL_AFFECTED_CHECKS_PASS_FULL_RELEASE_OPEN',overallReleased:false,
  examplesAndFrontendExpansionDeferredUntilDependenciesPass:true});
Object.assign(protocol.activeBatch,{status:budget.batch.status,authorizationStatusAtStart:'APPROVED_BY_USER',authorizationStatus:'APPROVED_AND_CLOSED',
  closedAt,callsAfter:239,actualNewCalls:10,actualPurposeCounts:counts,unusedBatchAllowance:4});
await save('protocol-final.json',protocol);
await writeFile(resolve(root,'protocol.json'),JSON.stringify(protocol,null,2));
console.log(JSON.stringify({totalBudgetUnits:239,baseline:70,candidate:169,newBudgetUnits:10,counts,apiTokens,freezeChecks,completeQualityGatePassed:false}));
