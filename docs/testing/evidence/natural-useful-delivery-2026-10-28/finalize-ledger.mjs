import { createHash } from 'node:crypto';
import { readFile, writeFile, readdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28');
const decisionPath = process.argv[2];
if (!decisionPath) throw Error('Usage: node finalize-ledger.mjs <explicit-adoption-decision-relative-to-repo.json>');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const task = await json(resolve(evidence, 'task-protocol.json'));
if (task.selectedMaximumActualCalls !== 500 || task.previousActualCalls !== 0) throw Error('Independent 500-call task required');
const decision = await json(resolve(repo, decisionPath));
if (typeof decision.candidateAdopted !== 'boolean' || !['31', '45', '46', '47', '48', '49'].includes(decision.defaultVersion)
    || typeof decision.overallQualityGoalClaimed !== 'boolean') throw Error('Explicit adoption and quality boundaries required');
const phaseCounts = {};
const stages = [];
const copies = [];
const histories = [];
const deliveries = [];
for (const directory of await readdir(evidence, { withFileTypes: true })) {
  if (!directory.isDirectory()) continue;
  const stage = directory.name;
  const stageEvidence = resolve(evidence, stage);
  let protocol;
  try { protocol = await json(resolve(stageEvidence, 'protocol.json')); }
  catch (failure) { if (failure.code === 'ENOENT') continue; throw failure; }
  const closure = await json(resolve(stageEvidence, 'closure.json'));
  if (closure.status !== 'CLOSED' || !closure.entryLockedByClosure) throw Error('All frozen stages must be closed before finalization');
  const stageRuntime = resolve(runtime, stage);
  if ((await json(resolve(stageRuntime, 'closure.json'))).status !== 'CLOSED') throw Error('Runtime closure missing');
  const traces = (await readdir(resolve(stageRuntime, 'traces'))).filter(name => name.endsWith('.json'));
  if (traces.length !== closure.actualCalls || traces.length > protocol.maximumNewActualCalls) throw Error('Closed stage count changed');
  const phases = {};
  for (const name of traces) {
    let trace;
    try { trace = await json(resolve(stageRuntime, 'traces', name)); }
    catch { trace = {}; }
    const phase = trace.phase ?? 'UNRESOLVED_RESERVATION';
    phases[phase] = (phases[phase] ?? 0) + 1;
    phaseCounts[phase] = (phaseCounts[phase] ?? 0) + 1;
  }
  for (const file of closure.files) {
    const copied = await readFile(resolve(repo, file.path));
    if (hash(copied) !== file.sha256) throw Error('Copied original changed');
    const relative = file.path.split(`/${stage}/`).at(-1);
    const original = await readFile(resolve(stageRuntime, relative));
    if (!original.equals(copied)) throw Error('Runtime and safe copied original differ');
    copies.push({ path: file.path, sha256: file.sha256 });
  }
  let results = [];
  try { results = (await readdir(resolve(stageEvidence, 'results'))).filter(name => name.endsWith('.json')); }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  for (const name of results) {
    const result = await json(resolve(stageEvidence, 'results', name));
    if (name.endsWith('-history.json')) histories.push({ stage, name,
      passed: result.status === 200 && result.bodyEqual && result.versionEqual && result.processingEqual });
    else deliveries.push({ stage, id: result.id, status: result.status });
  }
  stages.push({ stage, actualCalls: traces.length, phases, closed: true });
}
const total = Object.values(phaseCounts).reduce((sum, value) => sum + value, 0);
if (total > 500) throw Error('Task authorization exceeded');
const ledger = { authorizedMaximum: 500, previousActualCalls: 0, actualTaskCalls: total, remainingUnused: 500 - total,
  phases: phaseCounts, stages, qualityRetries: 0, paidReviewCalls: phaseCounts.REVIEW ?? 0,
  counting: 'Every upstream attempt including scope/body/Plan/execution/repair/failure/review and incomplete reservations; no closed prior-task allowance reused.',
  defaultVersion: decision.defaultVersion, candidateVersion: decision.candidateVersion ?? '46',
  candidateAdopted: decision.candidateAdopted, productionDeployed: decision.productionDeployed === true };
await save(resolve(evidence, 'actual-call-ledger.json'), ledger);
const verification = { checkedAt: new Date().toISOString(), actualModelCalls: total, taskMaximum: 500,
  closedStages: stages, byteIdenticalSafeOriginalCopies: copies.length, copies,
  historyChecks: histories, allCapturedHistoriesEqual: histories.every(item => item.passed),
  businessResponses: deliveries.length, http200Responses: deliveries.filter(item => item.status === 200).length,
  noPrivateAuthenticationOrServerLogFilesCopied: true,
  boundary: 'Accounting, response delivery and body/history identity do not prove prompt or downstream-work quality. See the explicit adoption decision and main report.',
  adoptionDecision: { path: decisionPath.replaceAll('\\', '/'), sha256: hash(await readFile(resolve(repo, decisionPath))) } };
await save(resolve(evidence, 'final-verification.json'), verification);
const closure = { status: 'CLOSED', closedAt: new Date().toISOString(), actualCalls: total, unusedAuthorization: 500 - total,
  entryLockedByClosure: true, defaultVersion: decision.defaultVersion,
  candidateVersion: decision.candidateVersion ?? '46', candidateAdopted: decision.candidateAdopted,
  overallQualityGoalClaimed: decision.overallQualityGoalClaimed, productionDeployed: decision.productionDeployed === true,
  files: await Promise.all(['actual-call-ledger.json', 'final-verification.json'].map(async path => ({ path, sha256: hash(await readFile(resolve(evidence, path))) }))) };
await save(resolve(evidence, 'closure.json'), closure);
await save(resolve(runtime, 'closure.json'), closure);
console.log(JSON.stringify({ closed: true, actualCalls: total, remainingUnused: 500 - total, phaseCounts,
  safeCopiedOriginals: copies.length, historyChecks: histories.length, historiesEqual: verification.allCapturedHistoriesEqual,
  defaultVersion: decision.defaultVersion, candidateAdopted: decision.candidateAdopted }));
