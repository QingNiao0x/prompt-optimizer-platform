import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { checkResearchLength } from '../../../scripts/me00a-work-checks.mjs';
const require = createRequire(resolve('apps/web/package.json'));
const root = resolve('tmp/me00a-20261012'), output = resolve(root, 'candidate15-real');
const [vendor, arm] = process.argv.slice(2);
if (!['doubao', 'deepseek'].includes(vendor) || !['raw', 'direct', 'plan', 'review'].includes(arm)) throw new Error('Bounded capture target required');
const submission = JSON.parse(await readFile(resolve(output, 'web', `${vendor}-${arm}-submission.json`), 'utf8'));
const tracePath = resolve(root, 'traces', `${String(submission.attemptNumber).padStart(3, '0')}.json`);
const browser = await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9336');
try {
  const page = browser.contexts()[0].pages().find(p => new URL(p.url()).hostname === (vendor === 'deepseek' ? 'chat.deepseek.com' : 'www.doubao.com'));
  if (!page) throw new Error('Conversation page unavailable');
  if (new URL(page.url()).pathname !== submission.conversationPath) {
    // Doubao assigns a conversation route after the first response; bind only its one pending exact-payload submission.
    const traceNames = (await readdir(resolve(root, 'traces'))).filter(name => /^\d+\.json$/.test(name));
    const traces = await Promise.all(traceNames.map(async name => JSON.parse(await readFile(resolve(root, 'traces', name), 'utf8'))));
    const pending = traces.filter(t => t.version === 'candidate15' && t.vendor === vendor && t.status === 'UI_SUBMITTED');
    if (vendor !== 'doubao' || !/^\/chat\/(?:local_\d+)?$/.test(submission.conversationPath) || !/^\/chat\/\d+$/.test(new URL(page.url()).pathname)
      || pending.length !== 1 || pending[0].attemptNumber !== submission.attemptNumber
      || !submission.literalOutgoingPayloadMatches?.some(item => item.exact)) throw new Error('Conversation changed before capture');
    submission.lateAssignedConversationPath = new URL(page.url()).pathname;
    submission.lateRouteBinding = 'ONE_PENDING_EXACT_PAYLOAD_SUBMISSION_IN_OWNED_NEW_CHAT';
  }
  const response = vendor === 'deepseek' ? page.locator('.ds-assistant-message-main-content')
    : page.getByTestId('receive_message');
  const expectedConversationPath = new URL(page.url()).pathname;
  const actions = vendor === 'deepseek' ? response.locator('..').locator('..').locator('[role=button]') : page.getByTestId('message_action_regenerate');
  const until = Date.now() + 300000;
  let last = '', stable = 0;
  while (Date.now() < until) {
    if (new URL(page.url()).pathname !== expectedConversationPath) throw new Error('Conversation changed during capture; no work bound to this attempt');
    const count = await response.count();
    const text = count === 1 ? await response.innerText() : '';
    const doneControls = await actions.count();
    if (text && text === last && doneControls >= 1) stable++; else stable = 0;
    last = text;
    if (stable >= 2) break;
    await page.waitForTimeout(1500);
  }
  if (stable < 2) {
    submission.status = 'UI_COMPLETION_TIMEOUT'; submission.endedAt = new Date().toISOString();
    submission.failure = 'No stable completed assistant response within capture deadline; no retry.';
    await writeFile(tracePath, JSON.stringify(submission, null, 2)); throw new Error(submission.failure);
  }
  const completionObservedAt = new Date().toISOString();
  const visibleSegments = vendor === 'doubao'
    ? await response.getByTestId('message_text_content').allTextContents() : [await response.innerText()];
  const renderedText = await response.innerText(), renderedHtml = await response.innerHTML();
  const copy = vendor === 'deepseek' ? actions.first() : page.getByTestId('receive_message').getByTestId('message_action_copy');
  if (vendor === 'deepseek') {
    await copy.hover(); await page.waitForTimeout(400);
    if (!(await page.locator('[role=tooltip],.ds-tooltip').allTextContents()).some(text => text.trim() === '复制')) throw new Error('Copy action not verified');
  }
  await page.evaluate(() => {
    window.__me00aCopyText = null;
    window.__me00aOriginalClipboardWrite = navigator.clipboard.writeText;
    window.__me00aOriginalClipboardRichWrite = navigator.clipboard.write;
    window.__me00aOriginalExecCommand = document.execCommand;
    navigator.clipboard.writeText = async text => { window.__me00aCopyText = text; window.__me00aCopyMode = 'WRITE_TEXT'; };
    navigator.clipboard.write = async items => {
      for (const item of items) if (item.types.includes('text/plain')) {
        window.__me00aCopyText = await (await item.getType('text/plain')).text(); window.__me00aCopyMode = 'CLIPBOARD_ITEM_TEXT_PLAIN';
      }
    };
    document.execCommand = function(command, ...args) {
      if (command.toLowerCase() !== 'copy') return window.__me00aOriginalExecCommand.call(document, command, ...args);
      const active = document.activeElement;
      const selected = typeof active?.value === 'string' ? active.value.slice(active.selectionStart ?? 0, active.selectionEnd ?? active.value.length) : window.getSelection()?.toString();
      window.__me00aCopyText = selected; window.__me00aCopyMode = 'EXEC_COMMAND_SELECTED_TEXT'; return !!selected;
    };
  });
  let work, copyMode;
  try {
    await copy.click();
    await page.waitForFunction(() => typeof window.__me00aCopyText === 'string', null, { timeout: 6000 });
    work = await page.evaluate(() => window.__me00aCopyText);
    copyMode = await page.evaluate(() => window.__me00aCopyMode);
  } finally {
    await page.evaluate(() => {
      navigator.clipboard.writeText = window.__me00aOriginalClipboardWrite;
      navigator.clipboard.write = window.__me00aOriginalClipboardRichWrite;
      document.execCommand = window.__me00aOriginalExecCommand;
      delete window.__me00aOriginalClipboardWrite; delete window.__me00aOriginalClipboardRichWrite;
      delete window.__me00aOriginalExecCommand; delete window.__me00aCopyText; delete window.__me00aCopyMode;
    });
  }
  if (!work || !renderedText) throw new Error('Empty official copy or rendered work');
  if (new URL(page.url()).pathname !== expectedConversationPath) throw new Error('Conversation changed before saving work');
  const currentProfile = vendor === 'deepseek' ? { ...submission.request.profile,
    deepThinking: await page.locator('[aria-pressed]').filter({ hasText: '深度思考' }).getAttribute('aria-pressed'),
    smartSearch: await page.locator('[aria-pressed]').filter({ hasText: '智能搜索' }).getAttribute('aria-pressed') }
    : { ...submission.request.profile, visibleLabel: await page.getByTestId('chat_input_action_model').innerText(), mode: await page.getByTestId('chat_input_action_mode').innerText() };
  if (!isDeepStrictEqual(currentProfile, submission.request.profile)) throw new Error('Visible settings changed while generating');
  const name = `${vendor}-${arm}`, folder = arm === 'review' ? 'reviews' : 'works';
  await mkdir(resolve(output, folder), { recursive: true });
  const save = (suffix, value) => writeFile(resolve(output, folder, `${name}${suffix}`), typeof value === 'string' ? value : JSON.stringify(value, null, 2), { flag: 'wx' });
  await save('.md', work); await save('.rendered.txt', renderedText); await save('.rendered.html', renderedHtml);
  await response.screenshot({ path: resolve(output, folder, `${name}.png`), timeout: 20000 });
  const metadata = { vendor, arm, attemptNumber: submission.attemptNumber, inputSha256: submission.request.inputSha256,
    workSha256: createHash('sha256').update(work).digest('hex'), profile: currentProfile,
    capture: 'OFFICIAL_ASSISTANT_COPY_ACTION_WITH_CLIPBOARD_WRITE_CAPTURE_NO_OS_CLIPBOARD_READ_OR_WRITE', copyMode,
    copiedAndRenderedVersionsBothRetained: true, completionObservedAt,
    visibleResponseSegments: visibleSegments, visibleSegmentCount: visibleSegments.length,
    renderedScope: vendor === 'doubao' ? 'ASSISTANT_CONTAINER_INCLUDING_VISIBLE_PROGRESS_AND_UI_NOT_USED_FOR_COUNT' : 'ASSISTANT_FINAL_ANSWER',
    observedCompletionUpperBoundMs: Date.parse(completionObservedAt) - Date.parse(submission.submittedAt),
    exactServerLatencyAndTokens: 'UNKNOWN', noRegeneration: true,
    literalOutgoingPayloadMatches: submission.literalOutgoingPayloadMatches,
    independentLength: arm === 'review' ? null : checkResearchLength(work) };
  await save('.json', metadata);
  Object.assign(submission, { status: 'UI_COMPLETED', completionObservedAt, work, workSha256: metadata.workSha256,
    observedCompletionUpperBoundMs: metadata.observedCompletionUpperBoundMs });
  await writeFile(tracePath, JSON.stringify(submission, null, 2));
  console.log(JSON.stringify({ captured: name, attempt: submission.attemptNumber, officialCopiedChars: work.length, length: metadata.independentLength }));
} finally { await browser.close(); }
