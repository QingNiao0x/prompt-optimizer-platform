/** 将真实编译／正文断言与匿名评分关联；保留原分数，不让满分覆盖已验证的硬要求失败。 */
import { readFile, writeFile, realpath } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { pathToFileURL } from 'node:url';
import { safeMaterialPath, sha256 } from './prompt-output-evaluation.mjs';

const root = resolve(import.meta.dirname, '..');
const decisiveFailures = new Set(['FUNCTIONAL_FAILED', 'COMPILE_FAILED', 'OUTPUT_FORMAT_FAILED', 'FACT_OR_LENGTH_FAILED', 'SOURCE_OBJECT_FAILED']);

/** 仅同一作品哈希的确定失败可以追加拒绝理由；通过不能覆盖AI发现的问题，未评审仍未评审。 */
export function groundReviews(reviews, mapping, checks) {
  const byJob = new Map(mapping.map(item => [item.jobId, item]));
  const byBlind = new Map(mapping.map(item => [item.blindId, item]));
  const failures = new Map();
  for (const check of checks) {
    if (!decisiveFailures.has(check.status)) continue;
    const bound = byJob.get(check.jobId);
    if (!bound || bound.outputSha256 !== check.outputSha256) throw new Error('ASSERTION_WORK_BINDING_MISMATCH');
    const list = failures.get(bound.blindId) ?? [];
    list.push(check); failures.set(bound.blindId, list);
  }
  let corrected = 0;
  const entries = reviews.entries.map(entry => {
    const bound = byBlind.get(entry.blindId);
    if (!bound || bound.outputSha256 !== entry.outputSha256) throw new Error('REVIEW_WORK_BINDING_MISMATCH');
    if (entry.status !== 'REVIEWED' || !failures.has(entry.blindId)) return entry;
    const grounded = failures.get(entry.blindId);
    corrected++;
    const reasons = grounded.map(check => check.status === 'FACT_OR_LENGTH_FAILED'
      ? `确定性正文验收失败：${check.bodyHanCharacters}汉字；600–800范围=${check.bodyWithinRange}；必要MVP事实=${check.bodyHasMvp}。`
      : check.status === 'SOURCE_OBJECT_FAILED'
      ? `资料归属验收失败：未绑定版本的维修内容被填入明确版本格；${check.violations.length}处，原作品引文另存。`
      : `确定性Java验收失败：${check.status}；功能失败组合=${check.failures ?? '未编译执行'}。`);
    return { ...entry, criticalErrors: [...new Set([...entry.criticalErrors, ...reasons])],
      mechanicalGrounding: grounded, notes: (entry.notes ?? '') + ' 确定性验收优先，原AI分数和引文保留。' };
  });
  return { ...reviews, mechanicalGrounding: { correctedReviews: corrected, decisiveFailureWorks: failures.size,
    policy: 'Verified hard failures override acceptance; original AI scores are unchanged; pending reviews remain pending.' }, entries };
}

/** 只处理工作区临时合成验收文件，拒绝逃逸路径；另存结果不覆盖原评分。 */
async function owned(path) {
  const actual = await realpath(resolve(path));
  const name = relative(root, actual); safeMaterialPath(name);
  if (!/^tmp[/\\]/.test(name)) throw new Error('WORKSPACE_TMP_REQUIRED');
  return actual;
}

async function main(args) {
  const option = name => { const index = args.indexOf(name); if (index < 0 || !args[index + 1]) throw new Error('MISSING_ARGUMENT'); return args[index + 1]; };
  const run = await owned(option('--run'));
  const read = async path => JSON.parse(await readFile(await owned(path), 'utf8'));
  const manifest = await read(resolve(run, 'manifest.json'));
  const jobs = await readFile(resolve(run, 'jobs.json'), 'utf8');
  if (sha256(jobs) !== manifest.jobsSha256) throw new Error('FROZEN_JOBS_CHANGED');
  const checks = [];
  for (const optionName of ['--java-checks', '--news-checks', ...(args.includes('--source-checks') ? ['--source-checks'] : [])]) {
    const check = await read(option(optionName));
    if (check.sourceRun.replaceAll('\\', '/') !== relative(root, run).replaceAll('\\', '/')) throw new Error('ASSERTION_RUN_MISMATCH');
    checks.push(...check.entries);
  }
  const mapping = await read(resolve(run, 'blind-mapping.json'));
  const reviews = await read(option('--reviews'));
  const grounded = groundReviews(reviews, mapping, checks);
  const output = resolve(option('--output'));
  safeMaterialPath(relative(root, output));
  if (await owned(resolve(output, '..')) !== resolve(output, '..')) throw new Error('OUTPUT_LINK_ESCAPE');
  await writeFile(output, JSON.stringify(grounded, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ output, ...grounded.mechanicalGrounding }));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error('grounding-stopped=' + (/^[A-Z_]+$/.test(error.message) ? error.message : 'CHECK_INPUT_EVIDENCE')); process.exitCode = 2; });
}
