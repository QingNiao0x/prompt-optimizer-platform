import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { posix, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { targetPaths, testPath as TEST_PATH } from '../fixtures/project.mjs';
const SELF=fileURLToPath(import.meta.url);
const BUSINESS_PATHS=new Set(targetPaths);
const json=async path=>JSON.parse((await readFile(path,'utf8')).replace(/^\uFEFF/,''));
/** 额外测试只在自有受限子进程中运行，不直接import模型提供的测试文件。 */
export async function executeProvidedTests(payload) {
  const environment = Object.fromEntries(['SystemRoot', 'WINDIR', 'TEMP', 'TMP']
    .filter((name) => process.env[name]).map((name) => [name, process.env[name]]));
  return new Promise((finish) => {
    const child = spawn(process.execPath, ['--experimental-vm-modules', '--max-old-space-size=96', SELF, '--test-worker', payload],
      { env: environment, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '', errors = '', reason = '', settled = false;
    const timer = setTimeout(() => { reason = 'TEST_WORKER_TIMEOUT'; child.kill(); }, 8_000);
    const append = (value, stderr) => {
      if (stderr) errors += value.toString('utf8'); else output += value.toString('utf8');
      if (Buffer.byteLength(output) + Buffer.byteLength(errors) > 64 * 1024) { reason = 'TEST_OUTPUT_LIMIT'; child.kill(); }
    };
    child.stdout.on('data', (value) => append(value, false));
    child.stderr.on('data', (value) => append(value, true));
    const settle = (failure) => {
      if (settled) return;
      settled = true; clearTimeout(timer);
      try { if (failure) throw new Error(failure); finish(JSON.parse(output)); }
      catch (error) { finish({ passed: false, checks: [], executionFailure: reason || 'UNSUPPORTED_OR_INVALID_TEST',
        message: String(error.message).slice(0, 1_000), stderr: errors.slice(0, 1_000) }); }
    };
    child.on('error', (error) => settle(error.message));
    child.on('close', (code, signal) => settle(reason || (code === 0 ? '' : `测试进程退出 ${code ?? signal}`)));
  });
}

/** 断言和test函数由同一realm创建；不把宿主assert、回调或错误对象交给模型代码。 */
async function runTestWorker(payload) {
  const { sources, testSource } = await json(payload);
  assert.equal(typeof testSource, 'string');
  assert.ok(Buffer.byteLength(testSource) <= 256 * 1024);
  assert.deepEqual(Object.keys(sources).sort(), [...targetPaths].sort());
  const context = vm.createContext(Object.create(null), { codeGeneration: { strings: false, wasm: false }, microtaskMode: 'afterEvaluate' });
  new vm.Script(`
    globalThis.process = undefined; globalThis.require = undefined; globalThis.fetch = undefined;
    globalThis.console = undefined; globalThis.Buffer = undefined;
    const NativeDate = Date; const fixedNow = NativeDate.parse('2026-10-27T12:00:00.000Z');
    globalThis.Date = class extends NativeDate {
      constructor(...args) { super(...(args.length ? args : [fixedNow])); }
      static now() { return fixedNow; }
    };
    const registry = (() => {
      const entries = [];
      const same = (left, right) => Object.is(left, right) || (
        left && right && typeof left === 'object' && typeof right === 'object'
        && Array.isArray(left) === Array.isArray(right)
        && Object.keys(left).length === Object.keys(right).length
        && Object.keys(left).every((key) => Object.prototype.hasOwnProperty.call(right, key) && same(left[key], right[key]))
      );
      function strict(value, message = 'assertion failed') { if (!value) throw new Error(message); }
      strict.ok = strict;
      strict.equal = strict.strictEqual = (left, right) => strict(Object.is(left, right), 'strict equality mismatch');
      strict.deepEqual = strict.deepStrictEqual = (left, right) => strict(same(left, right), 'deep strict equality mismatch');
      Object.freeze(strict);
      return Object.freeze({ assert: strict,
        test(name, action) { if (typeof name !== 'string' || typeof action !== 'function' || entries.length >= 100) throw new Error('unsupported test registration'); entries.push({ name, action }); },
        count() { return entries.length; },
        run(index) { const entry = entries[index]; try {
          const result = entry.action(); if (result && typeof result.then === 'function') { result.catch(() => {}); throw new Error('async tests unsupported'); }
          return JSON.stringify({ name: entry.name, passed: true });
        } catch (error) { return JSON.stringify({ name: entry.name, passed: false, message: String(error.message).slice(0, 800) }); } },
      });
    })();
    Object.defineProperty(globalThis, '__testRegistry', { value: registry, writable: false, configurable: false });
    Object.freeze(JSON);
  `).runInContext(context, { timeout: 400 });
  const dynamicError = new vm.Script("new Error('dynamic import forbidden')").runInContext(context, { timeout: 400 });
  const modules = new Map();
  const create = (path) => {
    if (modules.has(path)) return modules.get(path);
    let module;
    if (path === 'node:test') module = new vm.SyntheticModule(['test'], function () {
      this.setExport('test', context.__testRegistry.test);
    }, { context, identifier: path });
    else if (path === 'node:assert/strict') module = new vm.SyntheticModule(['default'], function () {
      this.setExport('default', context.__testRegistry.assert);
    }, { context, identifier: path });
    else {
      assert.ok(BUSINESS_PATHS.has(path) || path === TEST_PATH, 'test module outside whitelist');
      const source = path === TEST_PATH ? testSource : sources[path];
      assert.equal(typeof source, 'string'); assert.ok(Buffer.byteLength(source) <= 256 * 1024);
      module = new vm.SourceTextModule(source, { context, identifier: path, importModuleDynamically: () => { throw dynamicError; } });
    }
    modules.set(path, module); return module;
  };
  const entry = create(TEST_PATH);
  await entry.link((specifier, parent) => {
    if (specifier === 'node:test' || specifier === 'node:assert/strict') return create(specifier);
    assert.ok(specifier.startsWith('./') || specifier.startsWith('../'), 'test import outside whitelist');
    return create(posix.normalize(posix.join(posix.dirname(parent.identifier), specifier)));
  });
  await entry.evaluate({ timeout: 400 });
  const count = new vm.Script('__testRegistry.count()').runInContext(context, { timeout: 400 });
  const checks = [];
  for (let index = 0; index < count; index += 1) {
    try { checks.push(JSON.parse(new vm.Script(`__testRegistry.run(${index})`).runInContext(context, { timeout: 400 }))); }
    catch (error) { checks.push({ name: `test-${index}`, passed: false, executionFailure: String(error.message).slice(0, 800) }); }
  }
  return { passed: checks.length > 0 && checks.every((check) => check.passed), checks };
}


if(process.argv[1]&&resolve(process.argv[1])===SELF) { if(process.argv[2]!=='--test-worker')throw Error('Only test worker entry'); process.stdout.write(JSON.stringify(await runTestWorker(process.argv[3]))); }
