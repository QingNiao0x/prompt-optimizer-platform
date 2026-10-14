import { readFile, writeFile, readdir, mkdir, cp } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

// 只冻结本机离线候选，不更新调用协议、创建账号、启动服务或请求供应商。
const repo = process.cwd();
const output = resolve('docs/testing/evidence/me00a-candidate12-2026-10-14');
const owned = resolve('tmp/me00a-candidate12-20261014');
const previous = resolve('docs/testing/evidence/me00a-candidate11-2026-10-14');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const load = async path => JSON.parse(await readFile(path, 'utf8'));
const save = async (name, value) => writeFile(resolve(output, name), JSON.stringify(value, null, 2) + '\n');

/** 枚举已指定的仓库目录；不读取本机凭据、环境或数据库备份。 */
async function files(path) {
  const list = [];
  for (const item of await readdir(path, { withFileTypes: true })) {
    const full = resolve(path, item.name);
    if (item.isDirectory()) list.push(...await files(full));
    else if (item.isFile()) list.push(full);
  }
  return list.sort();
}
const hashes = paths => Promise.all(paths.map(async path => ({
  path: relative(repo, path).replaceAll('\\', '/'), sha256: digest(await readFile(path))
})));

await mkdir(output, { recursive: true });
const protocol = await load(resolve('tmp/me00a-20261012/protocol.json'));
const traces = (await readdir(resolve('tmp/me00a-20261012/traces'))).filter(name => /^\d+\.json$/.test(name));
if (traces.length !== 219 || protocol.phaseLimits.candidateValidation !== 149
    || protocol.maximumActualModelCalls !== 229 || protocol.activeBatch.authorizationStatus !== 'CLOSED_AFTER_AUTHORIZED_ONE_ROUND') {
  throw new Error('调用账目或预算已变更，先核对新依据，不能沿用本轮离线冻结');
}
const testClasses = (await readFile(resolve(owned, 'test-classes.txt'), 'utf8')).replace(/^\uFEFF/, '').trim().split(/\r?\n/);
if (testClasses.length !== 52) throw new Error('受影响检查范围必须包含上一候选49类与本轮回放、边界、历史检查');
const reports = [];
await mkdir(resolve(output, 'local-test-reports'), { recursive: true });
for (const name of testClasses) {
  const path = resolve('services/api/target/surefire-reports', `TEST-${name}.xml`);
  const raw = await readFile(path, 'utf8');
  const attributes = raw.match(/<testsuite\b([^>]+)>/)[1];
  const metric = key => Number(attributes.match(new RegExp(`\\b${key}="([^\"]+)"`))[1]);
  const counts = Object.fromEntries(['tests', 'failures', 'errors', 'skipped'].map(key => [key, metric(key)]));
  if (counts.failures || counts.errors || counts.skipped) throw new Error(`受影响检查未通过：${name}`);
  const sanitized = raw.replace(/\s*<properties>[\s\S]*?<\/properties>/, '');
  await writeFile(resolve(output, 'local-test-reports', `TEST-${name}.xml`), sanitized);
  reports.push({ name, ...counts, originalSha256: digest(raw), sanitizedSha256: digest(sanitized), propertiesRemoved: true });
}
for (const file of ['replay-red.xml', 'boundary-red.xml', 'compound-red-assembly.xml', 'compound-red-boundary.xml']) {
  const raw = await readFile(resolve(owned, file), 'utf8');
  await writeFile(resolve(output, file), raw.replace(/\s*<properties>[\s\S]*?<\/properties>/, ''));
}
await save('local-test-summary.json', {
  classes: reports.length, tests: reports.reduce((total, report) => total + report.tests, 0),
  failures: 0, errors: 0, skipped: 0, reports,
  initialReplay: { tests: 5, failures: 3, errors: 0, skipped: 0 },
  initialBoundary: { tests: 19, failures: 11, errors: 0, skipped: 0,
    caveat: '七项失败仅为分号断言过严，另四项命中平台问题；原报告保留。后续另纠正了软委托应进入硬规则列表的错误断言。' },
  compoundCoverageFailure: { assemblyFailures: 1, boundaryFailures: 1,
    cause: '中文列表分隔符未归一化，禁用假设数据误作条件，原件与报告保留' },
  isolationWait: '首次扩大检查在 Mockito 附加助手等待，仅停止已核对本轮进程，原范围本机重跑；不计模型请求'
});
await cp(resolve(owned, 'replays'), resolve(output, 'replays'), { recursive: true, force: false, errorOnExist: true });

const origins = [[9, 205], [10, 215], [10, 217], [11, 219]];
const attribution = [];
for (const [candidate, attempt] of origins) {
  const path = resolve(`docs/testing/evidence/me00a-candidate${candidate}-2026-10-14/traces/${attempt}.json`);
  const trace = await load(path);
  const content = trace.response.choices[0].message.content;
  const model = JSON.parse(content);
  const copy = await readFile(resolve(output, `replays/${attempt}/current-copy.md`), 'utf8');
  attribution.push({ attempt, sourceTrace: relative(repo, path).replaceAll('\\', '/'), sourceTraceSha256: digest(await readFile(path)),
    modelContentSha256: digest(content), currentCopySha256: digest(copy),
    appendedDelegationSourceLine: /^- 请根据原始研究目标提出可开展的推荐设计并说明适用条件/m.test(copy),
    appendedOriginalTaskIntroduction: /^- 为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。/m.test(copy),
    appendedAnswerCaption: /^- 本次测试实际确认信息（逐字题答）：/m.test(copy),
    participantHeadingInSentInput: trace.request.messages.some(message => /参与者与(?:随机化|分组)/.test(message.content)),
    participantHeadingInModelResponse: model.sections.some(section => /参与者与(?:随机化|分组)/.test(section.content)),
    participantHeadingInCurrentCopy: /参与者与(?:随机化|分组)/.test(copy),
    offlineReplayNotNewModelResponse: true, downstreamWorkGenerated: false });
}
await save('attribution-and-replay-checks.json', attribution);

const source = (await Promise.all(['services/api/src', 'apps/web/src', 'scripts'].map(path => files(resolve(path))))).flat();
source.push(resolve('services/api/pom.xml'), resolve('apps/web/package.json'));
const classRoot = resolve(owned, 'candidate12/classes');
await cp(resolve('services/api/target/classes'), classRoot, { recursive: true, force: false, errorOnExist: true });
const policy = ['AGENTS.md', 'docs/14-提示词增强与Plan最小干预优化方案.md',
  'docs/research/OpenAI提示工程全文学习与输出长度策略.md'].map(path => resolve(path));
const guidance = ['services/api/src/main/java/com/promptoptimizer/provider/domain/PromptRewriteStrategy.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/PromptOptimizationGuidance.java',
  'services/api/src/main/java/com/promptoptimizer/template/domain/TaskDeliveryProfile.java'];
const previousManifest = await load(resolve(previous, 'candidate11-source.json'));
const guidanceHashes = await hashes(guidance.map(path => resolve(path)));
if (!guidanceHashes.every(item => previousManifest.sourceFiles.some(old => old.path === item.path && old.sha256 === item.sha256))) {
  throw new Error('模型指导已变化，不能把本轮当成单纯组装隔离修复');
}
await save('budget-state.json', { total: 219, baseline: 70, candidateValidation: 149,
  limits: { total: 229, baseline: 80, candidateValidation: 149 }, newModelCalls: 0,
  remainingCandidateCalls: 0, newRealBatchStatus: 'NOT_STARTED_NUMERICAL_LIMIT_EXHAUSTED',
  accountReference: 'tmp/local-acceptance/account.json', newAccounts: 0, databaseWrites: 0,
  previousClosedBatchUnchanged: true, noPhaseBorrowing: true });
await save('candidate12-source.json', { version: 'candidate12',
  baseHead: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  sourceFiles: await hashes(source), classFiles: await hashes(await files(classRoot)),
  policyFiles: await hashes(policy), unchangedModelGuidance: guidanceHashes,
  evidenceInputFiles: await hashes(origins.map(([candidate, attempt]) =>
    resolve(`docs/testing/evidence/me00a-candidate${candidate}-2026-10-14/traces/${attempt}.json`))),
  frozenAt: new Date().toISOString(), localOnly: true, latestDownstreamWorkNotGenerated: true });
console.log(JSON.stringify({ version: 'candidate12', tests: reports.reduce((total, report) => total + report.tests, 0),
  classes: reports.length, sourceFiles: source.length, newModelCalls: 0, frozen: true }));
