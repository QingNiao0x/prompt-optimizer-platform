import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = dirname(fileURLToPath(import.meta.url)), repo = resolve(root, '../../../..');
const original = JSON.parse(await readFile(resolve(root, 'candidate-freeze.json'), 'utf8'));
const files = [];
for (const entry of original.files) {
  const sha256 = createHash('sha256').update(await readFile(resolve(repo, entry.path))).digest('hex');
  if (!entry.path.startsWith('apps/web/src/features/project-index/') && sha256 !== entry.sha256) throw Error(`Unexpected frozen product change: ${entry.path}`);
  files.push({ ...entry, sha256 });
}
const dtoEquality = [];
for (const id of ['C01', 'C02', 'Q01', 'V01', 'V02', 'V03']) {
  const before = await readFile(resolve(root, `browser/captures-r2/${id}/input.json`));
  const after = await readFile(resolve(root, `browser/captures-r3/${id}/input.json`));
  if (!before.equals(after)) throw Error(`New UI input differs; old generation evidence cannot be directly reused: ${id}`);
  dtoEquality.push({ id, sha256: createHash('sha256').update(after).digest('hex'), byteIdentical: true });
}
await writeFile(resolve(root, 'frontend-r2-freeze.json'), JSON.stringify({
  frozenAt: new Date().toISOString(), version: '46', revision: 'frontend-r2', previousFreeze: 'candidate-freeze.json',
  changedMechanisms: ['Same-named symbol bound to explicitly referenced scope', 'Unresolved ordinary file path retained as semantic query, not marked FOUND'],
  unchangedBackend: true, defaultBeforeAdoption: '31', holdoutOpenedByImplementer: false,
  evidenceReuse: 'Six exact browser DTOs unchanged; preserve previous first generation and works rather than sampling duplicates',
  dtoEquality, files,
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ frozen: true, version: '46', revision: 'frontend-r2', identicalUiInputs: dtoEquality.length, holdoutOpened: false }));
