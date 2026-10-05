/**
 * 检查合成租赁题中未绑定版本的维修内容是否被填入明确A/B版本列。
 * 只检查可证明的表格归属，不声称覆盖任意措辞、专业法律正确性或所有叙述冲突。
 */
import { readFile, writeFile, realpath } from 'node:fs/promises';
import { resolve, relative, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { safeMaterialPath, sha256 } from './prompt-output-evaluation.mjs';

const root = resolve(import.meta.dirname, '..');
const plain = text => text.normalize('NFKC').replace(/[*`]/g, '').replace(/\s+/g, ' ').trim();
// “与草稿A/B的对应关系”和“对应哪份草稿？”是关系或问题，不能当作明确版本标签。
const version = text => {
  const label = plain(text);
  return /^(?:租赁)?(?:草稿|版本)\s*[AB](?:$|[ (：:-]|表述|记载|内容)/i.test(label)
    && (label.match(/(?:草稿|版本)\s*[AB]\b/gi) ?? []).length === 1
    && !/对应关系|是否|如何|还是|未知|待核|未绑定/.test(label);
};
const value = text => /日常维护|全部设施故障/.test(plain(text));
const unknown = text => /版本对应待核实|对应关系.{0,8}(?:未知|未建立|待核)|版本归属.{0,8}(?:未知|未建立|待核)|未(?:绑定|指定|对应)|对应未知/.test(plain(text));
const cells = line => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(plain);

/** 只判定具体版本格中的已知内容；通用待核尾注不能抵消已建立的列归属。 */
export function unboundMaintenanceTableClaims(output) {
  let headers = null;
  const violations = [];
  for (const [index, line] of output.split(/\r?\n/).entries()) {
    if (!line.trim().startsWith('|')) { headers = null; continue; }
    const row = cells(line);
    if (row.every(cell => /^:?-+:?$/.test(cell))) continue;
    if (!headers) { headers = row; continue; }
    if (!row.some(value)) continue;
    // 横向版本表：只看具体版本列；一版/另一版匿名列和已证明的押金属性不被误判。
    for (let column = 0; column < Math.min(headers.length, row.length); column++) {
      if (version(headers[column]) && value(row[column]) && !unknown(row[column])) {
        violations.push({ line: index + 1, kind: 'NAMED_VERSION_COLUMN', version: headers[column],
          claim: row[column], quote: line });
      }
    }
    // 纵向版本表：行中给出明确A/B，与同一行维修取值共同构成归属断言。
    if (!headers.some(version) && row.some(cell => version(cell) && !unknown(cell)) && row.some(cell => value(cell) && !unknown(cell))) {
      violations.push({ line: index + 1, kind: 'NAMED_VERSION_ROW', version: row.find(version),
        claim: row.filter(value).join('；'), quote: line });
    }
  }
  return violations;
}

/** 固定合成题需要有一版/另一版原始材料；不存在该前提时拒绝套用此诊断。 */
export function hasUnboundMaintenanceFixture(item) {
  return item.id === 'CD-10-L' && item.files.some(file => file.path === 'materials/legal_memo/current-brief.md'
    && /维修条款一版写承租方承担日常维护，另一版新增全部设施故障费用/.test(file.content));
}

async function owned(path) {
  const actual = await realpath(resolve(path));
  const name = relative(root, actual); safeMaterialPath(name);
  if (!/^tmp[/\\]/.test(name)) throw new Error('WORKSPACE_TMP_REQUIRED');
  return actual;
}

async function main(args) {
  const option = name => { const i = args.indexOf(name); if (i < 0 || !args[i + 1]) throw new Error('MISSING_ARGUMENT'); return args[i + 1]; };
  const run = await owned(option('--run'));
  const manifest = JSON.parse(await readFile(join(run, 'manifest.json'), 'utf8'));
  const text = await readFile(join(run, 'jobs.json'), 'utf8');
  if (sha256(text) !== manifest.jobsSha256) throw new Error('FROZEN_JOBS_CHANGED');
  const batch = JSON.parse(text);
  const fixture = batch.cases.find(item => item.id === 'CD-10-L');
  if (!fixture || !hasUnboundMaintenanceFixture(fixture)) throw new Error('UNBOUND_SYNTHETIC_FIXTURE_REQUIRED');
  const entries = [];
  for (const job of batch.jobs.filter(item => item.caseId === 'CD-10-L')) {
    const result = JSON.parse(await readFile(join(run, 'execution', job.id + '.json'), 'utf8'));
    if (result.status !== 'SUCCESS') { entries.push({ jobId: job.id, status: result.status }); continue; }
    if (sha256(result.output) !== result.outputSha256 || result.executionUserSha256 !== job.userHash) throw new Error('ACTUAL_WORK_CHANGED');
    const violations = unboundMaintenanceTableClaims(result.output);
    entries.push({ jobId: job.id, arm: job.arm, modelId: job.modelId, outputSha256: result.outputSha256,
      status: violations.length ? 'SOURCE_OBJECT_FAILED' : 'NO_DEFINITE_TABLE_BINDING_FOUND', violations });
  }
  const output = resolve(option('--output')); safeMaterialPath(relative(root, output));
  if (await owned(resolve(output, '..')) !== resolve(output, '..')) throw new Error('OUTPUT_LINK_ESCAPE');
  await writeFile(output, JSON.stringify({ schemaVersion: 1, sourceRun: relative(root, run), checkedAt: new Date().toISOString(),
    mechanism: 'SYNTHETIC_UNBOUND_MAINTENANCE_NAMED_VERSION_TABLE_ONLY',
    scope: 'No definite table binding found is not full legal or semantic acceptance.', entries }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ output, checkedWorks: entries.length, failed: entries.filter(e => e.status === 'SOURCE_OBJECT_FAILED').length }));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error('source-check-stopped=' + (/^[A-Z_]+$/.test(error.message) ? error.message : 'CHECK_INPUT_EVIDENCE')); process.exitCode = 2; });
}
