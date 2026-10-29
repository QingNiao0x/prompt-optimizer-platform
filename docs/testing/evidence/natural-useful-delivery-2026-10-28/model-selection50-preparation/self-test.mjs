import assert from 'node:assert/strict';
import { validateProtocol, validatePublicResponses } from './verify-model-selection.mjs';

// In-memory negative controls only; never frozen or used as a live response.
const modelId = 'deepseek:deepseek-v4-pro', upstreamModel = 'deepseek-v4-pro';
const endpoint = 'https://api.deepseek.com/chat/completions';
const jobs = ['LOCAL_A', 'LOCAL_B', 'LOCAL_C'].map(id => ({ id, phase: 'GENERATION', version: '50',
  approvedEnhancementInput: { modelId, rawPrompt: 'Local offline control only' } }));
const protocol = { status: 'FROZEN', modelId, upstreamModel, endpoint, versions: ['50'], candidateVersions: ['50'],
  maximumNewActualCalls: 12, phaseLimits: { GENERATION: 12, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  parameters: { temperature: 0.2, generationMaxTokens: 8192, thinking: 'disabled', stream: false },
  qualityRetriesAllowed: false, accountCreationAllowed: false, allowedJobs: jobs.map(job => job.id),
  modelSelectionProfile: { name: 'OFFICIAL_MODEL_SELECTION_ABLATION_V1', modelId, upstreamModel, routeKey: 'deepseek',
    endpoint, baselineStage: 'local-offline-control', baselineJobsSha256: 'a'.repeat(64), baselineProtocolSha256: 'b'.repeat(64), maximumActualCalls: 12 } };
assert.equal(validateProtocol(protocol, jobs), jobs[0]);
const passed = ['finite-profile-positive'];
for (const [label, mutate] of [
  ['no-profile', x => { delete x.protocol.modelSelectionProfile; }],
  ['unknown-profile', x => { x.protocol.modelSelectionProfile.name = 'UNAPPROVED'; }],
  ['third-party-endpoint', x => { x.protocol.modelSelectionProfile.endpoint = 'https://invalid.example/chat'; }],
  ['public-upstream-identity-mismatch', x => { x.protocol.upstreamModel = 'deepseek-flash'; }],
  ['flash-job-in-pro-comparison', x => { x.jobs[0].approvedEnhancementInput.modelId = 'deepseek:deepseek-flash'; }],
  ['different-candidate', x => { x.jobs[0].version = '49'; }],
  ['more-than-three-jobs', x => { x.jobs.push({ ...x.jobs[0], id: 'EXTRA' }); }],
  ['raised-call-cap', x => { x.protocol.phaseLimits.GENERATION = 13; }],
  ['plan-call-in-generation-stage', x => { x.protocol.phaseLimits.PLAN = 1; }],
  ['thinking-changed', x => { x.protocol.parameters.thinking = 'enabled'; }],
]) {
  const input = structuredClone({ protocol, jobs }); mutate(input);
  assert.throws(() => validateProtocol(input.protocol, input.jobs), undefined, label); passed.push(label);
}
for (const [name, phase, cap, caseIds, suffix] of [
  ['NATIVE_PRO50_NORMAL_GEN_V1', 'GENERATION', 20, ['C01', 'C02', 'V01', 'V02', 'V03'], '_V50_PRO_GEN'],
  ['NATIVE_PRO50_PLAN_V1', 'PLAN', 8, ['P01', 'P02'], '_V50_PRO_PLAN'],
  ['NATIVE_PRO50_PLAN_COMPLETION_V1', 'GENERATION', 8, ['P01', 'P02'], '_V50_PRO_CONFIRMED_GEN'],
]) {
  const p = structuredClone(protocol);
  p.modelSelectionProfile = { ...p.modelSelectionProfile, name, maximumActualCalls: cap, baselineStage: 'registry50-development' };
  p.maximumNewActualCalls = cap; p.phaseLimits = { GENERATION: 0, PLAN: 0, EXECUTION: 0, REVIEW: 0 }; p.phaseLimits[phase] = cap;
  const j = caseIds.map(caseId => ({ id: caseId + suffix, caseId, phase, version: '50',
    approvedEnhancementInput: { modelId, rawPrompt: 'Local finite profile selftest only' } }));
  p.allowedJobs = j.map(job => job.id);
  assert.equal(validateProtocol(p, j), j[0]); passed.push(name + '-positive-first-job-any-supported-phase');
  for (const [label, mutate] of [
    ['wrong-id', x => { x.jobs[0].id = 'ARBITRARY_OTHER'; x.protocol.allowedJobs[0] = 'ARBITRARY_OTHER'; }],
    ['wrong-phase', x => { x.jobs[0].phase = phase === 'PLAN' ? 'GENERATION' : 'PLAN'; }],
    ['raised-cap', x => { x.protocol.maximumNewActualCalls += 1; x.protocol.modelSelectionProfile.maximumActualCalls += 1; x.protocol.phaseLimits[phase] += 1; }],
  ]) {
    const input = structuredClone({ protocol: p, jobs: j }); mutate(input);
    assert.throws(() => validateProtocol(input.protocol, input.jobs)); passed.push(name + '-' + label);
  }
  if (name === 'NATIVE_PRO50_NORMAL_GEN_V1') {
    const unchangedOld = structuredClone(protocol); unchangedOld.allowedJobs = j.map(job => job.id);
    assert.throws(() => validateProtocol(unchangedOld, j)); passed.push('original-ablation-still-rejects-five-normal-jobs');
  }
}
const account = { userId: 'LOCAL_USER', tenantId: 'LOCAL_TENANT', workspaceId: 'LOCAL_WORKSPACE' };
const selection = { modelId, upstreamModel, routeKey: 'deepseek', endpoint, defaultModel: false,
  catalogDefaultModelIds: ['deepseek:deepseek-flash'] };
const catalog = [{ id: modelId, provider: 'deepseek', defaultModel: false },
  { id: 'deepseek:deepseek-flash', provider: 'deepseek', defaultModel: true }];
const safe = validatePublicResponses(account, { ...account, unusedPrivateField: 'must-not-be-projected' }, catalog, selection);
assert.ok(!JSON.stringify(safe).includes('LOCAL_USER')); assert.ok(!Object.hasOwn(safe, 'unusedPrivateField'));
assert.equal(validatePublicResponses(account, account, catalog, selection, safe).defaultsUnchangedFromBefore, true);
passed.push('identity-not-saved', 'unchanged-defaults-positive');
const proDefault = { ...selection, defaultModel: true, catalogDefaultModelIds: [modelId] };
assert.equal(validatePublicResponses(account, account, catalog.map(entry => ({ ...entry, defaultModel: entry.id === modelId })), proDefault).defaultModel, true);
passed.push('actual-default-true-accepted-without-forcing-false');
for (const [label, mutate] of [
  ['wrong-identity', x => { x.identity.userId = 'OTHER'; }],
  ['missing-public-model', x => { x.catalog.shift(); }],
  ['wrong-catalog-provider', x => { x.catalog[0].provider = 'OTHER'; }],
  ['wrong-native-route', x => { x.selection.routeKey = 'OTHER'; }],
  ['secret-field-in-route-projection', x => { x.selection.apiKey = 'LOCAL_TEST_NEVER_REAL'; }],
  ['wrong-default-projection', x => { x.selection.catalogDefaultModelIds = [modelId]; }],
  ['defaults-changed-after-check', x => { x.before = { catalogDefaultModelIds: [modelId] }; }],
]) {
  const input = structuredClone({ identity: account, catalog, selection, before: safe }); mutate(input);
  assert.throws(() => validatePublicResponses(account, input.identity, input.catalog, input.selection, input.before), undefined, label); passed.push(label);
}
console.log(JSON.stringify({ kind: 'OFFLINE_MODEL_SELECTION_GUARD_SELFTEST', checksPassed: passed.length, checks: passed,
  actualModelCalls: 0, actualHttpRequests: 0, serviceStarted: false, liveResultsWritten: false }, null, 2));
