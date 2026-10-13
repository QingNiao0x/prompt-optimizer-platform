// Complete the two independent comparators after the saved Plan failure; do not retry or replace Plan.
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { rawExecutionInput, enhancedExecutionInput, answerLog, batchRequestLimit } from '../../scripts/me00a-eval.mjs';
const root = resolve('tmp/me00a-20261012');
const dir = resolve(root, 'candidate7-plan-r1/Q05-flash');
const protocol = JSON.parse(await readFile(resolve(root, 'protocol.json'), 'utf8'));
const sample = JSON.parse(await readFile(resolve(root, 'cases.json'), 'utf8')).find(x => x.id === 'Q05');
const answers = JSON.parse(await readFile(resolve(dir, 'actual-answers.json'), 'utf8')).answers;
const requestLimit = batchRequestLimit(protocol, 'candidate7', 'plan', [sample], ['deepseek-flash'], '1');
const require = createRequire(new URL('../../apps/web/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
const page = browser.contexts()[0].pages().find(x => x.url().startsWith('http://127.0.0.1:9001/'));
if (!page) throw new Error('Same-account authenticated browser required');
const account = JSON.parse(await readFile(resolve('tmp/enhancement-quality-20261012/account.json'), 'utf8'));
const save = (path, x) => writeFile(path, typeof x === 'string' ? x : JSON.stringify(x, null, 2), { flag: 'wx' });
const api = (path, body) => page.evaluate(async ({ path, body }) => {
  const csrf = document.cookie.split('; ').find(x => x.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
  const r = await fetch(path, { method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(240000) });
  return { status: r.status, body: await r.json() };
}, { path, body });
async function execute(prompt, label) {
  let phaseCalls = 0;
  for (let n = 1; n <= protocol.maximumActualModelCalls; n++) {
    try { if (JSON.parse(await readFile(resolve(root, 'traces', `${String(n).padStart(3, '0')}.json`), 'utf8')).phase === 'candidateValidation') phaseCalls++; }
    catch { /* Reserved attempts remain occupied even if incomplete. */ }
  }
  if (phaseCalls >= protocol.phaseLimits.candidateValidation) throw new Error('Phase budget exhausted');
  let trace;
  for (let n = 1; n <= requestLimit; n++) {
    const p = resolve(root, 'traces', `${String(n).padStart(3, '0')}.json`);
    try { await save(p, { phase: 'candidateValidation', label, startedAt: new Date().toISOString() }); trace = p; break; }
    catch (e) { if (e.code !== 'EEXIST') throw e; }
  }
  if (!trace) throw new Error('Batch budget exhausted');
  const request = { model: 'deepseek-flash', messages: [{ role: 'user', content: prompt }], ...protocol.executionParameters, stream: false };
  const started = performance.now();
  try {
    const r = await fetch('https://api.deepseek.com/chat/completions', { method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.MODEL_DEEPSEEK_API_KEY || process.env.MODEL_API_KEY}` },
      body: JSON.stringify(request), signal: AbortSignal.timeout(240000) });
    const response = await r.json();
    for (const c of response.choices ?? []) delete c.message?.reasoning_content;
    await writeFile(trace, JSON.stringify({ phase: 'candidateValidation', label, request, response, httpStatus: r.status,
      elapsedMs: Math.round(performance.now() - started) }, null, 2));
    if (!r.ok) throw new Error(`Downstream HTTP ${r.status}`);
    return { text: response.choices?.[0]?.message?.content ?? '', finishReason: response.choices?.[0]?.finish_reason, trace };
  } catch (e) {
    await save(resolve(dir, `${label}-execution-failure.json`), { trace, message: e.message }); throw e;
  }
}
const me = await api('/api/v1/auth/me');
if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId)
  throw new Error('Account mismatch; no account creation');
const raw = rawExecutionInput(sample, answers);
await save(resolve(dir, 'raw-input.md'), raw);
const rawWork = await execute(raw, 'candidate7-plan-r1-Q05-flash-raw-equal-information');
await save(resolve(dir, 'raw-work.md'), rawWork.text); await save(resolve(dir, 'raw-execution.json'), rawWork);
const direct = resolve(dir, 'direct-equal-information'); await mkdir(direct, { recursive: true });
const rawPrompt = sample.rawPrompt + `\n\n本次测试实际确认信息（逐字题答）：\n${answerLog(answers)}`;
const request = { rawPrompt, context: { customDescription: sample.context.description, files: sample.context.files },
  enhancement: sample.enhancement, conversationHistory: [], modelId: 'deepseek:deepseek-flash' };
await save(resolve(direct, 'input.json'), request);
const platform = await api('/api/v1/optimizations', request); await save(resolve(direct, 'platform.json'), platform);
if (platform.status !== 200) {
  await save(resolve(direct, 'failure.json'), { message: `Enhancement HTTP ${platform.status}`, originalPlanFailurePreserved: true });
  throw new Error(`Direct enhancement failed HTTP ${platform.status}`);
}
const copied = platform.body.data.optimizedPrompt; await save(resolve(direct, 'copied.md'), copied);
const list = await api('/api/v1/optimization-history?current=1&size=10');
let matched = false;
for (const row of list.body.data.records) {
  const history = await api(`/api/v1/optimization-history/${row.id}`);
  if (history.body.data.rawPrompt === rawPrompt && history.body.data.optimizedPrompt === copied) {
    await save(resolve(direct, 'history.json'), history);
    await save(resolve(direct, 'history-check.json'), { status: history.status, copiedBodyMatches: true }); matched = true; break;
  }
}
if (!matched) throw new Error('Actual direct copied body absent from same-account history');
const execution = enhancedExecutionInput(copied, sample); await save(resolve(direct, 'enhanced-input.md'), execution);
const directWork = await execute(execution, 'candidate7-plan-r1-Q05-flash-direct-equal-information');
await save(resolve(direct, 'work.md'), directWork.text); await save(resolve(direct, 'execution.json'), directWork);
await save(resolve(dir, 'comparator-completion.json'), { plan: 'FAILED_RESULT_INVALID_NO_COPY_OR_WORK',
  raw: 'COMPLETE', direct: 'COMPLETE', originalPlanFailurePreserved: true, extraPlanRetries: 0,
  scope: 'same candidate7 and actual answers; remaining authorized comparators only; no new round' });
process.stdout.write('Independent raw/direct comparators complete; Plan failure preserved\n');
