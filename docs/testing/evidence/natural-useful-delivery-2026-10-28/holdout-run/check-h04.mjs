import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const SELF = fileURLToPath(import.meta.url), HERE = dirname(SELF), SEAL = resolve(HERE, '../sealed-holdout');
const CHECKER_PATH = 'answer-cards/H04.check.mjs';
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const hash = text => createHash('sha256').update(text).digest('hex');

/** 按封存原始格式提取唯一javascript代码块，不改写实现，不运行模型返回的宿主脚本。 */
export function extractH04Source(text) {
  assert.equal(typeof text, 'string');
  const blocks = [...text.matchAll(/```([^\r\n]*)\r?\n([\s\S]*?)```/g)];
  assert.equal(blocks.length, 1, '作品必须只有一个代码块');
  const block = blocks[0]; assert.equal(block[1].trim().toLowerCase(), 'javascript');
  assert.equal((text.slice(0, block.index) + text.slice(block.index + block[0].length)).trim(), '', '代码块外不能有额外说明');
  assert.ok(Buffer.byteLength(block[2]) <= 256 * 1024, '作品超过安全执行容量');
  return block[2];
}

/** 独立子进程不继承密钥环境；VM中所有输入、断言、错误类型同realm，禁止暴露宿主对象。 */
export async function evaluateH04Source(source) {
  const environment = Object.fromEntries(['SystemRoot', 'WINDIR', 'TEMP', 'TMP'].filter(name => process.env[name]).map(name => [name, process.env[name]]));
  const payload = JSON.stringify({ source });
  return new Promise(finish => {
    const child = spawn(process.execPath, ['--experimental-vm-modules', '--max-old-space-size=96', SELF, '--worker'],
      { env: environment, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let output = '', errors = '', reason = '', settled = false;
    const timer = setTimeout(() => { reason = 'ARTIFACT_TOTAL_TIMEOUT'; child.kill(); }, 12000);
    const append = (value, isError) => {
      if (isError) errors += value.toString('utf8'); else output += value.toString('utf8');
      if (Buffer.byteLength(output) + Buffer.byteLength(errors) > 64 * 1024) { reason = 'ARTIFACT_OUTPUT_LIMIT'; child.kill(); }
    };
    child.stdout.on('data', value => append(value, false)); child.stderr.on('data', value => append(value, true));
    const settle = failure => {
      if (settled) return; settled = true; clearTimeout(timer);
      try { if (failure) throw Error(failure); finish(JSON.parse(output)); }
      catch (error) { finish({ passed: false, artifactLoadFailed: true, checks: [],
        executionFailure: reason || 'ISOLATED_EXECUTION_FAILED', error: String(error.message).slice(0, 1000), stderr: errors.slice(0, 1000) }); }
    };
    child.on('error', error => settle(error.message));
    child.on('close', (code, signal) => settle(reason || (code === 0 ? '' : `worker exit ${code ?? signal}`)));
    child.stdin.end(payload);
  });
}

async function runWorker() {
  const parts = []; let bytes = 0;
  for await (const part of process.stdin) { bytes += part.length; assert.ok(bytes <= 300 * 1024); parts.push(part); }
  const { source } = JSON.parse(Buffer.concat(parts).toString('utf8'));
  assert.equal(typeof source, 'string'); assert.ok(Buffer.byteLength(source) <= 256 * 1024);
  const manifest = await json(resolve(SEAL, 'manifest.json'));
  const sealedBytes = await readFile(resolve(SEAL, CHECKER_PATH));
  assert.equal(hash(sealedBytes), manifest.files.find(file => file.path === CHECKER_PATH).sha256, '封存检查器发生变化');
  const sealed = sealedBytes.toString('utf8');
  const start = sealed.indexOf('export function runContractChecks('), end = sealed.indexOf('const directlyInvoked');
  assert.ok(start >= 0 && end > start, '封存检查器结构不受当前安全工具支持');
  const contractSource = 'const assert=globalThis.__sealedAssert;\n' + sealed.slice(start, end);
  const context = vm.createContext(Object.create(null), { codeGeneration: { strings: false, wasm: false }, microtaskMode: 'afterEvaluate' });
  new vm.Script(`
    globalThis.process=undefined;globalThis.require=undefined;globalThis.fetch=undefined;
    globalThis.Buffer=undefined;globalThis.console=undefined;globalThis.WebSocket=undefined;
    (()=>{
      const keys=Object.keys,is=Object.is,getPrototypeOf=Object.getPrototypeOf,isArray=Array.isArray;
      const same=(a,b)=>is(a,b)||(a&&b&&typeof a==='object'&&typeof b==='object'
        &&isArray(a)===isArray(b)&&getPrototypeOf(a)===getPrototypeOf(b)
        &&keys(a).length===keys(b).length&&keys(a).every(k=>Object.prototype.hasOwnProperty.call(b,k)&&same(a[k],b[k])));
      function strict(value,message='assertion failed'){if(!value)throw new Error(message);}
      strict.ok=strict;
      strict.equal=strict.strictEqual=(a,b,message)=>strict(is(a,b),message||'strict equality mismatch');
      strict.notEqual=strict.notStrictEqual=(a,b,message)=>strict(!is(a,b),message||'unexpected identical reference');
      strict.deepEqual=strict.deepStrictEqual=(a,b,message)=>strict(same(a,b),message||'deep strict equality mismatch');
      strict.throws=(action,Expected)=>{let threw=false;try{action();}catch(error){threw=true;strict(error instanceof Expected,'incorrect error type');}strict(threw,'expected error was not thrown');};
      Object.defineProperty(globalThis,'__sealedAssert',{value:Object.freeze(strict),writable:false,configurable:false});
    })();
    for(const name of ['Object','Array','Map','Set','String','Number','Boolean','JSON','Math','TypeError','RangeError','Error','Function','Reflect','Promise']){
      const value=globalThis[name];if(value.prototype)Object.freeze(value.prototype);Object.freeze(value);
      Object.defineProperty(globalThis,name,{value,writable:false,configurable:false});
    }
  `).runInContext(context, { timeout: 400 });
  const dynamicError = new vm.Script("new Error('dynamic import is unavailable in isolated artifact runner')").runInContext(context, { timeout: 400 });
  const options = { context, importModuleDynamically: () => { throw dynamicError; } };
  const checker = new vm.SourceTextModule(contractSource, { ...options, identifier: 'sealed-H04-checker' });
  const artifact = new vm.SourceTextModule(source, { ...options, identifier: 'H04-user-artifact' });
  try {
    const denyImport = specifier => { const failure = Error(`Unsupported static import: ${specifier}`); failure.code = 'UNSUPPORTED_IMPORT'; throw failure; };
    await checker.link(denyImport); await checker.evaluate({ timeout: 400 });
    await artifact.link(denyImport); await artifact.evaluate({ timeout: 1000 });
    if (typeof artifact.namespace.mergeShoppingItems !== 'function') return { passed: false, artifactLoadFailed: true, checks: [], error: 'Missing named mergeShoppingItems function' };
    Object.defineProperty(context, '__sealedRun', { value: checker.namespace.runContractChecks, writable: false, configurable: false });
    Object.defineProperty(context, '__artifactMerge', { value: artifact.namespace.mergeShoppingItems, writable: false, configurable: false });
    const result = JSON.parse(new vm.Script('JSON.stringify(__sealedRun(__artifactMerge))').runInContext(context, { timeout: 2000 }));
    return { passed: result.passed, artifactLoadFailed: false, checks: result.results, sealedCheckerSha256: hash(sealedBytes),
      isolation: 'same-realm inputs/assertions/errors; no Node host module imports, package scripts, filesystem or network' };
  } catch (error) {
    return { passed: false, artifactLoadFailed: true, checks: [], error: String(error.message).slice(0, 1000),
      ...(error.code === 'UNSUPPORTED_IMPORT' ? { requiresManualToolScopeReview: true, executionFailure: 'UNSUPPORTED_IMPORT' } : {}) };
  }
}

export async function checkH04Work(content) {
  let source, formatPassed = true, formatError;
  try { source = extractH04Source(content); }
  catch (error) {
    formatPassed = false; formatError = error.message;
    // 格式只影响答案卡第10项。唯一可明确定位的代码仍原样验证其余行为，不能把额外说明
    // 自动当作整个函数不可用；多个代码块或无法定位代码时不猜测选哪份实现。
    const blocks = typeof content === 'string' ? [...content.matchAll(/```([^\r\n]*)\r?\n([\s\S]*?)```/g)] : [];
    if (blocks.length !== 1 || blocks[0][1].trim().toLowerCase() !== 'javascript') {
      return { passed: false, formatPassed, formatError, behaviorUnavailable: true, actualModelCalls: 0 };
    }
    source = blocks[0][2];
    if (Buffer.byteLength(source) > 256 * 1024) return { passed: false, formatPassed, formatError,
      behaviorUnavailable: true, executionFailure: 'ARTIFACT_CAPACITY_LIMIT', actualModelCalls: 0 };
  }
  const behavior = await evaluateH04Source(source);
  return { passed: formatPassed && behavior.passed, formatPassed, ...(formatError ? { formatError } : {}),
    artifactSha256: hash(source), behavior, actualModelCalls: 0 };
}

if (process.argv[1] && resolve(process.argv[1]) === SELF) {
  if (process.argv[2] === '--worker') process.stdout.write(JSON.stringify(await runWorker()));
  else if (process.argv[2] === '--self-test') {
    const correct = `export function mergeShoppingItems(items) {
      if (!Array.isArray(items)) throw new TypeError('items'); const groups = new Map();
      for (const item of items) {
        if (item === null || typeof item !== 'object' || Array.isArray(item) || typeof item.name !== 'string'
          || !item.name.trim() || !Number.isSafeInteger(item.quantity) || item.quantity <= 0) throw new TypeError('item');
        const name=item.name.trim(), key=name.toLowerCase(), previous=groups.get(key);
        if(previous){const quantity=previous.quantity+item.quantity;if(!Number.isSafeInteger(quantity))throw new RangeError('quantity');previous.quantity=quantity;}
        else groups.set(key,{name,quantity:item.quantity});
      }
      return [...groups.values()];
    }`;
    const legal = await evaluateH04Source(correct); assert.equal(legal.passed, true); assert.equal(legal.checks.length, 10);
    const baseline = await evaluateH04Source(await readFile(resolve(SEAL, 'inputs/H04/src/mergeShoppingItems.js'), 'utf8')); assert.equal(baseline.passed, false);
    const invalid = await evaluateH04Source(correct.replace("name=item.name.trim()", "name=item.name.trim().toLowerCase()")); assert.equal(invalid.passed, false);
    const hostEscape = await evaluateH04Source("export function mergeShoppingItems(items){return items.constructor.constructor('return process')();}"); assert.equal(hostEscape.passed, false);
    const loop = await evaluateH04Source('export function mergeShoppingItems(items){while(true){}}'); assert.equal(loop.passed, false);
    const imports = await evaluateH04Source("import fs from 'node:fs';export function mergeShoppingItems(items){return fs;}"); assert.equal(imports.executionFailure, 'UNSUPPORTED_IMPORT');
    assert.throws(() => extractH04Source('```javascript\nexport const x=1;\n```\n多余说明'));
    const report = { actualModelCalls: 0, legalControl: legal, baselineControl: baseline,
      wrongDisplayNameRejected: invalid, hostConstructorRejected: hostEscape, loopRejected: loop, importNotExecuted: imports,
      limits: '受限纯JavaScript ESM。若实际作品使用其他合法内置模块，标工具能力复核而非直接判语义失败。封存评分未修改。' };
    await writeFile(resolve(HERE, 'h04-runner-selftest.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
    console.log(JSON.stringify({ actualModelCalls: 0, controlsPassed: true, sealedBehaviorGroups: 10 }));
  } else {
    const path = process.argv[2]; if (!path) throw Error('用法：node check-h04.mjs <原始作品正文文本>');
    console.log(JSON.stringify(await checkH04Work(await readFile(path, 'utf8')), null, 2));
  }
}
