// Server-side only. No implicit operation, browser access, quality retry or credential archival.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname, basename } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { rawExecutionInput, enhancedExecutionInput, completedExecutionWork } from '../../../../scripts/me00a-eval.mjs';

const pub = resolve('docs/testing/evidence/me04-candidate34-framework-natural-2026-10-18');
const work = resolve('tmp/me04-candidate34-live-20261018');
const ledger = resolve('tmp/me00a-20261012');
const parameters = { temperature: 0.2, max_tokens: 10000, thinking: { type: 'disabled' }, stream: false };
const groups = { REQUESTS: 'REQUESTS' };
const arms = ['RAW', 'COMPACT', 'STRUCTURED'];
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
async function verifiedCopy(caseId, format) {
  const directory = resolve(work, 'live-results', caseId, format);
  const platform = await json(resolve(directory, 'platform.private.json'));
  const copied = await readFile(resolve(directory, 'native-copy.md'), 'utf8');
  const check = await json(resolve(directory, 'check.json'));
  if (platform.status !== 200 || platform.body.data.promptFormat !== format
      || platform.body.data.provider.mock !== false || !check.success || !check.nativeCopyMatches
      || check.copySha256 !== hash(copied) || !check.history?.formatMatches
      || newline(copied) !== newline(platform.body.data.optimizedPrompt)) throw Error('Verified successful native copy required');
  return copied;
}

/** All three arms contain exactly one user message; no system task instruction is added. */
async function prepareWorks() {
  const caseBytes = await readFile(resolve(pub, 'live-cases.json'));
  const cases = JSON.parse(caseBytes.toString('utf8')).cases;
  const source = await readFile(resolve(pub, 'candidate34-source.json'));
  if (JSON.parse(source.toString('utf8')).version !== 'candidate34') throw Error('Wrong source candidate');
  const lock = { sourceCandidate: 'candidate34', casesSha256: hash(caseBytes), sourceManifestSha256: hash(source),
    parameters, model: 'deepseek-flash', systemTaskInstructionAdded: false, actualConfirmations: [],
    sharedMaterials: [], groups: {} };
  for (const [group, caseId] of Object.entries(groups)) {
    const sample = cases.find(item => item.id === caseId);
    if (!sample || sample.planConfirmation !== null || sample.includeExamples !== false || sample.files.length)
      throw Error('Unexpected work case');
    const executionSample = { ...sample, context: { files: sample.files } };
    let copies;
    try { copies = { COMPACT: await verifiedCopy(caseId, 'COMPACT'), STRUCTURED: await verifiedCopy(caseId, 'STRUCTURED') }; }
    catch (error) {
      await save(resolve(work, 'works', group, 'dependency-skipped.json'), { state: 'SKIPPED_DEPENDENCY', reason: 'Both verified native copies are required; no reconstructed or substituted prompt.', detail: error.message });
      console.log('WORK_AND_REVIEW_DEPENDENCIES_SKIPPED; no model call');
      return;
    }
    const inputs = { RAW: rawExecutionInput(executionSample, []), COMPACT: enhancedExecutionInput(copies.COMPACT, executionSample),
      STRUCTURED: enhancedExecutionInput(copies.STRUCTURED, executionSample) };
    if (inputs.RAW !== sample.rawPrompt || inputs.COMPACT !== copies.COMPACT || inputs.STRUCTURED !== copies.STRUCTURED)
      throw Error('Execution helper changed frozen task');
    lock.groups[group] = { caseId, nativeCopyHashes: { COMPACT: hash(copies.COMPACT), STRUCTURED: hash(copies.STRUCTURED) }, arms: {} };
    for (const arm of arms) {
      const request = { model: 'deepseek-flash', ...parameters, messages: [{ role: 'user', content: inputs[arm] }] };
      await save(resolve(work, 'works', group, arm, 'request.json'), request);
      lock.groups[group].arms[arm] = { requestSha256: hash(JSON.stringify(request)), promptSha256: hash(inputs[arm]) };
    }
  }
  await save(resolve(work, 'works/input-lock.json'), lock);
  console.log('THREE_USER_ONLY_WORK_REQUESTS_FROZEN; no model call');
}

async function approvedBatch() {
  const protocol = await json(resolve(ledger, 'protocol.json'));
  const batch = protocol.activeBatch;
  if (!batch || batch.version !== 'candidate34-live' || batch.sourceCandidate !== 'candidate34'
      || batch.status !== 'OPEN' || batch.authorizationStatus !== 'APPROVED_BY_USER'
      || batch.qualityRetriesAllowed !== false || batch.callsBefore !== 381 || batch.maximumNewCalls !== 8
      || batch.purposeMaximums?.EXECUTION !== 3 || batch.purposeMaximums?.REVIEW !== 2
      || protocol.maximumActualModelCalls !== 399 || protocol.phaseLimits?.baseline !== 80
      || protocol.phaseLimits?.candidateValidation !== 319) throw Error('Incorrect or closed approved candidate34 batch');
  return batch;
}

async function verifyWork(group, arm, request) {
  const lock = await json(resolve(work, 'works/input-lock.json'));
  if (lock.sourceCandidate !== 'candidate34' || lock.model !== 'deepseek-flash'
      || lock.casesSha256 !== hash(await readFile(resolve(pub, 'live-cases.json')))
      || lock.sourceManifestSha256 !== hash(await readFile(resolve(pub, 'candidate34-source.json')))
      || lock.groups[group]?.caseId !== groups[group]) throw Error('Work scope hash drift');
  for (const format of ['COMPACT', 'STRUCTURED']) {
    if (hash(await verifiedCopy(groups[group], format)) !== lock.groups[group].nativeCopyHashes[format]) throw Error('Copy hash drift');
  }
  if (request.model !== 'deepseek-flash' || hash(JSON.stringify(request)) !== lock.groups[group].arms[arm]?.requestSha256
      || request.messages?.length !== 1 || request.messages[0].role !== 'user'
      || hash(request.messages[0].content) !== lock.groups[group].arms[arm].promptSha256) throw Error('Work request drift');
  const cases = (await json(resolve(pub, 'live-cases.json'))).cases;
  const expected = arm === 'RAW' ? cases.find(item => item.id === groups[group]).rawPrompt : await verifiedCopy(groups[group], arm);
  if (request.messages[0].content !== expected) throw Error('Work input changed frozen raw/copy');
}

async function verifyReview(id, request) {
  const lock = await json(resolve(work, 'reviews/input-lock.json'));
  if (lock.sourceCandidate !== 'candidate34' || lock.independentCalls !== true
      || lock.casesSha256 !== hash(await readFile(resolve(pub, 'live-cases.json')))
      || request.model !== reviewModels[id] || hash(JSON.stringify(request)) !== lock.requestSha256?.[id]
      || request.messages?.length !== 2 || request.messages[0].role !== 'system' || request.messages[1].role !== 'user'
      || hash(request.messages[0].content) !== lock.systemSha256 || hash(request.messages[1].content) !== lock.packetSha256
      || !equal(request.response_format, { type: 'json_object' })) throw Error('Frozen review request drift');
  for (const group of Object.keys(groups)) for (const arm of arms) {
    const text = await readFile(resolve(work, 'works', group, arm, 'work.md'), 'utf8');
    if (hash(text) !== lock.workHashes?.[group]?.[arm]) throw Error('Reviewed work hash drift');
  }
  if (lock.hardChecksFileSha256 && lock.hardChecksFileSha256 !== hash(await readFile(resolve(work, 'hard-checks.json'))))
    throw Error('Review hard-check specification drift');
}

async function call(purpose, group, arm) {
  const id = purpose === 'REVIEW' ? group : arm;
  if (purpose === 'EXECUTION' && (!Object.hasOwn(groups, group) || !arms.includes(arm))
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
  const record = { version: 'candidate34-live', phase: 'candidateValidation', purpose, operation: operationId,
    sourceCandidate: 'candidate34', startedAt: new Date().toISOString(), request };
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
