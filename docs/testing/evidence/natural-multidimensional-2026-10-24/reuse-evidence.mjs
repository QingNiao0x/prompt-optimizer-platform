import { readFile } from 'node:fs/promises';
import { resolve, relative, isAbsolute } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

export const jsonFile = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
export const sha256File = async path => createHash('sha256').update(await readFile(path)).digest('hex');

/** 复用指针只能指向仓库内已闭批原件，不能从任意外部路径注入证据。 */
export function ownedPath(repo, path) {
  assert.equal(typeof path, 'string');
  const full = resolve(repo, path);
  const rel = relative(repo, full);
  assert.ok(rel && !rel.startsWith('..') && !isAbsolute(rel), 'Reuse path must stay inside repository');
  return full;
}

/** 验证复制件、原件、闭批和同题材料；复用只提供比较证据，不生成本阶段传输计数。 */
export async function verifiedReuse(repo, evidence, privateRoot, cases) {
  let manifest;
  try { manifest = await jsonFile(resolve(evidence, 'reuse-manifest.json')); }
  catch (failure) { if (failure.code === 'ENOENT') return { results: [], historyChecks: [], files: [] }; throw failure; }
  if (Array.isArray(manifest.sources)) {
    const sources = [];
    for (const source of manifest.sources) sources.push(await verifySourceReuse(repo, evidence, privateRoot, cases, source));
    assert.deepEqual(manifest.files, manifest.sources.flatMap(source => source.files), 'Aggregate source files changed');
    const results = sources.flatMap(source => source.results);
    assert.equal(new Set(results.map(row => row.id)).size, results.length, 'Multiple sources reused the same job');
    return { results, historyChecks: sources.flatMap(source => source.historyChecks), files: manifest.files, manifest };
  }
  return verifySourceReuse(repo, evidence, privateRoot, cases, manifest);
}

/** 多个闭批逐一验证，避免为了复用跨阶段原件而重写任何旧记录。 */
async function verifySourceReuse(repo, evidence, privateRoot, cases, manifest) {
  const verify = async item => {
    const path = ownedPath(repo, item.path);
    assert.equal(await sha256File(path), item.sha256, 'Reused evidence drift: ' + item.path);
    return path;
  };
  for (const item of manifest.files) await verify(item);
  const sourceClosure = await jsonFile(await verify(manifest.sourceClosure));
  assert.equal(sourceClosure.status, 'CLOSED');
  const sourceCases = (await jsonFile(await verify(manifest.sourceCases))).cases;
  assert.ok(Array.isArray(manifest.reusedCaseIds) && manifest.reusedCaseIds.length);
  for (const id of manifest.reusedCaseIds) {
    assert.deepEqual(sourceCases.find(c=>c.id===id),cases.find(c=>c.id===id),'Reused case materials changed: '+id);
    assert.ok(cases.some(c=>c.id===id));
  }
  const sourcePublic = await jsonFile(await verify(manifest.sourcePublicResults));
  const results = [];
  const historyChecks = [];
  for (const item of manifest.results) {
    const target = ownedPath(repo, item.path);
    assert.equal(relative(privateRoot, target).replaceAll('\\', '/'), 'results/' + item.id + '.json', 'Wrong reuse target');
    const copied = await jsonFile(await verify(item));
    const original = await jsonFile(await verify(item.reusedFrom));
    const { reusedFrom, ...unchanged } = copied;
    assert.deepEqual(reusedFrom, item.reusedFrom);
    const { reusedFrom: previousSource, ...originalFields } = original;
    assert.deepEqual(unchanged, originalFields, 'Copied result must retain original business fields');
    if (previousSource) await verify(previousSource);
    assert.deepEqual(original, sourcePublic.find(row => row.id === item.id), 'Private/public closed evidence differs');
    assert.equal(copied.id, item.id);
    assert.ok(manifest.reusedCaseIds.includes(copied.caseId));
    results.push(copied);
  }
  for (const item of manifest.historyChecks) {
    const target = ownedPath(repo, item.path);
    assert.equal(relative(evidence, target).replaceAll('\\', '/'), 'history-checks/' + item.recordId + '.json', 'Wrong history reuse target');
    const copied = await jsonFile(await verify(item));
    const original = await jsonFile(await verify(item.reusedFrom));
    const { reusedFrom, ...unchanged } = copied;
    assert.deepEqual(reusedFrom, item.reusedFrom);
    const { reusedFrom: previousSource, ...originalFields } = original;
    assert.deepEqual(unchanged, originalFields);
    if (previousSource) await verify(previousSource);
    historyChecks.push(copied);
  }
  return { results, historyChecks, files: manifest.files, manifest };
}
