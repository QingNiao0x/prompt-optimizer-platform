/** 真实合成账号与普通业务入口。证据不保存密码、Cookie或浏览器持久会话。 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { directory, root, runtime, readJson, save, exists, digest } from './evaluation.mjs';

const stage = process.argv[2];
assert(['current', 'candidate', 'workflow', 'daily'].includes(stage));
const profile = stage === 'current' ? 'core-repair-v4' : stage === 'candidate' ? 'internal-design-v1'
  : (await readJson(resolve(directory, 'adoption.json'))).runtimeVersion;
const require = createRequire(resolve(root, 'apps/web/package.json'));
const { chromium, expect } = require('@playwright/test');
const { createServer } = await import(new URL('../../../../apps/web/node_modules/vite/dist/node/index.js', import.meta.url));
const ownFrontend = stage !== 'daily';
const frontend = ownFrontend ? 'http://127.0.0.1:5179' : 'http://127.0.0.1:5173';
const vite = ownFrontend ? await createServer({ root: resolve(root, 'apps/web'), logLevel: 'error',
  server: { host: '127.0.0.1', port: 5179, strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:9047', changeOrigin: true } } } }) : undefined;
await vite?.listen();
await mkdir(runtime, { recursive: true });
await mkdir(resolve(directory, 'results'), { recursive: true });
await mkdir(resolve(directory, 'workflow'), { recursive: true });
const account = await readJson(resolve(root, 'tmp/local-acceptance/account.json'));
let password = process.env.POP_EVAL_PASSWORD;
delete process.env.POP_EVAL_PASSWORD;
assert(account.identifier.startsWith('qa_') && password);
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 },
  permissions: ['clipboard-read', 'clipboard-write'], locale: 'zh-CN' });
const page = await context.newPage();
const lines = createInterface({ input: process.stdin, output: process.stdout, terminal: false });
const samples = (await readJson(resolve(directory, 'cases.json'))).cases;
if (await exists(resolve(directory, 'holdout.json'))) samples.push(...(await readJson(resolve(directory, 'holdout.json'))).cases);
const runId = `${stage}-${Date.now()}`;

const bind = async (sample, phase, suffix) => {
  const response = await fetch('http://127.0.0.1:9078/job', { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ caseId: sample.id, phase, job: `${stage}_${sample.id}_${suffix}` }) });
  assert.equal(response.status, 200);
};

const post = async (path, input) => {
  const token = (await context.cookies()).find(cookie => cookie.name === 'XSRF-TOKEN')?.value;
  const response = await context.request.post(frontend + path, { headers: { 'X-XSRF-TOKEN': decodeURIComponent(token ?? '') },
    data: input, timeout: 250_000 });
  return { status: response.status(), body: await response.json() };
};

const requestFor = sample => ({ rawPrompt: sample.rawPrompt, modelId: 'deepseek:deepseek-flash',
  context: { customDescription: sample.description ?? '', files: sample.files },
  enhancement: { templateCode: 'AUTO', includeConversationHistory: false, includePermissionBoundaries: true, promptFormat: 'COMPACT' },
  conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] }, planConfirmation: null });

/** 登录使用真实验证码及CSRF。由运行本次任务的操作者识读图像，最多三次，不创建替代账号。 */
async function login() {
  await page.goto(frontend + '/login');
  await context.request.get(frontend + '/api/v1/auth/csrf');
  let authenticated = false;
  for (let attempt = 0; attempt < 3; attempt++) {
    const response = await context.request.get(frontend + '/api/v1/auth/captcha');
    assert.equal(response.status(), 200);
    const path = resolve(runtime, `${runId}-captcha-${attempt}.png`);
    await writeFile(path, await response.body(), { flag: 'wx' });
    console.log(JSON.stringify({ event: 'CAPTCHA', path }));
    const captcha = (await lines.question('')).trim();
    const result = await post('/api/v1/auth/login', { identifier: account.identifier, password, captcha });
    if (result.status === 200) { authenticated = true; break; }
    assert.equal(result.body.error?.code, 'CAPTCHA_INVALID', '既有合成账号登录失败');
  }
  password = undefined;
  assert(authenticated);
  const actor = (await (await context.request.get(frontend + '/api/v1/auth/me')).json()).data;
  for (const key of ['userId', 'tenantId', 'workspaceId']) assert.equal(actor[key], account[key]);
  const models = (await (await context.request.get(frontend + '/api/v1/models')).json()).data;
  assert(models.some(model => model.id === 'deepseek:deepseek-flash' && model.provider === 'deepseek'));
  console.log(JSON.stringify({ event: 'AUTHENTICATED', stage, syntheticAccountReused: true }));
}

async function captureHistory(result, path) {
  if (!result.recordId) return;
  const response = await context.request.get(frontend + '/api/v2/optimization-history/' + result.recordId);
  assert.equal(response.status(), 200);
  const history = (await response.json()).data;
  assert.equal(history.optimizedPrompt, result.optimizedPrompt);
  await save(path, { samePrompt: true, history });
}

async function generateApi(sample) {
  const input = requestFor(sample);
  const path = resolve(directory, 'results', `${stage}_${sample.id}.json`);
  if (await exists(path)) return;
  await save(resolve(runtime, `${stage}_${sample.id}.started.json`), { startedAt: new Date().toISOString() });
  await bind(sample, sample.work ? 'enhancement' : 'regression', 'generate');
  const response = await post('/api/v2/optimizations', input);
  await save(path, { caseId: sample.id, stage, input, response });
  if (response.status === 200) {
    assert.equal(response.body.data.chainVersion, profile);
    assert.equal(response.body.data.provider.mock, false);
    assert(response.body.data.processing.completedStages.includes('BODY_GENERATION'));
    await captureHistory(response.body.data, resolve(directory, 'results', `${stage}_${sample.id}_history.json`));
  }
  console.log(JSON.stringify({ event: 'GENERATION', stage, caseId: sample.id, status: response.status }));
}

/** 真实文件选择与目录索引。目标模块与相似模块一起存在，避免用极简材料替代定位。 */
async function prepareFiles(sample) {
  if (sample.id === 'W_CODE') {
    const folder = resolve(runtime, 'SyntheticSurvey');
    for (const file of sample.files.filter(file => file.path.endsWith('.mjs'))) {
      const path = resolve(folder, file.path);
      await mkdir(resolve(path, '..'), { recursive: true });
      await writeFile(path, file.content);
    }
    for (let index = 0; index < 24; index++) {
      const path = resolve(folder, `src/otherModules/survey${index}.mjs`);
      await mkdir(resolve(path, '..'), { recursive: true });
      await writeFile(path, `// 相似模块${index}，与重点人群新增及导入无调用关系\n` + Array.from({ length: 30 }, (_, line) =>
        `export const survey${line} = '其他调查、日期和导入模块的显示标签 ${line}';`).join('\n'));
    }
    // 项目索引使用目录句柄入口。原脚本选中了普通文件上传的回退输入，不能据此等待索引状态。
    const entries = sample.files.filter(file => file.path.endsWith('.mjs'));
    for (let index = 0; index < 24; index++) entries.push({ path: `src/otherModules/survey${index}.mjs`,
      content: `// 相似模块${index}，与重点人群新增及导入无调用关系\n` + Array.from({ length: 30 }, (_, line) =>
        `export const survey${line} = '其他调查、日期和导入模块的显示标签 ${line}';`).join('\n') });
    await page.evaluate(async files => {
      const storage = await navigator.storage.getDirectory();
      const project = await storage.getDirectoryHandle('SyntheticSurvey', { create: true });
      for (const file of files) {
        const parts = file.path.split('/');
        let parent = project;
        for (const name of parts.slice(0, -1)) parent = await parent.getDirectoryHandle(name, { create: true });
        const handle = await parent.getFileHandle(parts.at(-1), { create: true });
        const writer = await handle.createWritable();
        await writer.write(file.content);
        await writer.close();
      }
      Object.defineProperty(window, 'showDirectoryPicker', { configurable: true, value: async () => project });
    }, entries);
    await page.getByRole('button', { name: '添加上下文', exact: true }).click();
    await page.getByRole('menuitem', { name: '选择文件夹', exact: true }).click();
    await expect(page.getByText(/27 个文本文件已完整建立本地索引/)).toBeVisible({ timeout: 40_000 });
    const conventions = sample.files.filter(file => !file.path.endsWith('.mjs'));
    await page.getByTestId('context-file-input').setInputFiles(conventions.map(file => ({
      name: file.path, mimeType: 'text/plain', buffer: Buffer.from(file.content),
    })));
  } else if (sample.files.length) {
    await page.getByTestId('context-file-input').setInputFiles(sample.files.map(file => ({
      name: file.path, mimeType: 'text/plain', buffer: Buffer.from(file.content),
    })));
  }
  const labels = { TASK_TARGET: '处理对象', FACT_REFERENCE: '事实参考', STYLE_REFERENCE: '格式或风格参考', DIAGNOSTIC_REFERENCE: '排查线索' };
  for (const file of sample.files.filter(file => file.purpose && (sample.id !== 'W_CODE' || !file.path.endsWith('.mjs')))) {
    const row = page.locator('.file-list > li').filter({ hasText: file.path });
    await row.locator('.file-purpose .el-select__wrapper').click();
    const listbox = await row.getByRole('combobox').getAttribute('aria-controls');
    assert(listbox);
    await page.locator(`[id="${listbox}"]`).getByRole('option', { name: labels[file.purpose], exact: true }).click();
  }
}

async function generateUi(sample, workflowOnly = false) {
  const label = sample.id === 'W_CODE' ? `${stage}_${sample.id}_contextfix` : `${stage}_${sample.id}`;
  const path = resolve(directory, workflowOnly ? 'workflow' : 'results', `${label}.json`);
  if (await exists(path)) return;
  await page.addInitScript(plan => {
    localStorage.setItem('prompt-optimizer.plan-mode.v1', JSON.stringify({ enabled: plan, introSeen: true }));
  }, Boolean(sample.plan));
  await page.goto(frontend + '/workbench');
  await expect(page.getByLabel('原始提示词', { exact: true })).toBeVisible({ timeout: 30_000 });
  await prepareFiles(sample);
  await page.getByLabel('原始提示词', { exact: true }).fill(sample.rawPrompt);
  const responses = [];
  const listener = async response => {
    const url = new URL(response.url());
    if (response.request().method() !== 'POST' || !['/api/v2/optimizations', '/api/v2/optimizations/plan'].includes(url.pathname)) return;
    responses.push({ route: url.pathname, input: response.request().postDataJSON(),
      status: response.status(), body: await response.json() });
  };
  page.on('response', listener);
  await bind(sample, workflowOnly ? 'workflow' : sample.plan ? 'regression' : 'enhancement',
    sample.id === 'W_CODE' ? 'ui_contextfix' : 'ui');
  const confirmSending = async () => {
    const dialog = page.getByRole('dialog', { name: '确认发送上下文', exact: true });
    if (await dialog.isVisible()) await dialog.getByRole('button', { name: '确认发送', exact: true }).click();
  };
  const waitFor = async predicate => {
    await expect.poll(async () => { await confirmSending(); return responses.some(predicate); },
      { timeout: 250_000, intervals: [200, 500, 1000] }).toBe(true);
    return responses.find(predicate);
  };
  try {
    await page.getByRole('button', { name: sample.plan ? '先确认并增强' : '直接增强提示词', exact: true }).click();
    if (sample.plan) {
      const plan = await waitFor(response => response.route.endsWith('/plan'));
      await save(resolve(directory, 'results', `${stage}_${sample.id}_plan.json`), plan);
      if (plan.status !== 200) { await save(path, { caseId: sample.id, stage, responses, response: plan }); return; }
      const questions = plan.body.data.questions;
      const answers = [];
      if (questions.length) {
        const dialog = page.getByRole('dialog', { name: '确认关键细节', exact: true });
        for (const [index, question] of questions.entries()) {
          assert(/日期|时间|交付|赔偿|补偿/.test(question.question), 'Plan新增了冻结用户没有保留的决定');
          await dialog.getByLabel('填写回答').fill(sample.answer);
          answers.push({ questionId: question.id, question: question.question, answer: sample.answer });
          await dialog.getByRole('button', { name: index === questions.length - 1 ? '生成最终提示词' : '下一题', exact: true }).click();
        }
      }
      await save(resolve(directory, 'results', `${stage}_${sample.id}_answers.json`), answers);
    }
    const generated = await waitFor(response => response.route === '/api/v2/optimizations');
    await save(path, { caseId: sample.id, stage, responses, input: generated.input, response: generated });
    if (generated.status !== 200) return;
    const result = generated.body.data;
    assert.equal(result.chainVersion, profile);
    assert.equal(result.provider.mock, false);
    assert(result.processing.completedStages.includes('BODY_GENERATION'));
    await expect(page.getByTestId('compact-prompt')).toHaveText(result.optimizedPrompt);
    assert(result.optimizedPrompt.length < 16_000, '不能将批量源码直接堆入正文');
    if (sample.id === 'W_CODE') {
      for (const suffix of ['date.mjs', 'form.mjs', 'import.mjs']) assert(generated.input.context.files.some(file =>
        file.path.includes('src/occupationalInfo/') && file.path.includes(suffix)), '显式目标文件未进入生成输入');
    }
    await page.bringToFront();
    await page.locator('.copy-main-button').click();
    await expect.poll(async () => (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')).toBe(result.optimizedPrompt.replace(/\r\n/g, '\n'));
    await captureHistory(result, resolve(directory, workflowOnly ? 'workflow' : 'results', `${label}_history.json`));
    await page.getByTestId('prompt-handoff').click();
    const dialog = page.getByRole('dialog', { name: '交给执行 AI', exact: true });
    const checkboxes = dialog.locator('.handoff-materials input[type=checkbox]:not(:disabled)');
    for (let index = 0; index < await checkboxes.count(); index++) await checkboxes.nth(index).locator('xpath=ancestor::label').click();
    const handoff = await dialog.getByLabel('完整交接内容预览').inputValue();
    await dialog.getByRole('button', { name: '复制交接内容', exact: true }).click();
    await expect.poll(async () => (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')).toBe(handoff.replace(/\r\n/g, '\n'));
    const receiver = await context.newPage();
    await receiver.setContent('<label for="input">执行端消息</label><textarea id="input"></textarea>');
    await receiver.bringToFront();
    await receiver.getByLabel('执行端消息').press('Control+V');
    await expect(receiver.getByLabel('执行端消息')).toHaveValue(handoff);
    await receiver.close();
    await page.bringToFront();
    await save(resolve(directory, workflowOnly ? 'workflow' : 'results', `${label}_handoff.json`), {
      copiedExact: true, receivedExact: true, content: handoff, availableFragments: await checkboxes.count(),
    });
    await dialog.getByRole('button', { name: '关闭', exact: true }).click();
    if (sample.feedback) {
      await bind(sample, 'workflow', 'feedback');
      await page.getByTestId('revise-prompt').click();
      await page.getByLabel('本次修改意见').fill(sample.feedback);
      const revisionResponse = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/revise'), { timeout: 250_000 });
      await page.getByRole('button', { name: '应用修改意见', exact: true }).click();
      const response = await revisionResponse;
      const value = { status: response.status(), body: await response.json() };
      await save(resolve(directory, 'workflow', `${stage}_${sample.id}_revision.json`), value);
      if (value.status === 200) {
        const revised = value.body.data.result;
        await expect(page.getByTestId('compact-prompt')).toHaveText(revised.optimizedPrompt);
        await expect(page.getByTestId('prompt-change-review')).toHaveCount(1);
        await captureHistory(revised, resolve(directory, 'workflow', `${stage}_${sample.id}_revision_history.json`));
      }
    }
    await page.screenshot({ path: resolve(runtime, `${stage}_${sample.id}.png`), fullPage: true });
    console.log(JSON.stringify({ event: 'UI_VERIFIED', stage, caseId: sample.id, version: result.chainVersion }));
  } finally { page.off('response', listener); }
}

try {
  await login();
  if (['workflow', 'daily'].includes(stage)) {
    for (const id of ['W_OFFICE', 'R_PLAN']) await generateUi(samples.find(sample => sample.id === id), true);
  } else {
    for (const sample of samples) {
      try {
        if (['W_CODE', 'W_OFFICE', 'R_PLAN'].includes(sample.id)) await generateUi(sample);
        else await generateApi(sample);
      } catch (error) {
        await save(resolve(runtime, `${runId}-${sample.id}-failure.json`), { type: error.name, message: error.message });
        await page.screenshot({ path: resolve(runtime, `${runId}-${sample.id}-failed.png`), fullPage: true }).catch(() => {});
        console.log(JSON.stringify({ event: 'CASE_FLOW_FAILED', stage, caseId: sample.id, type: error.name, message: error.message }));
        process.exitCode = 1;
      }
    }
  }
  console.log(JSON.stringify({ event: 'BROWSER_STAGE_COMPLETE', stage }));
} catch (error) {
  await save(resolve(runtime, `${runId}-failure.json`), { type: error.name, message: error.message });
  await page.screenshot({ path: resolve(runtime, `${runId}-failed.png`), fullPage: true }).catch(() => {});
  console.log(JSON.stringify({ event: 'BROWSER_STAGE_FAILED', stage, type: error.name, message: error.message }));
  process.exitCode = 1;
} finally {
  password = undefined;
  lines.close();
  await browser.close();
  await vite?.close();
}
