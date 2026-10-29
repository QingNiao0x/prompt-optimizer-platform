import assert from 'node:assert/strict';
import { readFile, readdir, mkdir, writeFile, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const runtime = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28');
const stage = 'single-pass54-thinking-probe';
const publicRoot = resolve(evidence, stage), privateRoot = resolve(runtime, stage);
const readJson = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const absent = async path => { try { await stat(path); throw Error('Closed or already attempted path: ' + path); }
  catch (error) { if (error.code !== 'ENOENT') throw error; } };
async function count() {
  let actual = 0;
  for (const item of await readdir(runtime, { withFileTypes: true })) {
    if (!item.isDirectory()) continue;
    try { actual += (await readdir(resolve(runtime, item.name, 'traces'))).filter(name => name.endsWith('.json')).length; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  return actual;
}

if (process.argv[2] === 'FREEZE') {
  await absent(resolve(publicRoot, 'protocol.json'));
  const path = 'tmp/natural-useful-delivery-2026-10-28/registry54-confirmed-generation-recovery-r3/traces/001.json';
  const bytes = await readFile(resolve(repo, path)), trace = JSON.parse(bytes), request = trace.request;
  assert.equal(trace.jobId, 'P02_CONFIRMED_V54_GEN_RECOVERY'); assert.equal(trace.httpStatus, 200);
  assert.equal(request.model, 'deepseek-flash'); assert.equal(request.temperature, 0.2);
  assert.deepEqual(request.thinking, { type: 'disabled' }); assert.equal(request.max_tokens, 8192);
  assert.deepEqual(request.response_format, { type: 'json_object' });
  assert.equal(request.stream, false); assert.equal(request.messages.length, 2);
  const task = await readJson(resolve(evidence, 'task-protocol.json'));
  assert.equal(task.selectedMaximumActualCalls, 500); assert.ok(await count() + 2 <= 500);
  await mkdir(publicRoot, { recursive: true });
  const jobs = ['OFF', 'ON'].map(arm => {
    const payload = structuredClone(request); payload.max_tokens = 32768;
    if (arm === 'ON') { payload.thinking = { type: 'enabled' }; payload.reasoning_effort = 'high'; }
    return { id: 'P02_CONFIRMED_54_' + arm, caseId: 'P02_CONFIRMED', version: '54', phase: 'EXECUTION',
      purpose: 'SINGLE_PASS_THINKING_MECHANISM', arm, approvedModelRequest: payload };
  });
  await save(resolve(publicRoot, 'jobs.json'), jobs);
  await save(resolve(publicRoot, 'protocol.json'), { status: 'FROZEN', experimentProfile: 'SINGLE_PASS54_THINKING_V1',
    versions: ['54'], maximumNewActualCalls: 2, phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 2, REVIEW: 0 },
    allowedJobs: jobs.map(job => job.id), endpoint: 'https://api.deepseek.com/chat/completions',
    actualAtFreeze: await count(), taskMaximum: 500, model: 'deepseek-flash', outputCap: 32768,
    thinkingArms: ['OFF', 'ON'], sourceTrace: { path, sha256: sha256(bytes) },
    frozenScriptSha256: sha256(await readFile(fileURLToPath(import.meta.url))),
    jobsSha256: sha256(await readFile(resolve(publicRoot, 'jobs.json'))),
    qualityRetriesAllowed: false, protocolRepairsAllowed: false, actualWorkEvidence: false,
    stoppingRule: 'Run one OFF and one ON, retain all outputs; do not run UNKNOWN or works on severe ON error.',
    acceptance: 'Inspect final prompt and task separately: confirmed current condition, original independent restrictions, no authorization expansion; parsing alone is insufficient.',
    unchanged: ['full system and user messages', 'source material', 'actual bound question and answer', 'model', 'temperature', 'stream', 'JSON response format'],
    changed: ['max_tokens 8192 to 32768 in both arms', 'ON only: thinking enabled and reasoning_effort high'],
    source: 'https://api-docs.deepseek.com/guides/thinking_mode/' });
  console.log(JSON.stringify({ frozen: true, stage, maximumNewActualCalls: 2, actual: await count() }));
} else {
  const protocol = await readJson(resolve(publicRoot, 'protocol.json'));
  for (const root of [evidence, publicRoot, runtime, privateRoot]) await absent(resolve(root, 'closure.json'));
  assert.equal(sha256(await readFile(fileURLToPath(import.meta.url))), protocol.frozenScriptSha256);
  assert.equal(sha256(await readFile(resolve(publicRoot, 'jobs.json'))), protocol.jobsSha256);
  assert.equal(sha256(await readFile(resolve(repo, protocol.sourceTrace.path))), protocol.sourceTrace.sha256);
  const job = (await readJson(resolve(publicRoot, 'jobs.json'))).find(value => value.id === process.argv[2]);
  assert.ok(job && protocol.allowedJobs.includes(job.id)); assert.ok(await count() < 500);
  const traces = resolve(privateRoot, 'traces'); await mkdir(traces, { recursive: true });
  const previous = (await readdir(traces)).filter(name => name.endsWith('.json'));
  assert.ok(previous.length < 2);
  for (const name of previous) assert.notEqual((await readJson(resolve(traces, name))).jobId, job.id);
  assert.ok(process.env.MODEL_API_KEY, 'Inject the existing credential locally; never pass it as an argument.');
  const tracePath = resolve(traces, String(previous.length + 1).padStart(3, '0') + '.json');
  const record = { batch: stage, jobId: job.id, phase: 'EXECUTION', caseId: job.caseId,
    mechanismOnly: true, request: job.approvedModelRequest, reservedAt: new Date().toISOString() };
  await save(tracePath, record); // Reserve before any possible paid request; interruption consumes budget.
  const started = Date.now();
  try {
    const response = await fetch(protocol.endpoint, { method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + process.env.MODEL_API_KEY },
      body: JSON.stringify(job.approvedModelRequest), signal: AbortSignal.timeout(300000) });
    delete process.env.MODEL_API_KEY;
    const bytes = Buffer.from(await response.arrayBuffer());
    record.httpStatus = response.status; record.elapsedMs = Date.now() - started;
    record.responseBytes = bytes.length; record.responseSha256 = sha256(bytes); record.response = JSON.parse(bytes);
  } catch (error) { record.transportFailure = error.name; record.elapsedMs = Date.now() - started; }
  await writeFile(tracePath, JSON.stringify(record, null, 2) + '\n');
  console.log(JSON.stringify({ stage, jobId: job.id, status: record.httpStatus ?? 0,
    elapsedMs: record.elapsedMs, finishReason: record.response?.choices?.[0]?.finish_reason,
    usage: record.response?.usage, actual: await count(), taskMaximum: 500 }));
}
