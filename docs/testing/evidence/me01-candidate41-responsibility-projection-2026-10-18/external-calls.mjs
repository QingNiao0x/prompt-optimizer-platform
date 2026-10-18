// Server-side only. No implicit operation, browser access, quality retry or credential archival.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname, basename } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { rawExecutionInput, enhancedExecutionInput, completedExecutionWork } from '../../../../scripts/me00a-eval.mjs';

const pub = resolve('docs/testing/evidence/me01-candidate41-responsibility-projection-2026-10-18');
const work = resolve('tmp/me01-candidate41-live-20261018');
const ledger = resolve('tmp/me00a-20261012');
const parameters = { temperature: 0.2, max_tokens: 8192, thinking: { type: 'disabled' }, stream: false };
const groups = { TS: 'TS-IMPLICIT' };
const arms = ['RAW', 'PLAN'];
const permittedArms = { TS: arms };
const reviewModels = { R1: 'deepseek-flash', R2: 'deepseek-v4-pro' };
const hash = value => createHash('sha256').update(value).digest('hex');
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const newline = value => value.replace(/\r\n/g, '\n');
const save = async (path, value) => {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, Buffer.isBuffer(value) || typeof value === 'string'
    ? value : `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' });
};

/** Unchanged native copied input is required; a corrected or reconstructed prompt cannot qualify. */
async function verifiedCopy() {
  const directory = resolve(work, 'flow/TS-IMPLICIT');
  const platform = await json(resolve(directory, 'platform.private.json'));
  const copy = await readFile(resolve(directory, 'native-copy.md'), 'utf8');
  const check = await json(resolve(directory, 'check.json'));
  if (platform.status !== 200 || platform.body.data.provider.mock !== false || !check.success
      || !check.nativeCopyMatches || !check.historyMatches || hash(copy) !== check.copySha256
      || newline(copy) !== newline(platform.body.data.optimizedPrompt)) throw Error('Verified unchanged native copy required');
  return copy;
}

/** Raw receives only verbatim actual submitted answers; Plan is executed exactly as copied. */
async function prepareWorks() {
  const caseBytes = await readFile(resolve(pub, 'request-cases.json')); const cases = JSON.parse(caseBytes.toString('utf8'));
  const sample = cases.find(row => row.id === 'TS-IMPLICIT'); const executionSample = { ...sample, context: { files: [] } };
  const confirmation = await json(resolve(work, 'flow/TS-IMPLICIT/actual-confirmation.private.json'));
  const submitted = await json(resolve(work, 'flow/TS-IMPLICIT/enhancement-request.private.json'));
  if (!equal(confirmation, submitted.planConfirmation)) throw Error('Actual confirmation mismatch');
  const copy = await verifiedCopy();
  const inputs = { RAW: rawExecutionInput(executionSample, confirmation.answers), PLAN: enhancedExecutionInput(copy, executionSample) };
  if (inputs.PLAN !== copy) throw Error('Plan body patched by execution helper');
  const source = await readFile(resolve(pub, 'candidate41-source.json'));
  const lock = { sourceCandidate: 'candidate41', sourceManifestSha256: hash(source), casesSha256: hash(caseBytes),
    model: 'deepseek-flash', parameters, actualConfirmations: confirmation.answers, systemTaskInstructionAdded: false,
    sharedMaterials: [], groups: { TS: { caseId: sample.id, nativeCopyHashes: { PLAN: hash(copy) }, arms: {} } } };
  for (const arm of arms) {
    const request = { model: 'deepseek-flash', ...parameters, messages: [{ role: 'user', content: inputs[arm] }] };
    await save(resolve(work, 'works/TS', arm, 'request.json'), request);
    lock.groups.TS.arms[arm] = { requestSha256: hash(JSON.stringify(request)), promptSha256: hash(inputs[arm]) };
  }
  await save(resolve(work, 'works/input-lock.json'), lock);
  console.log('TWO_SAME_INFORMATION_WORKS_FROZEN; no model call');
}

async function approvedBatch() {
  const protocol = await json(resolve(ledger, 'protocol.json'));
  const batch = protocol.activeBatch;
  if (!batch || batch.version !== 'candidate41-live' || batch.sourceCandidate !== 'candidate41'
      || batch.status !== 'OPEN' || batch.authorizationStatus !== 'APPROVED_BY_USER'
      || batch.qualityRetriesAllowed !== false || batch.callsBefore !== 422 || batch.maximumNewCalls !== 6
      || batch.purposeMaximums?.EXECUTION !== 2 || batch.purposeMaximums?.REVIEW !== 0
      || protocol.maximumActualModelCalls !== 438 || protocol.phaseLimits?.baseline !== 80
      || protocol.phaseLimits?.candidateValidation !== 358) throw Error('Incorrect or closed approved candidate41 batch');
  return batch;
}

async function verifyWork(group, arm, request) {
  const lock = await json(resolve(work, 'works/input-lock.json'));
  const cases = await json(resolve(pub, 'request-cases.json'));
  const sample = cases.find(row => row.id === 'TS-IMPLICIT');
  const confirmation = await json(resolve(work, 'flow/TS-IMPLICIT/actual-confirmation.private.json'));
  const manifest = await json(resolve(pub, 'candidate41-source.json'));
  if (lock.sourceCandidate !== 'candidate41' || lock.model !== 'deepseek-flash'
      || lock.casesSha256 !== hash(await readFile(resolve(pub, 'request-cases.json')))
      || lock.sourceManifestSha256 !== hash(await readFile(resolve(pub, 'candidate41-source.json')))
      || !equal(lock.actualConfirmations, confirmation.answers)) throw Error('Work scope or actual answer drift');
  for (const row of manifest.sourceFiles) if (hash(await readFile(resolve(row.path))) !== row.sha256) throw Error('Source drift');
  const copy = await verifiedCopy();
  const expected = arm === 'RAW' ? rawExecutionInput({ ...sample, context: { files: [] } }, confirmation.answers) : copy;
  if (group !== 'TS' || !arms.includes(arm) || request.model !== 'deepseek-flash'
      || request.messages?.length !== 1 || request.messages[0].role !== 'user' || request.messages[0].content !== expected
      || hash(copy) !== lock.groups.TS.nativeCopyHashes.PLAN
      || hash(JSON.stringify(request)) !== lock.groups.TS.arms[arm].requestSha256) throw Error('Work input was changed');
}

async function verifyReview(id, request) {
  const lock = await json(resolve(work, 'reviews/input-lock.json'));
  if (lock.sourceCandidate !== 'candidate41' || lock.independentCalls !== true
      || lock.casesSha256 !== hash(await readFile(resolve(pub, 'request-cases.json')))
      || request.model !== reviewModels[id] || hash(JSON.stringify(request)) !== lock.requestSha256?.[id]
      || request.messages?.length !== 2 || request.messages[0].role !== 'system' || request.messages[1].role !== 'user'
      || hash(request.messages[0].content) !== lock.systemSha256 || hash(request.messages[1].content) !== lock.packetSha256
      || !equal(request.response_format, { type: 'json_object' })) throw Error('Frozen review request drift');
  for (const group of ['TS']) for (const arm of arms) {
    const text = await readFile(resolve(work, 'works', group, arm, 'work.md'), 'utf8');
    if (hash(text) !== lock.workHashes?.[group]?.[arm]) throw Error('Reviewed work hash drift');
  }
  if (lock.hardChecksFileSha256 && lock.hardChecksFileSha256 !== hash(await readFile(resolve(work, 'hard-checks.json'))))
    throw Error('Review hard-check specification drift');
}

async function call(purpose, group, arm) {
  const id = purpose === 'REVIEW' ? group : arm;
  if (purpose === 'EXECUTION' && !permittedArms[group]?.includes(arm)
      || purpose === 'REVIEW' && !Object.hasOwn(reviewModels, id)) throw Error('Frozen operation required');
  const directory = purpose === 'EXECUTION' ? resolve(work, 'works', group, arm) : resolve(work, 'reviews', id);
  const request = await json(resolve(directory, 'request.json'));
  await approvedBatch();
  if (purpose === 'EXECUTION') await verifyWork(group, arm, request);
  else await verifyReview(id, request);
  for (const [key, value] of Object.entries(parameters)) if (!equal(request[key], value)) throw Error('Frozen model parameter drift');
  const key = process.env.MODEL_DEEPSEEK_API_KEY ?? process.env.MODEL_API_KEY;
  let endpoint;
  try { endpoint = new URL(process.env.MODEL_DEEPSEEK_ENDPOINT ?? 'https://api.deepseek.com/chat/completions'); }
  catch { throw Error('Invalid protected endpoint configuration'); }
  if (!key || endpoint.protocol !== 'https:' || endpoint.hostname !== 'api.deepseek.com'
      || endpoint.pathname !== '/chat/completions' || endpoint.username || endpoint.password || endpoint.search
      || endpoint.hash || (endpoint.port && endpoint.port !== '443')) throw Error('Official server-side model configuration unavailable');
  const operationId = purpose === 'EXECUTION' ? `${group}-${arm}` : id;
  // Operation guards are independent for the three arms and two reviews. Failure consumes the reservation.
  await save(resolve(directory, 'call-once.json'), { purpose, operationId, qualityRetriesAllowed: false, scheduledOnce: true });
  const cp = (await readFile(resolve(work, 'runner/classpath.txt'), 'utf8')).trim();
  const tracePath = execFileSync('E:/Delelop/Java/jdk-21/bin/java.exe', ['-Dfile.encoding=UTF-8', '-cp', cp,
    'ReserveEvaluationCall', purpose], { encoding: 'utf8', windowsHide: true }).trim();
  if (!/^\d+\.json$/.test(basename(tracePath)) || dirname(resolve(tracePath)) !== resolve(ledger, 'traces'))
    throw Error('Invalid canonical reservation path');
  const record = { version: 'candidate41-live', phase: 'candidateValidation', purpose, operation: operationId,
    sourceCandidate: 'candidate41', startedAt: new Date().toISOString(), request };
  await writeFile(tracePath, `${JSON.stringify(record, null, 2)}\n`);
  const started = Date.now();
  try {
    // Redirects are rejected, preventing any credential forwarding beyond the fixed official endpoint.
    const response = await fetch(endpoint, { method: 'POST', redirect: 'error',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` },
      body: JSON.stringify(request), signal: AbortSignal.timeout(120000) });
    const bytes = Buffer.from(await response.arrayBuffer());
    await save(resolve(directory, 'response.raw.bin'), bytes);
    record.httpStatus = response.status;
    record.responseRawSha256 = hash(bytes);
    try { record.response = JSON.parse(bytes.toString('utf8')); }
    catch { record.responseText = bytes.toString('utf8'); }
    record.elapsedMs = Date.now() - started;
    await writeFile(tracePath, `${JSON.stringify(record, null, 2)}\n`);
    await save(resolve(directory, 'response.json'), record);
    const completed = completedExecutionWork(record);
    await save(resolve(directory, purpose === 'EXECUTION' ? 'work.md' : 'review.md'), completed.text);
    console.log(JSON.stringify({ purpose, operationId, trace: basename(tracePath), httpStatus: response.status,
      finishReason: completed.finishReason, tokens: record.response?.usage, elapsedMs: record.elapsedMs }));
  } catch (error) {
    record.elapsedMs = Date.now() - started;
    record.failure = error.name;
    await writeFile(tracePath, `${JSON.stringify(record, null, 2)}\n`);
    await save(resolve(directory, 'failure.json'), { failure: error.name, elapsedMs: record.elapsedMs });
    throw Error(`Recorded external attempt failed: ${error.name}`);
  }
}

const mode = process.argv[2];
if (mode === 'prepare-works') await prepareWorks();
else if (mode === 'work') await call('EXECUTION', process.argv[3], process.argv[4]);
else if (mode === 'review') await call('REVIEW', process.argv[3]);
else if (!mode) console.log('Explicit operation required: prepare-works | work REQUESTS RAW|COMPACT|STRUCTURED | review R1|R2');
else throw Error('Unknown frozen external operation');
