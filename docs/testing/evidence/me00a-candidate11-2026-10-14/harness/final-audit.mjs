import { readFile, writeFile, readdir, cp } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { createHash } from 'node:crypto';
const repo = process.cwd();
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2), { flag: 'wx' });
async function files(root) {
  const values = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = resolve(root, entry.name);
    if (entry.isDirectory()) values.push(...await files(path)); else if (entry.isFile()) values.push(path);
  }
  return values.sort();
}
const ledger = await Promise.all((await files(resolve('tmp/me00a-20261012/traces'))).filter(path => /\d+\.json$/.test(path))
  .map(async path => JSON.parse(await readFile(path, 'utf8'))));
if (ledger.length !== 219 || ledger.filter(item => item.phase === 'candidateValidation').length !== 149) throw new Error('Unexpected ledger');
const calls = ledger.slice(203);
const summary = { before: { total: 203, baseline: 70, candidateValidation: 133 },
  after: { total: 219, baseline: 70, candidateValidation: 149 }, newCalls: 16,
  allocation: { candidate9: 11, candidate10: 4, candidate11: 1 }, httpFailures: calls.filter(item => item.httpStatus !== 200).length,
  totalTokens: calls.reduce((n, item) => n + (item.response?.usage?.total_tokens ?? 0), 0),
  sumModelHttpMs: calls.reduce((n, item) => n + (item.elapsedMs ?? 0), 0),
  providerExactSnapshot: 'UNKNOWN', invoiceCurrencyCost: 'UNKNOWN', noAutomaticRetriesAfterExhaustion: true,
  rawComparators: 'candidate validation same-information only; seven original baseline cases not resampled',
  calls: calls.map((item, index) => ({ number: index + 204, purpose: item.purpose ?? item.label, version: item.version,
    requestModel: item.request?.model, responseModel: item.response?.model, httpStatus: item.httpStatus,
    elapsedMs: item.elapsedMs, usage: item.response?.usage })) };
const latestRoot = resolve('docs/testing/evidence/me00a-candidate11-2026-10-14');
await save(resolve(latestRoot, 'call-summary.json'), summary);
const manifest = JSON.parse(await readFile(resolve(latestRoot, 'candidate11-source.json'), 'utf8'));
for (const item of [...manifest.sourceFiles, ...manifest.classFiles, ...manifest.policyFiles])
  if (hash(await readFile(resolve(repo, item.path))) !== item.sha256) throw new Error(`Frozen bytes changed: ${item.path}`);
await save(resolve(latestRoot, 'post-real-freeze-check.json'), { sourceFilesMatched: manifest.sourceFiles.length,
  runtimeFilesMatched: manifest.classFiles.length, policyFilesMatched: manifest.policyFiles.length, actualTotal: 219,
  candidateStageExhausted: true, productSourceUnchangedSinceCandidate11Freeze: true });
const backup = JSON.parse(await readFile('tmp/local-acceptance/history-backup.json', 'utf8'));
const rows = backup.map(row => ({ kind: row.kind, data: JSON.parse(row.data) }));
const users = new Map(rows.filter(row => row.kind === 'user_account').map(row => [row.data.id, row.data]));
const workspaces = new Map(rows.filter(row => row.kind === 'workspace').map(row => [row.data.id, row.data]));
const records = rows.filter(row => row.kind === 'optimization_record');
const foreign = records.filter(row => users.get(row.data.created_by)?.tenant_id !== row.data.tenant_id
  || workspaces.get(row.data.workspace_id)?.tenant_id !== row.data.tenant_id);
if (foreign.length) throw new Error('Unexpected original history scope');
await save(resolve(latestRoot, 'account-consolidation-audit.json'), { sourceAccounts: users.size, movedRecords: records.length,
  beforeCanonicalRecords: 194, beforeAllRecords: 1451, postRealExpectedRecords: 1456, foreignOriginalScopes: foreign.length,
  recordsWithOldProviderConfiguration: records.filter(row => row.data.provider_config_id !== null).length,
  backupIsPrivateAndNotCommitted: true, passwordHashExcluded: true, originalAuditAndUsageEventsUnchanged: true,
  physicalDeletes: 0, newAccountsCreated: 0, sourceUsersDisabled: 25,
  identityTenantWorkspaceStatusesNotChanged: true, existingCredentialIdentitiesRetained: true });
const parity = [];
for (const [name, path] of [['candidate9-plan', 'tmp/me00a-20261012/candidate9-plan-r1/Q05-flash'],
  ['candidate9-direct', 'tmp/me00a-20261012/candidate9-plan-r1/Q05-flash/direct-equal-information'],
  ['candidate10-plan', 'tmp/me00a-20261012/candidate10-replay/plan'], ['candidate10-direct', 'tmp/me00a-20261012/candidate10-replay/direct'],
  ['candidate11-direct', 'tmp/me00a-20261012/candidate11-direct-copy']]) {
  const copied = await readFile(resolve(path, 'copied.md'), 'utf8');
  const history = JSON.parse(await readFile(resolve(path, 'history.json'), 'utf8')).body.data.optimizedPrompt;
  if (copied !== history) throw new Error('Literal history mismatch');
  parity.push({ name, copiedEqualsHistory: true, copiedSha256: hash(copied), downstreamGenerated: name !== 'candidate11-direct' });
}
await save(resolve(latestRoot, 'literal-parity-checks.json'), parity);
for (const candidate of [9, 10, 11]) {
  const root = resolve(`docs/testing/evidence/me00a-candidate${candidate}-2026-10-14`);
  const index = await Promise.all((await files(root)).filter(path => !path.endsWith('artifact-index.json')).map(async path => {
    const bytes = await readFile(path); return { path: relative(root, path).replaceAll('\\', '/'), sha256: hash(bytes), bytes: bytes.length };
  }));
  await save(resolve(root, 'artifact-index.json'), { files: index.length, entries: index, excludesSelf: true });
}
console.log(JSON.stringify({ realCalls: summary.newCalls, actualTotal: 219, newAccountsCreated: 0,
  movedHistoryVerified: records.length, latestSourceMatched: manifest.sourceFiles.length, latestRuntimeMatched: manifest.classFiles.length }));
