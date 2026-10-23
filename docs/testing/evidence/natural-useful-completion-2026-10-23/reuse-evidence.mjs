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
  const verify = async item => {
    const path = ownedPath(repo, item.path);
    assert.equal(await sha256File(path), item.sha256, 'Reused evidence drift: ' + item.path);
    return path;
  };
  for (const item of manifest.files) await verify(item);
  const sourceClosure = await jsonFile(await verify(manifest.sourceClosure));
  assert.equal(sourceClosure.status, 'CLOSED');
  assert.deepEqual((await jsonFile(await verify(manifest.sourceCases))).cases, cases, 'Reused case materials changed');
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
    assert.deepEqual(unchanged, original, 'Copied result must retain original fields');
    assert.deepEqual(original, sourcePublic.find(row => row.id === item.id), 'Private/public closed evidence differs');
    assert.equal(copied.id, item.id);
    assert.ok(cases.some(sample => sample.id === copied.caseId));
    results.push(copied);
  }
  for (const item of manifest.historyChecks) {
    const target = ownedPath(repo, item.path);
    assert.equal(relative(evidence, target).replaceAll('\\', '/'), 'history-checks/' + item.recordId + '.json', 'Wrong history reuse target');
    const copied = await jsonFile(await verify(item));
    const original = await jsonFile(await verify(item.reusedFrom));
    const { reusedFrom, ...unchanged } = copied;
    assert.deepEqual(reusedFrom, item.reusedFrom);
    assert.deepEqual(unchanged, original);
    historyChecks.push(copied);
  }
  return { results, historyChecks, files: manifest.files, manifest };
}
