import { readFile, writeFile, readdir, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { rawExecutionInput, capturedExecutionResponse, completedExecutionWork } from '../../../scripts/me00a-eval.mjs';
import { checkResearchLength, parseStrictJson } from '../../../scripts/me00a-work-checks.mjs';
const stage = process.argv[2];
if (stage !== 'generate') throw new Error('Explicit single stage required');
const root = resolve('tmp/me00a-20261012'), output = resolve(root, 'candidate13-real');
const publicRoot = resolve('docs/testing/evidence/me00a-candidate13-2026-10-15');
const hash = text => createHash('sha256').update(text).digest('hex');
const load = async path => JSON.parse(await readFile(path, 'utf8'));
const save = (path, value) => writeFile(path, typeof value === 'string' ? value : JSON.stringify(value, null, 2), { flag: 'wx' });
const protocol = await load(resolve(root, 'protocol.json')), batch = protocol.activeBatch;
if (batch.version !== 'candidate13' || batch.authorizationStatus !== 'APPROVED_BY_USER'
    || batch.maximumNewCalls !== 20 || protocol.maximumActualModelCalls !== 263 || protocol.phaseLimits.candidateValidation !== 183)
  throw new Error('Explicitly approved frozen numerical budget required');
const manifest = await load(resolve(publicRoot, 'candidate13-source.json'));
for (const item of [...manifest.sourceFiles, ...manifest.classFiles, ...manifest.policyFiles]) {
  if (hash(await readFile(resolve(item.path))) !== item.sha256) throw new Error(`Frozen file changed: ${item.path}`);
}
async function fixture(fileKey, hashKey) {
  const name = batch[fileKey];
  if (!/^[A-Za-z0-9_-]+\.json$/.test(name)) throw new Error('Fixture path outside whitelist');
  const bytes = await readFile(resolve(root, name));
  if (hash(bytes) !== batch[hashKey]) throw new Error('Fixture hash mismatch');
  return JSON.parse(bytes);
}
const sample = (await fixture('casesFile', 'casesSha256'))[0];
const answers = (await fixture('answersFile', 'answersSha256')).answers;
const originalPlanInput = await fixture('planInputFile', 'planInputSha256');
const account = await load(resolve('tmp/local-acceptance/account.json'));
await mkdir(output, { recursive: true });

/** Reserve every attempt before I/O; the phase and purpose limits cannot borrow from one another. */
async function reserve(purpose) {
  const names = (await readdir(resolve(root, 'traces'))).filter(name => /^\d+\.json$/.test(name));
  const previous = await Promise.all(names.map(name => load(resolve(root, 'traces', name))));
  const current = previous.filter(item => item.version === 'candidate13');
  const limit = purpose === 'EXECUTION' ? batch.maximumExecutionCalls : batch.maximumReviewCalls;
  if (current.filter(item => item.purpose === purpose).length >= limit
      || previous.filter(item => item.phase === 'candidateValidation').length >= protocol.phaseLimits.candidateValidation)
    throw new Error('Purpose or phase budget exhausted');
  const maximum = Math.min(protocol.maximumActualModelCalls, batch.maximumActualCallsAfterBatch, batch.callsBefore + batch.maximumNewCalls);
  for (let number = 1; number <= maximum; number++) {
    const path = resolve(root, 'traces', `${String(number).padStart(3, '0')}.json`);
    try { await save(path, { phase: 'candidateValidation', version: 'candidate13', purpose, reservedAt: new Date().toISOString() }); return path; }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
  }
  throw new Error('Actual request budget exhausted');
}
async function request(model, messages, purpose, label) {
  if (!batch.executionModels.includes(model)) throw new Error('Model outside approved batch');
  const key = process.env.MODEL_DEEPSEEK_API_KEY || process.env.MODEL_API_KEY;
  if (!key) throw new Error('Protected server key unavailable');
  const trace = await reserve(purpose);
  const input = { model, messages, ...protocol.executionParameters, stream: false };
  const record = { phase: 'candidateValidation', version: 'candidate13', purpose, label,
    request: input, startedAt: new Date().toISOString(), accountHash: hash(account.userId) };
  const started = performance.now();
  try {
    const response = await fetch('https://api.deepseek.com/chat/completions', { method: 'POST', headers: {
      'Content-Type': 'application/json', Authorization: `Bearer ${key}` }, body: JSON.stringify(input), signal: AbortSignal.timeout(240000) });
    Object.assign(record, await capturedExecutionResponse(response));
    record.elapsedMs = Math.round(performance.now() - started);
    await writeFile(trace, JSON.stringify(record, null, 2));
    return { ...completedExecutionWork(record), trace, inputSha256: hash(messages.at(-1).content) };
  } catch (error) {
    record.elapsedMs = Math.round(performance.now() - started); record.failure = error.message;
    await writeFile(trace, JSON.stringify(record, null, 2)); throw error;
  }
}
async function lockedInputs() {
  const lock = await load(resolve(output, 'copies-lock.json'));
  const inputs = {};
  for (const arm of ['raw', 'direct', 'plan']) {
    inputs[arm] = await readFile(resolve(output, arm, 'execution-input.md'), 'utf8');
    if (hash(inputs[arm]) !== lock[arm].sha256) throw new Error('Execution input differs from frozen copied body');
    if (arm !== 'raw' && inputs[arm] !== await readFile(resolve(output, arm, 'copied.md'), 'utf8')) throw new Error('Literal copied-body mismatch');
  }
  return inputs;
}
if (stage === 'generate') {
  const require = createRequire(resolve('apps/web/package.json'));
  const browser = await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
  try {
    const page = browser.contexts()[0].pages().find(page => page.url().startsWith('http://127.0.0.1:9001/'));
    if (!page) throw new Error('Normal same-account local browser required');
    async function api(path, body) {
      return page.evaluate(async ({ path, body }) => {
        const token = document.cookie.split('; ').find(item => item.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
        const response = await fetch(path, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json',
          ...(token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }),
          signal: AbortSignal.timeout(240000) });
        return { status: response.status, body: await response.json() };
      }, { path, body });
    }
    const me = await api('/api/v1/auth/me');
    if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId) throw new Error('Canonical identity mismatch');
    await save(resolve(output, 'identity-check.json'), { sameAccount: true, newAccountsCreated: 0, userHash: hash(account.userId) });
    await api('/api/v1/auth/csrf');
    const restored = await api('/api/v1/local-evaluation/replay-plan', {});
    await save(resolve(output, 'plan-session-replay.json'), restored);
    if (restored.status !== 200 || restored.body.questionCount !== 4 || restored.body.newPlanningModelCalls !== 0) throw new Error('Original four-question replay failed');
    const lock = {};
    for (const arm of ['plan', 'direct']) {
      const folder = resolve(output, arm); await mkdir(folder);
      const input = { ...originalPlanInput, rawPrompt: arm === 'plan' ? sample.rawPrompt : rawExecutionInput(sample, answers),
        planConfirmation: arm === 'plan' ? { planId: restored.body.planId, planningContext: restored.body.planningContext, answers } : undefined };
      const start = Date.now();
      await save(resolve(folder, 'input.json'), input);
      const result = await api('/api/v1/optimizations', input); await save(resolve(folder, 'platform.json'), result);
      if (result.status !== 200) throw new Error(`Generation ${arm} HTTP ${result.status}; no manual retry`);
      const copied = result.body.data.optimizedPrompt;
      await save(resolve(folder, 'copied.md'), copied); await save(resolve(folder, 'execution-input.md'), copied);
      const list = await api('/api/v1/optimization-history?current=1&size=20');
      let history;
      for (const row of list.body.data?.records ?? []) {
        if (Date.parse(row.createdAt) < start - 1000) continue;
        const detail = await api(`/api/v1/optimization-history/${row.id}`);
        if (detail.status === 200 && detail.body.data.rawPrompt === input.rawPrompt && detail.body.data.optimizedPrompt === copied) { history = detail; break; }
      }
      if (!history) throw new Error('New same-account history differs from copied body');
      await save(resolve(folder, 'history.json'), history);
      await save(resolve(folder, 'checks.json'), { copiedEqualsHistory: true, noScriptAnswerRepair: true,
        copySha256: hash(copied), originalActualAnswerCount: answers.length, newModelPlanningCalls: 0 });
      lock[arm] = { sha256: hash(copied), literalCopiedBody: true };
      console.log(`DONE generation ${arm}`);
    }
    await mkdir(resolve(output, 'raw'));
    const raw = rawExecutionInput(sample, answers); await save(resolve(output, 'raw/execution-input.md'), raw);
    lock.raw = { sha256: hash(raw), actualAnswersOnly: true };
    await save(resolve(output, 'copies-lock.json'), lock);
    console.log('COPIES_LOCKED; review copies before executing downstream');
  } finally { await browser.close(); }
} else if (stage === 'execute') {
  const inputs = await lockedInputs();
  await mkdir(resolve(output, 'works'));
  const lengths = {};
  for (const [model, prefix] of [['deepseek-flash', 'flash'], ['deepseek-v4-pro', 'pro']]) {
    for (const arm of ['raw', 'direct', 'plan']) {
      const name = `${prefix}-${arm}`;
      const result = await request(model, [{ role: 'user', content: inputs[arm] }], 'EXECUTION', name);
      await save(resolve(output, 'works', `${name}.md`), result.text);
      await save(resolve(output, 'works', `${name}.json`), result);
      lengths[name] = checkResearchLength(result.text);
      console.log(`DONE execution ${name} bodyHan=${lengths[name].bodyHan} pass=${lengths[name].pass}`);
    }
  }
  await save(resolve(output, 'independent-length-check.json'), lengths);
} else {
  await lockedInputs();
  const names = ['flash-raw', 'flash-direct', 'flash-plan', 'pro-raw', 'pro-direct', 'pro-plan'];
  const mapping = Object.fromEntries([...names].sort((left, right) => hash(manifest.frozenAt + left).localeCompare(hash(manifest.frozenAt + right)))
    .map((name, index) => [['U', 'V', 'W', 'X', 'Y', 'Z'][index], name]));
  await save(resolve(output, 'locked-mapping.json'), mapping);
  const works = Object.fromEntries(await Promise.all(names.map(async name => [name, await readFile(resolve(output, 'works', `${name}.md`), 'utf8')])));
  const lengths = await load(resolve(output, 'independent-length-check.json'));
  const anonymousLengths = Object.fromEntries(Object.entries(mapping).map(([id, name]) => [id, lengths[name]]));
  const inverse = Object.fromEntries(Object.entries(mapping).map(([id, name]) => [name, id]));
  const pairs = ['flash', 'pro'].flatMap(model => [['raw', 'direct'], ['raw', 'plan'], ['direct', 'plan']]
    .map(([left, right]) => ({ left: inverse[`${model}-${left}`], right: inverse[`${model}-${right}`] })));
  const pack = `原始任务：\n${sample.rawPrompt}\n\n实际逐字题答：\n${JSON.stringify({ answers })}\n\n`
    + Object.entries(mapping).map(([id, name]) => `作品 ${id}：\n${works[name]}`).join('\n\n')
    + `\n\n独立硬检查：\n${JSON.stringify(anonymousLengths)}`;
  await save(resolve(output, 'review-pack.md'), pack);
  const system = '你是独立AI辅助评审。只依据原题、实际题答及匿名作品，不互看评审结果。评价准确性、完整性、清晰度和可执行性，提供具体证据。拟议设计可推荐类别、尺度、流程及适用条件，不代表参数已确认或研究已实施；不可编造观测数据。核对受众与研究对象、信息流、任务重复、分配和配对结构以及设计可开展程度。独立篇幅检查单列，评分不能抵消硬失败，不要求作品自报字数。';
  const schema = `只输出严格JSON：{"reviewerType":"AI_ASSISTED","scores":{"U":{"accuracy":1,"completeness":1,"clarity":1,"executability":1}},"issues":[{"work":"U","severity":"MAJOR","evidence":"原文","reason":"依据"}],"pairs":[{"left":"U","right":"V","verdict":"LEFT_WIN|RIGHT_WIN|TIE|BOTH_FAIL","reason":"依据"}]}。scores包含U至Z，各维度整数1至5。pairs必须且仅包含以下六对：${JSON.stringify(pairs)}。实质失败双方分别记录，不预设必有胜者。`;
  for (const [index, model] of batch.executionModels.entries()) {
    const result = await request(model, [{ role: 'system', content: system }, { role: 'user', content: pack + '\n\n' + schema }], 'REVIEW', `reviewer-${index + 1}`);
    await save(resolve(output, `reviewer-${index + 1}.md`), result.text);
    let record;
    try {
      const review = parseStrictJson(result.text);
      const dimensions = ['accuracy', 'completeness', 'clarity', 'executability'];
      if (review.reviewerType !== 'AI_ASSISTED' || Object.keys(review.scores ?? {}).sort().join() !== Object.keys(mapping).sort().join()
          || Object.keys(mapping).some(id => dimensions.some(key => !Number.isInteger(review.scores[id]?.[key]) || review.scores[id][key] < 1 || review.scores[id][key] > 5))
          || !Array.isArray(review.issues) || review.issues.some(issue => !mapping[issue.work] || !issue.evidence || !issue.reason)
          || !Array.isArray(review.pairs) || review.pairs.length !== 6
          || pairs.some(pair => review.pairs.filter(value => value.left === pair.left && value.right === pair.right
            && ['LEFT_WIN', 'RIGHT_WIN', 'TIE', 'BOTH_FAIL'].includes(value.verdict) && value.reason).length !== 1)) throw new Error('Review protocol invalid');
      record = { valid: true, model, review, ...result };
    } catch (error) { record = { valid: false, model, reason: error.message, ...result }; }
    await save(resolve(output, `reviewer-${index + 1}.json`), record);
    console.log(`DONE independent AI reviewer ${index + 1} valid=${record.valid}`);
  }
}
