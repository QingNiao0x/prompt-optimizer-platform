/**
 * 同一批合成输入的直接增强 / Plan 增强业务对照。
 * --prepare 冻结样例并导出 Qoder 工作区；业务比较优先使用 prompt-comparison-reviewed.mjs 逐题复核。
 * 旧的关键词答题方式仅供显式选择的敏感性对照，不能当作可靠的业务确认。
 * 不修改业务代码、不伪造 Qoder 输出，不自动接受推荐答案或覆盖既有证据。
 */
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname, isAbsolute, relative, sep } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { comparisonCases, fixedAnswers } from './prompt-comparison-cases.mjs';
import { buildIndustryCases } from './prompt-comparison-industry-cases.mjs';
import { createAcceptanceSession } from './plan-business-acceptance.mjs';
import { redact } from './plan-quality-eval.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const args = process.argv.slice(2);
const json = value => JSON.stringify(value, (_key, item) => {
  if (item instanceof RegExp) return { pattern: item.source, flags: item.flags };
  return typeof item === 'string' ? redact(item) : item;
}, 2);
const digest = text => createHash('sha256').update(text).digest('hex');

/** 新文件采用独占写入，重复运行必须使用新目录或新证据名。 */
async function save(path, content) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, content, { encoding: 'utf8', flag: 'wx' });
}

/** Qoder 目录只包含固定输入，不含答案卡、预期结果或运行后输出。 */
async function prepare() {
  let selectedCases = args.includes('--industry') ? await buildIndustryCases(root) : comparisonCases;
  if (args.includes('--only')) {
    const requested = new Set((args[args.indexOf('--only') + 1] ?? '').split(','));
    selectedCases = selectedCases.filter(item => requested.has(item.id));
    if (!selectedCases.length || selectedCases.length !== requested.size) throw new Error('Unknown case selection.');
  }
  const runId = new Date().toISOString().replace(/[:.]/g, '-');
  const output = resolve(root, 'tmp/prompt-comparison', runId);
  const qoderRoot = await mkdtemp(resolve(tmpdir(), 'prompt-comparison-qoder-'));
  const specification = json(selectedCases);
  await save(resolve(output, 'cases.json'), specification);
  const version = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  const changedPaths = execFileSync('git', ['-c', 'core.quotepath=false', 'status', '--porcelain'],
    { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim().split('\n').filter(Boolean);
  const metadata = {
    runId, createdAt: new Date().toISOString(), commit: version, changedPaths,
    caseCount: selectedCases.length, repetitions: 1, caseSha256: digest(specification), qoderRoot,
    dataset: args.includes('--industry') ? 'project-excerpts-and-synthetic-industry-materials' : 'synthetic-regression-cases',
    track: 'Same raw prompt and supplied text material; Plan receives independent answer-card responses.',
    scope: 'API business comparison; not a full directory indexing benchmark or two-person release review.',
    qoderStatus: 'PENDING_NATIVE_ENHANCE_OUTPUT',
  };
  await save(resolve(output, 'manifest.json'), json(metadata));
  for (const currentCase of selectedCases) {
    if (!/^[a-z0-9_-]+$/.test(currentCase.id)) throw new Error('Unsafe case ID.');
    const workspace = resolve(qoderRoot, currentCase.id);
    await mkdir(workspace, { recursive: true });
    for (const file of currentCase.files) {
      if (isAbsolute(file.path) || file.path.split(/[\\/]/).includes('..')
        || /(^|[\\/])(?:\.env(?:\..*)?|id_rsa|id_ed25519)$|\.(?:pem|key)$/i.test(file.path)) {
        throw new Error('Unsafe synthetic material path.');
      }
      const target = resolve(workspace, file.path);
      if (!target.startsWith(workspace + sep)) throw new Error('Material escapes its workspace.');
      await save(target, file.content);
    }
    // 空材料案例也有独立目录；输入和说明放在目录外，避免被误识别为项目事实。
    await save(resolve(output, 'qoder-inputs', currentCase.id + '.txt'), currentCase.rawPrompt
      + (currentCase.contextDescription ? '\n\n项目补充描述：' + currentCase.contextDescription : ''));
  }
  await save(resolve(output, 'Qoder运行说明.md'), `# Qoder 原生增强对照\n\n本轮样例包含合成材料或白名单项目资料节选，不含真实业务个人信息；资料性质以各案例说明为准。\n\n每例打开下表的独立工作区，清空会话，将同 ID 的 qoder-inputs/*.txt 放入输入框，使用原生“增强提示词”按钮。不要点击发送执行开发任务，不把答案卡或其他案例导入当前工作区。保存完整增强结果、工具版本和耗时；失败同样记录。普通 CLI 对话结果必须单独标注，不能冒充原生按钮输出。\n\n| 案例 | 工作区 |\n| --- | --- |\n${selectedCases.map(item => `| ${item.id} | ${resolve(qoderRoot, item.id)} |`).join('\n')}\n`);
  console.log(json({ prepared: true, output, qoderRoot, cases: selectedCases.length, modelCalls: 0 }));
}

function optimizationRequest(currentCase, modelId, confirmation) {
  return {
    rawPrompt: currentCase.rawPrompt, modelId,
    context: { customDescription: currentCase.contextDescription,
      files: currentCase.files.map(({ path, language, content }) => ({ path, language, content })) },
    enhancement: { templateCode: 'AUTO', includeConversationHistory: false,
      includePermissionBoundaries: true, includeExamples: false },
    conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] },
    ...(confirmation ? { planConfirmation: confirmation } : {}),
  };
}

/** 这里只判协议完整性；语义正确性由保存的完整输出逐项审阅，不能以接口成功充当业务通过。 */
function protocolChecks(result) {
  return {
    realProvider: result?.provider?.mock === false,
    promptPresent: typeof result?.optimizedPrompt === 'string' && result.optimizedPrompt.trim().length > 0,
    fourSections: ['BACKGROUND', 'TASK', 'OUTPUT', 'CONSTRAINTS'].every(type =>
      result?.sections?.some(section => section.type === type && section.content?.trim())),
  };
}

async function run(outputArgument) {
  const output = resolve(outputArgument);
  const expectedParent = resolve(root, 'tmp/prompt-comparison');
  if (!output.startsWith(expectedParent + sep)) throw new Error('Run directory must be under tmp/prompt-comparison.');
  const manifest = JSON.parse(await readFile(resolve(output, 'manifest.json'), 'utf8'));
  const specification = await readFile(resolve(output, 'cases.json'), 'utf8');
  if (digest(specification) !== manifest.caseSha256) throw new Error('Frozen cases changed.');
  const frozenCases = JSON.parse(specification);
  const require = createRequire(new URL('../apps/web/package.json', import.meta.url));
  const { chromium } = require('@playwright/test');
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
  const context = browser.contexts()[0];
  const page = context?.pages().find(item => item.url().startsWith('http://127.0.0.1:5175/'));
  if (!page) throw new Error('Open the local acceptance browser first.');
  const session = await createAcceptanceSession(context, page, { baseUrl: 'http://127.0.0.1:5175',
    outputRoot: resolve(output, 'api-session') });
  await save(resolve(output, 'selected-model.json'), json({ model: session.model, selectedAt: new Date().toISOString() }));
  const summaries = [];
  let consecutiveFailures = 0;
  for (const [index, currentCase] of frozenCases.entries()) {
    const arms = index % 2 === 0 ? ['direct', 'plan'] : ['plan', 'direct'];
    for (const arm of arms) {
      const started = performance.now();
      const requestStart = session.requests.length;
      const evidence = { id: currentCase.id, arm, repetition: 1, startedAt: new Date().toISOString(),
        caseSha256: manifest.caseSha256, semanticReview: 'PENDING' };
      console.log(json({ event: 'comparison.start', caseId: currentCase.id, arm }));
      try {
        let confirmation;
        if (arm === 'plan') {
          const planned = await session.plan({ ...currentCase, files: optimizationRequest(currentCase, session.model.id).context.files });
          evidence.prepared = planned.prepared;
          evidence.plan = planned.plan;
          evidence.answers = fixedAnswers(planned.plan.questions ?? [], currentCase);
          confirmation = { planId: planned.plan.planId, planningContext: planned.plan.planningContext,
            answers: evidence.answers };
          evidence.refinedQuery = await page.evaluate(async ({ rawPrompt, confirmation }) => {
            const { buildRefinedContextQuery } = await import('/src/features/optimization/optimizationRequest.ts');
            return buildRefinedContextQuery(rawPrompt, confirmation);
          }, { rawPrompt: currentCase.rawPrompt, confirmation });
          console.log(json({ event: 'comparison.plan_ready', caseId: currentCase.id,
            questions: evidence.plan.questions?.length ?? 0,
            unknownAnswers: evidence.answers.filter(answer => answer.answer === '暂不确定').length }));
        }
        evidence.final = await session.call('/api/v1/optimizations', optimizationRequest(currentCase, session.model.id, confirmation));
        evidence.protocolChecks = protocolChecks(evidence.final);
        if (!Object.values(evidence.protocolChecks).every(Boolean)) throw new Error('Returned payload failed protocol checks.');
        consecutiveFailures = 0;
      } catch (error) {
        evidence.error = redact(error.message);
        consecutiveFailures++;
      }
      evidence.elapsedMs = Math.round(performance.now() - started);
      evidence.requests = session.requests.slice(requestStart);
      await save(resolve(output, 'platform', `${currentCase.id}--${arm}.json`), json(evidence));
      const summary = { id: currentCase.id, arm, elapsedMs: evidence.elapsedMs,
        questions: evidence.plan?.questions?.length ?? 0,
        ambiguities: evidence.final?.ambiguities?.length ?? null,
        length: evidence.final?.optimizedPrompt?.length ?? null,
        provider: evidence.final?.provider ?? evidence.plan?.provider,
        protocolChecks: evidence.protocolChecks, semanticReview: 'PENDING', error: evidence.error,
        requestId: evidence.requests.at(-1)?.requestId };
      summaries.push(summary);
      console.log(json({ event: 'comparison.saved', ...summary }));
      if (consecutiveFailures >= 3) break;
    }
    if (consecutiveFailures >= 3) break;
  }
  await save(resolve(output, 'platform-summary.json'), json({ manifest, model: session.model,
    completed: summaries.length, expected: frozenCases.length * 2, results: summaries }));
  // CDP 连接退出后保留人工登录窗口，不清理用户会话。
  console.log(json({ event: 'comparison.finished', output: relative(root, output), completed: summaries.length }));
  process.exit(summaries.some(item => item.error) ? 1 : 0);
}

/** 人工登录窗口保持到用户主动关闭；不因准备样例耗时而提前失效，也不自动调用模型。 */
async function openLogin() {
  const require = createRequire(new URL('../apps/web/package.json', import.meta.url));
  const { chromium } = require('@playwright/test');
  const browser = await chromium.launch({ channel: 'chrome', headless: false,
    args: ['--remote-debugging-port=9325', '--remote-debugging-address=127.0.0.1', '--start-maximized'] });
  const context = await browser.newContext({ locale: 'zh-CN', viewport: null });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:5175/');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.evaluate(() => { document.title = 'Prompt Optimizer 业务评测 — 请在此登录'; });
  await page.bringToFront();
  console.log('LOGIN_WINDOW_READY: Chrome 中完成正常登录和验证码；窗口保持至主动关闭，不自动调用模型。');
  await new Promise(resolveClosed => browser.once('disconnected', resolveClosed));
}

if (args.includes('--help')) {
  console.log('node scripts/prompt-comparison-eval.mjs --prepare [--industry] [--only id1,id2]\nnode scripts/prompt-comparison-eval.mjs --login\n优先业务流程：node scripts/prompt-comparison-reviewed.mjs --collect <prepared-directory>\n逐题填写 reviewed-answers.json 后：node scripts/prompt-comparison-reviewed.mjs --finish <prepared-directory>\n仅关键词答题敏感性对照：node scripts/prompt-comparison-eval.mjs --run-dir <prepared-directory> --allow-heuristic-answers');
} else if (args.includes('--login')) {
  await openLogin();
} else if (args.includes('--prepare')) {
  await prepare();
} else if (args.includes('--run-dir')) {
  if (!args.includes('--allow-heuristic-answers')) throw new Error('Use prompt-comparison-reviewed.mjs to review answers before generation; heuristic answers require explicit --allow-heuristic-answers.');
  const directory = args[args.indexOf('--run-dir') + 1];
  if (!directory || directory.startsWith('--')) throw new Error('Missing prepared run directory.');
  await run(directory);
} else {
  throw new Error('Choose --prepare or --run-dir; see --help.');
}
