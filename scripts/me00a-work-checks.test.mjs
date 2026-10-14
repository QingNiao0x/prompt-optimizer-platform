import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { checkResearchLength, parseStrictJson, checkGivenTrimJson } from './me00a-work-checks.mjs';

test('saved real works keep both frozen count scopes', async () => {
  const root = new URL('../docs/testing/evidence/me00a-candidate8-2026-10-14/q05-flash/', import.meta.url);
  for (const [path, expected] of [['raw-work.md', [1253, 1313, false]], ['work.md', [1405, 1446, false]],
    ['direct-equal-information/work.md', [1159, 1206, true]]]) {
    const result = checkResearchLength(await readFile(new URL(path, root), 'utf8'));
    assert.deepEqual([result.bodyHan, result.withHeadingsHan, result.pass], expected);
  }
});
test('complete strict JSON and exact given examples are checked', () => {
  assert.equal(checkGivenTrimJson('{"result":"Alpha"}', false), true);
  assert.equal(checkGivenTrimJson('{"result":"Alpha","examples":[{"input":"  Alpha  ","output":"Alpha"}]}', true), true);
  assert.equal(checkGivenTrimJson('{"result":"Alpha","examples":[{"input":"Beta","output":"Beta"}]}', true), false);
  assert.equal(checkGivenTrimJson('{"result":"Alpha","extra":1}', false), false);
});
test('fenced, trailing and duplicate-key JSON are failures', () => {
  for (const work of ['```json\n{"result":"Alpha"}\n```', '{"result":"Alpha"}说明',
    '{"result":"Beta","result":"Alpha"}', '{"result":"Alpha","examples":[{"input":"x","input":"  Alpha  ","output":"Alpha"}]}'])
    assert.throws(() => parseStrictJson(work));
  assert.deepEqual(parseStrictJson('[{"input":"a"},{"input":"b"}]'), [{ input: 'a' }, { input: 'b' }]);
});
