/** 从全部已闭阶段原件结算实际消耗，缺失作品与零上游预检分别报告，不把输入变短当作质量或账单收益。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const runtime = resolve(repo, 'tmp/natural-writer-input70-2026-10-29');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const jobs = [], stages = [], phases = {};
let actual = 0;
for (const directory of await readdir(evidence, { withFileTypes: true })) {
  if (!directory.isDirectory()) continue;
  const stage = resolve(evidence, directory.name);
  let protocol;
  try { protocol = await json(resolve(stage, 'protocol.json')); }
  catch (error) { if (error.code === 'ENOENT') continue; throw error; }
  const closure = await json(resolve(stage, 'closure.json'));
  assert.equal(closure.status, 'CLOSED');
  const names = (await readdir(resolve(runtime, directory.name, 'traces'))).filter(name => name.endsWith('.json'));
  assert.equal(names.length, closure.actualCalls);
  actual += names.length;
  for (const entry of closure.files) assert.equal(sha(await readFile(resolve(repo, entry.path))), entry.sha256);
  const traces = [];
  for (const name of names.sort()) {
    const path = resolve(stage, 'traces', name), bytes = await readFile(path);
    assert.equal(sha(bytes), sha(await readFile(resolve(runtime, directory.name, 'traces', name))));
    const trace = JSON.parse(bytes); traces.push(trace);
    phases[trace.phase ?? 'UNRESOLVED_RESERVATION'] = (phases[trace.phase ?? 'UNRESOLVED_RESERVATION'] ?? 0) + 1;
  }
  stages.push({ stage: directory.name, actualCalls: names.length, closed: true, allJobsDelivered: closure.allJobsDelivered });
  for (const job of await json(resolve(stage, 'jobs.json'))) {
    const attempts = traces.filter(trace => trace.jobId === job.id);
    let result;
    try { result = await json(resolve(stage, 'results', job.id + '.json')); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    const row = { stage: directory.name, id: job.id, caseId: job.caseId, version: job.version, phase: job.phase,
      actualCalls: attempts.length, status: result?.status ?? null, error: result?.body?.error?.code ?? null,
      interpretation: !attempts.length ? 'NOT_SENT_TO_MODEL' : result?.status === 200 ? 'DELIVERED' : 'FAILED_DELIVERY',
      upstreamElapsedMs: attempts.reduce((sum, trace) => sum + (trace.elapsedMs ?? 0), 0),
      usage: Object.fromEntries(['prompt_tokens', 'completion_tokens', 'total_tokens'].map(key => [key,
        attempts.every(trace => Number.isFinite(trace.response?.usage?.[key]))
          ? attempts.reduce((sum, trace) => sum + trace.response.usage[key], 0) : null])),
      completedStages: result?.body?.data?.processing?.completedStages ?? null };
    jobs.push(row);
  }
}
assert.equal(actual, 44); assert.ok(actual <= 48);
const implementation = await json(resolve(evidence, '../natural-useful-delivery-2026-10-28/writer70-offline/engineering-freeze.json'));
for (const entry of implementation.files) assert.equal(sha(await readFile(resolve(repo, entry.path))), entry.sha256);
const metrics = { actualCalls: actual, authorizedMaximum: 48, remaining: 48 - actual, previousClosedTaskActual: 500,
  previousTaskReopened: false, phases, stages, jobs, allExecutedStagesClosed: true,
  all12FrozenSourceHashesStillEqual: true, actualTokenUsageNotMonetaryPrice: true,
  workQualityNotInferredFromTokensOrHttpStatus: true };
await writeFile(resolve(evidence, 'actual-call-ledger.json'), JSON.stringify(metrics, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ actualCalls: actual, remaining: 48 - actual, phases,
  ordinaryGenerationAttempts: jobs.filter(job => job.phase === 'GENERATION' && job.actualCalls).length,
  ordinaryGenerationDelivered: jobs.filter(job => job.phase === 'GENERATION' && job.actualCalls && job.status === 200).length,
  allExecutedStagesClosed: true, defaultVersion: '31', adopted: false }));
for (const job of jobs.filter(job => job.caseId === 'W70-N1' && job.phase === 'GENERATION'))
  console.log(JSON.stringify({ id: job.id, actualCalls: job.actualCalls, upstreamElapsedMs: job.upstreamElapsedMs, usage: job.usage }));
