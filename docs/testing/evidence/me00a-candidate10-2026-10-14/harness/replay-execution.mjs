import { createRequire } from 'node:module';
import { readFile, writeFile, readdir, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { capturedExecutionResponse, completedExecutionWork } from '../../../../../scripts/me00a-eval.mjs';
import { checkResearchLength } from '../../../../../scripts/me00a-work-checks.mjs';
const root = resolve('tmp/me00a-20261012'), source = resolve(root, 'candidate9-plan-r1/Q05-flash');
const out = resolve(root, 'candidate10-replay'); await mkdir(out, { recursive: true });
const protocol = JSON.parse(await readFile(resolve(root, 'protocol.json'), 'utf8')), batch = protocol.activeBatch;
if (batch.version !== 'candidate10' || batch.authorizationStatus !== 'APPROVED_BY_USER' || batch.maximumNewCalls !== 4) throw new Error('Frozen replay batch required');
const save = (path, value) => writeFile(path, typeof value === 'string' ? value : JSON.stringify(value, null, 2), { flag: 'wx' });
const hash = value => createHash('sha256').update(value).digest('hex');
const account = JSON.parse(await readFile(resolve('tmp/local-acceptance/account.json'), 'utf8'));
const require = createRequire(resolve('apps/web/package.json'));
const browser = await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
const page = browser.contexts()[0].pages().find(page => page.url().startsWith('http://127.0.0.1:9001/'));
if (!page) throw new Error('Existing fixture browser required');
async function api(path, body) {
  return page.evaluate(async ({ path, body }) => {
    const csrf = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
    const response = await fetch(path, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json',
      ...(csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: response.status, body: await response.json() };
  }, { path, body });
}
const me = await api('/api/v1/auth/me');
if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId) throw new Error('Same account mismatch');
await save(resolve(out, 'identity-check.json'), { sameAccount: true, newAccountsCreated: 0, userHash: hash(account.userId) });
for (const arm of ['plan', 'direct']) {
  const dir = resolve(out, arm); await mkdir(dir);
  const request = JSON.parse(await readFile(resolve(source, arm === 'plan' ? 'input.json' : 'direct-equal-information/input.json'), 'utf8'));
  await save(resolve(dir, 'input.json'), request);
  const result = await api('/api/v1/optimizations', request); await save(resolve(dir, 'platform.json'), result);
  if (result.status !== 200) throw new Error(`Replay enhancement HTTP ${result.status}; no replacement Plan or account created`);
  const copied = result.body.data.optimizedPrompt; await save(resolve(dir, 'copied.md'), copied);
  const history = await api('/api/v1/optimization-history?current=1&size=10');
  let matched = false;
  for (const row of history.body.data.records) {
    const detail = await api(`/api/v1/optimization-history/${row.id}`);
    if (detail.body.data.rawPrompt === request.rawPrompt && detail.body.data.optimizedPrompt === copied) {
      await save(resolve(dir, 'history.json'), detail); matched = true; break;
    }
  }
  if (!matched) throw new Error('Copied/history mismatch');
  await save(resolve(dir, 'execution-input.md'), copied);
  const traces = await readdir(resolve(root, 'traces'));
  const previous = await Promise.all(traces.filter(name => /^\d+\.json$/.test(name)).map(async name => JSON.parse(await readFile(resolve(root, 'traces', name), 'utf8'))));
  if (previous.filter(item => item.phase === 'candidateValidation').length >= protocol.phaseLimits.candidateValidation
      || previous.filter(item => item.version === 'candidate10' && item.purpose === 'EXECUTION').length >= 2) throw new Error('Replay execution budget exhausted');
  let trace;
  for (let number = 1; number <= batch.maximumActualCallsAfterBatch; number++) {
    const path = resolve(root, 'traces', `${String(number).padStart(3, '0')}.json`);
    try { await save(path, { version: 'candidate10', purpose: 'EXECUTION', phase: 'candidateValidation' }); trace = path; break; }
    catch (failure) { if (failure.code !== 'EEXIST') throw failure; }
  }
  if (!trace) throw new Error('Actual replay budget exhausted');
  const input = { model: 'deepseek-flash', messages: [{ role: 'user', content: copied }], ...protocol.executionParameters, stream: false };
  const record = { version: 'candidate10', purpose: 'EXECUTION', phase: 'candidateValidation', arm, request: input, startedAt: new Date().toISOString() };
  const started = performance.now();
  try {
    const response = await fetch('https://api.deepseek.com/chat/completions', { method: 'POST', headers: { 'Content-Type': 'application/json',
      Authorization: `Bearer ${process.env.MODEL_DEEPSEEK_API_KEY || process.env.MODEL_API_KEY}` }, body: JSON.stringify(input), signal: AbortSignal.timeout(240000) });
    Object.assign(record, await capturedExecutionResponse(response)); record.elapsedMs = Math.round(performance.now() - started);
    for (const choice of record.response?.choices ?? []) delete choice.message?.reasoning_content;
    await writeFile(trace, JSON.stringify(record, null, 2));
    const work = completedExecutionWork(record).text; await save(resolve(dir, 'work.md'), work);
    await save(resolve(dir, 'checks.json'), { copiedEqualsHistory: true, executionInputSha256: hash(copied),
      noScriptAnswerRepair: true, length: checkResearchLength(work),
      repeatedIndependentSourceLines: ['- 我没有额外真实数据', '- 请根据原始研究目标提出可开展的推荐设计并说明适用条件'].filter(text => copied.includes(text)) });
  } catch (failure) { record.failure = failure.message; await writeFile(trace, JSON.stringify(record, null, 2)); throw failure; }
  console.log(`DONE candidate10 ${arm}`);
}
