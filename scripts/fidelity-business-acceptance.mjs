/**
 * 第一批真实模型验收。复用正常浏览器登录；逐例收集、审阅答案、再生成，避免Plan过期。
 * 只发送冻结合成样例；凭据留在浏览器，证据独占写入，HTTP成功与业务通过分别记录。
 */
import { mkdir, readFile, writeFile, access } from 'node:fs/promises';
import { dirname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fidelityCases } from './fidelity-acceptance-cases.mjs';
import { createAcceptanceSession } from './plan-business-acceptance.mjs';
import { redact } from './plan-quality-eval.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const [command, directory, caseId] = process.argv.slice(2);
const hash = text => createHash('sha256').update(text).digest('hex');
const json = value => JSON.stringify(value, (_key, item) => typeof item === 'string' ? redact(item) : item, 2);
const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();

/** 所有证据使用独占文件；保护已取得的失败及首轮输出。 */
async function save(path, value) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, typeof value === 'string' ? value : json(value), { encoding: 'utf8', flag: 'wx' });
}

async function connect() {
  const require = createRequire(new URL('../apps/web/package.json', import.meta.url));
  const { chromium } = require('@playwright/test');
  const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
  const context = browser.contexts()[0];
  const page = context?.pages().find(tab => tab.url().startsWith('http://127.0.0.1:5175/'));
  if (!page) throw new Error('缺少已打开的本地验收页面。');
  return { browser, context, page };
}

/** 最多收集9个场景的直接增强、Plan和最终增强；上游重试次数需另从可用遥测核验。 */
async function prepare() {
  const output = resolve(root, 'tmp/fidelity-acceptance', new Date().toISOString().replace(/[:.]/g, '-'));
  const specification = json(fidelityCases);
  const candidatePaths = [
    'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/RequirementFidelityGuard.java',
    'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/OptimizationResultAssembler.java',
    'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/PlanAmbiguityMerger.java',
    'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/OptimizationPlanningServiceImpl.java',
    'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/ConfirmedDecisionSet.java',
    'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/PlanQuestionFilter.java',
    'services/api/src/main/java/com/promptoptimizer/policy/service/impl/ConstraintCompleterImpl.java',
    'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatiblePromptEnhancementProvider.java',
  ];
  const candidateHashes = {};
  for (const path of candidatePaths) candidateHashes[path] = hash(await readFile(resolve(root, path)));
  await save(resolve(output, 'cases.json'), specification);
  await save(resolve(output, 'manifest.json'), {
    startedAt: new Date().toISOString(), commit: git(['rev-parse', 'HEAD']), candidateHashes,
    caseSha256: hash(specification), cases: fidelityCases.length, repetitions: 1,
    maximumSuccessfulApiGenerations: fidelityCases.length * 3,
    scope: '真实API、固定合成材料；不验证完整目录索引、办公文件解析或双人上线门禁。',
    answerPolicy: '评测员只按冻结回答卡逐题填写。未知保留未知，不能自动接受推荐；回答不是实际用户业务确认。',
    comparison: '直接增强与Plan是体验组，Plan可获得回答卡信息，不据此推断等信息模型优势。',
  });
  console.log(json({ output, cases: fidelityCases.length, modelCalls: 0 }));
}

const protocolChecks = result => ({
  realProvider: result?.provider?.mock === false,
  promptPresent: !!result?.optimizedPrompt?.trim(),
  fourSections: ['BACKGROUND', 'TASK', 'OUTPUT', 'CONSTRAINTS'].every(type => result?.sections?.some(s => s.type === type && s.content?.trim())),
  constraintsInCopiedBody: result?.sections?.filter(s => s.type === 'CONSTRAINTS').every(s => result.optimizedPrompt?.includes(s.content)) ?? false,
});

async function run() {
  if (!['--collect', '--finish'].includes(command) || !directory || !caseId) throw new Error('缺少阶段、目录或场景ID。');
  const output = resolve(directory);
  if (!output.startsWith(resolve(root, 'tmp/fidelity-acceptance') + sep)) throw new Error('证据目录必须在tmp/fidelity-acceptance内。');
  const parse = async name => JSON.parse(await readFile(resolve(output, name), 'utf8'));
  const manifest = await parse('manifest.json');
  const specification = await readFile(resolve(output, 'cases.json'), 'utf8');
  if (hash(specification) !== manifest.caseSha256) throw new Error('冻结样例被修改，停止验收。');
  for (const [path, expected] of Object.entries(manifest.candidateHashes)) {
    if (hash(await readFile(resolve(root, path))) !== expected) throw new Error('候选业务代码已变化，必须另开验收批次。');
  }
  const cases = JSON.parse(specification);
  const index = cases.findIndex(item => item.id === caseId);
  if (index < 0) throw new Error('未知场景。');
  const item = cases[index];
  const { context, page } = await connect();
  const session = await createAcceptanceSession(context, page, { baseUrl: 'http://127.0.0.1:5175', outputRoot: resolve(output, 'sessions') });
  const modelPath = resolve(output, 'selected-model.json');
  let selected;
  try { await access(modelPath); selected = await parse('selected-model.json'); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    selected = { model: session.model, selectedAt: new Date().toISOString() };
    await save(modelPath, selected);
  }
  if (selected.model.id !== session.model.id || selected.model.displayName !== session.model.displayName) throw new Error('默认模型已变更，停止本轮。');
  // 标记先于付费调用；重复执行同一阶段会明确拒绝，而不是覆盖或挑选成功结果。
  await save(resolve(output, `${caseId}${command}.started.json`), { startedAt: new Date().toISOString(), model: selected.model });
  const makeRequest = confirmation => ({
    rawPrompt: item.rawPrompt, modelId: selected.model.id,
    context: { customDescription: item.contextDescription, files: [...item.files, ...(confirmation ? item.finalOnlyFiles ?? [] : [])].map(({ path, language, content }) => ({ path, language, content })) },
    enhancement: { templateCode: 'AUTO', includeConversationHistory: false, includePermissionBoundaries: true, includeExamples: false },
    conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] },
    ...(confirmation ? { planConfirmation: confirmation } : {}),
  });
  const arms = command === '--finish' ? ['plan-final'] : index % 2 === 0 ? ['direct', 'plan'] : ['plan', 'direct'];
  let failed = false;
  for (const arm of arms) {
    const started = performance.now();
    const requestStart = session.requests.length;
    const evidence = { id: item.id, title: item.title, arm, startedAt: new Date().toISOString(), semanticReview: 'PENDING', caseSha256: manifest.caseSha256 };
    console.log(json({ event: 'fidelity.start', caseId, arm }));
    try {
      if (arm === 'plan') {
        const planned = await session.plan({ ...item, files: makeRequest().context.files });
        evidence.prepared = planned.prepared;
        evidence.plan = planned.plan;
        await save(resolve(output, 'answers', `${caseId}.template.json`), {
          caseId, questions: planned.plan.questions.map(q => ({ questionId: q.id, question: q.question, answer: '', basis: '' })),
        });
      } else {
        let confirmation;
        if (arm === 'plan-final') {
          const planned = await parse(`results/${caseId}--plan.json`);
          const reviewed = await parse(`answers/${caseId}.json`);
          if (reviewed.caseId !== caseId || reviewed.questions.length !== planned.plan.questions.length) throw new Error('未逐题完成回答审核。');
          const answers = planned.plan.questions.map(q => {
            const match = reviewed.questions.filter(a => a.questionId === q.id);
            const answer = match[0];
            if (match.length !== 1 || answer.question !== q.question || !answer.answer?.trim() || answer.answer.length > 1500 || !answer.basis?.trim()) throw new Error('回答与绑定问题不一致或缺少冻结依据。');
            return { questionId: q.id, question: q.question, answer: answer.answer.trim() };
          });
          evidence.plan = planned.plan;
          evidence.prepared = planned.prepared;
          evidence.answerReview = reviewed;
          evidence.priorRequests = planned.requests;
          confirmation = { planId: planned.plan.planId, planningContext: planned.plan.planningContext, answers };
          evidence.refinedQuery = await page.evaluate(async ({ rawPrompt, confirmation }) => {
            const { buildRefinedContextQuery } = await import('/src/features/optimization/optimizationRequest.ts');
            return buildRefinedContextQuery(rawPrompt, confirmation);
          }, { rawPrompt: item.rawPrompt, confirmation });
        }
        evidence.final = await session.call('/api/v1/optimizations', makeRequest(confirmation));
        evidence.protocolChecks = protocolChecks(evidence.final);
        if (!Object.values(evidence.protocolChecks).every(Boolean)) throw new Error('接口契约核对失败，不能视为业务通过。');
        await save(resolve(output, 'prompts', `${caseId}--${arm}.md`), evidence.final.optimizedPrompt);
      }
    } catch (error) { evidence.error = redact(error.message); failed = true; }
    evidence.elapsedMs = Math.round(performance.now() - started);
    evidence.requests = session.requests.slice(requestStart);
    await save(resolve(output, 'results', `${caseId}--${arm}.json`), evidence);
    console.log(json({ event: 'fidelity.saved', caseId, arm, elapsedMs: evidence.elapsedMs,
      questions: evidence.plan?.questions?.length, ambiguities: evidence.final?.ambiguities?.length,
      chars: evidence.final?.optimizedPrompt?.length, provider: evidence.final?.provider ?? evidence.plan?.provider,
      checks: evidence.protocolChecks, error: evidence.error, requestId: evidence.requests.at(-1)?.requestId }));
  }
  // 仅断开脚本，人工登录窗口继续保留；不退出用户的浏览器会话。
  process.exit(failed ? 1 : 0);
}

if (command === '--help') {
  console.log('node scripts/fidelity-business-acceptance.mjs --prepare\nnode scripts/fidelity-business-acceptance.mjs --probe\nnode scripts/fidelity-business-acceptance.mjs --collect <run-directory> <case-id>\n逐题填写answers/<case-id>.json后：\nnode scripts/fidelity-business-acceptance.mjs --finish <run-directory> <case-id>');
} else if (command === '--prepare') await prepare();
else if (command === '--probe') {
  const { page } = await connect();
  const status = await page.evaluate(async () => {
    const auth = await fetch('/api/v1/auth/me', { credentials: 'same-origin' });
    if (!auth.ok) return { authenticated: false, status: auth.status };
    const response = await fetch('/api/v1/models', { credentials: 'same-origin' });
    const payload = await response.json();
    return { authenticated: true, status: response.status, defaultModel: payload.data?.find(model => model.defaultModel) };
  });
  console.log(json(status));
  process.exit(status.authenticated ? 0 : 1);
} else await run();
