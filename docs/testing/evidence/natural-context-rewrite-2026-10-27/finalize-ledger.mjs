import { createHash } from 'node:crypto';
import { readFile, writeFile, readdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const runtime = resolve(repo, 'tmp/natural-context-rewrite-2026-10-27');
const stages = ['live', 'works', 'validation', 'validation-confirmed'];
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const previous = await json(resolve(repo, 'docs/testing/evidence/natural-multidimensional-2026-10-24/actual-call-ledger.json'));
if (previous.authorizedMaximum !== 200 || previous.actualCalls !== 158) throw Error('Prior ledger drift');
const phaseCounts = {};
const stageCounts = [];
const copyChecks = [];
const historyChecks = [];
const deliveries = [];
let sensitiveTransportFieldCount = 0;
function countSensitiveTransportFields(value) {
  if (!value || typeof value !== 'object') return;
  for (const [key, child] of Object.entries(value)) {
    if (['authorization', 'cookie', 'set-cookie', 'password', 'apikey', 'api_key'].includes(key.toLowerCase())) sensitiveTransportFieldCount++;
    countSensitiveTransportFields(child);
  }
}
for (const stage of stages) {
  const stageEvidence = resolve(evidence, stage);
  const closure = await json(resolve(stageEvidence, 'closure.json'));
  if (closure.status !== 'CLOSED' || !closure.entryLockedByClosure) throw Error('All model stages must be closed');
  if ((await json(resolve(runtime, stage, 'closure.json'))).status !== 'CLOSED') throw Error('Runtime closure missing');
  const traces = (await readdir(resolve(runtime, stage, 'traces'))).filter(name => name.endsWith('.json'));
  if (traces.length !== closure.actualCalls) throw Error('Closed stage trace count changed');
  const phases = {};
  for (const name of traces) {
    const trace = await json(resolve(runtime, stage, 'traces', name));
    const phase = trace.phase ?? 'UNRESOLVED_RESERVATION';
    phases[phase] = (phases[phase] ?? 0) + 1;
    phaseCounts[phase] = (phaseCounts[phase] ?? 0) + 1;
    countSensitiveTransportFields(trace);
  }
  for (const file of closure.files) {
    const copied = await readFile(resolve(repo, file.path));
    if (hash(copied) !== file.sha256) throw Error('Copied original hash changed');
    const relative = file.path.split(`/${stage}/`).at(-1);
    const original = await readFile(resolve(runtime, stage, relative));
    if (!original.equals(copied)) throw Error('Runtime and public safe original differ');
    copyChecks.push({ path: file.path, sha256: file.sha256 });
  }
  for (const name of (await readdir(resolve(stageEvidence, 'results'))).filter(name => name.endsWith('.json'))) {
    const result = await json(resolve(stageEvidence, 'results', name));
    if (name.endsWith('-history.json')) {
      historyChecks.push({ stage, name, passed: result.status === 200 && result.bodyEqual && result.versionEqual && result.processingEqual });
    } else deliveries.push({ stage, id: result.id, status: result.status });
  }
  stageCounts.push({ stage, actualCalls: traces.length, phases, closed: true });
}
const actualNew = Object.values(phaseCounts).reduce((sum, value) => sum + value, 0);
if (actualNew !== 24 || previous.actualCalls + actualNew > 200) throw Error('Final actual count differs from the expected 24 or exceeds authorization');
if (sensitiveTransportFieldCount) throw Error('Captured trace contains a credential/header field');
const combinedPhases = {};
for (const [phase, value] of Object.entries(previous.phases)) combinedPhases[phase] = value.actualCalls;
for (const [phase, count] of Object.entries(phaseCounts)) combinedPhases[phase] = (combinedPhases[phase] ?? 0) + count;
const ledger = { authorizedMaximum: 200, previousActualCalls: 158, actualNewCalls: actualNew, actualTaskCalls: 158 + actualNew,
  remainingUnused: 200 - 158 - actualNew, newPhases: phaseCounts, combinedPhases, stages: stageCounts,
  qualityRetries: 0, paidReviewCallsThisRound: 0, protocolRepairCallsThisRound: 0,
  counting: 'Every upstream scope/body/Plan/execution attempt is counted; old158 carry forward once; no source/account/cookie file is counted as a model call.',
  defaultVersion: '31', candidateVersion: '45', candidateAdopted: false, productionDeployed: false };
await save(resolve(evidence, 'actual-call-ledger.json'), ledger);
const browserFiles = [];
for (const name of await readdir(resolve(evidence, 'real-browser'))) {
  const bytes = await readFile(resolve(evidence, 'real-browser', name));
  browserFiles.push({ path: `real-browser/${name}`, bytes: bytes.length, sha256: hash(bytes) });
}
const browser = await json(resolve(evidence, 'real-browser/verdict.json'));
const workSummary = await json(resolve(evidence, 'code-work-verdicts/summary.json'));
const verification = { checkedAt: new Date().toISOString(), actualNewModelCalls: actualNew, authorizedTaskActual: 158 + actualNew,
  byteIdenticalCopies: copyChecks.length, copies: copyChecks, closedStages: stageCounts.length,
  rawBusinessResponses: deliveries.length, rawHttp200Responses: deliveries.filter(item => item.status === 200).length,
  historyChecks, allHistoryCopiesEqual: historyChecks.every(item => item.passed),
  realBrowser: { files: browserFiles, actualModel: browser.providerMock === false, actualInputMatchesFrozen: browser.inputEqualsFrozen,
    indexedFiles: browser.indexedFiles, displayCopyHistoryEqual: browser.displayedCopiedAndHistoryEqual, version: browser.version },
  originalWorkVerdicts: workSummary.verdicts,
  workBoundary: 'The original strict wrapper FAIL for both45 works is preserved. Raw HTTP delivery does not imply file-protocol or business-quality acceptance. Separate offline business projection/test review cannot rewrite these original verdicts.',
  copiedPrivateAuthFiles: false, sensitiveTransportFieldCount, qualityAdoptionClaimed: false,
  excludes: 'Semantic review and adoption remain in the primary report; this file verifies model accounting, safe originals, actual UI, and byte/history consistency only.' };
await save(resolve(evidence, 'final-verification.json'), verification);
const closure = { status: 'CLOSED', closedAt: new Date().toISOString(), actualNewCalls: actualNew, actualTaskCalls: 158 + actualNew,
  unusedAuthorization: 200 - 158 - actualNew, entryLockedByClosure: true, defaultVersion: '31', candidateVersion: '45',
  candidateAdopted: false, overallQualityGoalClaimed: false, productionDeployed: false,
  files: ['actual-call-ledger.json', 'final-verification.json'].map(path => ({ path, sha256: '' })) };
for (const file of closure.files) file.sha256 = hash(await readFile(resolve(evidence, file.path)));
await save(resolve(evidence, 'closure.json'), closure);
await save(resolve(runtime, 'closure.json'), closure);
console.log(JSON.stringify({ closed: true, actualNew, actualTask: ledger.actualTaskCalls, remaining: ledger.remainingUnused,
  phaseCounts, byteIdenticalCopies: copyChecks.length, historyChecks: historyChecks.length,
  historiesEqual: verification.allHistoryCopiesEqual, strictWorkFailuresPreserved: workSummary.verdicts.filter(item => !item.passed).length }));
