import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import assert from 'node:assert/strict';

// 仅运行已人工审阅的纯分页函数，不提供文件、网络或进程入口；不扩展原有输入契约来制造收益。
const base = dirname(fileURLToPath(import.meta.url));
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
for (const arm of ['ORIGINAL', 'V31', 'V35']) for (const repeat of [1, 2]) {
  const id = `D03_${arm}_EXEC_R${repeat}`;
  const text = await readFile(resolve(base, 'works', id + '.md'), 'utf8');
  const blocks = [...text.matchAll(/```(?:javascript|js)\s*\n([\s\S]*?)```/g)]
    .map(match => match[1]).filter(code => code.includes('function paginateRows'));
  // V35 R2先引用旧代码，再明确交付修复实现；验证后者，同时在语义复核保留冗长问题。
  const source = blocks.at(-1);
  assert.ok(source && source.length < 4000 && !/\b(import|require|process|eval|Function)\b/.test(source));
  const context = vm.createContext({ rows: JSON.parse(JSON.stringify(rows)) });
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
    records.push({ id, check: index + 1, passed: true, selectedFunctionBlock: blocks.length });
  }
}
await writeFile(resolve(base, 'deterministic-code-checks.json'), JSON.stringify({ records, modelCalls: 0,
  conclusion: '三臂各两份实现均通过原契约内5组行为检查；功能持平，不计为质量提升；V35 R2有额外原代码复述及契约外防御说明。' }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ checks: records.length, passed: true, modelCalls: 0 }));
