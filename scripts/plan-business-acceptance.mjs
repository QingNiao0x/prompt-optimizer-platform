/**
 * 真实业务验收工具：使用人工正常登录的临时浏览器会话，不保存账号、Cookie 或 CSRF Token。
 * 复用产品的上下文、Plan 和增强接口；输出仅包含经脱敏的验收材料及结果。
 */
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { cases, createAnswers, evaluateQuestions, redact } from './plan-quality-eval.mjs';

export { cases };

/** 每次运行使用独立目录；已存在的证据文件拒绝覆盖。 */
export async function createAcceptanceSession(context, page, options = {}) {
  const baseUrl = options.baseUrl ?? 'http://127.0.0.1:5175';
  const url = new URL(baseUrl);
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) {
    throw new Error('业务验收入口只允许本地服务。');
  }
  const runId = new Date().toISOString().replace(/[:.]/g, '-');
  const repositoryRoot = fileURLToPath(new URL('../', import.meta.url));
  const outputDirectory = resolve(options.outputRoot ?? resolve(repositoryRoot, 'tmp/plan-business-acceptance'), runId);
  await mkdir(outputDirectory, { recursive: true });
  const requests = [];
  const results = [];

  const call = async (path, body) => {
    const started = performance.now();
    // 通过页面自身的同源会话调用，兼容 CDP 接入的临时浏览器；凭据不离开浏览器。
    const response = await page.evaluate(async ({ url, body }) => {
      if (new URL(url).origin !== location.origin) throw new Error('验收请求必须与登录页面同源。');
      const csrfCookie = document.cookie.split('; ').find(cookie => cookie.startsWith('XSRF-TOKEN='));
      const csrf = csrfCookie ? decodeURIComponent(csrfCookie.slice('XSRF-TOKEN='.length)) : '';
      const result = await fetch(url, {
        method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin',
        headers: body === undefined ? {} : { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(240_000),
      });
      return { ok: result.ok, status: result.status, payload: await result.json().catch(() => ({})) };
    }, { url: new URL(path, baseUrl).href, body });
    const payload = response.payload;
    const request = { path, status: response.status, requestId: payload.requestId,
      elapsedMs: Math.round(performance.now() - started) };
    requests.push(request);
    if (!response.ok) {
      // 只保存统一异常响应的脱敏消息，不记录请求正文、认证头或原始上游响应。
      request.errorMessage = redact(String(payload.error?.message ?? '').slice(0, 500));
      throw new Error(`${path} HTTP ${request.status}, code=${payload.error?.code ?? 'UNKNOWN'}, requestId=${request.requestId ?? 'unknown'}, message=${request.errorMessage}`);
    }
    return payload.data;
  };

  await call('/api/v1/auth/me');
  await call('/api/v1/auth/csrf');
  const models = await call('/api/v1/models');
  const model = options.modelId
    ? models.find(item => item.id === options.modelId)
    : models.find(item => item.defaultModel);
  if (!model) throw new Error('没有可用的已发布默认模型；验收不会静默更换模型。');

  const save = async (name, value) => {
    if (!/^[a-zA-Z0-9_-]+$/.test(name)) throw new Error('非法验收证据名称。');
    // 先处理字符串再序列化，避免脱敏正则破坏 JSON 的引号和转义字符。
    await writeFile(resolve(outputDirectory, `${name}.json`),
      JSON.stringify(value, (_key, item) => typeof item === 'string' ? redact(item) : item, 2),
      { encoding: 'utf8', flag: 'wx' });
  };

  /** 准备真实后端上下文并创建计划；不自动把候选项当作用户确认事实。 */
  const plan = async sample => {
    const start = requests.length;
    const prepared = sample.files.length ? await call('/api/v1/context/planning', {
      rawPrompt: sample.rawPrompt,
      context: { customDescription: sample.contextDescription, files: sample.files },
      permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] },
    }) : null;
    const planningContext = prepared ? { contextId: prepared.contextId, version: prepared.version } : null;
    const result = await call('/api/v1/optimizations/plan', {
      rawPrompt: sample.rawPrompt, contextDescription: sample.contextDescription,
      conversationHistory: [], planningContext, modelId: model.id,
    });
    if (result.provider?.mock !== false) throw new Error('计划返回了 Mock，不能计入真实模型验收。');
    return { sample, prepared, plan: result, requests: requests.slice(start) };
  };

  /** 最终提交前调用产品自身的二次检索查询构建器，保留真实的所选答案。 */
  const finish = async (planned, options = {}) => {
    const sample = planned.sample;
    const answers = options.answers ?? createAnswers(planned.plan.questions ?? [], sample);
    const confirmation = { planId: planned.plan.planId, planningContext: planned.plan.planningContext, answers };
    const refinedQuery = await page.evaluate(async ({ rawPrompt, confirmation }) => {
      const { buildRefinedContextQuery } = await import('/src/features/optimization/optimizationRequest.ts');
      return buildRefinedContextQuery(rawPrompt, confirmation);
    }, { rawPrompt: sample.rawPrompt, confirmation });
    const files = options.files ?? sample.files;
    const requestStart = requests.length;
    const final = await call('/api/v1/optimizations', {
      rawPrompt: sample.rawPrompt, modelId: model.id,
      context: { customDescription: sample.contextDescription, files },
      enhancement: { templateCode: 'AUTO', includeConversationHistory: false,
        includePermissionBoundaries: true, includeExamples: false },
      conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] },
      planConfirmation: confirmation,
    });
    const questions = planned.plan.questions ?? [];
    const questionMetrics = evaluateQuestions(sample, questions);
    const finalText = final.optimizedPrompt ?? '';
    const missingTerms = (sample.finalTerms ?? []).filter(term => !finalText.includes(term));
    const checks = {
      realProvider: planned.plan.provider?.mock === false && final.provider?.mock === false,
      noKnownRepetitions: questionMetrics.knownRepetitions === 0,
      requiredQuestionsPresent: questionMetrics.requiredMissing === 0,
      noOffTopicQuestions: questionMetrics.offTopic === 0,
      noDuplicateDimensions: questionMetrics.semanticDuplicates === 0,
      requiredTermsPresent: missingTerms.length === 0,
      conflictAsked: sample.category !== 'conflict'
        || (sample.mustAsk ?? []).some(pattern => pattern.test(questions.map(item => item.question).join('\n'))),
      noQuestionsWhenComplete: !sample.expectNoQuestions || questions.length === 0,
      expectedTemplate: planned.plan.templateCode === sample.expectedTemplate,
      boundedQuestionCount: questions.length <= 8,
      fourSections: ['BACKGROUND', 'TASK', 'OUTPUT', 'CONSTRAINTS'].every(type =>
        (final.sections ?? []).some(section => section.type === type && section.content?.trim())),
      confirmedAnswerCoverage: answers.filter(answer => !/^(暂不确定|尚未确定|待定|不知道|不清楚|unknown|tbd)[。.!！]?$/i.test(answer.answer.trim()))
        .every(answer => finalText.includes(answer.answer.trim())),
    };
    const result = {
      id: options.id ?? sample.id, category: sample.category,
      input: { rawPrompt: sample.rawPrompt, contextDescription: sample.contextDescription,
        firstPaths: sample.files.map(file => file.path), finalPaths: files.map(file => file.path) },
      model, actualProviders: { plan: planned.plan.provider, final: final.provider },
      checks, passed: Object.values(checks).every(Boolean), questionMetrics, missingTerms,
      prepared: planned.prepared ? { digest: planned.prepared.digest,
        latencyMs: planned.prepared.latencyMs } : null,
      plan: planned.plan, answers, refinedQuery, final,
      requests: [...planned.requests, ...requests.slice(requestStart)],
    };
    await save(result.id, result);
    results.push(result);
    return { id: result.id, passed: result.passed, checks, questions: questions.length,
      ambiguities: final.ambiguities?.length ?? 0, planLatencyMs: planned.plan.latencyMs,
      finalLatencyMs: final.latencyMs, requestId: result.requests.at(-1)?.requestId };
  };

  /** 单例失败仍保存错误并继续下一例，保留失败证据而不重试到成功。 */
  const run = async sample => {
    let planned;
    try {
      planned = await plan(sample);
      return await finish(planned);
    } catch (error) {
      const result = { id: sample.id, passed: false, error: redact(error.message),
        plan: planned?.plan, digest: planned?.prepared?.digest };
      results.push(result);
      await save(sample.id, result);
      return result;
    }
  };

  return { runId, outputDirectory, model, requests, results, call, save, plan, finish, run };
}

/** 命令行仅打开正常登录界面；图形验证码和密码由用户在浏览器填写。 */
const cliArguments = globalThis.process?.argv ?? [];
if (cliArguments[1] && import.meta.url === pathToFileURL(resolve(cliArguments[1])).href) {
  const require = createRequire(new URL('../apps/web/package.json', import.meta.url));
  const { chromium } = require('@playwright/test');
  const interactive = cliArguments.includes('--interactive');
  const browser = await chromium.launch({ channel: 'chrome', headless: false,
    ...(interactive ? { args: ['--remote-debugging-port=9325', '--remote-debugging-address=127.0.0.1'] } : {}) });
  try {
    const context = await browser.newContext({ locale: 'zh-CN' });
    const page = await context.newPage();
    const baseUrl = globalThis.process.env.PLAN_EVAL_BASE_URL ?? 'http://127.0.0.1:5175';
    const localUrl = new URL(baseUrl);
    if (!['localhost', '127.0.0.1', '[::1]'].includes(localUrl.hostname)) throw new Error('只允许本地服务。');
    await page.goto(baseUrl);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.evaluate(() => { document.title = '业务验收专用窗口 — 请在此登录'; });
    console.log('请在验收浏览器中正常登录；本程序不保存密码或会话凭据。');
    const deadline = Date.now() + 10 * 60_000;
    let authenticated = false;
    while (Date.now() < deadline) {
      const response = await context.request.get(new URL('/api/v1/auth/me', baseUrl).href);
      if (response.ok()) { authenticated = true; break; }
      await new Promise(resolveWait => setTimeout(resolveWait, 1500));
    }
    if (!authenticated) throw new Error('等待正常登录超时，未执行模型调用。');
    if (interactive) {
      console.log('INTERACTIVE_AUTHENTICATED：可继续使用此临时浏览器进行业务验收。');
      await new Promise(resolveClosed => browser.once('disconnected', resolveClosed));
    } else {
    const session = await createAcceptanceSession(context, page, { baseUrl });
    console.log({ model: session.model, outputDirectory: session.outputDirectory });
    for (const sample of cases) console.log(await session.run(sample));
    await session.save('summary', { model: session.model, results: session.results.map(item =>
      ({ id: item.id, passed: item.passed, checks: item.checks, error: item.error })) });
    globalThis.process.exitCode = session.results.every(item => item.passed) ? 0 : 1;
    }
  } finally {
    await browser.close();
  }
}
