// Opt-in real-model comparison for the seven frozen synthetic cases. Never creates an account.
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

/** Preserve the original user input; attachments are the same evidence in both execution arms. */
export function rawExecutionInput(sample, answers = []) {
  return enhancedExecutionInput(sample.rawPrompt, sample)
    + (answers.length ? `\n\n本次测试实际确认信息（逐字题答）：\n${answerLog(answers)}` : '');
}

/** Execute the actual copied body without repairing omitted decisions; append only equal source evidence. */
export function enhancedExecutionInput(copied, sample) {
  const materials = sample.context.files.map(file => `资料 ${file.path}：\n${file.content}`).join('\n\n');
  return copied + (materials ? `\n\n本次资料：\n${materials}` : '');
}

/** Use only answers actually submitted for the server's questions, never unselected options. */
export function answerLog(answers) {
  return answers.map(answer => `问题：${answer.question}\n回答：${answer.answer}`).join('\n');
}

/** An approved batch limits both scope and attempts; unused phase allowance cannot widen it. */
export function batchRequestLimit(protocol, version, mode, cases, models, round = '1') {
  const batch = protocol.activeBatch;
  if (!batch) return protocol.maximumActualModelCalls;
  if (version !== batch.version || (batch.secondRoundAuthorized === false && round !== '1')
      || mode !== batch.mode || cases.some(sample => !batch.cases.includes(sample.id))
      || models.some(model => model !== batch.model)) throw new Error('Input outside approved batch scope');
  return Math.min(protocol.maximumActualModelCalls, batch.callsBefore + batch.maximumNewCalls);
}

const directRun = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (directRun) { try { await run(); process.exit(0); } catch (error) { console.error(error.message); process.exit(1); } }

async function run() {
  const [version = 'baseline', round = '1', mode = 'direct', selected = 'all', selectedModels = 'all'] = process.argv.slice(2);
  if (!/^(baseline|candidate[1234567])$/.test(version) || !/^[12]$/.test(round)
    || !['direct', 'plan'].includes(mode)) throw new Error('Invalid evaluation phase');
  const root = resolve('tmp/me00a-20261012');
  const phase = version === 'baseline' ? 'baseline' : 'candidateValidation';
  const protocol = JSON.parse(await readFile(resolve(root, 'protocol.json'), 'utf8'));
  const models = protocol.models.filter(model => selectedModels === 'all' || selectedModels.split(',').includes(model));
  if (!models.length) throw new Error('No approved model selected');
  const cases = JSON.parse(await readFile(resolve(root, 'cases.json'), 'utf8'))
    .filter(sample => selected === 'all' || selected.split(',').includes(sample.id));
  const requestLimit = batchRequestLimit(protocol, version, mode, cases, models, round);
  const account = JSON.parse(await readFile(resolve('tmp/enhancement-quality-20261012/account.json'), 'utf8'));
  const require = createRequire(new URL('../apps/web/package.json', import.meta.url));
  const { chromium } = require('@playwright/test');
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
  const context = browser.contexts()[0];
  const page = context.pages().find(item => item.url().startsWith('http://127.0.0.1:9001/'));
  if (!page) throw new Error('Same-account authenticated local browser required');
  const save = (path, value) => writeFile(path, typeof value === 'string' ? value : JSON.stringify(value, null, 2), { flag: 'wx' });
  async function api(path, body) {
    return page.evaluate(async ({ path, body }) => {
      const csrf = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
      const started = performance.now();
      const response = await fetch(path, { method: body === undefined ? 'GET' : 'POST',
        headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(240000) });
      if (!response.headers.get('content-type')?.includes('application/json')) {
        return { status: response.status, elapsedMs: Math.round(performance.now() - started),
          body: { error: { code: 'NON_JSON_LOCAL_RESPONSE' } } };
      }
      return { status: response.status, elapsedMs: Math.round(performance.now() - started), body: await response.json() };
    }, { path, body });
  }
  async function execute(prompt, model, label) {
    await mkdir(resolve(root, 'traces'), { recursive: true });
    let count = 0;
    for (let index = 1; index <= protocol.maximumActualModelCalls; index++) {
      try { const existing = JSON.parse(await readFile(resolve(root, 'traces', `${String(index).padStart(3, '0')}.json`), 'utf8'));
        if (existing.phase === phase) count++; } catch { /* Empty reserved requests still consume the global budget. */ }
    }
    if (count >= protocol.phaseLimits[phase]) throw new Error('Phase request budget exhausted');
    let trace;
    for (let index = 1; index <= requestLimit; index++) {
      const path = resolve(root, 'traces', `${String(index).padStart(3, '0')}.json`);
      try { await save(path, { phase, label, startedAt: new Date().toISOString() }); trace = path; break; }
      catch (error) { if (error.code !== 'EEXIST') throw error; }
    }
    if (!trace) throw new Error('Global actual-request budget exhausted');
    const request = { model, messages: [{ role: 'user', content: prompt }], ...protocol.executionParameters, stream: false };
    const started = performance.now();
    try {
      const response = await fetch('https://api.deepseek.com/chat/completions', { method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.MODEL_DEEPSEEK_API_KEY || process.env.MODEL_API_KEY}` },
        body: JSON.stringify(request), signal: AbortSignal.timeout(240000) });
      const output = await response.json();
      for (const choice of output.choices ?? []) delete choice.message?.reasoning_content;
      await writeFile(trace, JSON.stringify({ phase, label, httpStatus: response.status,
        elapsedMs: Math.round(performance.now() - started), request, response: output }, null, 2));
      if (!response.ok) throw new Error(`Execution HTTP ${response.status}`);
      return { text: output.choices?.[0]?.message?.content ?? '', finishReason: output.choices?.[0]?.finish_reason, trace };
    } catch (error) { await writeFile(trace, JSON.stringify({ phase, label, request, failure: error.message })); throw error; }
  }
  async function enhanced(sample, modelId, dir, raw = sample.rawPrompt, confirmation) {
    const request = { rawPrompt: raw, context: { customDescription: sample.context.description, files: sample.context.files },
      enhancement: sample.enhancement, conversationHistory: [], modelId, ...(confirmation ? { planConfirmation: confirmation } : {}) };
    await save(resolve(dir, 'input.json'), request);
    const result = await api('/api/v1/optimizations', request);
    await save(resolve(dir, 'platform.json'), result);
    if (result.status !== 200) throw new Error(`Enhancement HTTP ${result.status} ${result.body.error?.code ?? ''}`);
    const copied = result.body.data.optimizedPrompt;
    await save(resolve(dir, 'copied.md'), copied);
    const history = await api('/api/v1/optimization-history?current=1&size=10');
    let matchedHistory = false;
    for (const row of history.body.data?.records ?? []) {
      const detail = await api(`/api/v1/optimization-history/${row.id}`);
      if (detail.body.data?.rawPrompt === raw && detail.body.data?.optimizedPrompt === copied) { await save(resolve(dir, 'history-check.json'), {
        status: detail.status, copiedBodyMatches: true, promptFormat: detail.body.data.metadata?.promptFormat ?? detail.body.data.promptFormat });
        await save(resolve(dir, 'history.json'), detail);
        matchedHistory = true; break; }
    }
    if (!matchedHistory) throw new Error('Actual copied body was not found in same-account history');
    return copied;
  }
  try {
    if (!process.env.MODEL_DEEPSEEK_API_KEY && !process.env.MODEL_API_KEY) throw new Error('Configured server key unavailable');
    const me = await api('/api/v1/auth/me');
    if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId)
      throw new Error('Fixture account identity mismatch; no new account permitted');
    await api('/api/v1/auth/csrf');
    const out = resolve(root, `${version}-${mode}-r${round}`); await mkdir(out, { recursive: true });
    try { await save(resolve(out, 'identity-check.json'), { sameAccount: true, newAccountsCreated: 0,
      userHash: createHash('sha256').update(account.userId).digest('hex') }); } catch (error) { if (error.code !== 'EEXIST') throw error; }
    let caseFailures = 0;
    for (const sample of cases) for (const model of models) {
      const label = `${sample.id}-${model.endsWith('pro') ? 'pro' : 'flash'}`;
      const dir = resolve(out, label); await mkdir(dir); console.log(`BEGIN ${version} ${mode} ${label} round=${round}`);
      try {
        const modelId = `deepseek:${model}`;
        if (mode === 'direct') {
          const raw = rawExecutionInput(sample); await save(resolve(dir, 'raw-input.md'), raw);
          const rawWork = await execute(raw, model, `${version}-${mode}-r${round}-${label}-raw`);
          await save(resolve(dir, 'raw-work.md'), rawWork.text); await save(resolve(dir, 'raw-execution.json'), rawWork);
          const copied = await enhanced(sample, modelId, dir);
          // Only the platform's actual copied body is executed; same safe source evidence remains available.
          const withEvidence = enhancedExecutionInput(copied, sample);
          await save(resolve(dir, 'enhanced-input.md'), withEvidence);
          const work = await execute(withEvidence, model, `${version}-${mode}-r${round}-${label}-enhanced`);
          await save(resolve(dir, 'work.md'), work.text); await save(resolve(dir, 'execution.json'), work);
        } else {
          const prepared = await api('/api/v1/context/planning', { rawPrompt: sample.rawPrompt,
            context: { customDescription: sample.context.description, files: sample.context.files }, enhancement: sample.enhancement,
            conversationHistory: [], modelId }); await save(resolve(dir, 'context-prepared.json'), prepared);
          if (prepared.status !== 200) throw new Error(`Plan context HTTP ${prepared.status}`);
          const plan = await api('/api/v1/optimizations/plan', { rawPrompt: sample.rawPrompt, planningContext: { contextId: prepared.body.data.contextId, version: prepared.body.data.version }, modelId });
          await save(resolve(dir, 'plan.json'), plan); if (plan.status !== 200) throw new Error(`Plan HTTP ${plan.status}`);
          const questions = plan.body.data.questions ?? [];
          // Synthetic test persona delegates design; it does not fabricate observations, approvals or chosen parameters.
          const answers = questions.map(q => ({ questionId: q.id, question: q.question,
            answer: '这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。' }));
          await save(resolve(dir, 'actual-answers.json'), { kind: 'SCRIPTED_SYNTHETIC_TEST_PERSONA', answers });
          const confirmation = { planId: plan.body.data.planId, planningContext: { contextId: prepared.body.data.contextId, version: prepared.body.data.version }, answers };
          const copied = await enhanced(sample, modelId, dir, sample.rawPrompt, confirmation);
          const planInput = enhancedExecutionInput(copied, sample);
          await save(resolve(dir, 'enhanced-input.md'), planInput);
          const planWork = await execute(planInput, model, `${version}-${mode}-r${round}-${label}-plan`);
          await save(resolve(dir, 'work.md'), planWork.text); await save(resolve(dir, 'execution.json'), planWork);
          const raw = rawExecutionInput(sample, answers); await save(resolve(dir, 'raw-input.md'), raw);
          const rawWork = await execute(raw, model, `${version}-${mode}-r${round}-${label}-raw-equal-information`);
          await save(resolve(dir, 'raw-work.md'), rawWork.text); await save(resolve(dir, 'raw-execution.json'), rawWork);
          const directDir = resolve(dir, 'direct-equal-information'); await mkdir(directDir);
          const directRaw = sample.rawPrompt + (answers.length ? `\n\n本次测试实际确认信息（逐字题答）：\n${answerLog(answers)}` : '');
          const directCopy = await enhanced(sample, modelId, directDir, directRaw);
          // The direct comparator receives the same source files as R+ and P+; copied decisions remain untouched.
          const directInput = enhancedExecutionInput(directCopy, sample);
          await save(resolve(directDir, 'enhanced-input.md'), directInput);
          const directWork = await execute(directInput, model, `${version}-${mode}-r${round}-${label}-direct-equal-information`);
          await save(resolve(directDir, 'work.md'), directWork.text); await save(resolve(directDir, 'execution.json'), directWork);
        }
        console.log(`DONE ${version} ${mode} ${label}`);
      } catch (error) { caseFailures++; await save(resolve(dir, 'failure.json'), { message: error.message }); console.log(`FAILED ${label} ${error.message}`);
        if (/budget/i.test(error.message)) throw error; }
    }
    if (caseFailures) throw new Error(`${caseFailures} scenarios incomplete; evidence retained`);
  } finally { /* The shared authenticated CDP browser stays open for subsequent phases. */ }
}
