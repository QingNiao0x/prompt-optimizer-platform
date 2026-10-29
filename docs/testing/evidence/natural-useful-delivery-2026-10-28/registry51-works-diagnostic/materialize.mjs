import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdir, readFile, realpath } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { here, evidence, repo, sha, readJson, writeNew, absent, openClosedStage, lockedResult,
  anonymousWork, randomOrder, normalizedRequest, relativeFile, repeatFromFrozenJob } from './support.mjs';

if (process.argv.includes('--help')) {
  console.log('node materialize.mjs --materialize-closed. Finite five-case diagnostic only, after all three registry51 work stages close. No HTTP/model/code execution. Public packets here; private mapping only under repository tmp.'); process.exit(0);
}
assert.ok(process.argv.includes('--materialize-closed'), 'Default is no action; use --help');
const stageSpecs = [
  { name: 'registry51-query-works', cases: ['Q01'], arms: ['51'], count: 2, cap: 2, tokens: 8192 },
  { name: 'registry51-content-works', cases: ['U02', 'U03'], arms: ['ORIGINAL', '31', '51'], count: 12, cap: 12, tokens: 4500 },
  { name: 'registry51-code-works', cases: ['C01', 'C02'], arms: ['51'], count: 4, cap: 4, tokens: 8192 },
];
// Check every new closure before reading any new product. A running stage is never review input.
const fresh = [];
for (const spec of stageSpecs) {
  const source = await openClosedStage(spec.name), jobs = source.jobs.value;
  const expected = spec.cases.flatMap(caseId => spec.arms.flatMap(version => [1, 2].map(repeat => `${caseId}_${version === 'ORIGINAL' ? 'ORIGINAL' : 'V' + version}_EXEC_R${repeat}`)));
  assert.equal(jobs.length, spec.count); assert.deepEqual(jobs.map(job => job.id).sort(), expected.sort());
  assert.equal(source.protocol.value.phaseLimits.EXECUTION, spec.cap);
  for (const phase of ['GENERATION', 'PLAN', 'REVIEW']) assert.equal(source.protocol.value.phaseLimits[phase], 0);
  for (const job of jobs) assert.equal(job.phase, 'EXECUTION');
  fresh.push({ ...source, spec });
}
const preparation = resolve(evidence, 'registry51-works-preparation');
const content = await readJson(resolve(preparation, 'bundle.json'));
const contentProof = await readJson(resolve(preparation, 'reuse-provenance.json'));
const code = await readJson(resolve(preparation, 'code-planned-bundle.json'));
const codeProof = await readJson(resolve(preparation, 'code-reuse-provenance.json'));
const reviewPlan = await readJson(resolve(preparation, 'preflight-review-plan.json'));
assert.equal(content.sha256, contentProof.value.bundleSha256); assert.equal(code.sha256, codeProof.value.plannedBundleSha256);
assert.equal(reviewPlan.value.bundleSha256, content.sha256); assert.equal(reviewPlan.value.reuseProofSha256, contentProof.sha256);
const oldQuery = await openClosedStage('content-works46'), oldCode = await openClosedStage('code-works46');
const packetDirectory = resolve(here, 'packets');
const privateRoot = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry51-works-diagnostic/coordinator');
await absent(packetDirectory); await absent(resolve(privateRoot, 'map.json'));
await mkdir(privateRoot, { recursive: true });
assert.ok((await realpath(privateRoot)).startsWith(await realpath(resolve(repo, 'tmp')) + sep));
const seed = randomBytes(32).toString('hex'), packets = [], mapping = [], equivalence = [];
for (const caseId of ['Q01', 'U02', 'U03', 'C01', 'C02']) {
  const codeCase = caseId.startsWith('C'), sample = codeCase ? code.value.cases.find(item => item.caseId === caseId) : content.value.cases.find(item => item.id === caseId);
  assert.ok(sample);
  const source = fresh.find(item => item.spec.cases.includes(caseId));
  const profile = codeCase ? { systemSha256: sample.systemSha256, maxTokens: 8192 }
    : { ...contentProof.value.profiles.find(item => item.caseId === caseId), maxTokens: caseId === 'Q01' ? 8192 : 4500 };
  const entries = source.jobs.value.filter(job => job.caseId === caseId).map(job => ({ source, job }));
  const reuse = codeCase ? code.value.reusedOriginalWorks : caseId === 'Q01' ? content.value.reusedWorkResults : [];
  for (const original of reuse.filter(item => item.caseId === caseId)) {
    const old = codeCase ? oldCode : oldQuery, job = old.jobs.value.find(item => item.id === original.sourceJobId);
    assert.ok(job && job.caseId === caseId && job.version === original.version);
    assert.equal(resolve(repo, original.result.path), resolve(old.root, 'results', job.id + '.json'));
    entries.push({ source: old, job, expectedHash: original.result.sha256 });
  }
  const expectedArms = codeCase ? ['ORIGINAL', '51'] : ['ORIGINAL', '31', '51'];
  assert.equal(entries.length, expectedArms.length * 2);
  for (const version of expectedArms) assert.deepEqual(entries.filter(item => item.job.version === version).map(item => repeatFromFrozenJob(item.job)).sort(), [1, 2]);
  const requests = entries.map(item => normalizedRequest(item.job, sample, profile));
  assert.ok(requests.every(value => isDeepStrictEqual(value, requests[0])), 'Sources, system or execution profile differ beyond the prompt');
  equivalence.push({ caseId, normalizedRequestSha256: sha(JSON.stringify(requests[0])), onlyPromptDiffers: true,
    olderBaselineReused: caseId === 'Q01' || codeCase, maxTokens: profile.maxTokens });
  const ordered = randomOrder(entries.map(item => ({ ...item, key: item.source.stage + '/' + item.job.id })), seed, caseId), variants = [];
  for (const [index, entry] of ordered.entries()) {
    const result = await lockedResult(entry.source, entry.job, entry.expectedHash), label = String.fromCharCode(65 + index);
    const publicWork = anonymousWork(result.value, label); variants.push(publicWork);
    mapping.push({ caseId, label, stage: entry.source.stage, jobId: entry.job.id, version: entry.job.version, repeat: repeatFromFrozenJob(entry.job),
      result: { path: relativeFile(result.file), sha256: result.sha256 },
      contentSha256: publicWork.content === null ? null : sha(publicWork.content), deliveryState: publicWork.deliveryState });
  }
  // Whitelist original inputs only. No enhanced prompt, generation metadata, source-stage name or answer card enters a packet.
  packets.push({ caseId, originalPrompt: sample.rawPrompt, originalMaterials: sample.executorMaterials.map(item => ({ path: item.path, content: item.content })),
    contextDescription: sample.contextDescription ?? '', variants,
    review: { purpose: '有限实际作品诊断；不代表整体采用或发布验收。',
      criteria: reviewPlan.value.review.criteria,
      instructions: ['逐份引用作品与原始材料，判断原目标、事实状态、条件和范围是否保留，指出最小必要修改。',
        '允许合理设计变体，篇幅、标题数量或候选自增要求不计收益；完整交付也不自动说明质量通过。',
        '所有重复与失败均保留，允许持平、混合或都不通过，不推断真人修改分钟数。'],
      codeScope: codeCase ? '代码作品另用既有独立行为检查器；不在宿主直接运行模型源码。' : null,
      limitations: ['部分已闭批基线可能在较早时间执行，模型实际权重版本不可由请求证明。', '少量重复只作描述，不能证明统计稳定或整体质量提升。'] } });
}
const packetHashes = packets.map(packet => ({ file: packet.caseId + '.json', sha256: sha(JSON.stringify(packet, null, 2) + '\n') }));
const allSources = [...fresh, oldQuery, oldCode];
const map = { coordinatorOnly: true, seed, mapping, inputEquivalence: equivalence,
  sourceStages: allSources.map(source => ({ stage: source.stage, closureSha256: source.closure.sha256,
    jobsSha256: source.jobs.sha256, protocolSha256: source.protocol.sha256 })),
  preparation: [{ file: 'bundle.json', sha256: content.sha256 }, { file: 'reuse-provenance.json', sha256: contentProof.sha256 },
    { file: 'code-planned-bundle.json', sha256: code.sha256 }, { file: 'code-reuse-provenance.json', sha256: codeProof.sha256 },
    { file: 'preflight-review-plan.json', sha256: reviewPlan.sha256 }], packetHashes,
  traceContentsOpened: false, intermediateBodiesExtracted: false, actualNewModelCalls: 0, adoptionClaimed: false };
// Save the private seed/mapping before public packets: an interruption cannot silently create new labels.
await writeNew(resolve(privateRoot, 'map.json'), map); await mkdir(packetDirectory);
for (const packet of packets) await writeNew(resolve(packetDirectory, packet.caseId + '.json'), packet);
await writeNew(resolve(packetDirectory, 'manifest.json'), { packetHashes, finalWorksOnly: true,
  everyRepeatAndFailureRetained: true, provenanceHidden: true, actualNewModelCalls: 0, adoptionClaimed: false });
console.log(JSON.stringify({ publicPacketDirectory: packetDirectory, packetHashes,
  coordinatorMapPath: resolve(privateRoot, 'map.json'), coordinatorMapSha256: sha(await readFile(resolve(privateRoot, 'map.json'))), actualNewModelCalls: 0 }));
