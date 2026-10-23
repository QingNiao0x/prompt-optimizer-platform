/** 内部出题者逐源复核的机械补证；只读冻结原件，生成的 TS 只在内存执行纯函数。 */
import assert from 'node:assert/strict';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolve, relative } from 'node:path';
import vm from 'node:vm';
import { executionMaterialSuffix } from '../evaluation-inputs.mjs';

const root = process.cwd();
const evidence = resolve(root, 'docs/testing/evidence/natural-execution-boundary-2026-10-23/unseen');
const results = resolve(root, 'tmp/natural-execution-boundary-2026-10-23/unseen/results');
const require = createRequire(import.meta.url);
const ts = require(resolve(root, 'apps/web/node_modules/typescript/lib/typescript.js'));
const sha256 = value => createHash('sha256').update(value).digest('hex');
const localPath = value => relative(root, value).replaceAll('\\', '/');
const readJson = async path => JSON.parse(await readFile(path, 'utf8'));
const casePath = resolve(evidence, 'cases.json');
const caseBytes = await readFile(casePath);
const pack = JSON.parse(caseBytes.toString('utf8'));
// 同目录可继续生成独立评审原件；本复核只消费指定的八份生成与二十四份作品。
const sourceFiles = (await readdir(results)).filter(name =>
  /^EB-(?:W01|C02|R03|U04)_(?:V(?:31|34)_GEN|(?:ORIGINAL|V31|V34)_EXEC_R[12])\.json$/.test(name)).sort();
assert.equal(sourceFiles.length, 32);
const records = await Promise.all(sourceFiles.map(async name => {
  const path = resolve(results, name);
  const bytes = await readFile(path);
  return { record: JSON.parse(bytes.toString('utf8')), path: localPath(path), sha256: sha256(bytes) };
}));
const lookup = id => {
  const found = records.find(item => item.record.id === id);
  assert(found, `Missing frozen result ${id}`);
  return found.record;
};
const checkSources = records.map(item => {
  const { record } = item;
  assert.equal(record.status, 200);
  const sample = pack.cases.find(value => value.id === record.caseId);
  assert(sample);
  if (record.phase === 'GENERATION') {
    assert.equal(record.request.rawPrompt, sample.rawPrompt);
    assert.equal(record.request.context.customDescription, sample.contextDescription ?? '');
    assert.deepEqual(record.request.context.files, sample.files ?? []);
    for (const forbidden of ['executorMaterials', 'executionEnvironment', 'oracle', 'acceptance']) {
      assert(!(forbidden in record.request));
    }
    assert.equal(typeof record.body.data.optimizedPrompt, 'string');
  } else {
    assert.equal(record.phase, 'EXECUTION');
    const arm = record.id.includes('_ORIGINAL_') ? 'ORIGINAL' : record.id.includes('_V31_') ? 'V31' : 'V34';
    const prompt = arm === 'ORIGINAL' ? sample.rawPrompt : lookup(`${sample.id}_${arm}_GEN`).body.data.optimizedPrompt;
    assert.equal(record.request.messages[1].content, prompt + executionMaterialSuffix(sample));
    assert.equal(record.body.data.finishReason, 'stop');
    assert.equal(typeof record.body.data.content, 'string');
  }
  return { id: record.id, phase: record.phase, path: item.path, sha256: item.sha256,
    httpStatus: record.status, inputBoundary: 'PASS', contentReadByReviewer: true };
});
assert.equal(checkSources.filter(row => row.phase === 'GENERATION').length, 8);
assert.equal(checkSources.filter(row => row.phase === 'EXECUTION').length, 24);

/** 待测源码已人工通读；仅允许原夹具归一化函数这一项导入，不授予文件、进程或网络能力。 */
function loadPureStockFunction(content) {
  const match = content.match(/```(?:ts|typescript)\s*\n([\s\S]*?)```/);
  assert(match, 'Expected complete TypeScript implementation');
  const code = match[1];
  const transformed = ts.transpileModule(code, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, strict: true },
    reportDiagnostics: true,
  });
  const diagnostics = transformed.diagnostics ?? [];
  assert.equal(diagnostics.filter(row => row.category === ts.DiagnosticCategory.Error).length, 0);
  const imports = [];
  const sandbox = {
    exports: {},
    require(name) {
      imports.push(name);
      assert.equal(name, './normalizeSku');
      return { normalizeSku(sku) {
        const normalized = sku.trim().toUpperCase();
        if (!normalized) throw new Error('sku is required');
        return normalized;
      } };
    },
  };
  const context = vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
  vm.runInContext(transformed.outputText, context, { timeout: 1000 });
  assert.equal(typeof sandbox.exports.summarizeStock, 'function');
  assert.deepEqual(imports, ['./normalizeSku']);
  return { codeSha256: sha256(code), run(entries) {
    sandbox.__stockInput = entries;
    return vm.runInContext('exports.summarizeStock(__stockInput)', context, { timeout: 1000 });
  } };
}

const stockTests = records.filter(item => item.record.caseId === 'EB-C02' && item.record.phase === 'EXECUTION').map(item => {
  const implementation = loadPureStockFunction(item.record.body.data.content);
  const checks = [];
  const equal = (name, input, expected) => {
    const before = JSON.stringify(input);
    const result = implementation.run(input);
    assert.notEqual(result, input);
    assert.deepEqual(JSON.parse(JSON.stringify(result)), expected);
    assert.equal(JSON.stringify(input), before);
    checks.push({ name, pass: true });
    return result;
  };
  equal('normalize-and-merge', Object.freeze([
    Object.freeze({ sku: ' ab-7 ', delta: 8 }), Object.freeze({ sku: 'AB-7', delta: -3 }),
    Object.freeze({ sku: 'x-2', delta: 0 }),
  ]), [{ sku: 'AB-7', delta: 5 }, { sku: 'X-2', delta: 0 }]);
  equal('retain-net-zero', Object.freeze([
    Object.freeze({ sku: 'net', delta: 4 }), Object.freeze({ sku: 'NET', delta: -4 }),
  ]), [{ sku: 'NET', delta: 0 }]);
  equal('empty-input', Object.freeze([]), []);
  equal('ASCII-order', Object.freeze([
    Object.freeze({ sku: '_a', delta: 1 }), Object.freeze({ sku: 'z', delta: -2 }),
    Object.freeze({ sku: 'a', delta: 3 }), Object.freeze({ sku: '0', delta: 0 }),
  ]), [{ sku: '0', delta: 0 }, { sku: 'A', delta: 3 }, { sku: 'Z', delta: -2 }, { sku: '_A', delta: 1 }]);
  for (const [label, value] of [['NaN', NaN], ['positive-infinity', Infinity], ['negative-infinity', -Infinity], ['fraction', 0.25]]) {
    let error;
    try { implementation.run(Object.freeze([Object.freeze({ sku: 'a', delta: value })])); }
    catch (caught) { error = caught; }
    assert(error);
    assert.equal(error.name, 'RangeError');
    assert.equal(error.message, 'delta must be finite integer');
    checks.push({ name: `reject-${label}`, pass: true });
  }
  let skuError;
  try { implementation.run(Object.freeze([Object.freeze({ sku: '  ', delta: 1 })])); }
  catch (caught) { skuError = caught; }
  assert(skuError);
  assert.equal(skuError.name, 'Error');
  assert.equal(skuError.message, 'sku is required');
  checks.push({ name: 'reuse-empty-sku-error', pass: true });
  const original = Object.freeze([Object.freeze({ sku: 'A', delta: 1 })]);
  const first = implementation.run(original);
  const second = implementation.run(original);
  assert.notEqual(first, second);
  assert.notEqual(first[0], original[0]);
  assert.notEqual(first[0], second[0]);
  checks.push({ name: 'new-result-array-and-objects', pass: true });
  return { id: item.record.id, codeSha256: implementation.codeSha256, pass: true, checks };
});
assert.equal(stockTests.length, 6);

const arms = ['ORIGINAL', 'V31', 'V34'];
const workIds = caseId => arms.flatMap(arm => [1, 2].map(repeat => `${caseId}_${arm}_EXEC_R${repeat}`));
const allPass = caseId => workIds(caseId).map(id => ({ id, verdict: 'PASS_ORIGINAL_ORACLE', requiredCorrections: [] }));
const summary = {
  schemaVersion: 1,
  recordedAt: new Date().toISOString(),
  reviewer: { role: 'INTERNAL_CASE_AUTHOR_SOURCE_REVIEW', independentExternalBlindReview: false,
    authorKnewOriginalCases: true, candidateGuidanceAuthoredByReviewer: false, candidateGuidanceReadForThisReview: false,
    modelsCalled: 0, rubric: 'Sealed original case oracle only; no development medical case or new threshold used.' },
  caseSet: { path: localPath(casePath), sha256: sha256(caseBytes), caseIds: pack.cases.map(row => row.id),
    openingReceipt: localPath(resolve(evidence, 'opening-receipt.json')) },
  coverage: { generationRead: 8, executionWorksRead: 24, executionInputEquality: 'PASS',
    frozenFilesModified: false, onlyNewOutputs: ['source-checks.mjs', 'source-checks.json'],
    interpretation: 'HTTP success and mechanical checks are evidence only; semantic judgments below apply the existing oracle.' },
  sources: checkSources,
  runtimeChecks: { caseId: 'EB-C02', sourceReviewedBeforeExecution: true, generatedCodeIO: 'NONE',
    execution: 'TypeScript transpiled in memory, six functions executed in VM, only fixture normalizeSku import allowed; frozen inputs and source unchanged.',
    typeCheckBoundary: 'transpile diagnostics and runtime checks, not a full TypeScript project typecheck or actual checkout integration',
    tests: stockTests },
  cases: [
    { id: 'EB-W01', generation: { V31: 'PASS_ORIGINAL_ORACLE', V34: 'PASS_ORIGINAL_ORACLE' },
      works: allPass('EB-W01'),
      sourceEvidence: [
        { source: 'archive/pinecone/autumn-swap/attendance.txt', fact: '34报名、28签到，6名志愿者已计入28；六份作品均无重复相加。' },
        { source: 'archive/pinecone/autumn-swap/expenses.txt', fact: '288+74+130=492；六份作品一致，31第二份另列108预算结余也正确。' },
        { source: 'archive/pinecone/autumn-swap/debrief.txt', fact: '标识冲突6次问路及两箱未分类占用10分钟均覆盖；改进仍标为建议或下次安排，没有冒充已实施。' },
      ],
      stageBoundary: 'Both generations instruct reading archives without asserting optimization-stage absence means no archive; all six works use supplied archive and deliver retrospective prose.',
      comparison34To31: 'TIE_FOR_REQUIRED_USER_CORRECTIONS',
      interpretation: '34改进段较简略，31更具体；原oracle允许简洁落实建议，不新增具体步骤门槛，不能据此说34更好或判其不通过。原始臂亦完成。' },
    { id: 'EB-C02', generation: { V31: 'PASS_ORIGINAL_ORACLE', V34: 'PASS_ORIGINAL_ORACLE' },
      works: allPass('EB-C02'),
      sourceEvidence: [
        { source: 'docs/stock-contract.txt', fact: '六份保留签名、类型、既有normalizeSku；合并、零值保留、ASCII顺序、有限整数和空sku错误与不变性均经上述正常契约检查。' },
      ],
      stageBoundary: 'Both generations preserve local contract lookup and actual code delivery. No unsupported assertion that contracts or tools are absent.',
      comparison34To31: 'TIE_FOR_REQUIRED_USER_CORRECTIONS',
      interpretation: '六份实质等价。正常夹具通过不证明所有输入或项目集成都通过；原始臂亦通过，未证明增强新增收益。' },
    { id: 'EB-R03', generation: { V31: 'PASS_ORIGINAL_ORACLE', V34: 'PASS_ORIGINAL_ORACLE' },
      works: allPass('EB-R03'),
      sourceEvidence: [
        { source: 'records/index.txt; records/2024/type-a.txt; records/2024/type-b.txt; records/2024/type-c.txt', fact: '六份均给B=36/40=90%、A=48/60=80%、C=56/80=70%，前二B/A；保留全年人次口径与来源。' },
        { source: 'records/2025/archive-note.txt', fact: '六份均排除2025浏览量1200，没有将其当2024签到数。' },
      ],
      stageBoundary: 'Conditional missing-data language does not assert records are absent. Works acknowledge all comparison inputs exist and answer the ranking.',
      comparison34To31: 'TIE_FOR_REQUIRED_USER_CORRECTIONS',
      interpretation: '34两份追加较多可比性与局限讨论；其活动差异说明为条件或可能性，没有伪装成已知原因，排序仍正确。原oracle不以更长的讨论计收益，也不新设极短篇幅要求。' },
    { id: 'EB-U04', generation: { V31: 'FAIL_EXPLICIT_CONFIRMATION_DROPPED', V34: 'PASS_ORIGINAL_ORACLE' },
      generationEvidence: [
        { id: 'EB-U04_V31_GEN', quote: '请直接交付这封通知正文', issue: '原始先向我确认新地点被删，改为直接交付正文；待确认状态不等于落实提问。' },
        { id: 'EB-U04_V34_GEN', quote: '请先向我确认新地点', finding: '保留显式用户保留决定与提问，并允许先写独立部分。' },
      ],
      works: workIds('EB-U04').map(id => ({ id, verdict: 'FAIL_ORIGINAL_EXPLICIT_CONFIRMATION',
        requiredCorrections: id === 'EB-U04_V31_EXEC_R1'
          ? ['Add an explicit focused question asking the user for the new venue.', 'Remove unsupported notice date 2027年5月9日; input specifies the activity date only.']
          : ['Add an explicit focused question asking the user for the new venue.'],
        preserved: ['2027年5月9日10:00', 'new venue pending, no invented venue', 'old venue not reused as new', 'no fabricated change reason', 'independent draft delivered'],
      })),
      sourceEvidence: [
        { source: 'original rawPrompt; oracle.executionChecks[0]', fact: '原题要求先向我确认新地点；原oracle同样要求显式问题。六份作品均仅给待确认/另行通知，无实际地点提问。' },
        { source: 'EB-U04_V31_EXEC_R1', quote: '活动组织方\n2027年5月9日', issue: '把活动日期当作通知落款日期，现有资料没有该事实。' },
      ],
      stageBoundary: 'All six works preserve the genuine unknown and user venue choice without choosing a venue, but fail the explicit clarification action. This is not a reason to excuse or remove the original question gate.',
      comparison34To31: 'PROMPT_FIDELITY_IMPROVES_BUT_BOTH_EXECUTION_ARMS_FAIL',
      interpretation: '34修复原问题的传达，不能据此称作品完成；其两份均未再出现31第一份的通知日期问题，只是本批局部观察，不能外推稳定少改。原始臂也漏问。新增署名/日期占位可删但不升级为新的硬门槛。' },
  ],
  overall: {
    generationFidelity: { V31: { pass: 3, fail: 1 }, V34: { pass: 4, fail: 0 } },
    executionByArm: Object.fromEntries(arms.map(arm => [arm, { pass: 6, fail: 2, failingCase: 'EB-U04' }])),
    newStageAbsenceClaimsObserved: 0,
    statement: '四题全部保留。三题原始/31/34均可用；地点题34提示词保留了31遗漏的显式确认，但24份作品中该题六份均漏问，不能将待确认当已提问。除31一份未经依据的通知日期外，未见34相对31稳定减少必需修改。',
    attribution: 'U04 original request and V34 copy both carry the clarification; actual works still omit it. V31 additionally drops it in generation. Preserve mixed platform/downstream evidence rather than crediting prompt fidelity as completed execution.',
    boundary: 'Internal regression and source-grounded review only, not external blind review, stable unseen benefit, medical validation or release approval. No model calls or quality retries performed by this review.'
  }
};
await writeFile(resolve(evidence, 'source-checks.json'), JSON.stringify(summary, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ output: localPath(resolve(evidence, 'source-checks.json')), sourceResults: records.length,
  pureFunctionImplementations: stockTests.length, pureFunctionChecksPerImplementation: stockTests[0].checks.length,
  generationFidelity: summary.overall.generationFidelity, executionByArm: summary.overall.executionByArm }));
