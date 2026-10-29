import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

const here = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
const origin = 'http://127.0.0.1:9014';
const originalProfileName = 'OFFICIAL_MODEL_SELECTION_ABLATION_V1';
// Each future profile has a fixed job set and phase allowance. Adding Plan
// inspection does not enlarge the original closed three-GEN ablation contract.
const profiles = Object.freeze({
  [originalProfileName]: { phase: 'GENERATION', cap: 12, count: 3 },
  NATIVE_PRO50_NORMAL_GEN_V1: { phase: 'GENERATION', cap: 20, count: 5,
    ids: ['C01', 'C02', 'V01', 'V02', 'V03'].map(id => id + '_V50_PRO_GEN') },
  NATIVE_PRO50_PLAN_V1: { phase: 'PLAN', cap: 8, count: 2,
    ids: ['P01_V50_PRO_PLAN', 'P02_V50_PRO_PLAN'] },
  NATIVE_PRO50_PLAN_COMPLETION_V1: { phase: 'GENERATION', cap: 8, count: 2,
    ids: ['P01_V50_PRO_CONFIRMED_GEN', 'P02_V50_PRO_CONFIRMED_GEN'] },
});
const modelId = 'deepseek:deepseek-v4-pro', upstreamModel = 'deepseek-v4-pro';
const endpoint = 'https://api.deepseek.com/chat/completions';
const json = async file => JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const absent = async (file, message) => {
  try { await stat(file); throw Error(message); } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
};

/** Finite protocol guard; default catalog membership is not permission to change the frozen model. */
export function validateProtocol(protocol, jobs) {
  const check = (condition, reason) => assert.ok(condition, reason);
  check(protocol.status === 'FROZEN', 'Stage is not frozen');
  const profile = protocol.modelSelectionProfile;
  check(profile && Object.hasOwn(profiles, profile.name), 'An explicitly supported finite native Pro profile is required');
  const specification = profiles[profile.name];
  for (const [key, expected] of Object.entries({ modelId, upstreamModel, routeKey: 'deepseek', endpoint, maximumActualCalls: specification.cap })) {
    check(profile[key] === expected, 'Frozen model-selection profile differs from the approved finite route');
  }
  check(protocol.modelId === modelId && protocol.upstreamModel === upstreamModel && protocol.endpoint === endpoint,
    'Top-level route differs from the approved profile');
  check(protocol.maximumNewActualCalls === specification.cap, 'Stage call allowance differs from this finite profile');
  for (const phase of ['GENERATION', 'PLAN', 'EXECUTION', 'REVIEW']) {
    check(protocol.phaseLimits?.[phase] === (phase === specification.phase ? specification.cap : 0), 'Unexpected phase allowance');
  }
  check(isDeepStrictEqual(protocol.versions, ['50']) && isDeepStrictEqual(protocol.candidateVersions, ['50']), 'Only candidate50 may use this profile');
  check(protocol.parameters?.temperature === 0.2 && protocol.parameters?.generationMaxTokens === 8192
    && protocol.parameters?.thinking === 'disabled' && protocol.parameters?.stream === false, 'Frozen generation parameters differ');
  check(protocol.qualityRetriesAllowed === false && protocol.accountCreationAllowed === false, 'Retry/account boundaries differ');
  check(Array.isArray(jobs) && jobs.length === specification.count, 'Frozen job count differs from this finite profile');
  check(new Set(jobs.map(job => job.id)).size === specification.count, 'Job IDs must be distinct');
  check(isDeepStrictEqual(protocol.allowedJobs, jobs.map(job => job.id)), 'Allowed jobs must preserve frozen job order');
  if (specification.ids) check(isDeepStrictEqual([...jobs.map(job => job.id)].sort(), [...specification.ids].sort()), 'Unexpected job in this finite normal/Plan profile');
  for (const job of jobs) {
    check(/^[A-Z0-9_-]+$/.test(job.id) && job.phase === specification.phase && job.version === '50', 'Unexpected job phase or candidate');
    if (specification.ids) check(job.caseId === job.id.split('_V50_')[0], 'Job and finite case ID differ');
    check(job.approvedEnhancementInput?.modelId === modelId && typeof job.approvedEnhancementInput.rawPrompt === 'string',
      'Actual frozen input must explicitly choose the approved Pro model');
    check(job.entrypoint === undefined, 'This comparison is a candidate entrypoint, not a default switch');
  }
  check(/^[a-z0-9-]+$/.test(profile.baselineStage ?? ''), 'A finite baseline stage is required');
  if (specification.ids) check(profile.baselineStage === 'registry50-development', 'New finite profiles must reference the closed Flash50 baseline');
  for (const key of ['baselineJobsSha256', 'baselineProtocolSha256']) check(/^[a-f0-9]{64}$/.test(profile[key] ?? ''), 'Baseline hash is missing');
  return jobs[0];
}

/** Returns only explicitly safe public fields; no raw identity/catalog/session is returned. */
export function validatePublicResponses(account, identity, catalog, selection, before) {
  assert.ok(['userId', 'tenantId', 'workspaceId'].every(key => account[key] && identity?.[key] === account[key]),
    'Real authenticated identity does not match the fixed synthetic account');
  assert.ok(Array.isArray(catalog), 'Public model catalog must be an array');
  const model = catalog.filter(entry => entry.id === modelId);
  assert.ok(model.length === 1 && model[0].provider === 'deepseek' && typeof model[0].defaultModel === 'boolean',
    'Approved public model is missing or belongs to a different provider');
  assert.ok(selection && typeof selection === 'object' && !Array.isArray(selection), 'Safe route projection is required');
  assert.ok(isDeepStrictEqual(Object.keys(selection).sort(),
    ['modelId', 'upstreamModel', 'routeKey', 'endpoint', 'defaultModel', 'catalogDefaultModelIds'].sort()),
  'Unexpected route projection fields; raw response will not be saved');
  assert.ok(selection.modelId === modelId && selection.upstreamModel === upstreamModel
    && selection.routeKey === 'deepseek' && selection.endpoint === endpoint, 'Resolved native catalog route differs from the frozen profile');
  assert.ok(typeof selection.defaultModel === 'boolean' && selection.defaultModel === model[0].defaultModel,
    'Resolved model default flag differs from the normal public catalog');
  assert.ok(Array.isArray(selection.catalogDefaultModelIds) && selection.catalogDefaultModelIds.every(id => typeof id === 'string')
    && new Set(selection.catalogDefaultModelIds).size === selection.catalogDefaultModelIds.length, 'Invalid public default model IDs');
  const defaults = catalog.filter(entry => entry.defaultModel === true).map(entry => entry.id).sort();
  assert.ok(isDeepStrictEqual([...selection.catalogDefaultModelIds].sort(), defaults), 'Route default IDs differ from the normal public catalog');
  if (before) assert.ok(isDeepStrictEqual(before.catalogDefaultModelIds, defaults), 'Catalog defaults changed between before and after checks');
  return { modelId, upstreamModel, routeKey: 'deepseek', endpoint, defaultModel: selection.defaultModel,
    catalogDefaultModelIds: defaults, fixedIdentityMatched: true, publicCatalogModelFound: true,
    nativeCatalogSelectionMatched: true, defaultsUnchangedFromBefore: before ? true : null };
}

async function main() {
  if (process.argv.includes('--help')) {
    console.log('node verify-model-selection.mjs <future-frozen-stage> [--label BEFORE|AFTER] [--execute-get]. Default offline preflight. Future execute-get uses exactly three loopback GETs; no login, POST, model call or trace. Requires LOGIN_READY and exclusive active-job access.');
    return;
  }
  const stage = process.argv[2]; assert.match(stage ?? '', /^[a-z0-9-]+$/);
  const flags = process.argv.slice(3);
  const labelIndex = flags.indexOf('--label');
  const label = labelIndex < 0 ? 'BEFORE' : flags[labelIndex + 1];
  assert.ok(['BEFORE', 'AFTER'].includes(label));
  const allowedArgs = new Set(['--execute-get', '--label', label]);
  assert.ok(flags.every(flag => allowedArgs.has(flag)), 'Unknown argument');
  assert.ok(flags.filter(flag => flag === '--execute-get').length <= 1 && flags.filter(flag => flag === '--label').length <= 1);
  const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', stage), stageEvidence = resolve(evidence, stage);
  for (const directory of [evidence, dirname(runtime), runtime, stageEvidence]) await absent(resolve(directory, 'closure.json'), 'Closed task/stage cannot be probed by this live tool');
  const protocolBytes = await readFile(resolve(stageEvidence, 'protocol.json'));
  const jobsBytes = await readFile(resolve(stageEvidence, 'jobs.json'));
  const protocol = JSON.parse(protocolBytes), jobs = JSON.parse(jobsBytes), firstJob = validateProtocol(protocol, jobs);
  const profileName = protocol.modelSelectionProfile.name;
  const resultPath = resolve(runtime, 'results', label === 'BEFORE' ? 'MODEL_SELECTION_CHECK.json' : 'MODEL_SELECTION_CHECK_AFTER.json');
  await absent(resultPath, 'Safe check result already exists; never overwrite it');
  let before;
  if (label === 'AFTER') {
    before = await json(resolve(runtime, 'results/MODEL_SELECTION_CHECK.json'));
    assert.ok(before.label === 'BEFORE' && before.profile === profileName && before.status === 200,
      'A successful same-stage before-check is required');
    assert.ok(before.protocolSha256 === hash(protocolBytes) && before.jobsSha256 === hash(jobsBytes), 'Frozen stage changed after the before-check');
  }
  if (!flags.includes('--execute-get')) {
    console.log(JSON.stringify({ event: 'MODEL_SELECTION_CHECK_PREFLIGHT', stage, label, profile: profileName,
      modelCalls: 0, httpRequests: 0, requiresExecuteGet: true })); return;
  }
  // Only the fixed private session is read. Cookies remain in memory and are
  // never copied to evidence, error messages, stdout, command arguments or traces.
  const account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
  const session = await json(resolve(runtime, 'auth/session.json'));
  assert.ok(Array.isArray(session.cookies) && session.cookies.length > 0, 'Existing private session is required; this tool never logs in');
  assert.ok(session.cookies.every(entry => Array.isArray(entry) && entry.length === 2 && entry.every(value => typeof value === 'string')
    && /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(entry[0]) && !/[\r\n;]/.test(entry[1])), 'Invalid private cookie representation');
  const cookie = session.cookies.map(([name, value]) => name + '=' + value).join('; ');
  let requests = 0;
  const get = async (path, guarded = false) => {
    assert.ok(['/api/v1/auth/me', '/api/v1/models', '/api/v2/evaluation/model-selection'].includes(path));
    requests += 1; assert.ok(requests <= 3, 'Unexpected additional GET blocked');
    const response = await fetch(origin + path, { method: 'GET', redirect: 'manual',
      headers: { Cookie: cookie, ...(guarded ? { 'X-Evaluation-Job': firstJob.id } : {}) }, signal: AbortSignal.timeout(30000) });
    assert.ok(response.status === 200, 'Read-only loopback model-selection GET failed');
    const body = await response.json(); assert.ok(body && Object.hasOwn(body, 'data'), 'Safe API envelope missing');
    return body.data;
  };
  const identity = await get('/api/v1/auth/me');
  assert.ok(['userId', 'tenantId', 'workspaceId'].every(key => identity?.[key] === account[key] && account[key]), 'Fixed identity check failed');
  const catalog = await get('/api/v1/models');
  await writeFile(resolve(runtime, 'active-job.json'), JSON.stringify(firstJob, null, 2) + '\n');
  const selection = await get('/api/v2/evaluation/model-selection', true);
  const safe = validatePublicResponses(account, identity, catalog, selection, before);
  await mkdir(dirname(resultPath), { recursive: true });
  await writeFile(resultPath, JSON.stringify({ id: label === 'BEFORE' ? 'MODEL_SELECTION_CHECK' : 'MODEL_SELECTION_CHECK_AFTER',
    label, stage, profile: profileName, status: 200, ...safe, checkedAt: new Date().toISOString(),
    protocolSha256: hash(protocolBytes), jobsSha256: hash(jobsBytes), guardedJobId: firstJob.id,
    httpRequests: requests, methods: ['GET', 'GET', 'GET'], modelCalls: 0, tracesWritten: false,
    catalogMutationPerformed: false, rawSessionOrIdentitySaved: false }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ event: 'MODEL_SELECTION_CHECK_COMPLETE', stage, label, profile: profileName,
    modelId, httpRequests: requests, modelCalls: 0, catalogMutationPerformed: false }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => {
    // Do not print a fetch Response, headers, raw server body, account or session.
    // JSON parser/network errors can quote malformed private data; even their
    // message text is intentionally omitted from this public diagnostic.
    console.error(JSON.stringify({ event: 'MODEL_SELECTION_CHECK_FAILED',
      reason: 'Frozen-profile or read-only local check failed; raw exception omitted to protect private session data.' })); process.exitCode = 1;
  });
}
