import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstat, mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, posix, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { materializeSyntheticProject, rootName, targetPaths } from './synthetic-project.mjs';

const SELF = fileURLToPath(import.meta.url);
const WORKSPACE_TMP = resolve(dirname(SELF), '../../../../tmp');
const ALLOWED_MODULES = new Set(targetPaths);
const MAX_FILE_BYTES = 256 * 1024;
const VM_TIMEOUT_MS = 400;

/** 模型只改三个目标文件，不能修改包脚本、验收测试或通过路径跳转扩大写入范围。 */
export function validateCodeWork(work) {
  assert.ok(work && typeof work === 'object' && !Array.isArray(work), '代码作品必须是JSON对象');
  assert.deepEqual(Object.keys(work).sort(), ['files'], '代码作品仅允许files字段');
  assert.ok(Array.isArray(work.files) && work.files.length <= targetPaths.length, 'files超出白名单范围');
  const seen = new Set();
  return work.files.map((file) => {
    assert.ok(file && typeof file === 'object' && !Array.isArray(file), '文件变更必须是对象');
    assert.deepEqual(Object.keys(file).sort(), ['content', 'path'], '文件变更仅允许path和content');
    assert.equal(typeof file.path, 'string');
    const path = file.path.replace(/\\/g, '/');
    assert.ok(!isAbsolute(path) && !/^[A-Za-z]:/.test(path)
      && path.split('/').every((segment) => segment && segment !== '.' && segment !== '..')
      && ALLOWED_MODULES.has(path), '文件路径不在合成目标白名单中');
    assert.ok(!seen.has(path), '代码作品不允许重复路径');
    seen.add(path);
    assert.equal(typeof file.content, 'string');
    assert.ok(Buffer.byteLength(file.content, 'utf8') <= MAX_FILE_BYTES, '文件内容超过离线执行上限');
    return { path, content: file.content };
  });
}

/** 每臂创建全新tmp副本，保留其它夹具逐字不变，不覆盖已有实验目录。 */
export async function materializeAndCheckCodeWork(work, destination, options = {}) {
  const changes = validateCodeWork(work);
  const root = resolve(destination);
  assert.ok(root.startsWith(`${WORKSPACE_TMP}${sep}`), '作品副本必须位于工作区tmp内');
  await mkdir(dirname(root), { recursive: true });
  const parent = await realpath(dirname(root));
  const tmp = await realpath(WORKSPACE_TMP);
  assert.ok(parent === tmp || parent.startsWith(`${tmp}${sep}`), '作品父目录不能通过链接越界');
  await mkdir(root);
  const fixture = await materializeSyntheticProject(root);
  for (const file of changes) await writeFile(resolve(root, file.path), file.content, 'utf8');
  const acceptance = await checkCodeWork(root, options);
  const preserved = [];
  for (const file of fixture.files.filter((item) => !ALLOWED_MODULES.has(item.path))) {
    const content = await readFile(resolve(root, file.path), 'utf8');
    assert.equal(content, file.content, '非目标文件发生修改');
    preserved.push({ path: file.path, sha256: createHash('sha256').update(content).digest('hex') });
  }
  return { root, changedPaths: changes.map((file) => file.path), preserved, acceptance };
}

/** 自有子进程设置总超时及内存/输出上限，模型死循环不能挂住整批评测。 */
export async function checkCodeWork(projectRoot, { allowToday = true } = {}) {
  const environment = Object.fromEntries(['SystemRoot', 'WINDIR', 'TEMP', 'TMP']
    .filter((name) => process.env[name]).map((name) => [name, process.env[name]]));
  return new Promise((finish) => {
    const child = spawn(process.execPath, ['--experimental-vm-modules', '--max-old-space-size=96', SELF,
      '--isolated-vm-check', resolve(projectRoot), ...(allowToday ? [] : ['--exclude-today'])],
    { env: environment, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    let output = '', errors = '', stopped = '', settled = false;
    const timer = setTimeout(() => { stopped = 'SANDBOX_TOTAL_TIMEOUT'; child.kill(); }, 12_000);
    const append = (value, isError) => {
      if (isError) errors += value.toString('utf8'); else output += value.toString('utf8');
      if (Buffer.byteLength(output) + Buffer.byteLength(errors) > 64 * 1024) {
        stopped = 'SANDBOX_OUTPUT_LIMIT'; child.kill();
      }
    };
    child.stdout.on('data', (value) => append(value, false));
    child.stderr.on('data', (value) => append(value, true));
    const settle = (failure) => {
      if (settled) return;
      settled = true; clearTimeout(timer);
      try {
        if (failure) throw new Error(failure);
        const result = JSON.parse(output);
        assert.ok(Array.isArray(result.checks) && typeof result.passed === 'boolean');
        finish(result);
      } catch (error) {
        finish({ allowToday, checks: [{ id: 'isolated-execution', passed: false,
          message: String(error.message).slice(0, 800) }], passed: false,
        executionFailure: stopped || 'SANDBOX_EXECUTION_FAILED', stderr: errors.slice(0, 1_500) });
      }
    };
    child.on('error', (error) => settle(error.message));
    child.on('close', (code, signal) => settle(stopped || (code === 0 ? '' : `隔离进程退出 ${code ?? signal}`)));
  });
}

/** 只读取三个授权模块；不执行模型测试、包脚本或任何新依赖。 */
async function readAllowedSources(projectRoot) {
  const root = await realpath(projectRoot);
  assert.equal(JSON.parse(await readFile(resolve(root, 'package.json'), 'utf8')).name, rootName, '仅验收本批合成项目');
  const sources = new Map();
  for (const path of targetPaths) {
    const absolute = resolve(root, path);
    assert.ok((await realpath(absolute)).startsWith(`${root}${sep}`), '目标源码链接越界');
    assert.equal((await lstat(absolute)).isSymbolicLink(), false, '目标源码不允许符号链接');
    const source = await readFile(absolute, 'utf8');
    assert.ok(Buffer.byteLength(source, 'utf8') <= MAX_FILE_BYTES, '目标源码超过执行上限');
    sources.set(path, source);
  }
  return sources;
}

/** 新realm内传入JSON参数并序列化结果，不暴露宿主对象；每次函数执行都有timeout。 */
async function callExport(sources, modulePath, exportName, args, clockDate) {
  const context = vm.createContext(Object.create(null), {
    codeGeneration: { strings: false, wasm: false }, microtaskMode: 'afterEvaluate',
  });
  new vm.Script(`
    const NativeDate = Date;
    const fixedNow = NativeDate.parse(${JSON.stringify(clockDate)} + 'T12:00:00.000Z');
    globalThis.Date = class extends NativeDate {
      constructor(...args) { super(...(args.length ? args : [fixedNow])); }
      static now() { return fixedNow; }
    };
    globalThis.process = undefined; globalThis.require = undefined; globalThis.fetch = undefined;
    globalThis.console = undefined; globalThis.Buffer = undefined; Object.freeze(JSON);
  `).runInContext(context, { timeout: VM_TIMEOUT_MS });
  // import失败也只把该realm的错误交给模型，避免宿主Error对象携带宿主Function构造器。
  const dynamicImportError = new vm.Script("new Error('不允许动态import')")
    .runInContext(context, { timeout: VM_TIMEOUT_MS });
  const modules = new Map();
  const createModule = (path) => {
    assert.ok(ALLOWED_MODULES.has(path) && sources.has(path), '模块不在合成白名单中');
    if (!modules.has(path)) modules.set(path, new vm.SourceTextModule(sources.get(path), {
      context, identifier: path,
      importModuleDynamically: () => { throw dynamicImportError; },
    }));
    return modules.get(path);
  };
  const entry = createModule(modulePath);
  await entry.link((specifier, referencingModule) => {
    assert.ok(specifier.startsWith('./') || specifier.startsWith('../'), '仅允许白名单内的相对模块');
    return createModule(posix.normalize(posix.join(posix.dirname(referencingModule.identifier), specifier)));
  });
  await entry.evaluate({ timeout: VM_TIMEOUT_MS });
  context.__workExports = entry.namespace;
  const result = new vm.Script(`(() => {
    const args = JSON.parse(${JSON.stringify(JSON.stringify(args))});
    const result = __workExports[${JSON.stringify(exportName)}](...args);
    if (result && typeof result.then === 'function') {
      result.catch(() => {}); throw new Error('原有同步接口不能改成Promise');
    }
    return JSON.stringify(result);
  })()`).runInContext(context, { timeout: VM_TIMEOUT_MS });
  assert.equal(typeof result, 'string', '接口没有返回JSON');
  return JSON.parse(result);
}

/** 独立作品验收：要求来自原任务和明确的今日边界，不使用作品自报结论。 */
async function runChecksInVm(projectRoot, { allowToday = true } = {}) {
  const sources = await readAllowedSources(projectRoot);
  const today = '2026-10-27';
  const createSurvey = (input, date = today) => callExport(sources, targetPaths[1], 'createSurvey', [input, date], date);
  const importSurveys = (rows, date = today) => callExport(sources, targetPaths[2], 'importSurveys', [rows, date], date);
  const checks = [];
  const check = async (id, action) => {
    try { await action(); checks.push({ id, passed: true }); }
    catch (error) { checks.push({ id, passed: false, message: String(error.message).slice(0, 1_000) }); }
  };
  await check('create-past', async () => assert.equal((await createSurvey({ surveyDate: '2026-10-26' })).ok, true));
  await check('create-today', async () => assert.equal((await createSurvey({ surveyDate: today })).ok, allowToday));
  await check('create-future', async () => {
    const result = await createSurvey({ surveyDate: '2026-10-28' });
    assert.equal(result.ok, false); assert.ok(result.fieldErrors?.surveyDate);
  });
  await check('create-invalid-calendar', async () => assert.equal((await createSurvey({ surveyDate: '2026-02-30' })).ok, false));
  await check('create-invalid-format', async () => assert.equal((await createSurvey({ surveyDate: '2026/10/26' })).ok, false));
  await check('create-keeps-other-fields', async () => {
    const record = { id: 'synthetic-42', surveyDate: '2026-10-26', note: '保留其他字段' };
    assert.deepEqual(await createSurvey(record), { ok: true, record });
  });
  await check('import-today', async () => {
    const result = await importSurveys([{ surveyDate: today }]);
    assert.equal(result.inserted.length, allowToday ? 1 : 0);
    assert.equal(result.errors.length, allowToday ? 0 : 1);
  });
  await check('import-row-errors-and-partial-success', async () => {
    const first = { id: 'synthetic-1', surveyDate: '2026-10-26' };
    const result = await importSurveys([first, { surveyDate: '2026-10-28' }, { surveyDate: '2026-02-30' }]);
    assert.deepEqual(result.inserted, [first]);
    assert.deepEqual(result.errors.map(({ row, field }) => ({ row, field })), [{ row: 2, field: 'surveyDate' }, { row: 3, field: 'surveyDate' }]);
    assert.ok(result.errors.every(({ code }) => typeof code === 'string' && code.length > 0));
  });
  await check('empty-import', async () => assert.deepEqual(await importSurveys([]), { inserted: [], errors: [] }));
  await check('cross-year-future', async () => assert.equal((await createSurvey({ surveyDate: '2027-01-01' }, '2026-12-31')).ok, false));
  return { allowToday, checks, passed: checks.every(({ passed }) => passed) };
}

/** 验收器自测只使用手写控制与合成源码，不作为模型质量收益或真实调用计数。 */
export async function selfTestCodeWork(destination) {
  const root = resolve(destination);
  const baseline = await checkCodeWork(fileURLToPath(new URL('./fixtures/project', import.meta.url)));
  assert.deepEqual(baseline.checks.filter((check) => !check.passed).map((check) => check.id),
    ['create-future', 'import-row-errors-and-partial-success', 'cross-year-future']);
  const source = await readFile(new URL('./fixtures/project/src/views/records/dateRules.js', import.meta.url), 'utf8');
  const fix = (operator) => ({ files: [{ path: targetPaths[0], content: source.replace('  return null;',
    `  if (value ${operator} today) return 'FUTURE_DATE';\n  return null;`) }] });
  const valid = await materializeAndCheckCodeWork(fix('>'), resolve(root, 'valid-control'));
  assert.equal(valid.acceptance.passed, true);
  const exclusive = await materializeAndCheckCodeWork(fix('>='), resolve(root, 'exclude-today-control'), { allowToday: false });
  assert.equal(exclusive.acceptance.passed, true);
  const createSource = await readFile(new URL('./fixtures/project/src/views/records/create.js', import.meta.url), 'utf8');
  const capability = await materializeAndCheckCodeWork({ files: [...fix('>').files, {
    path: targetPaths[1], content: `
      if ([typeof process, typeof require, typeof fetch, typeof Buffer].some((value) => value !== 'undefined')) {
        throw new Error('意外暴露宿主能力');
      }
      ${createSource}`,
  }] }, resolve(root, 'capability-control'));
  assert.equal(capability.acceptance.passed, true);
  const forbiddenWrites = [
    '../outside.js', '/outside.js', 'C:/outside.js',
    'src/views/records/dateRules.spec.js', 'package.json', 'src/views/records-old/create.js',
  ];
  for (const path of forbiddenWrites) assert.throws(() => validateCodeWork({ files: [{ path, content: '' }] }));
  assert.throws(() => validateCodeWork({ files: [fix('>').files[0], fix('>').files[0]] }));
  const attack = async (id, content) => {
    const result = await materializeAndCheckCodeWork({ files: [{ path: targetPaths[1], content }] }, resolve(root, id));
    assert.equal(result.acceptance.passed, false);
    return { id, checks: result.acceptance.checks, executionFailure: result.acceptance.executionFailure ?? null };
  };
  const denied = [];
  denied.push(await attack('builtin-import-control', "import fs from 'node:fs'; export function createSurvey() { return fs; }"));
  denied.push(await attack('host-constructor-control', "export function createSurvey(input) { return input.constructor.constructor('return process')(); }"));
  denied.push(await attack('loop-control', 'export function createSurvey() { while (true) {} }'));
  assert.ok(denied.at(-1).checks.some((check) => /timed out/.test(check.message ?? '')), '函数死循环应被调用级timeout捕获');
  return { purpose: 'offline harness self-test; no model quality claims', baseline,
    legalControl: valid.acceptance, exclusiveTodayControl: exclusive.acceptance, capabilityIsolationControl: capability.acceptance,
    forbiddenWritesRejected: forbiddenWrites, duplicatePathRejected: true, denied,
    nonTargetFilesPreserved: valid.preserved.length, actualModelCalls: 0 };
}

if (process.argv[1] && resolve(process.argv[1]) === SELF) {
  const allowToday = !process.argv.includes('--exclude-today');
  if (process.argv[2] === '--isolated-vm-check') {
    process.stdout.write(JSON.stringify(await runChecksInVm(process.argv[3], { allowToday })));
  } else if (process.argv[2] === '--self-test') {
    if (!process.argv[3]) throw new Error('需要新的tmp控制目录');
    console.log(JSON.stringify(await selfTestCodeWork(process.argv[3]), null, 2));
  } else {
    if (!process.argv[2]) throw new Error('用法：node check-code-work.mjs <项目副本> [--exclude-today]');
    const result = await checkCodeWork(process.argv[2], { allowToday });
    console.log(JSON.stringify(result, null, 2));
    process.exitCode = result.passed ? 0 : 1;
  }
}
