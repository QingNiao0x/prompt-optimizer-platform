import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, realpath, stat, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
export const sha = bytes => createHash('sha256').update(bytes).digest('hex');
export const readJson = async file => {
  const bytes = await readFile(file); return { bytes, sha256: sha(bytes), value: JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, '')) };
};
export const writeNew = (file, value) => writeFile(file, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
export async function absent(file) {
  try { await stat(file); throw Error('Output already exists; labels and originals must not be overwritten'); }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
}

/** Closed public products only: no trace, private runtime, sealed material or intermediate body is opened. */
export async function openClosedStage(stage) {
  assert.match(stage, /^[a-z0-9-]+$/);
  const root = resolve(evidence, stage), closure = await readJson(resolve(root, 'closure.json'));
  assert.equal(closure.value.status, 'CLOSED'); assert.equal(closure.value.entryLockedByClosure, true);
  return { stage, root, closure, jobs: await readJson(resolve(root, 'jobs.json')), protocol: await readJson(resolve(root, 'protocol.json')) };
}

export async function lockedResult(source, job, expectedHash) {
  const file = resolve(source.root, 'results', job.id + '.json');
  const entry = source.closure.value.files.find(item => resolve(repo, item.path) === file); assert.ok(entry);
  const actual = await realpath(file); assert.ok(actual.startsWith(await realpath(source.root) + sep));
  const result = await readJson(file); assert.equal(result.sha256, entry.sha256);
  if (expectedHash) assert.equal(result.sha256, expectedHash);
  assert.equal(result.value.id, job.id); assert.equal(result.value.caseId, job.caseId);
  assert.equal(result.value.version, job.version); assert.equal(result.value.phase, 'EXECUTION');
  const delivery = source.closure.value.deliveries.find(item => item.id === job.id); assert.ok(delivery);
  assert.equal(delivery.status, result.value.status);
  return { ...result, file };
}

/** Metadata is whitelisted; successful or truncated text is retained byte-for-byte, never replaced with a better attempt. */
export function anonymousWork(result, label) {
  const content = result.status === 200 ? result.body?.data?.content : null;
  if (result.status !== 200) return { label, deliveryState: 'NOT_DELIVERED', content: null,
    failure: { httpStatus: result.status, code: typeof result.body?.error?.code === 'string' ? result.body.error.code : 'PUBLIC_REQUEST_FAILED' } };
  assert.equal(typeof content, 'string');
  const state = !content.trim() ? 'EMPTY' : result.body.data.finishReason === 'stop' ? 'DELIVERED' : 'INCOMPLETE';
  return { label, deliveryState: state, content, failure: state === 'DELIVERED' ? null : { code: state } };
}

/** Deterministic once a private random seed is saved; no version or repeated-arm grouping leaks into public labels. */
export function randomOrder(items, seed, caseId) {
  assert.match(seed, /^[a-f0-9]{64}$/);
  return [...items].sort((a, b) => sha(seed + '\0' + caseId + '\0' + a.key).localeCompare(sha(seed + '\0' + caseId + '\0' + b.key)));
}

/** The frozen job ID always defines the repeat; an optional duplicate field must agree, never replace it. */
export function repeatFromFrozenJob(job) {
  assert.equal(typeof job.id, 'string');
  const match = job.id.match(/_R([12])$/); assert.ok(match, 'Frozen execution job must end with _R1 or _R2');
  const repeat = Number(match[1]);
  if (Object.hasOwn(job, 'repeat')) assert.equal(job.repeat, repeat, 'Repeat field conflicts with frozen job ID');
  return repeat;
}

export function normalizedRequest(job, sample, profile) {
  const request = structuredClone(job.approvedModelRequest); assert.ok(request?.messages);
  assert.equal(request.messages[0].role, 'system'); assert.equal(sha(request.messages[0].content), profile.systemSha256);
  assert.equal(request.max_tokens, profile.maxTokens); assert.equal(request.model, 'deepseek-flash');
  assert.equal(request.temperature, 0.2); assert.deepEqual(request.thinking, { type: 'disabled' }); assert.equal(request.stream, false);
  assert.equal(request.messages[1].role, 'user');
  if (sample.materialSuffix !== undefined) {
    assert.equal(request.messages.length, 2); assert.equal(sha(sample.materialSuffix), sample.materialSuffixSha256);
    assert.ok(request.messages[1].content.endsWith(sample.materialSuffix));
    const prompt = request.messages[1].content.slice(0, -sample.materialSuffix.length);
    assert.ok(prompt.trim()); if (job.version === 'ORIGINAL') assert.equal(prompt, sample.rawPrompt);
    request.messages[1].content = '<PROMPT_VARIANT>' + sample.materialSuffix;
  } else {
    assert.equal(request.messages.length, 3); assert.equal(request.messages[2].role, 'user');
    assert.equal(sha(request.messages[2].content), profile.commonMaterialsMessageSha256);
    if (job.version === 'ORIGINAL') assert.equal(request.messages[1].content, sample.rawPrompt);
    request.messages[1].content = '<PROMPT_VARIANT>';
  }
  return request;
}

export function relativeFile(file) {
  const path = relative(repo, file); assert.ok(path && !isAbsolute(path) && !path.startsWith('..'));
  return path.replaceAll('\\', '/');
}
