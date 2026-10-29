import assert from 'node:assert/strict';
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
const output = dirname(fileURLToPath(import.meta.url)), evidence = resolve(output, '..'), repo = resolve(evidence, '../../../..');
const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28'), root = resolve(runtime, 'single-pass54-thinking-probe');
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const hash = value => createHash('sha256').update(value).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const protocol = await json(resolve(output, 'protocol.json')), files = [], privateOriginals = [], deliveries = [];
const names = (await readdir(resolve(root, 'traces'))).filter(value => value.endsWith('.json')).sort();
assert.equal(names.length, 2); assert.equal(protocol.maximumNewActualCalls, 2);
await mkdir(resolve(output, 'traces'), { recursive: true });
for (const name of names) {
  const path = resolve(root, 'traces', name), bytes = await readFile(path), trace = JSON.parse(bytes);
  privateOriginals.push({ path: relative(repo, path).replaceAll('\\', '/'), sha256: hash(bytes) });
  // Keep provider reasoning in the locked private original, publish only final content, usage and its hash.
  const publicTrace = structuredClone(trace);
  for (const choice of publicTrace.response?.choices ?? []) {
    for (const key of ['reasoning_content', 'reasoning']) {
      if (typeof choice.message?.[key] === 'string') {
        publicTrace.providerReasoningChars = choice.message[key].length;
        delete choice.message[key];
      }
    }
  }
  publicTrace.privateOriginalSha256 = hash(bytes); publicTrace.providerReasoningOmittedFromPublic = true;
  const destination = resolve(output, 'traces', name); await save(destination, publicTrace);
  files.push({ path: relative(repo, destination).replaceAll('\\', '/'), sha256: hash(await readFile(destination)) });
  deliveries.push({ id: trace.jobId, status: trace.httpStatus, delivered: trace.httpStatus === 200
    && trace.response?.choices?.[0]?.finish_reason === 'stop' && !!trace.response?.choices?.[0]?.message?.content });
}
let total = 0;
for (const item of await readdir(runtime, { withFileTypes: true })) {
  if (!item.isDirectory()) continue;
  try { total += (await readdir(resolve(runtime, item.name, 'traces'))).filter(value => value.endsWith('.json')).length; }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
}
await save(resolve(output, 'actual-call-ledger.json'), { stage: 'single-pass54-thinking-probe', actualCalls: 2,
  taskActualAfter: total, taskRemainingAfter: 500 - total, taskMaximumActualCalls: 500,
  phases: { EXECUTION: 2 }, qualityRetries: 0, paidReviewCalls: 0, noAccountOrCredentialFilesCopied: true });
const closure = { status: 'CLOSED', closedAt: new Date().toISOString(), stage: 'single-pass54-thinking-probe', actualCalls: 2,
  phases: { EXECUTION: 2 }, entryLockedByClosure: true, allJobsDelivered: deliveries.every(item => item.delivered),
  deliveries, qualityAdoptionClaimed: false, files, privateOriginals,
  evidenceCopy: 'Final content and usage preserved; public traces omit provider reasoning only; exact private originals locked by SHA256.' };
await save(resolve(output, 'closure.json'), closure); await save(resolve(root, 'closure.json'), closure);
console.log(JSON.stringify({ closed: true, calls: 2, actual: total, privateOriginalsLocked: true }));
