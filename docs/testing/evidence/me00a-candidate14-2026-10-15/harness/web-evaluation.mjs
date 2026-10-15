import { createRequire } from 'node:module';
import { readFile, writeFile, readdir, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { resolve } from 'node:path';
const require = createRequire(resolve('apps/web/package.json'));
const root = resolve('tmp/me00a-20261012'), output = resolve(root, 'candidate14-real');
const publicRoot = resolve('docs/testing/evidence/me00a-candidate14-2026-10-15');
const hash = value => createHash('sha256').update(value).digest('hex');
const load = async path => JSON.parse(await readFile(path, 'utf8'));
const saveNew = (path, value) => writeFile(path, typeof value === 'string' ? value : JSON.stringify(value, null, 2), { flag: 'wx' });
const [stage, vendor, arm] = process.argv.slice(2);
const host = vendor === 'deepseek' ? 'chat.deepseek.com' : vendor === 'doubao' ? 'www.doubao.com' : null;
if (!['freeze', 'submit'].includes(stage) || (stage !== 'freeze' && (!host || !['raw', 'direct', 'plan', 'review'].includes(arm)))) throw new Error('Explicit bounded web stage required');
const protocol = await load(resolve(root, 'protocol.json')), batch = protocol.activeBatch;
if (batch.version !== 'candidate14' || batch.status !== 'OPEN' || batch.authorizationAmendment !== 'protocol-amendment-12.json'
  || batch.maximumNewCalls !== 14 || batch.maximumExecutionCalls !== 6 || batch.maximumReviewCalls !== 2
  || protocol.maximumActualModelCalls !== 263 || protocol.phaseLimits.candidateValidation !== 183) throw new Error('Approved two-vendor scope drift');
const manifest = await load(resolve(publicRoot, 'candidate14-source.json'));
for (const item of [...manifest.sourceFiles, ...manifest.classFiles, ...manifest.policyFiles]) {
  if (hash(await readFile(resolve(item.path))) !== item.sha256) throw new Error('Frozen source changed');
}
const lock = await load(resolve(output, 'copies-lock.json'));
const inputs = {};
for (const name of ['raw', 'direct', 'plan']) {
  inputs[name] = await readFile(resolve(output, name, 'execution-input.md'), 'utf8');
  if (hash(inputs[name]) !== lock[name].sha256 || (name !== 'raw' && inputs[name] !== await readFile(resolve(output, name, 'copied.md'), 'utf8'))) throw new Error('Literal input drift');
}
await mkdir(resolve(output, 'web'), { recursive: true });
/** Read only visible controls; hidden model revisions, backend calls and tokens remain unknown. */
async function profile(page, vendor) {
  return vendor === 'deepseek' ? { vendor, surface: 'WEB', origin: 'https://chat.deepseek.com', visibleLabel: 'DeepSeek', mode: 'default',
    deepThinking: await page.locator('[aria-pressed]').filter({ hasText: '深度思考' }).getAttribute('aria-pressed'),
    smartSearch: await page.locator('[aria-pressed]').filter({ hasText: '智能搜索' }).getAttribute('aria-pressed'),
    exactModelVersion: 'NOT_EXPOSED', temperature: 'NOT_EXPOSED', maxTokens: 'NOT_EXPOSED', memoryAndPersonalization: 'NOT_EXPOSED',
  } : { vendor, surface: 'WEB', origin: 'https://www.doubao.com',
    visibleLabel: await page.getByTestId('chat_input_action_model').innerText(),
    mode: await page.getByTestId('chat_input_action_mode').innerText(), search: 'NOT_EXPOSED_IN_SELECTED_MODE',
    exactModelVersion: 'NOT_EXPOSED', temperature: 'NOT_EXPOSED', maxTokens: 'NOT_EXPOSED', memoryAndPersonalization: 'NOT_EXPOSED' };
}
/** Reserve the UI attempt immediately before clicking; failures consume the same bounded slot. */
async function reserve(record) {
  const names = (await readdir(resolve(root, 'traces'))).filter(n => /^\d+\.json$/.test(n));
  const previous = await Promise.all(names.map(n => load(resolve(root, 'traces', n))));
  const current = previous.filter(t => t.version === 'candidate14');
  if (current.some(t => t.label === record.label)) throw new Error('Existing submission is not repeated');
  if (previous.filter(t => t.phase === 'candidateValidation').length >= protocol.phaseLimits.candidateValidation
    || current.filter(t => t.purpose === record.purpose).length >= batch.purposeMaximums[record.purpose]
    || current.length >= batch.maximumNewCalls) throw new Error('Phase, purpose or batch budget exhausted');
  const max = Math.min(protocol.maximumActualModelCalls, batch.maximumActualCallsAfterBatch, batch.callsBefore + batch.maximumNewCalls);
  const number = Math.max(0, ...names.map(n => Number(n.replace('.json', '')))) + 1;
  if (number > max) throw new Error('Total budget exhausted');
  const path = resolve(root, 'traces', `${String(number).padStart(3, '0')}.json`);
  await saveNew(path, { ...record, attemptNumber: number, reservedAt: new Date().toISOString() });
  return { number, path };
}
const browser = await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9336');
try {
  if (stage === 'freeze') {
    const profiles = {};
    for (const name of ['doubao', 'deepseek']) {
      const page = browser.contexts()[0].pages().find(p => new URL(p.url()).hostname === (name === 'deepseek' ? 'chat.deepseek.com' : 'www.doubao.com'));
      if (!page) throw new Error('Authenticated web page missing');
      profiles[name] = await profile(page, name);
    }
    if (profiles.deepseek.deepThinking !== 'false' || profiles.deepseek.smartSearch !== 'false'
      || profiles.doubao.visibleLabel !== '豆包 快速' || profiles.doubao.mode !== '对话') throw new Error('Visible mode changed');
    const freeze = { frozenAt: new Date().toISOString(), profiles, lock, case: 'Q05', actualQuestions: 4, examplesEnabled: false,
      calls: { platformGenerationMaximum: 6, executionMaximum: 6, reviewMaximum: 2 },
      review: 'two distinct vendors, independent new chats, same anonymous six-work pack, no shared opinions',
      interpretation: 'single-work fault screening per arm; website revision and hidden settings are unknown; no stable win-rate or pure model-capability conclusion',
      newChatsPerArm: true, noQualityRetries: true, noEvaluatorAnswerOrScaleRepair: true };
    await saveNew(resolve(output, 'web-freeze.json'), freeze);
    await saveNew(resolve(publicRoot, 'web-freeze.json'), freeze);
    console.log(JSON.stringify({ frozen: true, profiles, inputHashes: lock }));
  } else {
    const freeze = await load(resolve(output, 'web-freeze.json'));
    const existingTraceNames = (await readdir(resolve(root, 'traces'))).filter(name => /^\d+\.json$/.test(name));
    const existingTraces = await Promise.all(existingTraceNames.map(name => load(resolve(root, 'traces', name))));
    if (existingTraces.some(trace => trace.version === 'candidate14' && trace.vendor === vendor && trace.status === 'UI_SUBMITTED'))
      throw new Error('Capture the existing vendor submission before opening another chat');
    const page = browser.contexts()[0].pages().find(p => new URL(p.url()).hostname === host);
    if (!page) throw new Error('Required web page missing');
    await page.bringToFront();
    if (vendor === 'deepseek') await page.getByText('开启新对话', { exact: true }).click();
    else await page.getByTestId('flow_chat_sidebar').getByTestId('create_conversation_button').click();
    const editor = vendor === 'deepseek' ? page.locator('textarea') : page.getByTestId('chat_input_input').locator('[contenteditable=true]');
    await editor.waitFor({ state: 'visible' });
    await page.waitForURL(url => vendor === 'deepseek' ? url.pathname === '/' : /^\/chat\/?$/.test(url.pathname), { timeout: 10000 });
    const existingResponses = vendor === 'deepseek' ? page.locator('.ds-assistant-message-main-content') : page.getByTestId('receive_message');
    if (await existingResponses.count() !== 0) throw new Error('New chat contains a prior response');
    const currentProfile = await profile(page, vendor);
    if (!isDeepStrictEqual(currentProfile, freeze.profiles[vendor])) throw new Error('Visible settings differ from freeze');
    const input = arm === 'review' ? await readFile(resolve(output, 'review-pack.md'), 'utf8') : inputs[arm];
    if (arm === 'review') {
      const reviewLock = await load(resolve(output, 'review-lock.json'));
      if (hash(input) !== reviewLock.inputSha256) throw new Error('Anonymous reviewer pack drift');
    }
    await editor.fill(input);
    const semanticEditor = await editor.evaluate(el => el.tagName === 'TEXTAREA' ? el.value : [...el.children].map(p => p.textContent).join('\n'));
    if (semanticEditor !== input) throw new Error('Input differs before submission');
    const send = vendor === 'deepseek' ? page.locator('.ds-button--primary.ds-button--circle') : page.getByTestId('chat_input_send_button');
    if (await send.count() !== 1 || !await send.isVisible() || await send.getAttribute('aria-disabled') === 'true'
      || (vendor === 'deepseek' && (await send.getAttribute('class')).includes('--disabled')) || await send.isDisabled()) throw new Error('Send control unavailable');
    const literalTransportMatches = [];
    const inspect = (value, path = '$', depth = 0) => {
      if (depth > 20) return;
      if (typeof value === 'string') {
        if (value === input) literalTransportMatches.push({ path, sha256: hash(value), exact: true });
        else if (value.startsWith('{') || value.startsWith('[')) { try { inspect(JSON.parse(value), path + ':json', depth + 1); } catch {} }
      } else if (Array.isArray(value)) value.forEach((v, i) => inspect(v, `${path}[${i}]`, depth + 1));
      else if (value && typeof value === 'object') Object.entries(value).forEach(([k, v]) => inspect(v, `${path}.${k}`, depth + 1));
    };
    page.on('request', request => {
      if (request.method() !== 'POST' || !new URL(request.url()).hostname.endsWith(vendor === 'deepseek' ? 'deepseek.com' : 'doubao.com')) return;
      try { inspect(request.postDataJSON()); } catch {}
    });
    const record = { phase: 'candidateValidation', version: 'candidate14', purpose: arm === 'review' ? 'REVIEW' : 'EXECUTION',
      label: `${vendor}-web-${arm}`, vendor, arm, surface: 'WEB', accountingUnit: 'UI_SUBMISSION_ATTEMPT',
      request: { input, inputSha256: hash(input), profile: currentProfile }, startedAt: new Date().toISOString(),
      newChatBeforeSubmission: true, editorEqualsLiteralInput: true, hiddenBackendRequests: 'UNKNOWN', tokens: 'UNKNOWN', status: 'RESERVED' };
    const reservation = await reserve(record);
    record.attemptNumber = reservation.number;
    try {
      await send.click();
      record.status = 'UI_SUBMITTED'; record.submittedAt = new Date().toISOString();
      await page.waitForTimeout(3000);
      record.literalOutgoingPayloadMatches = literalTransportMatches;
      record.conversationPath = new URL(page.url()).pathname;
      await writeFile(reservation.path, JSON.stringify(record, null, 2));
      await saveNew(resolve(output, 'web', `${vendor}-${arm}-submission.json`), record);
      console.log(JSON.stringify({ attempt: reservation.number, vendor, arm, status: record.status, exactOutgoingPayloadMatches: literalTransportMatches.length }));
    } catch (error) {
      record.status = 'UI_SUBMISSION_FAILED'; record.failure = error.message; record.endedAt = new Date().toISOString();
      await writeFile(reservation.path, JSON.stringify(record, null, 2)); throw error;
    }
  }
} finally { await browser.close(); }
