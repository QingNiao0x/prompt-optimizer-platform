import { readFile, writeFile, readdir, mkdir, cp } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
const repo = process.cwd(), publicRoot = resolve('docs/testing/evidence/me00a-candidate10-2026-10-14');
const runtime = resolve('tmp/me00a-20261012'), owned = resolve('tmp/me00a-candidate10-20261014');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
async function save(path, value) {
  const bytes = JSON.stringify(value, null, 2);
  try { await writeFile(path, bytes, { flag: 'wx' }); }
  catch (failure) { if (failure.code !== 'EEXIST' || await readFile(path, 'utf8') !== bytes) throw failure; }
}
async function files(root) {
  const result = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = resolve(root, entry.name);
    if (entry.isDirectory()) result.push(...await files(path)); else if (entry.isFile()) result.push(path);
  }
  return result.sort();
}
const ledger = await files(resolve(runtime, 'traces'));
const records = await Promise.all(ledger.filter(path => /\d+\.json$/.test(path)).map(async path => JSON.parse(await readFile(path, 'utf8'))));
if (records.length !== 214 || records.filter(record => record.phase === 'candidateValidation').length !== 144)
  throw new Error('Actual ledger changed; do not automatically extend budget');
let protocol = JSON.parse(await readFile(resolve(runtime, 'protocol.json'), 'utf8'));
if (protocol.activeBatch.version === 'candidate10') protocol = JSON.parse(await readFile(resolve(publicRoot, 'protocol-before-amendment-07.json'), 'utf8'));
if (protocol.maximumActualModelCalls !== 229 || protocol.phaseLimits.candidateValidation !== 149) throw new Error('Formal limits changed');
await save(resolve(publicRoot, 'protocol-before-amendment-07.json'), protocol);
const cases = JSON.parse(await readFile(resolve(runtime, 'cases.json'), 'utf8')).filter(sample => sample.id === 'Q05');
const caseText = JSON.stringify(cases, null, 2);
await save(resolve(runtime, 'candidate10-q05-cases.json'), cases);
await save(resolve(publicRoot, 'q05-cases.json'), cases);
const amendment = { id: 'protocol-amendment-07', authorizedBy: 'USER_EXPLICIT_CONTINUE_WITHOUT_BATCH_RECONFIRMATION',
  authorizationText: '后续进行真实评测直接使用即可不需要向我进行确认, 以及以后每次评测等都是用同一个测试账号',
  before: { total: 214, baseline: 70, candidateValidation: 144 }, formalLimitsUnchanged: { total: 229, baseline: 80, candidateValidation: 149 },
  maximumNewCalls: 4, maximumActualCallsAfterBatch: 218, groups: { platformGeneration: 2, execution: 2, independentAiReviews: 0 },
  enhancementModel: 'deepseek-flash', executionModels: ['deepseek-flash'],
  reviewModels: [], round: 1, cases: ['Q05'], accountCreationAllowed: false,
  noPhaseBorrowing: true, previousClosedBatchNotReopened: true, everyFailedAttemptCounts: true,
  primaryComparison: 'raw equal actual information vs new actual copied body work, per execution model',
  diagnosis: 'same frozen copies and verbatim answers, change only execution model',
  unspentFormalCandidateAllowanceOutsideThisBatch: 1 };
await save(resolve(publicRoot, 'protocol-amendment-07.json'), amendment);
protocol.activeBatch = { version: 'candidate10', group: 'Q05_NATURAL_COVERAGE_REPAIR', mode: 'plan-replay', cases: ['Q05'], model: 'deepseek-flash',
  executionModels: amendment.executionModels, callsBefore: 214, maximumNewCalls: 4, maximumActualCallsAfterBatch: 218,
  maximumGenerationCalls: 2, maximumExecutionCalls: 2, maximumReviewCalls: 0, authorizationStatus: 'APPROVED_BY_USER',
  authorizationAmendment: 'protocol-amendment-07.json', secondRoundAuthorized: false,
  casesFile: 'candidate10-q05-cases.json', casesSha256: hash(caseText) };
await writeFile(resolve(runtime, 'protocol.json'), JSON.stringify(protocol, null, 2));
await save(resolve(publicRoot, 'protocol-approved.json'), protocol);
await mkdir(resolve(owned, 'candidate10'), { recursive: true });
try { await cp(resolve('services/api/target/classes'), resolve(owned, 'candidate10/classes'), { recursive: true, errorOnExist: true, force: false }); }
catch (failure) {
  if (failure.code !== 'ERR_FS_CP_EEXIST') throw failure;
  for (const original of await files(resolve('services/api/target/classes'))) {
    const copy = resolve(owned, 'candidate10/classes', relative(resolve('services/api/target/classes'), original));
    if (hash(await readFile(original)) !== hash(await readFile(copy))) throw new Error('Frozen class copy differs');
  }
}
const source = (await Promise.all(['services/api/src', 'apps/web/src', 'scripts'].map(path => files(resolve(path))))).flat();
source.push(resolve('services/api/pom.xml'), resolve('apps/web/package.json'));
const hashes = async list => Promise.all(list.map(async path => ({ path: relative(repo, path).replaceAll('\\', '/'), sha256: hash(await readFile(path)) })));
const manifest = { version: 'candidate10', baseHead: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  sourceFiles: await hashes(source), classFiles: await hashes(await files(resolve(owned, 'candidate10/classes'))),
  policyFiles: await hashes(['AGENTS.md', 'docs/14-提示词增强与Plan最小干预优化方案.md', 'docs/research/OpenAI提示工程全文学习与输出长度策略.md'].map(path => resolve(path))),
  casesSha256: hash(caseText), protocolSha256: hash(JSON.stringify(protocol, null, 2)), frozenAt: new Date().toISOString() };
await save(resolve(publicRoot, 'candidate10-source.json'), manifest);
const previous = JSON.parse(await readFile('tmp/me00a-candidate8-20261014/final-verified-tests.json', 'utf8'));
const names = previous.suites.map(item => item.name).concat(['CandidateNineAssemblyReplayTest', 'CandidateNineCoverageBoundaryTest', 'CandidateTenNaturalCoverageTest']);
await mkdir(resolve(publicRoot, 'local-test-reports'), { recursive: true });
const suites = [];
for (const name of names) {
  const paths = (await files(resolve('services/api/target/surefire-reports'))).filter(path => path.endsWith(`.${name}.xml`));
  if (paths.length !== 1) throw new Error('Missing affected report');
  const original = await readFile(paths[0], 'utf8');
  const header = original.match(/<testsuite\b[^>]+>/)[0];
  const n = key => Number(header.match(new RegExp(`\\b${key}="(\\d+)"`))[1]);
  if (n('failures') || n('errors') || n('skipped')) throw new Error('Affected tests not all passed');
  const archived = original.replace(/\s*<properties>[\s\S]*?<\/properties>/, '');
  await writeFile(resolve(publicRoot, 'local-test-reports', `${name}.xml`), archived, { flag: 'wx' });
  suites.push({ name, tests: n('tests'), failures: 0, errors: 0, skipped: 0, originalSha256: hash(original), archivedSha256: hash(archived) });
}
await save(resolve(publicRoot, 'local-test-summary.json'), { suites, tests: suites.reduce((n, item) => n + item.tests, 0),
  compositeAffectedRun: false, fullNativeRun: { tests: 690, failures: 0, reason: 'all affected tests passed at candidate10' },
  targetedFinalRun: 'SharedPlanBoundaryReplayTest,CandidateNineAssemblyReplayTest,CandidateNineCoverageBoundaryTest',
  finalAffectedReportsAllPassed: true, skipsAreNotPasses: true });
for (const name of ['consolidation-result.json', 'consolidation-verification.json'])
  await cp(resolve('tmp/local-acceptance', name), resolve(publicRoot, name), { errorOnExist: true, force: false });
await cp(resolve(owned, 'scripts-final.tap'), resolve(publicRoot, 'scripts-final.tap'), { errorOnExist: true, force: false });
console.log(JSON.stringify({ tests: suites.reduce((n, item) => n + item.tests, 0), sourceFiles: manifest.sourceFiles.length,
  runtimeFiles: manifest.classFiles.length, newModelCalls: 0, batchMaximum: 4 }));
