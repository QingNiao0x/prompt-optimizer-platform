import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkImplementation, extractImplementation, hash, loadChecks } from './check-pagination-works.mjs';

// 未读取任何候选提示词、作品或标签。故障在固定独立参考实现上注入，不按作品结果修改标准。
const base = dirname(fileURLToPath(import.meta.url));
const { checks, contractSha256 } = await loadChecks();
const reference = await readFile(resolve(base, 'fixtures/paginate-reference.mjs'), 'utf8');
const baseline = checkImplementation(reference, checks);
assert.equal(baseline.status, 'PASSED');
assert.equal(baseline.records.length, 32);
assert.equal(extractImplementation('说明\n```javascript\n' + reference + '\n```').source.trim(), reference.trim());

const mutations = [
  { id: 'wrong_total_uses_unfiltered_rows', code: reference.replace('const total = selected.length;', 'const total = rows.length;') },
  { id: 'last_page_is_not_clamped', code: reference.replace('Math.min(options.page ?? 1, totalPages)', '(options.page ?? 1)') },
  { id: 'empty_result_has_page_zero', code: reference.replace('totalPages === 0 ? 1', 'totalPages === 0 ? 0') },
  { id: 'mutates_array', code: reference.replace('const selected = [];', 'rows.reverse();\n  const selected = [];') },
  { id: 'mutates_element_in_place', code: reference.replace('const selected = [];', "rows[2] && (rows[2].name = rows[2].name.trim());\n  const selected = [];") },
  { id: 'changes_returned_element_but_not_ids', code: reference.replace('page * pageSize), page,', 'page * pageSize).map(row => ({...row, name: row.name.trim()})), page,') },
  { id: 'changes_active_filter', code: reference.replace("row.status === 'ACTIVE'", "row.status.startsWith('ACTIVE')") },
  { id: 'drops_keyword_normalization', code: reference.replace("String(options.keyword || '').trim().toLowerCase()", "String(options.keyword || '')") },
  { id: 'breaks_falsy_keyword_semantics', code: reference.replace("String(options.keyword || '')", "String(options.keyword ?? '')") },
  { id: 'changes_return_shape', code: reference.replace('return { items:', 'return { extra: true, items:') },
  { id: 'drops_options_default', code: reference.replace('options = {}', 'options') },
  { id: 'sorts_filtered_rows', code: reference.replace('const pageSize =', 'selected.sort((a, b) => a.name.localeCompare(b.name));\n  const pageSize =') },
];
const failures = [];
for (const fixture of mutations) {
  assert.notEqual(fixture.code, reference, fixture.id + ' must actually change source');
  const outcome = checkImplementation(fixture.code, checks);
  assert.equal(outcome.status, 'FAILED', fixture.id + ' must fail a contract check');
  failures.push({ id: fixture.id, codeSha256: hash(fixture.code), rejected: true,
    failedChecks: outcome.records.filter(row => !row.passed).map(row => ({ check: row.check, frozenInput: row.frozenInput, failures: row.failures })) });
}
// 特别验证旧的freeze+id检查盲点：元素写入与返回克隆值变化应在可变输入或完整记录比较中被发现。
assert.ok(failures.find(row => row.id === 'mutates_element_in_place').failedChecks.some(row => !row.frozenInput));
assert.ok(failures.find(row => row.id === 'changes_returned_element_but_not_ids').failedChecks.some(row => row.failures.includes('筛选后分页返回完整原记录且顺序正确')));
const result = { status: 'PASSED', modelCalls: 0, checkedBeforeReadingCandidateWorks: true, contractSha256,
  reference: { sourceSha256: hash(reference), contractRounds: baseline.records.length, passed: true },
  legacyBehaviorCasesUnchanged: checks.filter(row => row.legacy).map(row => row.id),
  deliberatelyBrokenFixtureCount: failures.length, failures,
  limits: '检查器自检只证明这些契约错误可以检出；故障fixture不是候选作品，不能计入产品收益或真实模型调用。' };
await writeFile(resolve(base, 'checker-selfcheck.json'), JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
await writeFile(resolve(base, 'fixtures/mutations.json'), JSON.stringify({ origin: 'Independent reference plus deliberate faults, not candidate outputs', fixtures: mutations }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ status: result.status, referenceRounds: baseline.records.length, deliberatelyBrokenFixtureCount: failures.length, modelCalls: 0 }));
