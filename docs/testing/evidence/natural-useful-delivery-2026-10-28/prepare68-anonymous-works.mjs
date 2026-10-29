import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const closure = await json(resolve(evidence, 'registry68-holdout-works/closure.json'));
assert.equal(closure.status, 'CLOSED');
const coordinator = await json(resolve(repo, 'tmp/natural-useful-delivery-2026-10-28/registry68-holdout-works-coordinator.json'));
const samples = (await json(resolve(evidence, 'sealed-registry52-holdout/cases.json'))).cases;
const notes = await json(resolve(evidence, 'sealed-registry52-holdout/review-notes.json'));
const pack = [];
for (const sample of samples) {
  const works = [];
  for (const item of coordinator.mapping.filter(item => item.caseId === sample.id)) {
    const path = resolve(evidence, item.source), bytes = await readFile(path);
    assert.equal(closure.files.find(file => resolve(repo, file.path) === path)?.sha256, sha(bytes));
    const result = JSON.parse(bytes);
    works.push({ workId: item.workId, delivered: result.status === 200 && result.body?.data?.finishReason === 'stop',
      content: result.body?.data?.content ?? '', httpStatus: result.status, finishReason: result.body?.data?.finishReason ?? null });
  }
  assert.equal(works.length, 6);
  pack.push({ caseId: sample.id, rawPrompt: sample.rawPrompt, materials: sample.materials,
    criteria: notes.cases.find(item => item.id === sample.id), works,
    reviewRules: notes.reviewRules.slice(0, 3),
    metadataOnlyOverlay: 'Apply the unchanged substantive criteria to ORIGINAL/31/68. Labels hide version and repeat. Six works are all first two registered observations, not selected winners.' });
}
const target = resolve(evidence, 'registry68-holdout-review-preparation');
await mkdir(target, { recursive: true });
await writeFile(resolve(target, 'anonymous-works.json'), JSON.stringify(pack, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ anonymousWorks: pack.reduce((n, c) => n + c.works.length, 0), modelCalls: 0,
  rootHasSeenWorkVersions: false, allRegisteredObservationsIncluded: true }));
