import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const stage = process.argv[2];
const bundlePath = process.argv[3];
if (!/^[a-z0-9-]+$/.test(stage ?? '') || !bundlePath) throw Error('Usage: node freeze-live.mjs <stage> <synthetic-browser-capture-bundle.json>');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const saveNew = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const priorPath = resolve(repo, 'docs/testing/evidence/natural-multidimensional-2026-10-24/actual-call-ledger.json');
const prior = await json(priorPath);
if (prior.authorizedMaximum !== 200 || prior.actualCalls !== 158) throw Error('Prior authorization ledger drifted');
for (const [name, released] of [['regression', 26], ['works', 16]]) {
  const closure = await json(resolve(dirname(priorPath), name, 'closure.json'));
  if (closure.status !== 'CLOSED' || closure.unusedStageReservationReleased !== released) throw Error('Released reservation evidence missing');
}
const bundle = await json(resolve(repo, bundlePath));
if (bundle.syntheticOnly !== true || !Array.isArray(bundle.cases) || !Array.isArray(bundle.jobs) || !bundle.jobs.length) throw Error('Frozen synthetic cases and browser-captured jobs required');
const ids = new Set();
for (const job of bundle.jobs) {
  if (!/^[A-Z0-9_-]+$/.test(job.id ?? '') || ids.has(job.id)) throw Error('Invalid or duplicate job');
  ids.add(job.id);
  if (!bundle.cases.some(sample => sample.id === job.caseId)) throw Error('Unknown frozen case');
  if (!['GENERATION', 'PLAN', 'EXECUTION'].includes(job.phase)) throw Error('Paid review not in this repair scope');
  if (job.phase === 'EXECUTION') {
    if (!job.approvedModelRequest || job.version !== 'ORIGINAL' && !['31', '45'].includes(job.version)) throw Error('Frozen same-information execution request required');
  } else if (!['31', '45'].includes(job.version) || !job.approvedEnhancementInput) throw Error('Actual browser capture and version required');
}
const stageRoot = resolve(evidence, stage);
await mkdir(stageRoot, { recursive: true });
const task = { status: 'FROZEN', selectedMaximumActualCalls: 200, previousActualCalls: 158, maximumNewActualCalls: 42,
  authorizationSource: 'Existing 200-call task; 158 used; new-rule stage closures released 26+16 unused reservations. Current user approves this repair and the new evaluation method.',
  destination: 'https://api.deepseek.com/chat/completions', syntheticOnly: true, priorLedger: priorPath,
  priorLedgerSha256: createHash('sha256').update(await readFile(priorPath)).digest('hex'), qualityRetriesAllowed: false };
try { await saveNew(resolve(evidence, 'task-protocol.json'), task); }
catch (failure) {
  if (failure.code !== 'EEXIST') throw failure;
  const existing = await json(resolve(evidence, 'task-protocol.json'));
  if (existing.status !== 'FROZEN' || existing.selectedMaximumActualCalls !== 200 || existing.previousActualCalls !== 158) throw Error('Task protocol mismatch');
}
await saveNew(resolve(stageRoot, 'cases.json'), { syntheticOnly: true, cases: bundle.cases });
await saveNew(resolve(stageRoot, 'jobs.json'), bundle.jobs);
await saveNew(resolve(stageRoot, 'protocol.json'), { batch: `natural-context-rewrite45-${stage}-2026-10-27`, status: 'FROZEN',
  taskMaximumActualCalls: 200, maximumNewActualCalls: 42, versions: ['31', '45'], candidateVersions: ['45'],
  modelId: 'deepseek:deepseek-flash', upstreamModel: 'deepseek-flash', endpoint: task.destination,
  parameters: { temperature: 0.2, generationMaxTokens: 8192, executionMaxTokens: 8192, reviewMaxTokens: 0, thinking: 'disabled', stream: false },
  generationPayloadVersions: { '31': ['31', '4'], '45': ['45'] }, phaseLimits: { GENERATION: 24, PLAN: 6, EXECUTION: 12 },
  allowedJobs: bundle.jobs.map(job => job.id), qualityRetriesAllowed: false, accountCreationAllowed: false });
console.log(JSON.stringify({ stage, jobs: bundle.jobs.length, callsMade: 0, remainingTaskMaximum: 42, endpoint: task.destination }));
