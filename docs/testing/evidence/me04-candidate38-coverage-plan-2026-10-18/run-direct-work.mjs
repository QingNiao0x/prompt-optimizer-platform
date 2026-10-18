// Server-side, one fixed native-copy work, no quality retry, no task system message, no credential archival.
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname, basename } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { completedExecutionWork, enhancedExecutionInput } from '../../../../scripts/me00a-eval.mjs';

const pub = resolve('docs/testing/evidence/me04-candidate38-coverage-plan-2026-10-18');
const root = resolve('tmp/me04-candidate38-live-20261018');
const out = resolve(root, 'work');
const ledger = resolve('tmp/me00a-20261012');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const json = async file => JSON.parse(await readFile(file, 'utf8'));
const save = async (name, value) => { await mkdir(out, { recursive: true }); await writeFile(resolve(out, name),
  typeof value === 'string' || Buffer.isBuffer(value) ? value : `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' }); };

const protocol = await json(resolve(ledger, 'protocol.json')); const batch = protocol.activeBatch;
if (batch.version !== 'candidate38-live' || batch.sourceCandidate !== 'candidate38' || batch.status !== 'OPEN'
    || batch.authorizationStatus !== 'APPROVED_BY_USER' || batch.maximumNewCalls !== 3 || batch.callsBefore !== 412
    || batch.purposeMaximums.EXECUTION !== 1 || batch.qualityRetriesAllowed !== false
    || protocol.maximumActualModelCalls !== 425 || protocol.phaseLimits.baseline !== 80 || protocol.phaseLimits.candidateValidation !== 345)
  throw Error('Frozen execution scope mismatch');
const manifest = await json(resolve(pub, 'candidate38-source.json'));
if (hash(await readFile(resolve(pub, 'candidate38-source.json'))) !== batch.sourceManifestSha256) throw Error('Manifest drift');
for (const row of manifest.sourceFiles) if (hash(await readFile(resolve(row.path))) !== row.sha256) throw Error('Source drift');
const check = await json(resolve(root, 'live-results/REQUESTS/COMPACT/check.json'));
const platform = await json(resolve(root, 'live-results/REQUESTS/COMPACT/platform.private.json'));
const copy = await readFile(resolve(root, 'live-results/REQUESTS/COMPACT/native-copy.md'), 'utf8');
if (!check.success || !check.nativeCopyMatches || !check.history?.bodyMatches || platform.status !== 200
    || platform.body.data.provider.mock !== false || hash(copy) !== check.copySha256
    || copy.replace(/\r\n/g, '\n') !== platform.body.data.optimizedPrompt.replace(/\r\n/g, '\n')) throw Error('Native-copy dependency missing');
const sample = (await json(resolve(pub, 'request-cases.json')))[0];
const input = enhancedExecutionInput(copy, { ...sample, context: { files: [] } });
if (input !== copy) throw Error('Execution tool changed native copy');
const request = { model: 'deepseek-flash', temperature: 0.2, max_tokens: 8192, thinking: { type: 'disabled' }, stream: false,
  messages: [{ role: 'user', content: input }] };
const key = process.env.MODEL_DEEPSEEK_API_KEY ?? process.env.MODEL_API_KEY;
const endpoint = new URL(process.env.MODEL_DEEPSEEK_ENDPOINT ?? 'https://api.deepseek.com/chat/completions');
if (!key || endpoint.protocol !== 'https:' || endpoint.hostname !== 'api.deepseek.com' || endpoint.pathname !== '/chat/completions'
    || endpoint.username || endpoint.password || endpoint.search || endpoint.hash || (endpoint.port && endpoint.port !== '443')) throw Error('Official protected configuration unavailable');
await save('request.json', request);
await save('input-lock.json', { copySha256: hash(copy), requestSha256: hash(JSON.stringify(request)), sourceManifestSha256: batch.sourceManifestSha256,
  actualUserAnswers: [], systemTaskInstructionAdded: false, sameInformationBaselineRun: false, qualityRetriesAllowed: false });
await save('call-once.json', { scheduledOnce: true, purpose: 'EXECUTION', startedAt: new Date().toISOString() });
const cp = (await readFile(resolve(root, 'runner/classpath.txt'), 'utf8')).trim();
const trace = execFileSync('E:/Delelop/Java/jdk-21/bin/java.exe', ['-Dfile.encoding=UTF-8', '-cp', cp, 'ReserveEvaluationCall', 'EXECUTION'],
  { encoding: 'utf8', windowsHide: true }).trim();
if (dirname(resolve(trace)) !== resolve(ledger, 'traces') || !/^\d+\.json$/.test(basename(trace))) throw Error('Canonical reservation mismatch');
const record = { version: 'candidate38-live', phase: 'candidateValidation', purpose: 'EXECUTION', sourceCandidate: 'candidate38',
  startedAt: new Date().toISOString(), request };
await writeFile(trace, `${JSON.stringify(record, null, 2)}\n`); const started = Date.now();
try {
  const response = await fetch(endpoint, { method: 'POST', redirect: 'error',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` }, body: JSON.stringify(request), signal: AbortSignal.timeout(120000) });
  const bytes = Buffer.from(await response.arrayBuffer()); await save('response.raw.bin', bytes);
  record.httpStatus = response.status; record.elapsedMs = Date.now() - started; record.responseRawSha256 = hash(bytes);
  try { record.response = JSON.parse(bytes.toString('utf8')); } catch { record.responseText = bytes.toString('utf8'); }
  await writeFile(trace, `${JSON.stringify(record, null, 2)}\n`); await save('response.json', record);
  const work = completedExecutionWork(record); await save('work.md', work.text);
  console.log(JSON.stringify({ trace: basename(trace), httpStatus: response.status, finishReason: work.finishReason,
    elapsedMs: record.elapsedMs, usage: record.response?.usage }));
} catch (error) {
  record.failure = error.name; record.elapsedMs = Date.now() - started; await writeFile(trace, `${JSON.stringify(record, null, 2)}\n`);
  await save('failure.json', { failure: error.name, elapsedMs: record.elapsedMs }); throw Error(`Recorded work failed: ${error.name}`);
}
