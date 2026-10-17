// Candidate 31 的同账号／原生剪贴板／历史核查流程；仅由显式 mode 触发一次冻结 UI 操作。
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';

const MODES = { 'mail-direct': 'MAIL-COMPACT', 'translation-direct': 'TRANSLATION-COMPACT',
  'plan-choice': 'PROJECT-CHOICE-STRUCTURED' };
const mode = process.argv[2];
if (!mode) {
  console.log('Usage: node docs/testing/evidence/me04-candidate32-structured-toggle-2026-10-18/run-live.mjs <mail-direct|translation-direct|plan-choice>');
  process.exit(0);
}
if (!Object.hasOwn(MODES, mode)) throw Error('Unsupported frozen mode');

const pub = resolve('docs/testing/evidence/me04-candidate32-structured-toggle-2026-10-18');
const work = resolve('tmp/me00a-candidate32-live-20261018/live-results');
const origin = 'http://127.0.0.1:5174';
const modelId = 'deepseek:deepseek-flash';
const caseBytes = await readFile(resolve(pub, 'live-cases.json'));
const proposalBytes = await readFile(resolve(pub, 'live-proposal.json'));
const cases = JSON.parse(caseBytes.toString('utf8'));
const proposal = JSON.parse(proposalBytes.toString('utf8'));
const sample = cases.find(item => item.id === MODES[mode]);
const account = JSON.parse(await readFile('tmp/local-acceptance/account.json', 'utf8'));
const require = createRequire(resolve('apps/web/package.json'));
const { chromium } = require('@playwright/test');
const hash = text => createHash('sha256').update(text).digest('hex');
const newline = text => text.replace(/\r\n/g, '\n');
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const save = async (file, value) => {
  await mkdir(resolve(file, '..'), { recursive: true });
  await writeFile(file, Buffer.isBuffer(value) || typeof value === 'string'
    ? value : `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' });
};
if (!sample || !equal(sample.context, { customDescription: '', files: [] }) || sample.includeExamples !== false
    || proposal.maximumNewCalls !== 18 || proposal.models.enhancement !== 'deepseek-flash'
    || proposal.parameters.temperature !== 0.2 || proposal.parameters.enhancementMaxTokens !== 10000
    || proposal.parameters.thinking !== 'disabled') throw Error('Frozen case/proposal mismatch');
if (sample.promptFormat !== (mode === 'plan-choice' ? 'STRUCTURED' : 'COMPACT')) throw Error('Frozen format mismatch');

// wx 锁在连接和业务操作前写入；失败也保留锁，不允许脚本自动质量重试或覆盖原件。
await save(resolve(work, `${mode}-reserved.json`), { mode, scheduledOnce: true, modelId,
  caseSha256: hash(caseBytes), proposalSha256: hash(proposalBytes), startedAt: new Date().toISOString() });
const output = resolve(work, sample.id);
await save(resolve(output, 'frozen-input.json'), { sample, modelId, caseSha256: hash(caseBytes), proposalSha256: hash(proposalBytes) });
const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
const context = browser.contexts()[0];
if (!context) throw Error('Root-authenticated browser context missing');
const page = await context.newPage();
page.setDefaultTimeout(20_000);
const calls = { plan: 0, enhancement: 0 };
let allowEnhancement = mode !== 'plan-choice';
let guardFailure;
let rejectGuard;
const rejected = new Promise((_, reject) => { rejectGuard = reject; });
// 避免在尚未开始等待响应时触发未处理拒绝；每个业务等待仍与该拒绝信号竞争。
void rejected.catch(() => undefined);
const captureTasks = [];
const responses = new Map();
const privateRequests = [];

const api = async path => page.evaluate(async path => {
  const response = await fetch(path, { signal: AbortSignal.timeout(30_000) });
  return { status: response.status, body: await response.json() };
}, path);
const waitResponse = async path => {
  const response = await Promise.race([page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === path, { timeout: 230_000 }), rejected]);
  const raw = await response.body();
  return { status: response.status(), body: JSON.parse(raw.toString('utf8')) };
};
const failGuard = async (route, reason) => {
  guardFailure = reason;
  await save(resolve(output, 'guard-failure.json'), { reason });
  await route.abort('blockedbyclient');
  rejectGuard(Error(reason));
};

async function installGuards() {
  await page.route('**/api/v1/optimizations**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() !== 'POST' || !['/api/v1/optimizations', '/api/v1/optimizations/plan'].includes(path)) {
      await route.continue(); return;
    }
    const body = request.postDataJSON();
    const isPlan = path.endsWith('/plan');
    const kind = isPlan ? 'plan' : 'enhancement';
    const validCommon = body.rawPrompt === sample.rawPrompt && body.modelId === modelId
      && equal(body.conversationHistory, []);
    const validPlan = mode === 'plan-choice' && calls.plan === 0 && body.contextDescription === ''
      && body.planningContext === null;
    const validEnhancement = allowEnhancement && calls.enhancement === 0
      && equal(body.context, sample.context) && equal(body.enhancement, {
        templateCode: 'AUTO', includeConversationHistory: false, includePermissionBoundaries: true,
        includeExamples: false, promptFormat: sample.promptFormat,
      }) && (mode === 'plan-choice'
        ? body.planConfirmation?.answers?.length === 1
          && body.planConfirmation.answers[0].answer === sample.plannedAnswer
        : body.planConfirmation === null);
    if (!validCommon || !(isPlan ? validPlan : validEnhancement)) {
      await failGuard(route, `Unexpected ${kind} input or extra request`); return;
    }
    calls[kind]++;
    privateRequests.push({ path, body });
    await save(resolve(output, `${kind}-request.private.json`), body);
    await route.continue();
  });
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (response.request().method() !== 'POST' || !['/api/v1/optimizations', '/api/v1/optimizations/plan'].includes(path)) return;
    const kind = path.endsWith('/plan') ? 'plan' : 'enhancement';
    const capture = (async () => {
      const raw = await response.body();
      const value = { status: response.status(), body: JSON.parse(raw.toString('utf8')) };
      responses.set(kind, value);
      await save(resolve(output, `${kind}-response.private.bin`), raw);
      await save(resolve(output, `${kind}-platform.private.json`), value);
    })();
    captureTasks.push(capture);
  });
}

async function nativeCopy(file) {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin });
  await page.bringToFront();
  await page.getByRole('button', { name: '复制提示词', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.copy-main-button')?.textContent?.includes('已复制'));
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  // UTF-8 原样序列化原生 readText；保留 Windows CRLF，不借保存过程规范化原件。
  await save(resolve(output, file), Buffer.from(copied, 'utf8'));
  return copied;
}

async function historyMatch(result, before, persist = true) {
  const response = await api('/api/v1/optimization-history?current=1&size=20');
  if (response.status !== 200) throw Error('Canonical history list unavailable');
  for (const row of response.body.data.records) {
    if (before.ids.includes(row.id)) continue;
    const detail = await api(`/api/v1/optimization-history/${row.id}`);
    const h = detail.body.data;
    if (detail.status !== 200 || h.rawPrompt !== sample.rawPrompt || h.optimizedPrompt !== result.optimizedPrompt) continue;
    for (const key of ['promptFormat', 'sections', 'ambiguities', 'appliedConstraints']) {
      if (!equal(h[key], result[key])) throw Error(`History drift: ${key}`);
    }
    if (persist) await save(resolve(output, 'history.private.json'), detail);
    return { body: true, promptFormat: true, sections: true, ambiguities: true, appliedConstraints: true,
      beforeTotal: before.total, afterTotal: response.body.data.total, matchingNewRecord: true };
  }
  throw Error('Matching newly generated canonical history missing');
}

async function switchLocally(result) {
  const before = { ...calls };
  await page.locator('label.switch-control').filter({ hasText: '结构化提示词' }).click();
  const switched = await page.evaluate(async () => {
    const { useOptimizationStore } = await import('/src/stores/optimization.ts');
    return JSON.parse(JSON.stringify(useOptimizationStore().result));
  });
  const expectedFormat = result.promptFormat === 'COMPACT' ? 'STRUCTURED' : 'COMPACT';
  if (switched.promptFormat !== expectedFormat) throw Error('Local format switch did not apply');
  for (const key of ['sections', 'ambiguities', 'appliedConstraints', 'evidenceCards']) {
    if (!equal(switched[key], result[key])) throw Error(`Local switch lost ${key}`);
  }
  const expected = result.sections.filter(section => section.type !== 'CLARIFICATIONS')
    .map(section => expectedFormat === 'COMPACT' ? section.content : `## ${section.title}\n${section.content}`).join('\n\n');
  if (switched.optimizedPrompt !== expected) throw Error('Local switch altered section content');
  await save(resolve(output, 'local-switch-result.private.json'), switched);
  const copied = await nativeCopy('local-switch-native-copy.md');
  if (newline(copied) !== newline(expected)) throw Error('Local switch clipboard drift');
  // 等待真实浏览器两帧；后续根代理还将用后端总账核对无新调用。
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  if (!equal(calls, before)) throw Error('Pure local formatting caused a model HTTP request');
  return { form: expectedFormat, semanticSectionsUnchanged: true, constraintsUnchanged: true,
    noNewModelHttpRequests: true, copySha256: hash(copied), originalHistoryUnchangedByLocalSwitch: true };
}

let summary;
try {
  await page.goto(`${origin}/workbench`, { waitUntil: 'domcontentloaded' });
  const me = await api('/api/v1/auth/me');
  if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId
      || (account.workspaceId && me.body.data.workspaceId !== account.workspaceId)) throw Error('Canonical account mismatch');
  const models = await api('/api/v1/models');
  if (models.status !== 200 || !models.body.data.some(model => model.id === modelId)) throw Error('Frozen model unavailable');
  await page.evaluate(plan => localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: plan, introSeen: true })), mode === 'plan-choice');
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelector('#workbench-model') !== null);
  // 仅设置已有目录中核实的模型选择；生成和题答提交均通过实际界面按钮，不注入结果或答案。
  await page.evaluate(async id => {
    const { useOptimizationStore } = await import('/src/stores/optimization.ts');
    const store = useOptimizationStore();
    if (store.isLoadingModels) await store.refreshAvailableModels();
    if (!store.availableModels.some(model => model.id === id)) throw Error('UI model directory mismatch');
    store.selectedModelId = id;
  }, modelId);
  const historyBefore = await api('/api/v1/optimization-history?current=1&size=20');
  if (historyBefore.status !== 200) throw Error('Canonical history unavailable before run');
  const before = { total: historyBefore.body.data.total, ids: historyBefore.body.data.records.map(record => record.id) };
  await save(resolve(output, 'history-before.private.json'), before);
  await installGuards();
  await page.getByLabel('原始提示词', { exact: true }).fill(sample.rawPrompt);
  if (sample.promptFormat === 'STRUCTURED') {
    await page.locator('label.switch-control').filter({ hasText: '结构化提示词' }).click();
  }
  let finalResponse;
  if (mode === 'plan-choice') {
    const waitingPlan = waitResponse('/api/v1/optimizations/plan');
    await page.getByRole('button', { name: '先确认并增强', exact: true }).click();
    const plan = await waitingPlan;
    await Promise.all(captureTasks);
    if (plan.status !== 200) throw Error(`Natural Plan failed HTTP ${plan.status}`);
    const questions = plan.body.data.questions;
    const question = questions?.[0];
    const loginMethod = question && /登录|认证/.test(question.question) && /方式|方法|选|邮箱|手机号/.test(question.question)
      && !/返回|响应|权限|角色|用户表|数据库|页面|验证码服务|发送服务|验收|字段|数据迁移|有效期/.test(question.question);
    if (questions?.length !== 1 || !loginMethod) {
      await save(resolve(output, 'plan-stop.private.json'), { reason: 'Actual Plan does not contain exactly the frozen login-method choice', questions });
      throw Error('Actual Plan outside frozen answer boundary; no answer submitted');
    }
    await save(resolve(output, 'actual-question-and-frozen-answer.private.json'), { question, answer: sample.plannedAnswer });
    const dialog = page.getByRole('dialog', { name: '确认关键细节' });
    if (question.type === 'FREE_TEXT') await dialog.getByLabel('填写回答').fill(sample.plannedAnswer);
    else if (question.allowCustomAnswer) await dialog.getByPlaceholder('输入更符合你实际情况的回答').fill(sample.plannedAnswer);
    else throw Error('Actual question disallows frozen exact answer; no option fabricated');
    // 先保留真实题答，待根代理离线导出对应有效输入白名单后再提交；不预造Plan题目。
    await save(resolve(output, 'actual-answers.private.json'), [{ questionId: question.id,
      question: question.question, answer: sample.plannedAnswer }]);
    let scoped = false;
    for (let poll = 0; poll < 180; poll++) {
      try { await readFile(resolve(output, 'plan-enhancement-scope-ready.json')); scoped = true; break; }
      catch { await page.waitForTimeout(1000); }
    }
    if (!scoped) throw Error('Actual Plan input scope was not registered; no enhancement submitted');
    allowEnhancement = true;
    const waitingFinal = waitResponse('/api/v1/optimizations');
    await dialog.getByRole('button', { name: '生成最终提示词', exact: true }).click();
    finalResponse = await waitingFinal;
  } else {
    const waitingFinal = waitResponse('/api/v1/optimizations');
    await page.getByRole('button', { name: '直接增强提示词', exact: true }).click();
    finalResponse = await waitingFinal;
  }
  await Promise.all(captureTasks);
  if (guardFailure) throw Error(guardFailure);
  if (finalResponse.status !== 200) throw Error(`Enhancement failed HTTP ${finalResponse.status}`);
  const result = finalResponse.body.data;
  if (result.provider.mock !== false || result.promptFormat !== sample.promptFormat) throw Error('Unexpected real provider or format');
  await page.getByRole('button', { name: '复制提示词', exact: true }).waitFor({ state: 'visible' });
  const copied = await nativeCopy('native-copy.md');
  if (newline(copied) !== newline(result.optimizedPrompt)) throw Error('Native clipboard drift');
  const history = await historyMatch(result, before);
  await page.screenshot({ path: resolve(output, 'result-copy.private.png'), fullPage: true });
  const local = await switchLocally(result);
  // 再次只读核查原生成历史未被本地排版覆写，不把本地切换正文冒充生成时历史正文。
  await historyMatch(result, before, false);
  summary = { mode, caseId: sample.id, success: true, actualUiGeneration: true,
    nativeCopyMatches: true, promptFormat: result.promptFormat, history, localSwitch: local,
    calls, copySha256: hash(copied), requestHash: hash(JSON.stringify(privateRequests)),
    responseHashes: Object.fromEntries([...responses].map(([key, value]) => [key, hash(JSON.stringify(value))])),
    comparisonOnlyNormalizesCrLf: true, clipboardEncoding: 'UTF-8 serialization of native readText; original line endings preserved',
    accountReused: true, finishedAt: new Date().toISOString() };
  await save(resolve(output, 'check.json'), summary);
  await save(resolve(pub, 'live-checks', `${mode}.json`), summary);
  console.log(JSON.stringify({ mode, success: true, calls, copySha256: summary.copySha256 }));
} catch (error) {
  await Promise.allSettled(captureTasks);
  await save(resolve(output, 'failure.private.json'), { message: error.message, calls, guardFailure, finishedAt: new Date().toISOString() });
  await page.screenshot({ path: resolve(output, 'failure.private.png'), fullPage: true }).catch(() => undefined);
  console.log(JSON.stringify({ mode, success: false, calls, reason: error.message }));
  throw error;
} finally {
  await page.close();
  await browser.close();
}
