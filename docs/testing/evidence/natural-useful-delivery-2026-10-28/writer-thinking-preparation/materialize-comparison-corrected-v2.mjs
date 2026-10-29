import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
import { dirname, resolve, relative, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const repository = resolve(root, '../../../..');
const sha = value => createHash('sha256').update(value).digest('hex');
const readJson = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));

/** True only when one completed JSON document is missing its outermost object closer. */
export function onlyRootObjectIsOpen(value) {
  const stack = [];
  let insideString = false, escaped = false;
  for (const character of value) {
    if (insideString) {
      if (escaped) escaped = false;
      else if (character === '\\') escaped = true;
      else if (character === '"') insideString = false;
      continue;
    }
    if (character === '"') insideString = true;
    else if (character === '{' || character === '[') stack.push(character);
    else if (character === '}' || character === ']') {
      if (stack.pop() !== (character === '}' ? '{' : '[')) return false;
    }
  }
  return !insideString && !escaped && stack.length === 1 && stack[0] === '{';
}

/**
 * Mirror only the product's existing one-root-closer compatibility, in memory.
 * A hash-bound successful product JSON parse is also required by the caller: this
 * function cannot silently accept duplicate keys that the product parser rejects.
 */
export function parseStopResponse(content, finishReason) {
  assert.equal(finishReason, 'stop', '非完整响应不得提取为正式正文');
  assert.equal(typeof content, 'string');
  const value = content.trim();
  try { return { parsed: JSON.parse(value), usedRootClosure: false }; }
  catch (failure) {
    if (!value.startsWith('{') || !/[}\]]$/.test(value) || !onlyRootObjectIsOpen(value)) throw failure;
    return { parsed: JSON.parse(value + '}'), usedRootClosure: true };
  }
}

/** The actual Java parser must already have accepted these exact candidate bytes. */
export function confirmedExtraction(content, finishReason, evidence) {
  assert.equal(evidence?.inputProvenance?.actualContentSha256, sha(content), '产品解析证据与候选字节不符');
  assert.ok(evidence.steps?.some(step => step.stage === 'product.unwrapAndReadResponse' && step.status === 'PASS'), '没有原产品JSON解析通过证据');
  const extracted = parseStopResponse(content, finishReason);
  assert.equal(typeof extracted.parsed.optimizedPrompt, 'string', '产品候选没有可抽取正文');
  return { optimizedPrompt: extracted.parsed.optimizedPrompt, usedRootClosure: extracted.usedRootClosure };
}

function selfTest() {
  const body = '  原样正文\n包含括号{与[、转义引号"和反斜杠\\。  ';
  const valid = JSON.stringify({ optimizedPrompt: body, taskUnderstanding: { requirements: [] } });
  const evidence = content => ({ inputProvenance: { actualContentSha256: sha(content) }, steps: [{ stage: 'product.unwrapAndReadResponse', status: 'PASS' }] });
  assert.equal(confirmedExtraction(valid, 'stop', evidence(valid)).optimizedPrompt, body);
  const missingRoot = valid.slice(0, -1);
  assert.equal(confirmedExtraction(missingRoot, 'stop', evidence(missingRoot)).optimizedPrompt, body);
  assert.equal(confirmedExtraction(missingRoot, 'stop', evidence(missingRoot)).usedRootClosure, true);
  for (const truncated of [
    '{"optimizedPrompt":"unfinished',
    '{"optimizedPrompt":"ok","items":[{"value":1}',
    '{"optimizedPrompt":"ok","record":{"inner":{"value":1}',
    '{"optimizedPrompt":12',
    '[{"optimizedPrompt":"ok"}',
    '{"optimizedPrompt":"ok"} trailing',
  ]) assert.throws(() => parseStopResponse(truncated, 'stop'));
  assert.throws(() => parseStopResponse(valid, 'length'));
  assert.throws(() => confirmedExtraction(valid, 'stop', evidence(missingRoot)));
  const duplicate = '{"optimizedPrompt":"one","optimizedPrompt":"two"}';
  assert.throws(() => confirmedExtraction(duplicate, 'stop', { inputProvenance: { actualContentSha256: sha(duplicate) }, steps: [{ stage: 'product.unwrapAndReadResponse', status: 'FAIL' }] }));
  console.log(JSON.stringify({ selfTest: 'PASS', candidateEdited: false, modelCalls: 0 }));
}

async function materialize(stage) {
  assert.match(stage ?? '', /^[a-z0-9-]+$/);
  assert.ok(!stage.startsWith('sealed-'));
  const stagePath = resolve(root, stage);
  const closure = await readJson(resolve(stagePath, 'closure.json'));
  assert.equal(closure.status, 'CLOSED'); assert.equal(closure.entryLockedByClosure, true);
  for (const entry of closure.files) {
    const file = resolve(repository, entry.path);
    const suffix = relative(stagePath, file);
    assert.ok(suffix && !isAbsolute(suffix) && suffix !== '..' && !suffix.startsWith('..\\') && !suffix.startsWith('../'));
    assert.equal(sha(await readFile(file)), entry.sha256, '闭批原件哈希不符');
  }
  const jobsBytes = await readFile(resolve(stagePath, 'jobs.json'));
  const jobs = JSON.parse(jobsBytes.toString('utf8'));
  assert.equal(jobs.length, 6);
  const productPath = resolve(root, 'offline-result-validation/writer-thinking-validation.json');
  const product = await readJson(productPath);
  assert.equal(product.closureSha256, sha(await readFile(resolve(stagePath, 'closure.json'))));
  assert.equal(product.modelCallsMadeByReplay, 0);
  const oldOutput = resolve(here, 'anonymous', stage);
  const oldManifest = await readJson(resolve(oldOutput, 'manifest.json'));
  for (const item of oldManifest.packetHashes) assert.equal(sha(await readFile(resolve(oldOutput, item.file))), item.sha256, '原匿名包已变化');
  const keyPath = resolve(here, 'coordinator', stage + '-map.json');
  const key = await readJson(keyPath);
  assert.equal(key.stage, stage); assert.equal(key.map.length, 6);
  const output = resolve(here, 'anonymous', stage + '-corrected-v2');
  try { await access(output); throw new Error('修正版目录已存在，不覆盖'); }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  const packets = [], audit = [];
  for (const caseId of ['Q01', 'U02', 'U03']) {
    const oldPacket = await readJson(resolve(oldOutput, caseId + '.json'));
    const mappings = key.map.filter(item => item.caseId === caseId);
    assert.equal(mappings.length, 2);
    assert.deepEqual(new Set(mappings.map(item => item.label)), new Set(['A', 'B']));
    const works = [];
    for (const label of ['A', 'B']) {
      const mapping = mappings.find(item => item.label === label);
      const job = jobs.find(item => item.id === mapping.jobId);
      assert.ok(job && job.caseId === caseId);
      const result = await readJson(resolve(stagePath, 'results', job.id + '.json'));
      const content = result.body?.data?.content, finishReason = result.body?.data?.finishReason;
      assert.equal(result.status, 200);
      const evidence = product.results.find(item => item.jobId === job.id);
      assert.equal(evidence?.inputProvenance?.jobsSha256, sha(jobsBytes));
      assert.equal(mapping.resultContentSha256, sha(content));
      const extracted = confirmedExtraction(content, finishReason, evidence);
      const previous = oldPacket.works.find(item => item.label === label);
      if (previous.optimizedPrompt !== null) assert.equal(extracted.optimizedPrompt, previous.optimizedPrompt, '已有效正文不得改写');
      works.push({ label, httpStatus: result.status, finishReason, extractionState: 'BODY_EXTRACTED_NOT_PRODUCT_VALIDATED', optimizedPrompt: extracted.optimizedPrompt });
      // Parse mechanics and coordinator identities are deliberately absent from the blind packet.
      audit.push({ caseId, label, jobId: job.id, thinkingArm: mapping.thinkingArm, sourceContentSha256: sha(content), extractedBodySha256: sha(extracted.optimizedPrompt), usedRootClosure: extracted.usedRootClosure, priorExtractionState: previous.extractionState, productOutcome: evidence.outcome });
    }
    const packet = { ...oldPacket, works };
    const bytes = JSON.stringify(packet, null, 2) + '\n';
    packets.push({ file: caseId + '.json', bytes, sha256: sha(bytes) });
  }
  await mkdir(output, { recursive: true });
  for (const packet of packets) await writeFile(resolve(output, packet.file), packet.bytes, { flag: 'wx' });
  await writeFile(resolve(output, 'manifest.json'), JSON.stringify({ stage, packetHashes: packets.map(({ file, sha256 }) => ({ file, sha256 })), modeIdentitiesHidden: true, sourceClosureSha256: sha(await readFile(resolve(stagePath, 'closure.json'))), previousPacketManifestSha256: sha(await readFile(resolve(oldOutput, 'manifest.json'))), actualNewModelCalls: 0 }, null, 2) + '\n', { flag: 'wx' });
  await writeFile(resolve(here, 'coordinator', stage + '-corrected-v2-audit.json'), JSON.stringify({ coordinatorOnly: true, originalMapSha256: sha(await readFile(keyPath)), originalMapPreserved: true, originalPacketPreserved: true, rawCandidatesEdited: false, productParseEvidenceSha256: sha(await readFile(productPath)), audit, actualNewModelCalls: 0 }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ output, packets: 3, works: 6, originalMapPreserved: true, actualNewModelCalls: 0 }));
}

if (process.argv[2] === '--self-test') selfTest();
else await materialize(process.argv[2]);
