import assert from 'node:assert/strict';
import { readFile, writeFile, readdir, mkdir, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28'), stage = 'metadata-only-repair-probe';
const root = resolve(runtime, stage), output = resolve(evidence, stage);
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
async function absent(path) { try { await stat(path); throw Error('Closed or already attempted'); } catch (error) { if (error.code !== 'ENOENT') throw error; } }
async function count() {
  let total = 0;
  for (const item of await readdir(runtime, { withFileTypes: true })) {
    if (!item.isDirectory()) continue;
    try { total += (await readdir(resolve(runtime, item.name, 'traces'))).filter(name => name.endsWith('.json')).length; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  return total;
}
for (const directory of [evidence, output, runtime, root]) await absent(resolve(directory, 'closure.json'));
const jobs = await json(resolve(output, 'jobs.json'));
if (process.argv[2] === 'FREEZE') {
  assert.equal(jobs.length, 2); assert.deepEqual(jobs.map(job => job.id), ['U02_META_PATCH', 'V02_META_PATCH']);
  for (const job of jobs) {
    const request = job.approvedModelRequest;
    assert.equal(request.model, 'deepseek-flash'); assert.equal(request.temperature, 0.2); assert.equal(request.max_tokens, 32768);
    assert.deepEqual(request.thinking, { type: 'enabled' }); assert.equal(request.reasoning_effort, 'high');
    assert.equal(request.stream, false); assert.deepEqual(request.response_format, { type: 'json_object' });
  }
  const task = await json(resolve(evidence, 'task-protocol.json')); assert.equal(task.selectedMaximumActualCalls, 500);
  assert.ok(await count() + 2 <= 500);
  const files = ['docs/testing/evidence/natural-useful-delivery-2026-10-28/run-metadata-probe.mjs',
    'docs/testing/evidence/natural-useful-delivery-2026-10-28/MetadataRepairProbe.java',
    'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/NaturalPromptMetadataRepair.java',
    'docs/testing/evidence/natural-useful-delivery-2026-10-28/metadata-only-repair-probe/jobs.json',
    'docs/testing/evidence/natural-useful-delivery-2026-10-28/registry56-regression/traces/001.json',
    'docs/testing/evidence/natural-useful-delivery-2026-10-28/registry56-regression/traces/007.json'];
  const frozenFiles = [];
  for (const path of files) frozenFiles.push({ path, sha256: hash(await readFile(resolve(repo, path))) });
  await save(resolve(output, 'protocol.json'), { status: 'FROZEN', versions: ['56'], metadataMechanismOnly: true,
    maximumNewActualCalls: 2, phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 2, REVIEW: 0 },
    allowedJobs: jobs.map(job => job.id), frozenFiles, endpoint: 'https://api.deepseek.com/chat/completions',
    actualAtFreeze: await count(), taskMaximumActualCalls: 500, protocolRepairsAllowed: false, qualityRetriesAllowed: false,
    candidateSelection: 'Always the first draft, before any protocol regeneration; no semantic best-of selection.',
    acceptance: 'Patch only listed metadata, exact first body unchanged, all original Java checks; semantic review remains separate.',
    stoppingRule: 'One call per case; retain failure, no further attempts or full body generation.' });
  console.log(JSON.stringify({ frozen: true, maximumCalls: 2, actual: await count() }));
} else {
  const protocol = await json(resolve(output, 'protocol.json'));
  for (const file of protocol.frozenFiles) assert.equal(hash(await readFile(resolve(repo, file.path))), file.sha256);
  const job = jobs.find(value => value.id === process.argv[2]); assert.ok(job && protocol.allowedJobs.includes(job.id));
  assert.ok(await count() < 500); assert.ok(process.env.MODEL_API_KEY);
  const traceDir = resolve(root, 'traces'); await mkdir(traceDir, { recursive: true });
  const names = (await readdir(traceDir)).filter(name => name.endsWith('.json')); assert.ok(names.length < 2);
  for (const name of names) assert.notEqual((await json(resolve(traceDir, name))).jobId, job.id);
  const record = { batch: stage, jobId: job.id, caseId: job.caseId, phase: 'EXECUTION', mechanismOnly: true,
    request: job.approvedModelRequest, reservedAt: new Date().toISOString() };
  const path = resolve(traceDir, String(names.length + 1).padStart(3, '0') + '.json'); await save(path, record);
  const start = Date.now(); let content = '', finishReason = '', usage = {};
  try {
    const response = await fetch(protocol.endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json',
      Authorization: 'Bearer ' + process.env.MODEL_API_KEY }, body: JSON.stringify(job.approvedModelRequest), signal: AbortSignal.timeout(300000) });
    delete process.env.MODEL_API_KEY;
    const bytes = Buffer.from(await response.arrayBuffer()); record.httpStatus = response.status;
    record.responseBytes = bytes.length; record.responseSha256 = hash(bytes); record.response = JSON.parse(bytes);
    for (const choice of record.response.choices ?? []) {
      if (choice.message) { delete choice.message.reasoning_content; delete choice.message.reasoning; }
    }
    content = record.response.choices?.[0]?.message?.content ?? ''; finishReason = record.response.choices?.[0]?.finish_reason ?? '';
    usage = record.response.usage ?? {};
  } catch (error) { record.transportFailure = error.name; }
  record.elapsedMs = Date.now() - start; await writeFile(path, JSON.stringify(record, null, 2) + '\n');
  await mkdir(resolve(root, 'results'), { recursive: true });
  await save(resolve(root, 'results', job.id + '.json'), { id: job.id, caseId: job.caseId, version: '56', phase: 'EXECUTION',
    status: record.httpStatus ?? 0, body: { data: { content, finishReason, usage } } });
  console.log(JSON.stringify({ job: job.id, status: record.httpStatus ?? 0, finishReason, elapsedMs: record.elapsedMs, actual: await count() }));
}
