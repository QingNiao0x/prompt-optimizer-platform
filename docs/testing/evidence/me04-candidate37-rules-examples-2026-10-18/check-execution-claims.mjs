/** Bind the unchanged execution-claim checker to actual work requests; no execution, edits or model calls. */
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
import { auditExecutionClaims } from '../../../../scripts/me00a-execution-claims.mjs';

const work = resolve('tmp/me04-candidate37-live-20261018');
const rows = [];
for (const [task, arms] of [['REQUESTS', ['RAW', 'COMPACT', 'STRUCTURED']], ['PATCH', ['STRUCTURED']]]) {
  for (const arm of arms) {
    const directory = resolve(work, 'works', task, arm);
    const request = JSON.parse(await readFile(resolve(directory, 'request.json'), 'utf8'));
    assert(!request.tools && request.messages.length === 1 && request.messages[0].role === 'user');
    const text = await readFile(resolve(directory, 'work.md'), 'utf8');
    rows.push({ task, arm, workSha256: createHash('sha256').update(text).digest('hex'),
      ...auditExecutionClaims(text, { executionToolsProvided: false, verifiedExecutionBeforeResponse: false }) });
  }
}
await writeFile(resolve(work, 'execution-claims.json'), `${JSON.stringify({ sourceCandidate: 'candidate37',
  modelCalls: 0, works: rows }, null, 2)}\n`, { flag: 'wx' });
console.log(JSON.stringify(rows.map(({ task, arm, state }) => ({ task, arm, state }))));
