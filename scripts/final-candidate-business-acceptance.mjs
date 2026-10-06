/**
 * 最终候选真实接口验收：冻结合成需求，分离提问与逐题回答审核，失败不覆盖、不自动挑选推荐项。
 * 登录会话仍在独立浏览器内；本文件只保存业务输出和白名单计时，不保存认证信息。
 */
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { redact } from './plan-quality-eval.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const hash = value => createHash('sha256').update(value).digest('hex');
const encode = value => JSON.stringify(value, (_key, item) => typeof item === 'string' ? redact(item) : item, 2) + '\n';
const id = value => {
  if (!/^[a-zA-Z0-9_-]{1,100}$/.test(value)) throw new Error('INVALID_EVIDENCE_ID');
  return value;
};

/** 构造独立运行，公开目录与实际输入冻结；不把隐藏评分卡送给模型。 */
export async function createFinalCandidateAcceptance(page, options) {
  if (!page.url().startsWith('http://127.0.0.1:5175/')) throw new Error('LOCAL_PAGE_REQUIRED');
  const output = resolve(options.output);
  if (!output.startsWith(resolve(root, 'tmp/prompt-comparison') + sep)) throw new Error('UNSAFE_EVIDENCE_PATH');
  if (!options.resume) await mkdir(output, { recursive: false });
  const save = async (name, value) => {
    const path = resolve(output, `${id(name)}.json`);
    await writeFile(path, encode(value), { encoding: 'utf8', flag: 'wx' });
    return path;
  };
  const requests = [];
  const call = async (path, body, label) => {
    const start = performance.now();
    const response = await page.evaluate(async ({ path, body }) => {
      const token = document.cookie.split('; ').find(item => item.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
      const result = await fetch(path, { method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', ...(token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {}) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(240_000) });
      return { status: result.status, requestId: result.headers.get('X-Request-ID'), payload: await result.json() };
    }, { path, body });
    const metadata = { label, path, status: response.status, requestId: response.payload.requestId ?? response.requestId,
      elapsedMs: Math.round(performance.now() - start), code: response.payload.error?.code,
      validationReason: response.payload.error?.details?.validationReason ?? response.payload.error?.details?.reason,
      validationField: response.payload.error?.details?.validationField ?? response.payload.error?.details?.field,
      modelAttempts: response.payload.error?.details?.modelAttempts };
    requests.push(metadata);
    if (response.status < 200 || response.status >= 300) {
      const error = new Error(`HTTP_${response.status}_${metadata.code ?? 'UNKNOWN'}`);
      error.metadata = metadata;
      throw error;
    }
    return response.payload.data;
  };
  await call('/api/v1/auth/me', undefined, 'identity');
  await call('/api/v1/auth/csrf', undefined, 'csrf');
  const models = await call('/api/v1/models', undefined, 'catalog');
  const catalog = { source: '/api/v1/models', fetchedAt: new Date().toISOString(), models };
  if (!options.resume) await save('catalog', catalog);
  const frozen = options.resume ? JSON.parse(await readFile(resolve(output, 'cases.json'), 'utf8')) : options.cases.map(sample => ({ id: sample.id, domain: sample.domain,
    rawPrompt: sample.rawPrompt, contextDescription: sample.contextDescription ?? '',
    files: (sample.files ?? sample.contextFiles ?? []).map(file => ({
      path: file.path, language: file.language ?? (file.path.endsWith('.md') ? 'markdown' : 'text'), content: file.content,
    })) }));
  if (frozen.some(sample => !sample.rawPrompt?.trim() || sample.rawPrompt.length > 8000)) throw new Error('INVALID_CASE_INPUT');
  const caseText = encode(frozen);
  if (!options.resume) await writeFile(resolve(output, 'cases.json'), caseText, { flag: 'wx' });
  const sources = [
    'enhancement/service/PlanningSessionService.java',
    'enhancement/service/impl/PlanningSessionServiceImpl.java',
    'enhancement/service/impl/PlanningDecisionPolicy.java',
    'provider/domain/PlanningProviderRequest.java',
    'enhancement/service/impl/SourceObjectContract.java',
    'enhancement/service/impl/TaskQuestionScope.java',
    'enhancement/service/impl/ExecutionRuleCompactor.java',
    'enhancement/service/impl/MinimalPromptOrganizer.java',
    'enhancement/service/impl/PendingDecisionSignature.java',
    'enhancement/service/impl/ReadOnlyMaterialComparison.java',
    'provider/domain/PromptRewriteStrategy.java',
    'template/domain/TaskIntentResolver.java',
    'enhancement/service/impl/AmbiguityDetector.java',
    'provider/domain/ProviderResponseValidationException.java',
    'enhancement/service/impl/ComparisonMaterialDecision.java',
    'enhancement/service/impl/NewsBodyFactContract.java',
    'enhancement/service/impl/PendingReminderIdentity.java',
    'enhancement/service/impl/PlanQuestionFilter.java', 'enhancement/service/impl/RoutineGuideDecision.java',
    'enhancement/service/impl/RoutineWritingPresentation.java',
    'enhancement/service/impl/PlanningAuthorizationState.java', 'enhancement/service/impl/PlanningConflictIdentity.java',
    'enhancement/service/impl/RequirementFidelityGuard.java', 'enhancement/service/impl/EvidenceStateGuard.java',
    'enhancement/service/impl/ResolvedPlanState.java', 'enhancement/service/impl/PlanAmbiguityMerger.java',
    'enhancement/service/impl/PlanAnswerSemantics.java', 'enhancement/service/impl/PlanRecommendationAligner.java',
    'enhancement/service/impl/NewsLengthContract.java',
    'template/domain/TaskDeliveryProfile.java',
    'enhancement/service/impl/OptimizationResultAssembler.java', 'enhancement/service/impl/DefaultEnhancementOrchestrator.java',
    'enhancement/service/impl/OptimizationPlanningServiceImpl.java', 'provider/domain/PromptOptimizationGuidance.java',
    'provider/infrastructure/openai/OpenAiCompatiblePromptEnhancementProvider.java',
  ];
  const sourceHashes = Object.fromEntries(await Promise.all(sources.map(async path => [path,
    hash(await readFile(resolve(root, 'services/api/src/main/java/com/promptoptimizer', path)))])));
  const manifest = options.resume ? JSON.parse(await readFile(resolve(output, 'manifest.json'), 'utf8')) : { createdAt: new Date().toISOString(), kind: 'REAL_AUTHENTICATED_API',
    dataAuthorization: 'SYNTHETIC', firstFailuresPreserved: true, caseSha256: hash(caseText), sourceHashes,
    reviewKind: 'AI_ASSISTED', independentHumanReview: 'NOT_PERFORMED', domainSpecialistReview: 'AFTER_LAUNCH_PER_USER',
    plannedPublishedModels: models.map(model => model.id), callsPerModelLimit: options.callsPerModelLimit ?? 40 };
  if (manifest.caseSha256 !== hash(caseText)) throw new Error('FROZEN_INPUT_CHANGED');
  if (!options.resume) await save('manifest', manifest);
  const counters = new Map();
  if (options.resume) {
    for (const name of await readdir(output)) {
      if (!name.endsWith('.json')) continue;
      const evidence = JSON.parse(await readFile(resolve(output, name), 'utf8'));
      // 包含另存的复验和工具校验失败，不能通过更换证据文件名绕过每批预算。
      if (evidence.modelId && ['plan', 'direct'].includes(evidence.arm)
          && ['COLLECTED', 'FAILED'].includes(evidence.status)) {
        counters.set(evidence.modelId, (counters.get(evidence.modelId) ?? 0) + 1);
      }
    }
  }
  const guard = async modelId => {
    if (!models.some(model => model.id === modelId)) throw new Error('UNPUBLISHED_MODEL');
    for (const [path, expected] of Object.entries(manifest.sourceHashes)) {
      if (hash(await readFile(resolve(root, 'services/api/src/main/java/com/promptoptimizer', path))) !== expected) {
        throw new Error('CANDIDATE_CHANGED_START_A_NEW_RUN');
      }
    }
    const count = (counters.get(modelId) ?? 0) + 1;
    if (count > manifest.callsPerModelLimit) throw new Error('LOGICAL_CALL_BUDGET_EXCEEDED');
    counters.set(modelId, count);
  };
  const draft = sample => ({ rawPrompt: sample.rawPrompt, context: { customDescription: sample.contextDescription, files: sample.files },
    enhancement: { templateCode: 'AUTO', includePermissionBoundaries: true, includeConversationHistory: false, includeExamples: false },
    conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] } });
  const collect = async (caseId, modelId, arm = 'plan') => {
    const sample = frozen.find(sample => sample.id === caseId);
    if (!sample || !['plan', 'direct'].includes(arm)) throw new Error('UNKNOWN_CASE_OR_ARM');
    const evidenceId = id(`${caseId}-${modelId.replace(/[^a-zA-Z0-9_-]/g, '_')}-${arm}`);
    const start = requests.length;
    const evidence = { id: evidenceId, caseId, modelId, arm, startedAt: new Date().toISOString() };
    try {
      await guard(modelId);
      if (arm === 'plan') {
        const prepared = sample.files.length ? await call('/api/v1/context/planning', {
          rawPrompt: sample.rawPrompt, context: { customDescription: sample.contextDescription, files: sample.files },
          permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] },
        }, `${evidenceId}-prepare`) : null;
        const planningContext = prepared ? { contextId: prepared.contextId, version: prepared.version } : null;
        evidence.prepared = prepared;
        evidence.plan = await call('/api/v1/optimizations/plan', { rawPrompt: sample.rawPrompt,
          contextDescription: sample.contextDescription, conversationHistory: [], planningContext, modelId }, `${evidenceId}-plan`);
        if (evidence.plan.provider?.mock !== false) throw new Error('MOCK_CANNOT_COUNT_AS_REAL');
      } else {
        evidence.result = await call('/api/v1/optimizations', { ...draft(sample), modelId }, `${evidenceId}-direct`);
        if (evidence.result.provider?.mock !== false) throw new Error('MOCK_CANNOT_COUNT_AS_REAL');
      }
      evidence.status = 'COLLECTED';
    } catch (error) { evidence.status = 'FAILED'; evidence.failure = error.metadata ?? { code: error.message }; }
    evidence.requests = requests.slice(start);
    await save(evidenceId, evidence);
    return evidence;
  };
  const finish = async (collected, reviewedAnswers, extraFiles = []) => {
    if (!collected.plan || collected.status !== 'COLLECTED') throw new Error('COLLECTED_PLAN_REQUIRED');
    const sample = frozen.find(sample => sample.id === collected.caseId);
    const evidence = { id: `${collected.id}-final`, caseId: sample.id, modelId: collected.modelId,
      arm: 'plan', startedAt: new Date().toISOString(), plan: collected.plan };
    const start = requests.length;
    try {
      const answers = collected.plan.questions.map(question => {
        const entry = reviewedAnswers[question.id];
        if (!entry?.answer?.trim() || entry.answer.length > 1500 || !entry.basis?.trim()) throw new Error('EVERY_QUESTION_NEEDS_REVIEWED_ANSWER');
        return { questionId: question.id, question: question.question, answer: entry.answer.trim() };
      });
      const confirmation = { planId: collected.plan.planId, planningContext: collected.plan.planningContext, answers };
      evidence.answers = answers;
      evidence.answerReview = reviewedAnswers;
      evidence.refinedQuery = await page.evaluate(async ({ rawPrompt, confirmation }) => {
        const { buildRefinedContextQuery } = await import('/src/features/optimization/optimizationRequest.ts');
        return buildRefinedContextQuery(rawPrompt, confirmation);
      }, { rawPrompt: sample.rawPrompt, confirmation });
      await guard(collected.modelId);
      evidence.result = await call('/api/v1/optimizations', { ...draft(sample), modelId: collected.modelId,
        context: { customDescription: sample.contextDescription, files: [...sample.files, ...extraFiles] },
        planConfirmation: confirmation }, `${collected.id}-final`);
      if (evidence.result.provider?.mock !== false) throw new Error('MOCK_CANNOT_COUNT_AS_REAL');
      evidence.status = 'COLLECTED';
    } catch (error) { evidence.status = 'FAILED'; evidence.failure = error.metadata ?? { code: error.message }; }
    evidence.requests = requests.slice(start);
    await save(evidence.id, evidence);
    return evidence;
  };
  return { output, models, cases: frozen, manifest, requests, collect, finish, save, call };
}
