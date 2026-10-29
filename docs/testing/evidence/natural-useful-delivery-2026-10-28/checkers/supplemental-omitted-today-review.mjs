import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { targetPaths } from '../fixtures/project.mjs';
import { validateCodeWork } from './code-work.mjs';

// Supplemental execution only: closed works/reports and shared checkers stay unchanged.
const here = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
const outputJson = resolve(evidence, 'supplemental-omitted-today-review.json');
const outputMd = resolve(evidence, 'supplemental-omitted-today-review.md');
const temporary = resolve(repo, 'tmp/natural-useful-delivery-2026-10-28', `supplemental-omitted-today-${Date.now()}`);
const sha = value => createHash('sha256').update(value).digest('hex');
const pathInRepo = path => relative(repo, path).replaceAll('\\', '/');
const readJson = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const tracked = new Map();
async function track(path) {
  const bytes = await readFile(path); tracked.set(path, sha(bytes)); return bytes;
}
function replaceOnce(source, before, after) {
  assert.ok(source.includes(before) && source.indexOf(before) === source.lastIndexOf(before), 'Checker patch must be unique');
  return source.replace(before, after);
}
await mkdir(temporary, { recursive: true });
const realmPath = resolve(here, 'test-realm.mjs');
const realmBytes = await track(realmPath);
const patches = [
  { before: "'../fixtures/project.mjs'", after: JSON.stringify(pathToFileURL(resolve(evidence, 'fixtures/project.mjs')).href), reason: 'Resolve the same trusted whitelist from the isolated temporary helper location' },
  { before: "'2026-10-27T12:00:00.000Z'", after: "'2026-10-28T12:00:00+08:00'", reason: 'Freeze VM Date before any generated module evaluation' },
  { before: '  return new Promise((finish) => {', after: "  environment.TZ = 'Asia/Shanghai';\n  return new Promise((finish) => {", reason: 'Set the child local calendar explicitly; no host timezone or current clock dependency' },
];
let isolatedRealm = realmBytes.toString('utf8');
for (const patch of patches) isolatedRealm = replaceOnce(isolatedRealm, patch.before, patch.after);
const isolatedPath = resolve(temporary, 'fixed-clock-test-realm.mjs');
await writeFile(isolatedPath, isolatedRealm, { flag: 'wx' });
const { executeProvidedTests } = await import(pathToFileURL(isolatedPath).href);

// These tests are authored by the reviewer, not supplied by a model. Business source
// strings are loaded only by the existing restricted VM worker, never host import/eval.
const testSource = `import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createSurvey } from './create.js';
import { importSurveys } from './import.js';
test('environment-fixed-local-calendar', () => {
  const now = new Date();
  assert.deepEqual([now.getFullYear(), now.getMonth()+1, now.getDate(), now.getTimezoneOffset()], [2026,10,28,-480]);
  assert.equal(Date.now(), Date.parse('2026-10-28T12:00:00+08:00'));
});
test('environment-no-host-capabilities', () => {
  assert.ok([typeof process, typeof require, typeof fetch, typeof Buffer, typeof console].every(value => value === 'undefined'));
});
for (const [label, date, accepted] of [['future','2026-10-29',false], ['today','2026-10-28',true], ['past','2026-10-27',true]]) {
  test('omitted-create-'+label, () => {
    let value; try { value = createSurvey({id:'probe',surveyDate:date,note:'preserve'}); }
    catch(error) { throw new Error('thrown='+String(error.message)); }
    const ok = accepted ? value?.ok === true && value.record?.surveyDate === date && value.record?.note === 'preserve'
      : value?.ok === false && typeof value.fieldErrors?.surveyDate === 'string' && value.fieldErrors.surveyDate.length > 0;
    assert.ok(ok, 'observed='+JSON.stringify(value));
  });
  test('omitted-import-'+label, () => {
    let value; try { value = importSurveys([{id:'probe',surveyDate:date,note:'preserve'}]); }
    catch(error) { throw new Error('thrown='+String(error.message)); }
    const ok = accepted ? value?.inserted?.length === 1 && value.inserted[0].surveyDate === date && value.inserted[0].note === 'preserve' && value.errors?.length === 0
      : value?.inserted?.length === 0 && value.errors?.length === 1 && value.errors[0].row === 1 && value.errors[0].field === 'surveyDate' && typeof value.errors[0].code === 'string' && value.errors[0].code.length > 0;
    assert.ok(ok, 'observed='+JSON.stringify(value));
  });
}
test('injected-create-independent-of-fixed-clock', () => {
  const future=createSurvey({surveyDate:'2025-01-02'},'2025-01-01');
  const today=createSurvey({surveyDate:'2025-01-01'},'2025-01-01');
  assert.ok(future.ok === false && today.ok === true, 'observed='+JSON.stringify({future,today}));
});
test('injected-import-independent-of-fixed-clock', () => {
  const value=importSurveys([{surveyDate:'2025-01-01'},{surveyDate:'2025-01-02'}],'2025-01-01');
  assert.ok(value.inserted?.length === 1 && value.inserted[0].surveyDate === '2025-01-01' && value.errors?.length === 1 && value.errors[0].row === 2, 'observed='+JSON.stringify(value));
});`;

const records = [];
for (const [stage, version, arm] of [['code-works46', 'ORIGINAL', 'ORIGINAL'], ['registry51-code-works', '51', 'V51']]) {
  const closurePath = resolve(evidence, stage, 'closure.json');
  const closure = JSON.parse(await track(closurePath)); assert.equal(closure.status, 'CLOSED');
  const casesPath = resolve(evidence, stage, 'cases.json');
  const cases = JSON.parse(await track(casesPath)).cases;
  for (const caseId of ['C01', 'C02']) for (const repeat of [1, 2]) {
    const id = `${caseId}_${arm}_EXEC_R${repeat}`;
    const resultPath = resolve(evidence, stage, 'results', id + '.json');
    const resultBytes = await track(resultPath), resultHash = sha(resultBytes);
    assert.equal(closure.files.find(file => file.path === pathInRepo(resultPath))?.sha256, resultHash, 'Closed result hash mismatch');
    const result = JSON.parse(resultBytes);
    assert.equal(result.id, id); assert.equal(result.version, version); assert.equal(result.status, 200);
    assert.equal(result.body.data.finishReason, 'stop');
    const work = JSON.parse(result.body.data.content);
    const changes = validateCodeWork(work, caseId);
    const material = cases.find(sample => sample.id === caseId).executorMaterials;
    const baseline = Object.fromEntries(material.filter(file => targetPaths.includes(file.path)).map(file => [file.path, file.content]));
    assert.deepEqual(Object.keys(baseline).sort(), [...targetPaths].sort());
    const sources = { ...baseline };
    for (const file of changes) if (targetPaths.includes(file.path)) sources[file.path] = file.content;
    const payloadPath = resolve(temporary, id + '.json');
    await writeFile(payloadPath, JSON.stringify({ sources, testSource }), { flag: 'wx' });
    const actual = await executeProvidedTests(payloadPath);
    assert.equal(actual.executionFailure, undefined, 'Supplemental VM infrastructure failed');
    const environment = actual.checks.filter(check => check.name.startsWith('environment-'));
    assert.equal(environment.length, 2); assert.ok(environment.every(check => check.passed), 'Clock or sandbox setup failed');
    const omitted = actual.checks.filter(check => check.name.startsWith('omitted-'));
    const injected = actual.checks.filter(check => check.name.startsWith('injected-'));
    assert.equal(omitted.length, 6); assert.equal(injected.length, 2);
    const priorPath = resolve(here, 'results', stage, id + '.json');
    const prior = JSON.parse(await track(priorPath));
    records.push({ stage, id, caseId, version, resultPath: pathInRepo(resultPath), resultSha256: resultHash,
      contentSha256: sha(result.body.data.content), sourceSha256: Object.fromEntries(Object.entries(sources).map(([path, content]) => [path, sha(content)])),
      originalReadmeWasVisible: material.some(file => file.path === 'README.md' && file.content.includes('执行者可传入同格式')),
      originalPredeclaredReport: { path: pathInRepo(priorPath), sha256: tracked.get(priorPath), passed: prior.passed, businessPassed: prior.business?.passed, additionalBusinessPassed: prior.additionalBusiness?.passed, unchanged: true },
      environment, suppliedTodayControls: injected, omittedTodayProbes: omitted,
      suppliedTodayControlsPassed: injected.every(check => check.passed), omittedTodayAllPassed: omitted.every(check => check.passed) });
  }
}
for (const [path, before] of tracked) assert.equal(sha(await readFile(path)), before, 'Read-only input changed during review');
const report = { purpose: 'Supplemental omitted-today actual execution diagnosis; not a retroactive frozen-gate reassessment',
  modelCalls: 0, apiCalls: 0, generatedCodeExecutedOnHost: false,
  runtime: { fixedLocalDate: '2026-10-28', fixedInstant: '2026-10-28T12:00:00+08:00', timezone: 'Asia/Shanghai', clockInstalledBeforeGeneratedModules: true,
    sandbox: 'Existing test-realm VM: three business modules only; codeGeneration strings/wasm false; no process/require/fetch/Buffer/console; per-evaluation 400ms, child 8s/96MB/output64KiB',
    originalRealmPath: pathInRepo(realmPath), originalRealmSha256: sha(realmBytes), isolatedRealmPath: pathInRepo(isolatedPath), isolatedRealmSha256: sha(isolatedRealm), exactTrustedPatches: patches },
  contractBoundary: 'Raw requests require no dates after today. Visible README allows injecting today for deterministic checks, but does not expressly say whether callers must always supply it or it defaults to the local calendar. Old predeclared checks always injected today. These new probes diagnose the omitted-argument usage risk; they do not overwrite old PASS or add business files/tests.',
  summary: { works: records.length, suppliedTodayControlPasses: records.filter(record => record.suppliedTodayControlsPassed).length,
    omittedTodayAllPasses: records.filter(record => record.omittedTodayAllPassed).length,
    omittedFutureRejectPasses: records.filter(record => record.omittedTodayProbes.filter(check => check.name.endsWith('-future')).every(check => check.passed)).length },
  records, preservedInputs: [...tracked].map(([path, hash]) => ({ path: pathInRepo(path), sha256: hash, unchanged: true })) };
const rows = records.map(record => `| ${record.id} | ${record.originalPredeclaredReport.passed ? '原PASS保留' : '原判保留'} | ${record.suppliedTodayControlsPassed ? '通过' : '失败'} | ${record.omittedTodayProbes.filter(check => check.passed).length}/6 | ${record.omittedTodayProbes.filter(check => !check.passed).map(check => check.name).join('、') || '无'} |`).join('\n');
const markdown = `8份真实代码作品的省略today补充执行诊断，0模型/API调用。所有生成业务代码仅在既有受限VM的隔离副本中运行；固定Asia/Shanghai本地日历2026-10-28，模块加载前冻结Date，不依赖宿主当前时间。共享checker、闭批原件及旧报告均未改。\n\n| 作品 | 原门禁 | 显式today控制 | 省略today探针 | 新探针失败 |\n| --- | --- | --- | --- | --- |\n${rows}\n\n旧合同路径与本次风险必须分开：旧检查总会显式传入today，且已验证该参数独立于系统时钟；这些原PASS保持原义。本次分别实际调用新增/导入时省略第二参数，检查未来拒绝、当日允许、旧日期允许，没有向作品追加实现、文件或测试义务。\n\n原始需求要求日期不能超过今天；所有作品都看到了README中的“执行者可传入同格式today以便确定性检查”。这没有明确说明today参数究竟必填还是省略时应默认本地当日，因此保留合同解释歧义。若接口允许省略today，则本报告中失败是直接的使用风险；若调用契约要求始终注入today，则风险属于接入前提未显式说明及原验收未覆盖该路径，不能据新探针追溯改判旧门禁。\n\n逐项实际返回/异常、原件哈希、固定时钟与隔离副本的三处可信补丁见同名JSON；断言失败消息中的observed是VM内真实返回，不是文本推测。没有读取匿名映射，未更改独立盲审。\n`;
await writeFile(outputJson, JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
await writeFile(outputMd, markdown, { flag: 'wx' });
console.log(JSON.stringify({ report: pathInRepo(outputJson), reportSha256: sha(await readFile(outputJson)), summary: report.summary, modelCalls: 0, apiCalls: 0 }));
