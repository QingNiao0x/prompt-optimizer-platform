// Actual page Plan, actual question answers, unchanged native copy and history. No quality retries.
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';

const [command, caseId] = process.argv.slice(2);
if (!['plan', 'finish'].includes(command) || !['TS-IMPLICIT', 'TS-USER-CHOICE', 'HOLDOUT-TEMPERATURE'].includes(caseId)
    || (command === 'finish' && caseId !== 'TS-IMPLICIT')) throw Error('Frozen browser command required');
const pub = resolve('docs/testing/evidence/me01-candidate41r1-responsibility-projection-2026-10-18');
const root = resolve('tmp/me01-candidate41r1-live-20261018');
const out = resolve(root, 'flow', caseId);
const ledger = resolve('tmp/me00a-20261012');
const origin = 'http://127.0.0.1:5174';
const pageName = `candidate41r1-${caseId}`;
const hash = value => createHash('sha256').update(value).digest('hex');
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const newline = value => value.replace(/\r\n/g, '\n');
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const save = async (name, value) => { const path = resolve(out, name); await mkdir(dirname(path), { recursive: true });
  await writeFile(path, typeof value === 'string' || Buffer.isBuffer(value) ? value : `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' }); };

/** All source/runtime bytes, scope and formal ceilings are checked before every actual UI request. */
async function preflight() {
  const bytes = await readFile(resolve(pub, 'candidate41r1-source.json')); const manifest = JSON.parse(bytes.toString('utf8'));
  const protocol = await json(resolve(ledger, 'protocol.json')); const batch = protocol.activeBatch;
  if (manifest.version !== 'candidate41r1' || batch.version !== 'candidate41r1-live' || batch.status !== 'OPEN'
      || batch.authorizationStatus !== 'APPROVED_BY_USER' || batch.maximumNewCalls !== 10 || batch.callsBefore !== 424
      || batch.sourceManifestSha256 !== hash(bytes) || batch.qualityRetriesAllowed !== false
      || protocol.maximumActualModelCalls !== 444 || protocol.phaseLimits.baseline !== 80 || protocol.phaseLimits.candidateValidation !== 364
      || !equal(batch.purposeMaximums, { PLANNING: 3, PLATFORM_GENERATION: 3, EXECUTION: 2, REVIEW: 2 })) throw Error('Frozen batch mismatch');
  if (!/^[A-Za-z0-9_-]+\.json$/.test(batch.casesFile) || hash(await readFile(resolve(pub, 'request-cases.json'))) !== batch.casesSha256
      || hash(await readFile(resolve(ledger, batch.casesFile))) !== batch.casesSha256) throw Error('Input/path drift');
  for (const row of manifest.sourceFiles) if (hash(await readFile(resolve(row.path))) !== row.sha256) throw Error('Source drift');
  for (const row of manifest.runtimeFiles) if (hash(await readFile(resolve(manifest.runtimeSnapshot, row.path))) !== row.sha256) throw Error('Runtime drift');
  const files = (await readdir(resolve(ledger, 'traces'))).filter(name => /^\d+\.json$/.test(name));
  let candidate = 0; for (const name of files) if ((await json(resolve(ledger, 'traces', name))).phase === 'candidateValidation') candidate++;
  if (files.length >= 434 || candidate >= 364) throw Error('Frozen budget exhausted');
  return { sample: (await json(resolve(pub, 'request-cases.json'))).find(row => row.id === caseId), batch };
}

const frozen = await preflight();
await save(`${command}-once-only.json`, { candidate: 'candidate41r1', startedAt: new Date().toISOString(), qualityRetriesAllowed: false });
const require = createRequire(resolve('apps/web/package.json'));
const browser = await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
const context = browser.contexts()[0];
const account = await json('tmp/local-acceptance/account.json');
let page, plan, platform, planCapture, platformCapture, guardFailure;
const pending = []; const counts = { plan: 0, enhancement: 0 };
const api = path => page.evaluate(async path => { const response = await fetch(path); return { status: response.status, body: await response.json() }; }, path);
const snapshot = () => page.evaluate(async () => { const { useOptimizationStore } = await import('/src/stores/optimization.ts'); const store = useOptimizationStore();
  return JSON.parse(JSON.stringify({ rawPrompt: store.rawPrompt, promptFormat: store.promptFormat, files: store.files,
    customDescription: store.customDescription, result: store.result, plan: store.plan, errorMessage: store.errorMessage })); });

/** Capture the normal clipboard button and a new server-side history record without repairing either. */
async function verifyChain(before) {
  if (platform?.status !== 200 || platform.body.data.provider.mock !== false || platform.body.data.promptFormat !== 'COMPACT') throw Error('Real natural enhancement failed');
  const current = await snapshot(); await save('current-store.private.json', current);
  if (!equal(current.result, platform.body.data)) throw Error('Store/result drift');
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin }); await page.bringToFront();
  await page.getByRole('button', { name: '复制提示词', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.copy-main-button')?.textContent?.includes('已复制'));
  const copy = await page.evaluate(() => navigator.clipboard.readText()); await save('native-copy.md', copy);
  if (newline(copy) !== newline(platform.body.data.optimizedPrompt)) throw Error('Native copy mismatch');
  const after = await api('/api/v1/optimization-history?current=1&size=20'); let found;
  const oldIds = before.body.data.records.map(row => row.id);
  for (const row of after.body.data.records) {
    if (oldIds.includes(row.id)) continue;
    const detail = await api(`/api/v1/optimization-history/${row.id}`); const value = detail.body.data;
    if (detail.status !== 200 || value.rawPrompt !== frozen.sample.rawPrompt || value.optimizedPrompt !== platform.body.data.optimizedPrompt) continue;
    for (const key of ['sections', 'promptFormat', 'ambiguities', 'appliedConstraints', 'constraintGroups'])
      if (!equal(value[key], platform.body.data[key])) throw Error(`History mismatch: ${key}`);
    found = detail; break;
  }
  if (!found) throw Error('New matching history missing'); await save('history.private.json', found);
  const confirmation = await json(resolve(out, 'actual-confirmation.private.json'));
  const check = { success: true, candidate: 'candidate41r1', caseId, sameAccount: true, newAccountsCreated: 0,
    boundPlanUsed: true, nativeCopyMatches: true, storeMatches: true, historyMatches: true,
    historyTotalDelta: after.body.data.total - before.body.data.total, copySha256: hash(copy),
    actualAnswers: confirmation.answers, sourceManifestSha256: frozen.batch.sourceManifestSha256, qualityRetriesAllowed: false };
  await save('check.json', check); return check;
}

try {
  if (command === 'plan') {
    for (const previous of context.pages()) {
      if (await previous.evaluate(() => window.name).catch(() => '') === pageName) await previous.close();
    }
    page = await context.newPage(); await page.goto(`${origin}/workbench`, { waitUntil: 'domcontentloaded' });
    await page.evaluate(name => { window.name = name; localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: true, introSeen: true })); }, pageName);
    await page.reload({ waitUntil: 'domcontentloaded' });
  } else {
    for (const open of context.pages()) if (await open.evaluate(() => window.name).catch(() => '') === pageName) { page = open; break; }
    if (!page) throw Error('Original Plan page missing');
    plan = await json(resolve(out, 'plan.private.json')); if (plan.status !== 200) throw Error('Actual Plan unavailable');
  }
  page.setDefaultTimeout(20000);
  const me = await api('/api/v1/auth/me');
  if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId
      || (account.workspaceId && me.body.data.workspaceId !== account.workspaceId)) throw Error('Canonical account mismatch');
  const before = await api('/api/v1/optimization-history?current=1&size=20'); if (before.status !== 200) throw Error('History unavailable');
  await save(`${command}-history-before.private.json`, before);
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (response.request().method() !== 'POST' || !['/api/v1/optimizations/plan', '/api/v1/optimizations'].includes(path)) return;
    const capture = (async () => { const captured = { status: response.status(), body: JSON.parse((await response.body()).toString('utf8')) };
      if (path.endsWith('/plan')) { plan = captured; await save('plan.private.json', captured); }
      else { platform = captured; await save('platform.private.json', captured); } })();
    pending.push(capture); if (path.endsWith('/plan')) planCapture = capture; else platformCapture = capture;
  });
  await page.route(/\/api\/v1\/optimizations(?:\/plan)?(?:\?.*)?$/, async route => {
    const request = route.request(); const path = new URL(request.url()).pathname;
    if (request.method() !== 'POST' || !['/api/v1/optimizations/plan', '/api/v1/optimizations'].includes(path)) return route.continue();
    try {
      await preflight(); const body = request.postDataJSON();
      if (body.rawPrompt !== frozen.sample.rawPrompt || body.modelId !== 'deepseek:deepseek-flash' || body.conversationHistory.length) throw Error('Task/model drift');
      if (path.endsWith('/plan')) {
        const expectedPlan = { rawPrompt: frozen.sample.rawPrompt, modelId: 'deepseek:deepseek-flash',
          contextDescription: '', conversationHistory: [], planningContext: null };
        // 无资料的真实页面不准备空上下文；只接受原生 null，不能由脚本伪造引用。
        if (command !== 'plan' || ++counts.plan !== 1 || !equal(body, expectedPlan)) throw Error('Unexpected Plan request');
        await save('plan-request.private.json', body);
      } else {
        if (planCapture) await planCapture;
        if (++counts.enhancement !== 1 || !frozen.sample.enhancementAllowed || plan?.status !== 200) throw Error('Unapproved enhancement');
        const answers = plan.body.data.questions.length ? await json(resolve(out, 'prepared-answers.json')) : [];
        const confirmation = { planId: plan.body.data.planId, planningContext: plan.body.data.planningContext, answers };
        const expected = { rawPrompt: frozen.sample.rawPrompt, modelId: 'deepseek:deepseek-flash', context: { customDescription: '', files: [] },
          enhancement: { templateCode: 'AUTO', includeConversationHistory: false, includePermissionBoundaries: true, includeExamples: false, promptFormat: 'COMPACT' },
          conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] }, planConfirmation: confirmation };
        // The production UI may submit its inferred template; AUTO is verified separately through the frozen task identity.
        if (['AUTO', 'FEATURE_DEVELOPMENT'].includes(body.enhancement?.templateCode)) expected.enhancement.templateCode = body.enhancement.templateCode;
        if (!equal(body, expected)) throw Error('Bound enhancement request mismatch');
        try {
          const locked = await json(resolve(out, 'actual-confirmation.private.json'));
          if (!equal(locked, body.planConfirmation)) throw Error('Actual submitted confirmation changed');
        } catch (error) {
          if (error.code !== 'ENOENT') throw error;
          await save('actual-confirmation.private.json', body.planConfirmation);
        }
        await save('enhancement-request.private.json', body);
      }
      await route.continue();
    } catch (error) { guardFailure = error.message; await save('guard-failure.json', { reason: error.message }); await route.abort('blockedbyclient'); }
  });
  if (command === 'plan') {
    await page.waitForFunction(() => document.querySelector('#workbench-model') !== null);
    await page.evaluate(async () => { const { useOptimizationStore } = await import('/src/stores/optimization.ts'); const store = useOptimizationStore();
      if (store.isLoadingModels || !store.availableModels.length) await store.refreshAvailableModels();
      if (!store.availableModels.some(model => model.id === 'deepseek:deepseek-flash')) throw Error('Frozen model unavailable');
      store.selectedModelId = 'deepseek:deepseek-flash'; });
    await page.getByLabel('原始提示词', { exact: true }).fill(frozen.sample.rawPrompt);
    const initial = await snapshot(); if (initial.promptFormat !== 'COMPACT' || initial.files.length || initial.customDescription || initial.result) throw Error('Fresh empty task required');
    const planned = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/optimizations/plan', { timeout: 150000 });
    const enhanced = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/optimizations', { timeout: 150000 });
    void enhanced.catch(() => undefined);
    await page.getByRole('button', { name: '先确认并增强', exact: true }).click(); await planned; await planCapture;
    if (plan.status !== 200) throw Error(`Plan failed ${plan.status}`);
    if (!plan.body.data.questions.length && frozen.sample.enhancementAllowed) { await enhanced; await platformCapture; }
    await save('plan-observation.json', { caseId, questionCount: plan.body.data.questions.length,
      summary: plan.body.data.summary, questions: plan.body.data.questions, actualAnswersSubmitted: false });
    if (platform) console.log(JSON.stringify(await verifyChain(before)));
    else console.log(JSON.stringify({ success: true, stage: 'PLAN_CAPTURED', caseId, questionCount: plan.body.data.questions.length }));
  } else {
    const answers = await json(resolve(out, 'prepared-answers.json'));
    if (answers.length !== plan.body.data.questions.length) throw Error('Complete actual answers required');
    // 原对话框和答案在失败后保留；按真实当前题回退，并等待每次渲染，不重新生成Plan。
    const originalDialog = page.locator('.el-dialog:visible').filter({ hasText: '生成前确认' });
    const currentQuestion = (await originalDialog.locator('h3:visible').innerText()).trim();
    let currentIndex = plan.body.data.questions.findIndex(q => q.question === currentQuestion);
    if (currentIndex < 0) throw Error('Original active question does not belong to bound Plan');
    while (currentIndex > 0) {
      await originalDialog.getByRole('button', { name: '上一题', exact: true }).click(); currentIndex--;
      await originalDialog.getByRole('heading', { name: plan.body.data.questions[currentIndex].question, exact: true }).waitFor();
    }
    const enhanced = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/optimizations', { timeout: 150000 });
    void enhanced.catch(() => undefined);
    for (let i = 0; i < answers.length; i++) {
      const q = plan.body.data.questions[i], answer = answers[i];
      if (answer.questionId !== q.id || answer.question !== q.question) throw Error('Actual answer binding mismatch');
      const dialog = page.locator('.el-dialog:visible').filter({ hasText: '生成前确认' });
      await dialog.getByRole('heading', { name: q.question, exact: true }).waitFor();
      const input = dialog.locator('textarea:visible');
      if (await input.count() !== 1) throw Error('Actual custom-answer control unavailable');
      await input.fill(answer.answer);
      await dialog.getByRole('button', { name: i === answers.length - 1 ? '生成最终提示词' : '下一题', exact: true }).click();
    }
    await enhanced; await platformCapture; console.log(JSON.stringify(await verifyChain(before)));
  }
  await Promise.all(pending); if (guardFailure) throw Error(guardFailure);
  await page.screenshot({ path: resolve(out, `${command}.private.png`), fullPage: true });
} catch (error) {
  await Promise.allSettled(pending); await save(`${command}-failure.json`, { reason: error.message, guardFailure, counts });
  if (page) { await save(`${command}-failure-store.private.json`, await snapshot()); await page.screenshot({ path: resolve(out, `${command}-failure.private.png`), fullPage: true }).catch(() => undefined); }
  console.log(JSON.stringify({ success: false, caseId, command, reason: error.message, counts })); process.exitCode = 1;
} finally { await browser.close(); }
