import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';

// 只回放已人工阅读的本地纯函数；不赋予文件、网络或进程入口，不把模型自述测试成功当证据。
const base = resolve('docs/testing/evidence/natural-execution-boundary-2026-10-23/development');
const samples = JSON.parse(await readFile(resolve(base, 'cases.json'), 'utf8')).cases;
const rows = JSON.parse(samples.find(x => x.id === 'D03').executorMaterials.find(x => x.name === 'rows_合成.json').content);
const examples = [
  [{}, ['r1', 'r3', 'r4'], 1, 5, 2],
  [{ page: 99, pageSize: 2, keyword: ' AL ' }, ['r5'], 2, 3, 2],
  [{ page: 99, keyword: 'Gamma' }, ['r4'], 1, 1, 1],
  [{ page: 99, keyword: 'none' }, [], 1, 0, 0],
  [{ page: 2, pageSize: 2, keyword: '' }, ['r4', 'r5'], 2, 5, 3],
];
const records = [];
for (const arm of ['ORIGINAL', 'V31', 'V34']) for (const repeat of [1, 2]) {
  const id = `D03_${arm}_EXEC_R${repeat}`;
  const text = await readFile(resolve(base, 'works', id + '.md'), 'utf8');
  const source = /```(?:javascript|js)\s*\n([\s\S]*?)```/.exec(text)?.[1];
  assert.ok(source && source.length < 4000 && !/\b(import|require|process|eval|Function)\b/.test(source));
  const context = vm.createContext({ rows: JSON.parse(JSON.stringify(rows)), examples });
  vm.runInContext(source.replace('export function', 'function'), context, { timeout: 100 });
  vm.runInContext('rows.forEach(Object.freeze); Object.freeze(rows);', context, { timeout: 100 });
  for (const [index, [options, ids, page, total, totalPages]] of examples.entries()) {
    context.options = options;
    const result = JSON.parse(vm.runInContext('JSON.stringify(paginateRows(rows, options))', context, { timeout: 100 }));
    assert.deepEqual(Object.keys(result).sort(), ['items', 'page', 'pageSize', 'total', 'totalPages'].sort());
    assert.deepEqual(result.items.map(row => row.id), ids);
    assert.deepEqual([result.page, result.total, result.totalPages], [page, total, totalPages]);
    assert.equal(result.pageSize, options.pageSize ?? 3);
    assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(rows)', context)), rows);
    records.push({ id, check: index + 1, passed: true });
  }
}
const notices = [];
for (const arm of ['ORIGINAL', 'V31', 'V34']) for (const repeat of [1, 2]) {
  const id = `D04_${arm}_EXEC_R${repeat}`;
  const text = (await readFile(resolve(base, 'works', id + '.md'), 'utf8')).trim();
  assert.ok(!text.includes('\n') && text.replace(/\s/g, '').length <= 90);
  for (const clause of ['线上', '教室不再开放', '日期尚未确定', '无需重复报名', '未报名', '后续通知']) assert.ok(text.includes(clause));
  notices.push({ id, passed: true, nonWhitespaceCharacters: text.replace(/\s/g, '').length });
}
await writeFile(resolve(base, 'deterministic-work-checks.json'), JSON.stringify({ records, notices,
  modelCalls: 0, conclusion: '三臂均通过契约内行为；不把超契约的非法参数处理计作增强收益。' }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ codeChecks: records.length, notices: notices.length, passed: true, modelCalls: 0 }));
