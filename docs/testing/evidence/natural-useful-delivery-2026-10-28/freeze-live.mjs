import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, readdir, stat } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateWriterThinkingBundle } from './writer-thinking-profile.mjs';
import { validateOfficialModelSelectionBundle } from './official-model-selection-profile.mjs';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28');
const stage = process.argv[2];
const bundlePath = process.argv[3];
if (!/^[a-z0-9-]+$/.test(stage ?? '') || !bundlePath) throw Error('Usage: node freeze-live.mjs <stage> <bundle-relative-to-repository.json>');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const saveNew = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
for (const path of [evidence, runtime]) {
  try { await stat(resolve(path, 'closure.json')); throw Error('Task already closed'); }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
}
const bundle = await json(resolve(repo, bundlePath));
const captureOnly = bundle.captureOnly === true;
if (bundle.syntheticOnly !== true || !Array.isArray(bundle.cases) || !Array.isArray(bundle.jobs)
    || (!bundle.jobs.length && !captureOnly) || (captureOnly && bundle.jobs.length)) throw Error('Frozen synthetic cases and jobs required; capture-only stages must have no model jobs');
const ids = new Set();
const caseIds = new Set();
for (const sample of bundle.cases) {
  if (!/^[A-Z0-9_-]+$/.test(sample.id ?? '') || caseIds.has(sample.id) || typeof sample.rawPrompt !== 'string' || !sample.rawPrompt.trim()) throw Error('Invalid or duplicate frozen case');
  caseIds.add(sample.id);
}
// Zero-call UI preparation may declare a finite runtime version without inventing a model job or fake context.
if (bundle.captureVersions !== undefined && (!captureOnly || !Array.isArray(bundle.captureVersions)
    || new Set(bundle.captureVersions).size !== bundle.captureVersions.length)) throw Error('Capture versions require a zero-call capture-only stage');
const versions = captureOnly ? [...(bundle.captureVersions ?? [])] : [...new Set(bundle.jobs.filter(job => job.version !== 'ORIGINAL').map(job => job.version))];
if ((!versions.length && !captureOnly) || versions.some(version => !['24', '31', '45', '46', '47', '48', '49', '50', '51', '52', '53', '54', '55', '56', '57', '58', '59', '60', '61', '62', '63', '64', '65', '66', '67', '68', '69', '70'].includes(version))) throw Error('Unknown candidate version');
if (versions.includes('59') && versions.some(version => ['55','56','57','58','60'].includes(version))) throw Error('Different reasoning profiles need separate frozen stages');
if (versions.includes('60') && versions.some(version => !['31','60'].includes(version))) throw Error('Finite responsibility comparison uses60 with optional31 only');
if (versions.includes('61') && versions.some(version => !['31','61'].includes(version))) throw Error('Content-first profile uses61 with optional31 only');
if (versions.includes('62') && versions.some(version => !['31','62'].includes(version))) throw Error('Effective-answer body-first profile uses62 with optional31 only');
if (versions.includes('63') && versions.some(version => !['31','63'].includes(version))) throw Error('Source-reference body-first profile uses63 with optional31 only');
if (versions.includes('64') && versions.some(version => !['31','64'].includes(version))) throw Error('Prepared body profile uses64 with optional31 only');
if (versions.includes('65') && versions.some(version => !['31','65'].includes(version))) throw Error('Current requirements profile uses65 with optional31 only');
if (versions.includes('66') && versions.some(version => !['31','66'].includes(version))) throw Error('Content exemplar profile uses66 with optional31 only');
if (versions.includes('67') && versions.some(version => !['31','67'].includes(version))) throw Error('Completion preparation profile uses67 with optional31 only');
if (versions.includes('68') && versions.some(version => !['31','68'].includes(version))) throw Error('Corrected completion preparation profile uses68 with optional31 only');
if (versions.includes('69') && versions.some(version => !['31','69','70'].includes(version))) throw Error('Diagnostic source role profile uses69 with optional31 only');
if (versions.includes('70') && versions.some(version => !['31','69','70'].includes(version))) throw Error('Writer input projection70 compares with69 (optional original31 only)');
if (versions.includes('69') && versions.includes('70') && bundle.jobs.some(job => ['GENERATION','PLAN'].includes(job.phase))) throw Error('Freeze native69 and70 generations separately; each version has its own exact inference profile');
// 54首层只开放两种真实Plan状态，不将单调用候选扩成新的模型或任务矩阵。
if (versions.includes('54') && (versions.length !== 1 || bundle.modelSelectionProfile !== undefined
    || bundle.experimentProfile !== undefined || bundle.jobs.some(job => !['P02_CONFIRMED', 'P02_UNKNOWN'].includes(job.caseId)
      || !['PLAN', 'GENERATION'].includes(job.phase)
      || ![`${job.caseId}_V54_${job.phase === 'PLAN' ? 'PLAN' : 'GEN'}`,
        `${job.caseId}_V54_${job.phase === 'PLAN' ? 'PLAN' : 'GEN'}_RECOVERY`].includes(job.id))
    || bundle.jobs.length > 1 || (bundle.phaseLimits?.PLAN ?? 0) > 4 || (bundle.phaseLimits?.GENERATION ?? 0) > 2))
  throw Error('54 is limited to one fresh Plan or single-pass completion job per stage');
if (versions.includes('54') && bundle.jobs.some(job => job.id.endsWith('_RECOVERY'))
    && ((bundle.phaseLimits?.PLAN ?? 0) > 4 || bundle.jobs.some(job => job.caseId !== 'P02_CONFIRMED')))
  throw Error('54 interruption recovery retains confirmed Plan4 plus generation2 allowance');
if (bundle.jobs.some(job => job.version === '53' && job.phase !== 'EXECUTION')) throw Error('53 is limited to exact-approved mechanism EXEC; product GEN/Plan is not opened');
if (bundle.jobs.some(job => job.version === '24' && job.phase !== 'EXECUTION')) throw Error('24 is limited to exact-approved single-call mechanism EXEC');
if (bundle.reviewProtocol !== undefined && (versions.length !== 1 || versions[0] !== '24'
    || JSON.stringify(bundle.reviewProtocol) !== JSON.stringify({ inspectionOrder: ['OPTIMIZED_PROMPT', 'TASK_RECORD'],
      historicalEvidenceIsNotCurrentInstruction: true, responsibilitiesReportedSeparately: true,
      stopOnSevereViolationOrOriginalProtectionFailure: true }))) throw Error('Unexpected review protocol outside finite24 mechanism');
const writerThinking = bundle.experimentProfile === undefined ? null : await validateWriterThinkingBundle(bundle, { repo, evidence });
const nativeModelSelection = bundle.modelSelectionProfile === undefined ? null
  : bundle.modelSelectionProfile.name === 'NATIVE_PRO52_PREPARE_MECHANISM_V1'
    ? await (await import('./native-pro52-profile.mjs')).validateNativePro52Bundle(bundle, { repo, evidence })
  : await validateOfficialModelSelectionBundle(bundle, { repo, evidence });
const executionTokenLimits = [...new Set(bundle.jobs.filter(job => job.phase === 'EXECUTION').map(job => job.approvedModelRequest?.max_tokens))];
if (executionTokenLimits.length > 1) throw Error('Same-stage execution arms must use one frozen output-token limit');
const executionMaxTokens = bundle.parameters?.executionMaxTokens ?? bundle.executionMaxTokens ?? executionTokenLimits[0] ?? 8192;
const reviewMaxTokens = bundle.parameters?.reviewMaxTokens ?? bundle.reviewMaxTokens ?? 8192;
const validOutputBudget = value => Number.isInteger(value) && value > 0 && value <= 32768;
if (!validOutputBudget(executionMaxTokens) || !validOutputBudget(reviewMaxTokens)) throw Error('Frozen execution or review token limit must be one positive integer at most 32768');
if (bundle.parameters?.temperature !== undefined && bundle.parameters.temperature !== 0.2
    || bundle.parameters?.generationMaxTokens !== undefined && bundle.parameters.generationMaxTokens !== 8192
    || bundle.parameters?.stream !== undefined && bundle.parameters.stream !== false
    || bundle.parameters?.thinking !== undefined && bundle.parameters.thinking !== 'disabled') throw Error('Generation and model parameters remain frozen');
for (const job of bundle.jobs) {
  if (!/^[A-Z0-9_-]+$/.test(job.id ?? '') || ids.has(job.id)) throw Error('Invalid or duplicate job');
  ids.add(job.id);
  if (!caseIds.has(job.caseId)) throw Error('Unknown frozen case');
  if (!['GENERATION', 'PLAN', 'EXECUTION', 'REVIEW'].includes(job.phase)) throw Error('Unknown model phase');
  if (['EXECUTION', 'REVIEW'].includes(job.phase)) {
    if (!job.approvedModelRequest || job.version !== 'ORIGINAL' && !versions.includes(job.version)) throw Error('Frozen same-information execution request required');
    const payload = job.approvedModelRequest;
    if (payload.model !== (nativeModelSelection?.upstreamModel ?? 'deepseek-flash') || payload.temperature !== 0.2 || payload.stream !== false
        || !writerThinking && (payload.thinking?.type !== 'disabled' || payload.reasoning_effort !== undefined)) throw Error('Execution model parameters differ from the approved route');
    if (payload.max_tokens !== (job.phase === 'REVIEW' ? reviewMaxTokens : executionMaxTokens)) throw Error('Approved model request and declared token limit differ');
  } else {
    if (!versions.includes(job.version) || !job.approvedEnhancementInput) throw Error('Frozen actual enhancement input and version required');
    if (job.approvedEnhancementInput.rawPrompt !== bundle.cases.find(sample => sample.id === job.caseId).rawPrompt) throw Error('Case and input raw prompt differ');
    if (job.approvedEnhancementInput.modelId !== undefined && job.approvedEnhancementInput.modelId !== (nativeModelSelection?.modelId ?? 'deepseek:deepseek-flash')) throw Error('Enhancement model differs from the finite frozen native model profile');
    if (job.entrypoint !== undefined && job.entrypoint !== 'DEFAULT') throw Error('Unknown entrypoint');
  }
}
const phases = ['GENERATION', 'PLAN', 'EXECUTION', 'REVIEW'];
const phaseLimits = Object.fromEntries(phases.map(phase => [phase, bundle.phaseLimits?.[phase] ?? 0]));
if (phases.some(phase => !Number.isInteger(phaseLimits[phase]) || phaseLimits[phase] < bundle.jobs.filter(job => job.phase === phase).length)) throw Error('Phase allowance must cover all frozen jobs');
const maximumNewActualCalls = Object.values(phaseLimits).reduce((sum, value) => sum + value, 0);
if ((!captureOnly && maximumNewActualCalls < 1) || (captureOnly && maximumNewActualCalls !== 0)
    || maximumNewActualCalls > 500) throw Error('Invalid stage call allowance');
let actual = 0;
try {
  for (const directory of await readdir(runtime, { withFileTypes: true })) {
    if (!directory.isDirectory()) continue;
    try { actual += (await readdir(resolve(runtime, directory.name, 'traces'))).filter(name => name.endsWith('.json')).length; }
    catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  }
} catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
if (maximumNewActualCalls > 500 - actual) throw Error('Stage exceeds remaining task budget');
const task = { status: 'FROZEN', taskId: 'natural-useful-delivery-2026-10-28', selectedMaximumActualCalls: 500,
  previousActualCalls: 0, maximumNewActualCalls: 500,
  authorizationSource: 'User explicitly authorized this automated implementation and at most 500 actual model calls on 2026-10-28; old closed tasks remain unchanged.',
  destination: 'https://api.deepseek.com/chat/completions', syntheticOnly: true,
  qualityRetriesAllowed: false, accountCreationAllowed: false, actualAttemptCounting: 'Reserve before every upstream attempt, including scope/body/Plan/execution/protocol repair/failure/paid review; incomplete reservations consume allowance.' };
try { await saveNew(resolve(evidence, 'task-protocol.json'), task); }
catch (failure) {
  if (failure.code !== 'EEXIST') throw failure;
  const existing = await json(resolve(evidence, 'task-protocol.json'));
  if (JSON.stringify(existing) !== JSON.stringify(task)) throw Error('Frozen task protocol differs');
}
const stageRoot = resolve(evidence, stage);
try { await stat(resolve(stageRoot, 'protocol.json')); throw Error('Stage already frozen; closed stages cannot be reopened'); }
catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
await mkdir(stageRoot, { recursive: true });
await saveNew(resolve(stageRoot, 'cases.json'), { syntheticOnly: true, cases: bundle.cases });
await saveNew(resolve(stageRoot, 'jobs.json'), bundle.jobs);
if (writerThinking) {
  await mkdir(resolve(stageRoot, 'writer-sources'), { recursive: true });
  for (const source of writerThinking.sources) await writeFile(resolve(stageRoot, source.snapshot), source.bytes, { flag: 'wx' });
}
await saveNew(resolve(stageRoot, 'protocol.json'), { batch: `natural-useful-delivery-${stage}-2026-10-28`, status: 'FROZEN',
  taskMaximumActualCalls: 500, maximumNewActualCalls, captureOnly, versions, candidateVersions: versions.filter(version => version !== '31'),
  modelId: nativeModelSelection?.modelId ?? 'deepseek:deepseek-flash', upstreamModel: nativeModelSelection?.upstreamModel ?? 'deepseek-flash', endpoint: task.destination,
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens,
    reviewMaxTokens: phaseLimits.REVIEW ? reviewMaxTokens : 0, thinking: 'disabled', stream: false },
  generationPayloadVersions: { '31': ['31', '4'], '45': ['45'], '46': ['46'], '47': ['47'], '48': ['48'], '49': ['49'], '50': ['50'], '51': ['51'], '52': ['52'], '54': ['54'] }, phaseLimits,
  prerequisiteStages: bundle.prerequisiteStages ?? [], allowedJobs: bundle.jobs.map(job => job.id),
  ...(bundle.reviewProtocol ? { reviewProtocol: bundle.reviewProtocol } : {}),
  ...(writerThinking ? { experimentProfile: bundle.experimentProfile, writerThinkingProfile: writerThinking.profile } : {}),
  ...(nativeModelSelection ? { modelSelectionProfile: nativeModelSelection } : {}),
  ...(versions.some(version => ['55', '56', '57', '58', '60'].includes(version)) ? { naturalInferenceProfile: { name: 'SINGLE_PASS_REASONING_V1', version: versions.includes('60') ? '60' : versions.includes('58') ? '58' : versions.includes('57') ? '57' : versions.includes('56') ? '56' : '55',
    generationMaxTokens: 32768, thinking: 'enabled', reasoningEffort: 'high', readTimeoutSeconds: 180,
    runnerTimeoutMs: 420000, planMaxTokens: 8192, planThinking: 'disabled' } } : {}),
  ...(versions.some(version=>['59','61','62','63','64','65','66','67','68','69','70'].includes(version)) ? { naturalInferenceProfile: { name: 'BODY_FIRST_REASONING_V1', version: versions.includes('70') ? '70' : versions.includes('69') ? '69' : versions.includes('68') ? '68' : versions.includes('67') ? '67' : versions.includes('66') ? '66' : versions.includes('65') ? '65' : versions.includes('64') ? '64' : versions.includes('63') ? '63' : versions.includes('62') ? '62' : versions.includes('61') ? '61' : '59',
    generationMaxTokens: 32768, thinking: 'enabled', reasoningEffort: 'high', readTimeoutSeconds: 180,
    runnerTimeoutMs: versions.some(version=>['62','63','64','65','66','67','68','69','70'].includes(version)) ? 1140000 : 780000, planMaxTokens: 8192, planThinking: 'disabled' } } : {}),
  frozenBundleSha256: createHash('sha256').update(await readFile(resolve(repo, bundlePath))).digest('hex'),
  qualityRetriesAllowed: false, accountCreationAllowed: false });
console.log(JSON.stringify({ stage, jobs: bundle.jobs.length, callsMade: 0, stageMaximum: maximumNewActualCalls,
  taskActual: actual, taskMaximum: 500, remainingTaskMaximum: 500 - actual, endpoint: task.destination }));
