// Explicit browser actions only. Reuses candidate32 capture safeguards, with real mode regeneration.
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';

const command = process.argv[2];
const caseId = process.argv[3];
const requestedStep = process.argv[4];
if (!command) {
  console.log('Usage: node docs/testing/evidence/me04-candidate33-generation-modes-2026-10-18/run-live.mjs run-case PATCH|MAIL | run-step PATCH|MAIL COMPACT|STRUCTURED');
  process.exit(0);
}
if (!['run-case', 'run-step'].includes(command) || !['PATCH', 'MAIL'].includes(caseId)
    || (command === 'run-step' && !['COMPACT', 'STRUCTURED'].includes(requestedStep))) throw Error('Frozen browser operation required');

const pub = resolve('docs/testing/evidence/me04-candidate33-generation-modes-2026-10-18');
const work = resolve('tmp/me04-candidate33-live-20261018/live-results');
const ledger = resolve('tmp/me00a-20261012');
const origin = 'http://127.0.0.1:5174';
const modelId = 'deepseek:deepseek-flash';
const pageName = `candidate33-live-${caseId}`;
const formats = command === 'run-case' ? ['COMPACT', 'STRUCTURED'] : [requestedStep];
const hash = value => createHash('sha256').update(value).digest('hex');
const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const newline = value => value.replace(/\r\n/g, '\n');
const json = async file => JSON.parse(await readFile(file, 'utf8'));
const save = async (file, value) => {
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, Buffer.isBuffer(value) || typeof value === 'string'
    ? value : `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' });
};

/** Every action rechecks frozen inputs, sources/runtime and the live approved purpose/phase allowance. */
async function preflight(needsModelCall = false) {
  const caseBytes = await readFile(resolve(pub, 'live-cases.json'));
  const catalogue = JSON.parse(caseBytes.toString('utf8'));
  const proposal = await json(resolve(pub, 'live-proposal.json'));
  const manifestBytes = await readFile(resolve(pub, 'candidate33-source.json'));
  const manifest = JSON.parse(manifestBytes.toString('utf8'));
  if (catalogue.candidate !== 'candidate33' || catalogue.qualityRetriesAllowed !== false
      || catalogue.accountCreationAllowed !== false || manifest.version !== 'candidate33'
      || hash(caseBytes) !== proposal.casesSha256 || hash(manifestBytes) !== proposal.sourceManifestSha256
      || proposal.maximumNewCalls !== 20 || proposal.purposeMaximums.PLATFORM_GENERATION !== 12
      || proposal.models.enhancement !== 'deepseek-flash' || proposal.parameters.temperature !== 0.2
      || proposal.parameters.max_tokens !== 10000 || proposal.parameters.thinking !== 'disabled') throw Error('Frozen scope or model parameter drift');
  const sample = catalogue.cases.find(item => item.id === caseId);
  if (!sample || sample.files.length || sample.includeExamples !== false || sample.planConfirmation !== null
      || !equal(sample.formats, ['COMPACT', 'STRUCTURED'])) throw Error('Unexpected frozen task');
  for (const row of manifest.sourceFiles) {
    if (hash(await readFile(resolve(row.path))) !== row.sha256) throw Error('Current source differs from frozen candidate33');
  }
  for (const row of manifest.runtimeFiles) {
    if (hash(await readFile(resolve(manifest.runtimeSnapshot, row.path))) !== row.sha256) throw Error('Frozen runtime drift');
  }
  const protocol = await json(resolve(ledger, 'protocol.json'));
  const batch = protocol.activeBatch;
  if (!batch || batch.version !== 'candidate33-live' || batch.sourceCandidate !== 'candidate33'
      || batch.status !== 'OPEN' || batch.authorizationStatus !== 'APPROVED_BY_USER'
      || batch.callsBefore !== 369 || batch.maximumNewCalls !== 20
      || batch.purposeMaximums?.PLATFORM_GENERATION !== 12 || batch.qualityRetriesAllowed !== false
      || protocol.maximumActualModelCalls !== 399 || protocol.phaseLimits?.candidateValidation !== 319
      || protocol.phaseLimits?.baseline !== 80 || batch.sourceManifestSha256 !== hash(manifestBytes)) throw Error('Incorrect or closed approved candidate33 batch');
  if (!/^[A-Za-z0-9_-]+\.json$/.test(batch.casesFile ?? '')) throw Error('Invalid approved request-case path');
  const requestCases = await readFile(resolve(ledger, batch.casesFile));
  if (hash(requestCases) !== batch.casesSha256) throw Error('Approved flat request-case hash drift');
  const rows = JSON.parse(requestCases.toString('utf8'));
  const flat = Array.isArray(rows) ? rows : rows.cases;
  if (!Array.isArray(flat) || !formats.every(format => flat.some(row => row.rawPrompt === sample.rawPrompt
    && row.promptFormat === format))) throw Error('Browser task not present in approved flat case scope');
  const traceFiles = (await readdir(resolve(ledger, 'traces'))).filter(name => /^\d+\.json$/.test(name));
  let candidateCount = 0;
  let generationCount = 0;
  for (const name of traceFiles) {
    let trace;
    try { trace = await json(resolve(ledger, 'traces', name)); }
    catch { candidateCount++; continue; } // An incomplete reservation never creates free allowance.
    if (trace.phase === 'candidateValidation') candidateCount++;
    if (trace.version === 'candidate33-live' && trace.purpose === 'PLATFORM_GENERATION') generationCount++;
  }
  const maximumActual = Math.min(protocol.maximumActualModelCalls, batch.callsBefore + batch.maximumNewCalls,
    batch.maximumActualCallsAfterBatch ?? batch.callsBefore + batch.maximumNewCalls);
  if (traceFiles.length > maximumActual || candidateCount > 319 || generationCount > 12
      || (needsModelCall && (traceFiles.length >= maximumActual || candidateCount >= 319 || generationCount >= 12)))
    throw Error('Approved actual/stage/purpose allowance exhausted');
  return { sample, caseSha256: hash(caseBytes), sourceManifestSha256: hash(manifestBytes),
    observedCounts: { actual: traceFiles.length, candidate: candidateCount, platformGeneration: generationCount } };
}

// A closed/unauthorized batch fails here before connecting to the browser or reserving an operation.
await preflight();
const account = await json('tmp/local-acceptance/account.json');
const require = createRequire(resolve('apps/web/package.json'));
const { chromium } = require('@playwright/test');
const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
const context = browser.contexts()[0];
if (!context) throw Error('Root-authenticated CDP context unavailable');
let page;
let currentOutput;
let activeFormat;
let requestAllowed = false;
let requestCount = 0;
let guardFailure;
let pendingCapture;
let lastResponse;
let rejectGuard;
const rejected = new Promise((_, reject) => { rejectGuard = reject; });
void rejected.catch(() => undefined);

const api = async path => page.evaluate(async path => {
  const response = await fetch(path, { signal: AbortSignal.timeout(30_000) });
  return { status: response.status, body: await response.json() };
}, path);
const snapshot = () => page.evaluate(async () => {
  const { useOptimizationStore } = await import('/src/stores/optimization.ts');
  const store = useOptimizationStore();
  return JSON.parse(JSON.stringify({ rawPrompt: store.rawPrompt, promptFormat: store.promptFormat,
    result: store.result, draft: store.resultDraft ?? null, plan: store.plan ?? null,
    canReuseGenerationSource: store.canReuseGenerationSource, hasPendingPromptFormat: store.hasPendingPromptFormat }));
});

async function attachPage(firstFormat) {
  if (firstFormat === 'COMPACT') {
    for (const existing of context.pages()) {
      if (existing.url().startsWith(origin) && await existing.evaluate(() => window.name).catch(() => '') === pageName)
        throw Error('Frozen task page already exists; do not replay COMPACT');
    }
    page = await context.newPage();
    await page.goto(`${origin}/workbench`, { waitUntil: 'domcontentloaded' });
    await page.evaluate(name => {
      window.name = name;
      localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: false, introSeen: true }));
    }, pageName);
    await page.reload({ waitUntil: 'domcontentloaded' });
  } else {
    for (const existing of context.pages()) {
      if (existing.url().startsWith(`${origin}/workbench`) && await existing.evaluate(() => window.name).catch(() => '') === pageName) page = existing;
    }
    if (!page) throw Error('Original COMPACT page required; never reconstruct its successful request or result');
  }
  page.setDefaultTimeout(20_000);
  const me = await api('/api/v1/auth/me');
  if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId
      || (account.workspaceId && me.body.data.workspaceId !== account.workspaceId)) throw Error('Canonical account mismatch');
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin });
}

async function installGuard() {
  await page.route('**/api/v1/optimizations**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() !== 'POST' || !['/api/v1/optimizations', '/api/v1/optimizations/plan'].includes(path)) {
      await route.continue(); return;
    }
    try {
      const checked = await preflight(true);
      const body = request.postDataJSON();
      const expected = { rawPrompt: checked.sample.rawPrompt, modelId,
        context: { customDescription: '', files: [] }, enhancement: { templateCode: 'AUTO', includeConversationHistory: false,
          includePermissionBoundaries: true, includeExamples: false, promptFormat: activeFormat },
        conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] }, planConfirmation: null };
      if (!requestAllowed || path !== '/api/v1/optimizations' || !equal(body, expected)) throw Error('Unexpected task, options, natural Plan or extra generation');
      requestAllowed = false;
      requestCount++;
      await save(resolve(currentOutput, 'request.private.json'), body);
      await save(resolve(currentOutput, 'request-body.private.bin'), request.postDataBuffer());
      await route.continue();
    } catch (error) {
      guardFailure = error.message;
      await save(resolve(currentOutput, 'guard-failure.json'), { reason: error.message }).catch(() => undefined);
      await route.abort('blockedbyclient');
      rejectGuard(Error(error.message));
    }
  });
  page.on('response', response => {
    if (response.request().method() !== 'POST' || new URL(response.url()).pathname !== '/api/v1/optimizations') return;
    const destination = currentOutput;
    pendingCapture = (async () => {
      const bytes = await response.body();
      lastResponse = { status: response.status(), body: JSON.parse(bytes.toString('utf8')) };
      await save(resolve(destination, 'response.private.bin'), bytes);
      await save(resolve(destination, 'platform.private.json'), lastResponse);
    })();
  });
}

async function clipboard() {
  await preflight();
  await page.bringToFront();
  await page.getByRole('button', { name: '复制提示词', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.copy-main-button')?.textContent?.includes('已复制'));
  const text = await page.evaluate(() => navigator.clipboard.readText());
  // Native readText is serialized unchanged as UTF-8; CRLF normalization is comparison-only.
  await save(resolve(currentOutput, 'native-copy.md'), Buffer.from(text, 'utf8'));
  return text;
}

async function historyBefore() {
  const response = await api('/api/v1/optimization-history?current=1&size=20');
  if (response.status !== 200) throw Error('Canonical history unavailable');
  return { total: response.body.data.total, ids: response.body.data.records.map(row => row.id) };
}

async function checkHistory(result, sample, before) {
  await preflight();
  const history = await api('/api/v1/optimization-history?current=1&size=20');
  if (history.status !== 200) throw Error('History list unavailable');
  for (const row of history.body.data.records) {
    if (before.ids.includes(row.id)) continue;
    const detail = await api(`/api/v1/optimization-history/${row.id}`);
    const actual = detail.body.data;
    if (detail.status !== 200 || actual.rawPrompt !== sample.rawPrompt || actual.optimizedPrompt !== result.optimizedPrompt) continue;
    for (const key of ['promptFormat', 'sections', 'ambiguities', 'appliedConstraints']) {
      if (!equal(actual[key], result[key])) throw Error(`History drift: ${key}`);
    }
    await save(resolve(currentOutput, 'history.private.json'), detail);
    return { bodyMatches: true, formatMatches: true, sectionsMatch: true, ambiguitiesMatch: true,
      constraintsMatch: true, newRecordFound: true, totalDelta: history.body.data.total - before.total };
  }
  throw Error('Matching newly saved history not found');
}

async function perform(format) {
  const checked = await preflight(true);
  activeFormat = format;
  currentOutput = resolve(work, caseId, format);
  await save(resolve(currentOutput, 'operation-reserved.json'), { candidate: 'candidate33', caseId, format,
    onceOnly: true, qualityRetriesAllowed: false, ...checked, startedAt: new Date().toISOString() });
  lastResponse = undefined;
  pendingCapture = undefined;
  guardFailure = undefined;
  const before = await historyBefore();
  await save(resolve(currentOutput, 'history-before.private.json'), before);
  if (format === 'COMPACT') {
    await preflight();
    await page.waitForFunction(() => document.querySelector('#workbench-model') !== null);
    // Select only a published model. No source/result/answer is injected into the page.
    await page.evaluate(async id => {
      const { useOptimizationStore } = await import('/src/stores/optimization.ts');
      const store = useOptimizationStore();
      if (store.isLoadingModels || !store.availableModels.length) await store.refreshAvailableModels();
      if (!store.availableModels.some(model => model.id === id)) throw Error('Frozen model unavailable');
      store.selectedModelId = id;
    }, modelId);
    await page.getByLabel('原始提示词', { exact: true }).fill(checked.sample.rawPrompt);
    const initial = await snapshot();
    if (initial.promptFormat !== 'COMPACT' || initial.result) throw Error('New task did not default to natural mode');
  } else {
    const previousOutput = resolve(work, caseId, 'COMPACT');
    const original = await json(resolve(previousOutput, 'platform.private.json'));
    const originalCheck = await json(resolve(previousOutput, 'check.json'));
    const initial = await snapshot();
    if (!originalCheck.success || original.status !== 200 || initial.rawPrompt !== checked.sample.rawPrompt
        || initial.promptFormat !== 'COMPACT' || !equal(initial.result, original.body.data)
        || !initial.canReuseGenerationSource || initial.draft !== null) throw Error('Live original COMPACT state required for mode regeneration');
    const callsBefore = requestCount;
    const countsBefore = checked.observedCounts;
    await preflight();
    await page.locator('label.switch-control').filter({ hasText: '结构化提示词' }).click();
    const selected = await snapshot();
    const afterSelection = await preflight();
    if (!equal(selected.result, initial.result) || selected.draft !== initial.draft || !equal(selected.plan, initial.plan)
        || selected.promptFormat !== 'STRUCTURED' || !selected.hasPendingPromptFormat
        || requestCount !== callsBefore || !equal(afterSelection.observedCounts, countsBefore)) throw Error('Selecting mode changed content or caused a model call');
    await save(resolve(currentOutput, 'selection-preserves-current.private.json'), { before: initial, after: selected });
    await save(resolve(currentOutput, 'selection-check.json'), { currentBodyUnchanged: true,
      currentSectionsUnchanged: true, noNewModelHttpRequest: true, canonicalTraceCountsUnchanged: true,
      explicitRegenerationRequired: true });
  }
  await preflight(true);
  requestAllowed = true;
  const waiting = Promise.race([page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/optimizations', { timeout: 230_000 }), rejected]);
  const label = format === 'COMPACT' ? '直接增强提示词' : '基于原始需求按所选模式重新增强';
  await page.getByRole('button', { name: label, exact: true }).click();
  await waiting;
  await pendingCapture;
  if (guardFailure) throw Error(guardFailure);
  if (lastResponse?.status !== 200) throw Error(`Generation failed HTTP ${lastResponse?.status}`);
  const result = lastResponse.body.data;
  if (result.provider.mock !== false || result.promptFormat !== format) throw Error('Unexpected provider or actual output mode');
  await page.getByRole('button', { name: '复制提示词', exact: true }).waitFor({ state: 'visible' });
  const current = await snapshot();
  await save(resolve(currentOutput, 'current-store.private.json'), current);
  if (!equal(current.result, result) || current.rawPrompt !== checked.sample.rawPrompt
      || current.promptFormat !== format || current.hasPendingPromptFormat) throw Error('Store/response mismatch');
  if (format === 'STRUCTURED') {
    const first = await json(resolve(work, caseId, 'COMPACT/request.private.json'));
    const second = await json(resolve(currentOutput, 'request.private.json'));
    if (!equal(second, { ...first, enhancement: { ...first.enhancement, promptFormat: 'STRUCTURED' } }))
      throw Error('Mode regeneration changed original task or confirmed information');
  }
  const copied = await clipboard();
  if (newline(copied) !== newline(result.optimizedPrompt)) throw Error('Native clipboard differs from actual result');
  const history = await checkHistory(result, checked.sample, before);
  await page.screenshot({ path: resolve(currentOutput, 'current-result.private.png'), fullPage: true });
  const check = { candidate: 'candidate33', caseId, format, success: true, actualBrowserGeneration: true,
    generationAction: format === 'COMPACT' ? 'direct' : 'explicit-mode-regeneration', nativeCopyMatches: true,
    currentStoreMatchesResponse: true, history, copySha256: hash(copied), sectionsSha256: hash(JSON.stringify(result.sections)),
    originalInputSha256: hash(checked.sample.rawPrompt), sourceManifestSha256: checked.sourceManifestSha256,
    casesSha256: checked.caseSha256, requestSha256: hash(await readFile(resolve(currentOutput, 'request.private.json'))),
    responseSha256: hash(await readFile(resolve(currentOutput, 'response.private.bin'))), accountReused: true,
    comparisonOnlyNormalizesCrLf: true, finishedAt: new Date().toISOString() };
  await save(resolve(currentOutput, 'check.json'), check);
  await save(resolve(pub, 'live-checks', `${caseId}-${format}.json`), check);
  console.log(JSON.stringify({ caseId, format, success: true, copySha256: check.copySha256 }));
}

try {
  await attachPage(formats[0]);
  await installGuard();
  for (const format of formats) await perform(format);
} catch (error) {
  requestAllowed = false;
  await Promise.resolve(pendingCapture).catch(() => undefined);
  if (currentOutput) {
    await save(resolve(currentOutput, 'failure.private.json'), { reason: error.message, guardFailure,
      observedHttpRequests: requestCount, finishedAt: new Date().toISOString() }).catch(() => undefined);
    if (page) {
      await save(resolve(currentOutput, 'failure-store.private.json'), await snapshot()).catch(() => undefined);
      await page.screenshot({ path: resolve(currentOutput, 'failure.private.png'), fullPage: true }).catch(() => undefined);
    }
  }
  console.log(JSON.stringify({ caseId, format: activeFormat, success: false, reason: error.message }));
  throw error;
} finally {
  // Retain the original page and its real request snapshot between explicit run-step invocations.
  if (page) await page.unroute('**/api/v1/optimizations**').catch(() => undefined);
  await browser.close();
}
