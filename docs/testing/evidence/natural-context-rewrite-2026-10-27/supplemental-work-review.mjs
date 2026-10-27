import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, posix, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { materializeAndCheckCodeWork } from './check-code-work.mjs';
import { targetPaths } from './synthetic-project.mjs';

const SELF = fileURLToPath(import.meta.url);
const EVIDENCE = dirname(SELF);
const REPOSITORY = resolve(EVIDENCE, '../../../..');
const TEST_PATH = 'src/views/records/dateRules.spec.js';
const BUSINESS_PATHS = new Set(targetPaths);
const json = async (path) => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = (value) => createHash('sha256').update(value).digest('hex');

/** 独立诊断不修改冻结判分；只投影业务文件，并在隔离realm中核对额外测试能否识别已知缺陷。 */
export async function supplementalWorkReview({ output = resolve(EVIDENCE, 'supplemental-work-review.json') } = {}) {
  const results = resolve(REPOSITORY, 'tmp/natural-context-rewrite-2026-10-27/works/results');
  const temporary = resolve(REPOSITORY, `tmp/natural-context-rewrite-2026-10-27/supplemental-${Date.now()}`);
  await mkdir(temporary, { recursive: true });
  const baselineSources = Object.fromEntries(await Promise.all(targetPaths.map(async (path) => [path,
    await readFile(resolve(EVIDENCE, 'fixtures/project', path), 'utf8')])));
  const originalTests = await readFile(resolve(EVIDENCE, 'fixtures/project', TEST_PATH), 'utf8');
  const correctSources = { ...baselineSources, [targetPaths[0]]: baselineSources[targetPaths[0]].replace(
    '  return null;', "  if (value > today) return 'FUTURE_DATE';\n  return null;") };
  const omittedImportSources = { ...correctSources, [targetPaths[2]]: baselineSources[targetPaths[2]].replace(
    'validateSurveyDate(record.surveyDate, today)', "validateSurveyDate(record.surveyDate, '9999-12-31')") };
  const excludedTodaySources = { ...correctSources, [targetPaths[0]]: correctSources[targetPaths[0]].replace(
    'value > today', 'value >= today') };
  const records = [];
  for (const name of (await readdir(results)).filter((name) => /^C01_(?:ORIGINAL|V31|V45)_EXEC_R[12]\.json$/.test(name)).sort()) {
    const resultPath = resolve(results, name);
    const originalResult = await readFile(resultPath, 'utf8');
    const result = JSON.parse(originalResult);
    assert.equal(result.status, 200);
    assert.equal(result.body.data.finishReason, 'stop');
    const work = JSON.parse(result.body.data.content);
    assert.ok(Array.isArray(work.files));
    const business = { files: work.files.filter((file) => BUSINESS_PATHS.has(file.path)) };
    const extras = work.files.filter((file) => !BUSINESS_PATHS.has(file.path));
    const frozenPath = resolve(EVIDENCE, 'code-work-verdicts', name);
    const frozenBytes = await readFile(frozenPath, 'utf8');
    const frozen = JSON.parse(frozenBytes);
    const projection = await materializeAndCheckCodeWork(business, resolve(temporary, result.id));
    const projectedSources = { ...baselineSources, ...Object.fromEntries(business.files.map((file) => [file.path, file.content])) };
    const extraReviews = [];
    for (const extra of extras) {
      assert.equal(typeof extra.path, 'string');
      assert.equal(typeof extra.content, 'string');
      const review = { path: extra.path, sha256: sha(extra.content), allowedByFrozenWorkProtocol: false,
        relation: extra.path === TEST_PATH ? '目标模块的日期校验回归测试' : '未评估的额外文件' };
      if (extra.path === TEST_PATH) {
        const contexts = [
          ['originalBaseline', baselineSources], ['projectedActualBusiness', projectedSources],
          ['handwrittenCorrectControl', correctSources], ['omittedImportValidationControl', omittedImportSources],
          ['excludedTodayControl', excludedTodaySources],
        ];
        const runs = {};
        for (const [label, sources] of contexts) {
          const payload = resolve(temporary, `${result.id}-${label}-tests.json`);
          await writeFile(payload, JSON.stringify({ testSource: extra.content, sources }), { flag: 'wx' });
          runs[label] = await executeProvidedTests(payload);
        }
        const normalize = (value) => value.replace(/\r\n/g, '\n').trimEnd();
        Object.assign(review, { execution: '仅支持本次实际使用的同步node:test与strict断言；由隔离realm实现，不加载Node宿主测试模块',
          preservesOriginalTestTextUnderLineEndingNormalization: normalize(extra.content).startsWith(normalize(originalTests)), runs,
          demonstratedRegressionValue: runs.originalBaseline.checks?.some((check) => !check.passed)
            && runs.projectedActualBusiness.passed && runs.handwrittenCorrectControl.passed
            && runs.omittedImportValidationControl.checks?.some((check) => !check.passed),
        });
      }
      extraReviews.push(review);
    }
    records.push({ id: result.id, version: result.version, originalResultSha256: sha(originalResult),
      frozenVerdictSha256: sha(frozenBytes), frozenProtocolPassed: frozen.acceptance.passed,
      diagnosisOnlyProjection: { statement: '仅排除额外文件进行业务归因；不重判原始交付协议，不代替原始完整作品验收',
        projectedPaths: business.files.map((file) => file.path), excludedPaths: extras.map((file) => file.path),
        businessAcceptance: projection.acceptance, nonTargetFilesPreserved: projection.preserved.length },
      extraReviews });
    assert.equal(await readFile(resultPath, 'utf8'), originalResult, '原始模型结果发生改变');
    assert.equal(await readFile(frozenPath, 'utf8'), frozenBytes, '冻结判分发生改变');
  }
  assert.equal(records.length, 6, '本次诊断只覆盖已完成的六份代码作品');
  const report = { purpose: '补充离线归因；不修改冻结协议、原始FAIL或采用标准', actualNewModelCalls: 0,
    projectionIsNotOriginalDeliveryPass: true, records,
    interpretation: {
      business: '业务投影结果仅说明实际返回的业务修改是否满足独立行为检查。',
      tests: '只有原基线失败、正确实现通过、导入遗漏控制失败，才证明新增测试具有本例可观察的回归价值。',
      protocol: '额外测试与任务相关也不改变未遵守三业务文件写入白名单的事实。',
      limits: '同一合成开发题、每臂两次；不能推导跨任务稳定收益、真人修改耗时或正式采用结论。',
    } };
  await writeFile(output, JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  return report;
}

/** 额外测试只在自有受限子进程中运行，不直接import模型提供的测试文件。 */
async function executeProvidedTests(payload) {
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

if (process.argv[1] && resolve(process.argv[1]) === SELF) {
  if (process.argv[2] === '--test-worker') process.stdout.write(JSON.stringify(await runTestWorker(process.argv[3])));
  else {
    const report = await supplementalWorkReview();
    console.log(JSON.stringify({ output: resolve(EVIDENCE, 'supplemental-work-review.json'), actualNewModelCalls: 0,
      records: report.records.map((record) => ({ id: record.id, frozenProtocolPassed: record.frozenProtocolPassed,
        projectedBusinessPassed: record.diagnosisOnlyProjection.businessAcceptance.passed,
        extraFiles: record.extraReviews.map((extra) => ({ path: extra.path, demonstratedRegressionValue: extra.demonstratedRegressionValue })) })) }));
  }
}
