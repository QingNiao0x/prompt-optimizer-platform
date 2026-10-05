/** 检查固定合成新闻题的实际正文，标题不计字数，模型自报字数不作为验收证据。 */
import { readFile, writeFile, realpath } from 'node:fs/promises';
import { resolve, relative, join } from 'node:path';
import { createHash } from 'node:crypto';
import { safeMaterialPath } from './prompt-output-evaluation.mjs';
const root = resolve(import.meta.dirname, '..');
const args = process.argv.slice(2);
const option = name => args[args.indexOf(name) + 1];
if (!args.includes('--run') || !args.includes('--output')) throw new Error('RUN_AND_OUTPUT_REQUIRED');
const run = await realpath(resolve(option('--run')));
const output = resolve(option('--output'));
for (const path of [run, output]) {
  const rel = relative(root, path); safeMaterialPath(rel);
  if (!/^tmp[/\\]/.test(rel)) throw new Error('WORKSPACE_TMP_REQUIRED');
}
if (await realpath(resolve(output, '..')) !== resolve(output, '..')) throw new Error('OUTPUT_LINK_ESCAPE');
const hash = value => createHash('sha256').update(value).digest('hex');
const manifest = JSON.parse(await readFile(join(run, 'manifest.json'), 'utf8'));
const text = await readFile(join(run, 'jobs.json'), 'utf8');
if (hash(text) !== manifest.jobsSha256) throw new Error('FROZEN_JOBS_CHANGED');
const entries = [];
for (const job of JSON.parse(text).jobs.filter(item => item.caseId === 'CD-09-M')) {
  const result = JSON.parse(await readFile(join(run, 'execution', job.id + '.json'), 'utf8'));
  if (result.status !== 'SUCCESS') { entries.push({ jobId: job.id, status: result.status }); continue; }
  if (hash(result.output) !== result.outputSha256 || result.executionUserSha256 !== job.userHash) throw new Error('ACTUAL_WORK_CHANGED');
  const lines = result.output.split(/\r?\n/);
  const marker = lines.findIndex(line => /^\s*(?:#{1,6}\s*|\d+[.、]\s*)?(?:新闻稿正文|正文)\s*[：:]?\s*$/.test(line));
  let body;
  if (marker >= 0) body = lines.slice(marker + 1).join('\n').trim();
  else {
    // 本题固定要求两个可选标题；无正文标签时，标题后的首个空行才是正文分界。
    let end = 0;
    for (; end < lines.length; end++) if (end > 0 && !lines[end].trim()) break;
    const titleText = lines.slice(0, end).join('\n');
    if (!/标题[一二12]|可选标题/.test(titleText)) {
      entries.push({ jobId: job.id, arm: job.arm, modelId: job.modelId, status: 'BODY_BOUNDARY_NEEDS_REVIEW' }); continue;
    }
    body = lines.slice(end + 1).join('\n').trim();
  }
  const han = (body.match(/\p{Script=Han}/gu) ?? []).length;
  const hasMvp = /(?:本地\s*MVP|本地最小可行产品)/i.test(body);
  entries.push({ jobId: job.id, arm: job.arm, modelId: job.modelId, outputSha256: result.outputSha256,
    bodyHanCharacters: han, bodyCharactersWithoutWhitespace: body.replace(/\s/g, '').length,
    bodyWithinRange: han >= 600 && han <= 800, bodyHasMvp: hasMvp,
    status: han >= 600 && han <= 800 && hasMvp ? 'PASS' : 'FACT_OR_LENGTH_FAILED' });
}
await writeFile(output, JSON.stringify({ schemaVersion: 1, sourceRun: relative(root, run), checkedAt: new Date().toISOString(),
  mechanism: 'EXPLICIT_600_TO_800_HAN_BODY_EXCLUDING_TWO_TITLES', entries }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output, entries }));
