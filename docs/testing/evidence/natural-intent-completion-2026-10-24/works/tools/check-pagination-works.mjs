import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

// 工具只读取原始契约和匿名作品，不读取提示词、候选版本或匿名映射；不发模型请求。
const here = dirname(fileURLToPath(import.meta.url));
export const contractPath = resolve(here, '../../regression/cases.json');
export const legacyCheckerPath = resolve(here, '../../../natural-material-responsibility-2026-10-24/development/check-code-works.mjs');
export const hash = value => createHash('sha256').update(value).digest('hex');

/** 保留旧五组具体预期，并只增加原契约已要求的默认调用、输入完整性与边界。 */
export async function loadChecks() {
  const bytes = await readFile(contractPath);
  const sample = JSON.parse(bytes).cases.find(item => item.id === 'D03');
  assert.ok(sample, 'D03 original contract must exist');
  const rows = JSON.parse(sample.executorMaterials.find(item => item.name === 'rows_合成.json').content);
  const contract = sample.executorMaterials.find(item => item.name === 'paginateRows现有契约.txt').content;
  const create = (id, options, ids, page, total, totalPages, extra = {}) => ({
    id, rows, options, ids, page, pageSize: options.pageSize ?? 3, total, totalPages, legacy: false, ...extra,
  });
  const checks = [
    // 与旧闭批check-code-works.mjs逐项相同，不修改旧判据或旧结果。
    create('L01_default', {}, ['r1', 'r3', 'r4'], 1, 5, 2, { legacy: true }),
    create('L02_shrink_last_page', { page: 99, pageSize: 2, keyword: ' AL ' }, ['r5'], 2, 3, 2, { legacy: true }),
    create('L03_single_result', { page: 99, keyword: 'Gamma' }, ['r4'], 1, 1, 1, { legacy: true }),
    create('L04_no_matches', { page: 99, keyword: 'none' }, [], 1, 0, 0, { legacy: true }),
    create('L05_middle_page', { page: 2, pageSize: 2, keyword: '' }, ['r4', 'r5'], 2, 5, 3, { legacy: true }),
    create('A06_omitted_options', {}, ['r1', 'r3', 'r4'], 1, 5, 2, { omitOptions: true }),
    create('A07_empty_rows', { page: 99 }, [], 1, 0, 0, { rows: [] }),
    create('A08_blank_keyword', { keyword: '\t  ' }, ['r1', 'r3', 'r4'], 1, 5, 2),
    create('A09_exact_active', {}, ['s3'], 1, 1, 1, { rows: [
      { id: 's1', name: 'lowercase', status: 'active' },
      { id: 's2', name: 'suffix', status: 'ACTIVE_PENDING' },
      { id: 's3', name: 'exact', status: 'ACTIVE' },
    ] }),
    create('A10_input_order', { keyword: 'a', pageSize: 10 }, ['r6', 'r4', 'r3', 'r1', 'r5'], 1, 5, 1,
      { rows: [rows[5], rows[3], rows[2], rows[0], rows[4]] }),
    create('A11_case_insensitive', { keyword: ' gAm ' }, ['r4'], 1, 1, 1),
    create('A12_page_size_one', { page: 99, pageSize: 1, keyword: 'AL' }, ['r5'], 3, 3, 3),
    create('A13_falsy_keyword', { keyword: 0 }, ['r1', 'r3', 'r4'], 1, 5, 2),
    create('A14_keyword_string_coercion', { keyword: 42 }, ['n1'], 1, 1, 1, { rows: [
      { id: 'n1', name: 'Room42', status: 'ACTIVE' },
      { id: 'n2', name: 'Room43', status: 'ACTIVE' },
    ] }),
    create('A15_exact_page_multiple', { page: 99, pageSize: 2 }, ['r4', 'r5'], 2, 4, 2, { rows: rows.slice(0, 5) }),
    create('A16_large_page_size', { page: 99, pageSize: 10 }, ['r1', 'r3', 'r4', 'r5', 'r6'], 1, 5, 1),
  ];
  return { checks, contract, acceptance: sample.acceptance, contractSha256: hash(bytes) };
}

/** 沿用旧检查器选最终实现的约定；只提取含定义的JS代码，不执行说明或匿名映射。 */
export function extractImplementation(text) {
  const definition = /\b(?:function\s+paginateRows\s*\(|(?:const|let|var)\s+paginateRows\s*=)/;
  const blocks = [...text.matchAll(/```([^\n]*)\n([\s\S]*?)```/g)]
    .filter(match => ['', 'javascript', 'js', 'mjs'].includes(match[1].trim().toLowerCase()))
    .map(match => match[2]).filter(code => definition.test(code));
  const source = blocks.at(-1) ?? (definition.test(text) ? text : null);
  assert.ok(source, '需要可替换的paginateRows完整JavaScript实现');
  return { source, selectedDefinitionBlock: blocks.length || 1 };
}

/** 只对已检查的纯分页代码执行。VM不提供宿主对象、文件、网络、模块或进程入口，动态代码生成关闭。 */
export function checkImplementation(source, checks) {
  const records = [];
  const forbidden = /\b(?:import|require|process|eval|Function)\b|\bexport\s+default\b/;
  if (source.length > 24_000 || forbidden.test(source)) {
    return { status: 'UNEXECUTED_NEEDS_REVIEW', records,
      necessaryChanges: ['代码超出纯函数执行范围，需人工核查依赖或提取边界；不能据此直接认定业务失败。'] };
  }
  const runnable = source.replace(/\bexport\s+(?=(?:async\s+)?function\b|(?:const|let|var)\b)/g, '')
    .replace(/\bexport\s*\{\s*paginateRows\s*\}\s*;?/g, '');
  for (const spec of checks) {
    // 不仅冻结输入：可变输入回合能够揭示被非严格模式冻结所隐藏的元素写入。
    for (const frozen of [false, true]) {
      const record = { check: spec.id, legacy: spec.legacy, frozenInput: frozen, passed: false, failures: [] };
      try {
        const context = vm.createContext({}, { codeGeneration: { strings: false, wasm: false } });
        vm.runInContext(`globalThis.__contractRows = ${JSON.stringify(spec.rows)};
          globalThis.__contractOptions = ${JSON.stringify(spec.options)};`, context, { timeout: 100 });
        if (frozen) vm.runInContext('globalThis.__contractRows.forEach(Object.freeze); Object.freeze(globalThis.__contractRows);', context, { timeout: 100 });
        vm.runInContext(`'use strict';\n${runnable}`, context, { timeout: 100 });
        const call = spec.omitOptions ? 'paginateRows(globalThis.__contractRows)' : 'paginateRows(globalThis.__contractRows, globalThis.__contractOptions)';
        const actual = JSON.parse(vm.runInContext(`JSON.stringify({result: ${call}, rowsAfter: globalThis.__contractRows})`, context, { timeout: 100 }));
        const compare = (label, value, expected) => {
          try { assert.deepEqual(value, expected); } catch { record.failures.push(label); }
        };
        compare('保持固定返回字段items/page/pageSize/total/totalPages', Object.keys(actual.result ?? {}).sort(), ['items', 'page', 'pageSize', 'total', 'totalPages'].sort());
        compare('输入数组、顺序及元素值保持不变', actual.rowsAfter, spec.rows);
        compare('筛选后分页返回完整原记录且顺序正确', actual.result?.items, spec.ids.map(id => spec.rows.find(row => row.id === id)));
        compare('页码、pageSize、命中总数及总页数正确', [actual.result?.page, actual.result?.pageSize, actual.result?.total, actual.result?.totalPages],
          [spec.page, spec.pageSize, spec.total, spec.totalPages]);
        record.passed = record.failures.length === 0;
      } catch (error) {
        record.failures.push('实现未能按原同步函数契约执行：' + String(error?.message ?? error));
      }
      records.push(record);
    }
  }
  return { status: records.every(record => record.passed) ? 'PASSED' : 'FAILED', records,
    necessaryChanges: [...new Set(records.flatMap(record => record.failures))] };
}

/** 接受只含匿名Markdown的目录，既不推测也不输出对应候选版本。 */
export async function checkAnonymousDirectory(inputDirectory, outputPath) {
  const contract = await loadChecks();
  const names = (await readdir(inputDirectory)).filter(name => /^work-\d+\.md$/.test(name)).sort();
  assert.ok(names.length, '没有找到匿名work-数字.md作品');
  const records = [];
  for (const name of names) {
    const bytes = await readFile(resolve(inputDirectory, name));
    const row = { anonymousName: name, textSha256: hash(bytes) };
    try {
      const extracted = extractImplementation(bytes.toString('utf8'));
      Object.assign(row, { selectedDefinitionBlock: extracted.selectedDefinitionBlock, sourceSha256: hash(extracted.source) },
        checkImplementation(extracted.source, contract.checks));
    } catch (error) {
      Object.assign(row, { status: 'UNEXECUTED_NEEDS_REVIEW', records: [], necessaryChanges: [String(error.message)] });
    }
    records.push(row);
  }
  const result = { modelCalls: 0, contractSha256: contract.contractSha256,
    legacyCheckerSha256: hash(await readFile(legacyCheckerPath)), checkCountPerImplementation: contract.checks.length * 2,
    limits: '只核对原契约内的分页行为和输入完整性。32个回合不是32份独立作品；通过不证明其他任务、文风、成本或候选采用。未执行需要人工复核，不能算通过。', records };
  await writeFile(outputPath, JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
  return result;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [, , inputDirectory, outputPath] = process.argv;
  assert.ok(inputDirectory && outputPath, '用法: node check-pagination-works.mjs <匿名目录> <新结果.json>');
  const result = await checkAnonymousDirectory(resolve(inputDirectory), resolve(outputPath));
  console.log(JSON.stringify({ works: result.records.length, modelCalls: 0,
    outcomes: result.records.map(record => ({ anonymousName: record.anonymousName, status: record.status })) }));
}
