import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

// Finite closed-product comparison only. Never opens traces, runtime sessions,
// source registries or sealed inputs, and never makes a network request.
const here = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(here, '../..'), repo = resolve(evidence, '../../../..');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const read = async path => {
  const bytes = await readFile(path); return { bytes, value: JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, '')), sha256: hash(bytes) };
};
const writeNew = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const absent = async path => {
  try { await stat(path); throw Error('Anonymous output already exists; no overwrite or relabeling'); }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
};
const stages = ['registry50-development', 'model-selection50-development'];
const sources = [];
for (const stage of stages) {
  const root = resolve(evidence, stage), closure = await read(resolve(root, 'closure.json'));
  assert.equal(closure.value.status, 'CLOSED'); assert.equal(closure.value.entryLockedByClosure, true);
  const protocol = await read(resolve(root, 'protocol.json')), jobs = await read(resolve(root, 'jobs.json'));
  assert.equal(jobs.value.length, 3);
  assert.ok(jobs.value.every(job => job.phase === 'GENERATION' && job.version === '50'));
  assert.ok(isDeepStrictEqual([...jobs.value.map(job => job.caseId)].sort(), ['Q01', 'U02', 'U03']));
  const publicFilesVerified = [];
  // Verify public result artifacts listed in the closure, but do not decode or
  // even open intermediate model traces. Only final products enter the packet.
  for (const entry of closure.value.files) {
    const file = resolve(repo, entry.path), suffix = relative(root, file);
    assert.ok(suffix && !isAbsolute(suffix) && !suffix.startsWith('..'));
    if (!suffix.replaceAll('\\', '/').startsWith('results/')) continue;
    assert.equal(hash(await readFile(file)), entry.sha256, 'Closed public result hash drifted');
    publicFilesVerified.push({ path: entry.path, sha256: entry.sha256 });
  }
  sources.push({ stage, root, closure, protocol, jobs, publicFilesVerified });
}
const profile = sources[1].protocol.value.modelSelectionProfile;
assert.equal(profile.name, 'OFFICIAL_MODEL_SELECTION_ABLATION_V1');
assert.equal(profile.baselineStage, sources[0].stage);
assert.equal(profile.baselineJobsSha256, sources[0].jobs.sha256);
assert.equal(profile.baselineProtocolSha256, sources[0].protocol.sha256);
assert.equal(sources[0].protocol.value.modelId, 'deepseek:deepseek-flash');
assert.equal(sources[1].protocol.value.modelId, 'deepseek:deepseek-v4-pro');
assert.ok(isDeepStrictEqual(sources[0].protocol.value.parameters, sources[1].protocol.value.parameters));

const requirementsFile = resolve(evidence, 'registry49-requirements-review-plan.md');
const requirements = await readFile(requirementsFile, 'utf8');
const headings = ['## Q01：查询全球使用AI最多的职业/行业', '## U02/C02：两周周末亲子共读试办方案',
  '## U03/C03：工具借用反馈分析与下一轮调整', '## 核真实正文时的最小核对'];
const taskCriteria = index => {
  const start = requirements.indexOf(headings[index]), end = requirements.indexOf(headings[index + 1]);
  assert.ok(start >= 0 && end > start); return requirements.slice(start + headings[index].length, end).trim();
};
const packetDirectory = resolve(here, 'packets'), coordinatorDirectory = resolve(here, 'coordinator');
await absent(packetDirectory); await absent(resolve(coordinatorDirectory, 'map.json'));
const packets = [], mapping = [], equivalence = [];
for (const [caseIndex, caseId] of ['Q01', 'U02', 'U03'].entries()) {
  const pair = sources.map(source => ({ source, job: source.jobs.value.find(job => job.caseId === caseId) }));
  const stripped = pair.map(({ job }) => { const value = structuredClone(job.approvedEnhancementInput); delete value.modelId; return value; });
  assert.ok(isDeepStrictEqual(stripped[0], stripped[1]), 'Complete original input differs beyond model selection');
  assert.deepEqual(stripped[0].conversationHistory, []); assert.equal(stripped[0].planConfirmation, null);
  equivalence.push({ caseId, identicalExceptModelId: true, sharedInputSha256: hash(JSON.stringify(stripped[0])),
    inputHashes: pair.map(({ source, job }) => ({ stage: source.stage, sha256: hash(JSON.stringify(job.approvedEnhancementInput)) })) });
  if (randomBytes(1)[0] % 2) pair.reverse();
  const variants = [];
  for (const [index, { source, job }] of pair.entries()) {
    const label = index === 0 ? 'A' : 'B', path = resolve(source.root, 'results', job.id + '.json');
    const result = await read(path);
    const entry = source.closure.value.files.find(item => resolve(repo, item.path) === path);
    assert.ok(entry); assert.equal(result.sha256, entry.sha256);
    assert.equal(result.value.caseId, caseId); assert.equal(result.value.id, job.id);
    const delivered = source.closure.value.deliveries.find(item => item.id === job.id);
    assert.ok(delivered); assert.equal(delivered.status, result.value.status);
    let optimizedPrompt = null, publicError = null;
    if (result.value.status === 200) {
      assert.equal(delivered.delivered, true);
      const product = result.value.body.data;
      assert.equal(product.provider.mock, false); assert.equal(product.chainVersion, '50');
      assert.ok(product.processing.completedStages.includes('BODY_GENERATION'));
      assert.equal(product.resultKind, 'TEXT');
      assert.ok(typeof product.optimizedPrompt === 'string' && product.optimizedPrompt.trim());
      optimizedPrompt = product.optimizedPrompt; // Preserve exact final bytes as a JSON string, no rewrites.
    } else {
      assert.equal(delivered.delivered, false);
      const error = result.value.body.error;
      assert.ok(typeof error.code === 'string' && typeof error.message === 'string' && typeof error.retryable === 'boolean');
      publicError = { code: error.code, message: error.message, retryable: error.retryable };
    }
    variants.push({ label, deliveryState: optimizedPrompt === null ? 'NOT_DELIVERED' : 'DELIVERED',
      publicHttpStatus: result.value.status, optimizedPrompt, publicError });
    mapping.push({ caseId, label, stage: source.stage, jobId: job.id, modelId: job.approvedEnhancementInput.modelId,
      resultFile: relative(repo, path).replaceAll('\\', '/'), resultSha256: result.sha256,
      finalBodySha256: optimizedPrompt === null ? null : hash(optimizedPrompt), delivered: optimizedPrompt !== null });
  }
  packets.push({ caseId, scope: '三道已见开发题的内部匿名最终产品正文审阅；不是未见题、实际作品或整体采用验收。',
    originalInput: stripped[0], variants,
    criteria: { originalRequirementsAndAllowedVariants: taskCriteria(caseIndex),
      common: ['保留原目标、事实状态、全部独立条件及真正委托范围。',
        '补充具体处理内容和可执行要求，直接帮助原定交付；篇幅、重排或同义改写不计收益。',
        '共享材料仍为执行输入，不要求逐句重抄；正文若主动安排相反做法，材料中的正确原句不能抵消误导。',
        '接受原计划允许的合理设计变体；指出最小必要删改，不依赖模型自报或内部检查表。'],
      failureBoundary: '未交付仅计产品交付失败，不存在可审正文；不得以另一臂存在正文认定其语义胜出，不补看中间稿。' },
    reviewRequest: '分别判断每个已交付正文的意图、硬条件、事实/建议/推断状态及有用补全，引用实际原句；两份均交付时再比较必要修改，允许持平、混合或都不通过。' });
}
await mkdir(packetDirectory, { recursive: true }); await mkdir(coordinatorDirectory, { recursive: true });
const packetHashes = [];
for (const packet of packets) {
  const filename = packet.caseId + '.json', bytes = JSON.stringify(packet, null, 2) + '\n';
  await writeFile(resolve(packetDirectory, filename), bytes, { flag: 'wx' }); packetHashes.push({ file: filename, sha256: hash(bytes) });
}
const coordinator = { coordinatorOnly: true, sourceStages: sources.map(source => ({ stage: source.stage,
  closureSha256: source.closure.sha256, jobsSha256: source.jobs.sha256, protocolSha256: source.protocol.sha256,
  verifiedPublicResults: source.publicFilesVerified })), inputEquivalence: equivalence, mapping,
  criteriaSource: { file: relative(repo, requirementsFile).replaceAll('\\', '/'), sha256: hash(requirements) },
  packetHashes, traceContentsOpened: false, intermediateBodiesExtracted: false, actualNewModelCalls: 0 };
await writeNew(resolve(coordinatorDirectory, 'map.json'), coordinator);
await writeNew(resolve(packetDirectory, 'manifest.json'), { packetHashes, armIdentitiesHidden: true,
  finalProductsOnly: true, failedBodiesNull: true, actualNewModelCalls: 0 });
console.log(JSON.stringify({ packets: packets.length, packetHashes,
  coordinatorMapSha256: hash(await readFile(resolve(coordinatorDirectory, 'map.json'))), actualNewModelCalls: 0 }));
