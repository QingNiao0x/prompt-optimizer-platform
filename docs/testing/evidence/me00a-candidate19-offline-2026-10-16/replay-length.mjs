/** Replay the existing frozen counter; old works stay immutable and keep their failures. */
import fs from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { checkResearchLength } from '../../../../scripts/me00a-work-checks.mjs';

const out = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(out, '../../../..');
const cases = ['Q05-raw-same-info/raw', 'Q05-direct-same-info/enhanced', 'Q05-plan/enhanced'];
const results = cases.map(item => {
  const [directory, arm] = item.split('/');
  const file = `docs/testing/evidence/me00a-candidate17-live-2026-10-16/real/${directory}/${arm}-work.md`;
  const bytes = fs.readFileSync(path.join(root, file));
  return { file, sha256: createHash('sha256').update(bytes).digest('hex'), ...checkResearchLength(bytes.toString('utf8')) };
});
if (results[2].bodyHan !== 1227 || results[2].pass || !results[1].pass) throw new Error('Captured work/counter drift');
fs.writeFileSync(path.join(out, 'length-replay.json'), JSON.stringify({ modelCalls: 0,
  scope: 'Original works only; candidate19 has no new generated work', results }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify(results.map(({ bodyHan, pass }) => ({ bodyHan, pass }))));
