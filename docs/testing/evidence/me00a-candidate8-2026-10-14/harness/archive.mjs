// 归档本轮合成输入、原始响应和作品；不复制账号、浏览器配置、密钥或认证 cookie。
import { readFile, writeFile, mkdir, readdir, copyFile } from 'node:fs/promises';
import { resolve, dirname, relative } from 'node:path';
import { createHash } from 'node:crypto';
const repo = resolve('.');
const root = resolve('tmp/me00a-20261012');
const out = resolve('docs/testing/evidence/me00a-candidate8-2026-10-14');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const copied = [];
async function archive(source, name) {
  const bytes = await readFile(source);
  if (!name.endsWith('.png') && /sk-[A-Za-z0-9_-]{20,}|Bearer\s+[A-Za-z0-9._-]{20,}/.test(bytes.toString('utf8')))
    throw new Error(`Potential credential: ${name}`);
  const destination = resolve(out, name);
  if (!destination.startsWith(out + '\\') && !destination.startsWith(out + '/')) throw new Error('Archive path outside node');
  await mkdir(dirname(destination), { recursive: true });
  await writeFile(destination, bytes, { flag: 'wx' });
  if (digest(await readFile(destination)) !== digest(bytes)) throw new Error('Archive byte mismatch');
  copied.push({ path: name, originalPath: relative(repo, source).replaceAll('\\', '/'), sha256: digest(bytes) });
}
async function tree(source, prefix) {
  for (const entry of await readdir(source, { withFileTypes: true })) {
    const p = resolve(source, entry.name); const name = prefix + '/' + entry.name;
    if (entry.isDirectory()) await tree(p, name); else if (entry.isFile()) await archive(p, name);
  }
}
await tree(resolve(root, 'candidate8-plan-r1/Q05-flash'), 'q05-flash');
await tree(resolve(root, 'candidate8-compliance-r1'), 'compliance');
await tree(resolve(root, 'candidate8-review'), 'review');
for (const name of ['checks.json', 'plan-clipboard.md', 'direct-clipboard.md', 'plan-copy.png', 'direct-copy.png',
  'api-statuses.json', 'plan-rendered-buttons.json', 'direct-rendered-buttons.json'])
  await archive(resolve(root, 'candidate8-ui-copy', name), 'ui-copy/' + name);
for (const name of ['me00a-eval.mjs', 'me00a-eval.test.mjs'])
  await archive(resolve('scripts', name), 'harness/scripts/' + name);
const records = [];
for (let n = 186; n <= 203; n++) {
  const file = `${n}.json`;
  await archive(resolve(root, 'traces', file), 'traces/' + file);
  const record = JSON.parse(await readFile(resolve(root, 'traces', file), 'utf8'));
  if (record.phase !== 'candidateValidation' || record.request.model !== 'deepseek-flash') throw new Error('Unauthorized recorded call');
  records.push({ trace: n, label: record.label ?? record.purpose ?? 'ENHANCEMENT_OR_PLAN',
    httpStatus: record.httpStatus, elapsedMs: record.elapsedMs, requestModel: record.request.model,
    returnedModel: record.response?.model, parameters: { temperature: record.request.temperature,
      max_tokens: record.request.max_tokens, thinking: record.request.thinking, stream: record.request.stream },
    usage: record.response?.usage, failure: record.failure ?? null });
}
const totals = records.reduce((sum, record) => {
  sum.elapsedMs += record.elapsedMs;
  for (const key of ['prompt_tokens', 'completion_tokens', 'total_tokens']) sum[key] += record.usage?.[key] ?? 0;
  return sum;
}, { elapsedMs: 0, prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 });
await writeFile(resolve(out, 'call-summary.json'), JSON.stringify({
  before: { total: 185, baseline: 70, candidateValidation: 115 },
  after: { total: 203, baseline: 70, candidateValidation: 133 }, newAttempts: 18,
  q05Attempts: 8, exampleAttempts: 10, failedHTTPAttempts: records.filter(x => x.httpStatus !== 200).length,
  workflowEnhancementOrPlanningRetries: 0, independentAIReviewAttempts: 2, invalidReviewJSON: 0,
  groupLimits: { q05: 14, examples: 20 }, groupUnusedNotTransferable: { q05: 6, examples: 10 },
  formalLimits: { total: 229, baseline: 80, candidateValidation: 149 },
  actualAuthorizedBatchCeilingFrom185: 219, activeBatchClosed: true, newAccountsCreated: 0,
  exactVendorVersion: 'UNKNOWN', currencyCost: 'UNKNOWN; no verified invoice',
  totals, elapsedScope: 'Sum of model HTTP durations only; excludes preparation, UI, local checks and offline analysis',
  qualityPassNotInferredFromHTTP: true, records
}, null, 2) + '\n', { flag: 'wx' });
await writeFile(resolve(out, 'archive-source-hashes.json'), JSON.stringify({ copied, credentialsExcluded: true,
  originalModelContentPreserved: true, noOriginalEvidenceOverwritten: true }, null, 2) + '\n', { flag: 'wx' });
console.log(`Archived ${copied.length} byte-verified artifacts; 18 actual attempts, cumulative203`);
