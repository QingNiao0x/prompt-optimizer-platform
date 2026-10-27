import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const stage = process.argv[2];
const expectedCalls = Number(process.argv[3]);
if (!/^[a-z0-9-]+$/.test(stage ?? '') || !Number.isInteger(expectedCalls) || expectedCalls < 0) throw Error('Usage: node close-stage.mjs <stage> <expected-actual-calls>');
const stageEvidence = resolve(evidence, stage);
const root = resolve(repo, 'tmp/natural-context-rewrite-2026-10-27', stage);
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const protocol = await json(resolve(stageEvidence, 'protocol.json'));
const files = [];
const phases = {};
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
async function copyExact(source, destination) {
  const bytes = await readFile(source);
  await mkdir(dirname(destination), { recursive: true });
  try { await writeFile(destination, bytes, { flag: 'wx' }); }
  catch (failure) { if (failure.code !== 'EEXIST' || hash(await readFile(destination)) !== hash(bytes)) throw failure; }
  files.push({ path: destination.slice(repo.length + 1).replaceAll('\\', '/'), sha256: hash(bytes) });
}
const traces = (await readdir(resolve(root, 'traces'))).filter(name => name.endsWith('.json')).sort();
if (traces.length !== expectedCalls || traces.length > protocol.maximumNewActualCalls) throw Error('Actual trace count differs from the expected closure');
for (const name of traces) {
  const trace = await json(resolve(root, 'traces', name));
  const phase = trace.phase ?? 'UNRESOLVED_RESERVATION';
  phases[phase] = (phases[phase] ?? 0) + 1;
  await copyExact(resolve(root, 'traces', name), resolve(stageEvidence, 'traces', name));
}
const results = (await readdir(resolve(root, 'results'))).filter(name => name.endsWith('.json')).sort();
for (const name of results) await copyExact(resolve(root, 'results', name), resolve(stageEvidence, 'results', name));
const jobs = await json(resolve(stageEvidence, 'jobs.json'));
const deliveries = [];
for (const job of jobs) {
  let result;
  try { result = await json(resolve(root, 'results', job.id + '.json')); }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  const body = job.phase === 'GENERATION' ? result?.body?.data?.optimizedPrompt : job.phase === 'EXECUTION' ? result?.body?.data?.content : result?.body?.data?.questions;
  deliveries.push({ id: job.id, status: result?.status ?? 0, delivered: result?.status === 200 && (typeof body === 'string' ? body.trim().length > 0 : Array.isArray(body)) });
}
let otherStagesActual = 0;
for (const entry of await readdir(dirname(root), { withFileTypes: true })) {
  if (!entry.isDirectory() || entry.name === stage) continue;
  try { otherStagesActual += (await readdir(resolve(dirname(root), entry.name, 'traces'))).filter(name => name.endsWith('.json')).length; }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
}
const effectiveStageAllowance = Math.min(protocol.maximumNewActualCalls, 200 - 158 - otherStagesActual);
const ledger = { stage, authorizationBaselineActual: 158, previousStagesActual: otherStagesActual, actualCalls: traces.length,
  taskActualAfter: 158 + otherStagesActual + traces.length, taskRemainingAfter: 200 - 158 - otherStagesActual - traces.length,
  phases, qualityRetries: 0, paidReviewCalls: 0, taskMaximumActualCalls: 200, noAccountOrCredentialFilesCopied: true };
await writeFile(resolve(stageEvidence, 'actual-call-ledger.json'), JSON.stringify(ledger, null, 2) + '\n', { flag: 'wx' });
const closure = { status: 'CLOSED', closedAt: new Date().toISOString(), stage, actualCalls: traces.length, phases,
  effectiveStageAllowance, unusedStageReservationReleased: effectiveStageAllowance - traces.length, entryLockedByClosure: true,
  allJobsDelivered: deliveries.every(item => item.delivered), deliveries, qualityAdoptionClaimed: false,
  evidenceCopy: 'Byte-identical JSON traces and business results only; private auth, cookies and server logs excluded', files };
await writeFile(resolve(stageEvidence, 'closure.json'), JSON.stringify(closure, null, 2) + '\n', { flag: 'wx' });
await writeFile(resolve(root, 'closure.json'), JSON.stringify(closure, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ stage, actualCalls: traces.length, phases, allJobsDelivered: closure.allJobsDelivered, closed: true, copiedFiles: files.length }));
