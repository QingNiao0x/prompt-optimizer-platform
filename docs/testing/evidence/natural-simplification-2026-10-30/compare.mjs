import { readFile, readdir, writeFile, mkdir, unlink } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { instruction, sha, readJson, saveNew, generationRequest, executionRequest, countAttempts, reserve, parseBody } from './simple-baseline.mjs';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const previous = resolve(repo, 'docs/testing/evidence/natural-writer-input70-2026-10-29');
const taskRoot = resolve(repo, 'tmp/natural-writer-input70-2026-10-29');
const runtime = resolve(taskRoot, 'simple-comparison');
const destination = 'https://api.deepseek.com/chat/completions';
const mode = process.argv[2];
const protocolPath = resolve(evidence, 'protocol.json');
const closurePath = resolve(evidence, 'closure.json');
const fileSha = async path => sha(await readFile(path, 'utf8'));
const exists = async path => { try { await readFile(path); return true; } catch (e) { if (e.code !== 'ENOENT') throw e; return false; } };

if (mode === 'freeze') {
  const status = await readJson(resolve(previous, 'final-status.json'));
  const task = await readJson(resolve(previous, 'task-protocol.json'));
  if (status.actualCalls !== 44 || status.authorizedAdditionalMaximum !== 48 || !status.allExecutedStagesClosed
      || task.selectedMaximumActualCalls !== 48 || task.destination !== destination || await countAttempts(taskRoot) !== 44
      || await exists(closurePath)) throw Error('Original 48-call task must have exactly four unused calls');
  const bundle = await readJson(resolve(previous, 'works-bundle.json'));
  const inputs = bundle.cases;
  const parameters = {};
  let bodyParameterSource;
  for (const name of (await readdir(resolve(previous, 'new-70-allocated/traces'))).sort()) {
    const trace = await readJson(resolve(previous, 'new-70-allocated/traces', name));
    // 旧传输日志统一记DIRECT；按首次返回的单正文协议定位实际BODY，不伪造它的阶段字段。
    const visible = JSON.parse(trace.response.choices[0].message.content);
    if (Object.keys(visible).join() === 'optimizedPrompt') {
      Object.assign(parameters, trace.request);
      delete parameters.messages;
      bodyParameterSource = { path: 'new-70-allocated/traces/' + name,
        sha256: await fileSha(resolve(previous, 'new-70-allocated/traces', name)) };
      break;
    }
  }
  if (parameters.model !== 'deepseek-flash' || parameters.thinking?.type !== 'enabled'
      || parameters.reasoning_effort !== 'high' || parameters.max_tokens !== 32768) throw Error('Frozen native70 body parameters unavailable');
  const cases = inputs.map(sample => {
    const raw = bundle.jobs.find(job => job.caseId === sample.id && job.version === 'ORIGINAL' && job.id.endsWith('_R1'));
    if (!raw || raw.approvedModelRequest.messages[1].content !== sample.rawPrompt) throw Error('Missing original R1 request');
    const candidate = bundle.jobs.find(job => job.caseId === sample.id && job.version === '70' && job.id.endsWith('_R1'));
    return { id: sample.id, rawPrompt: sample.rawPrompt, materials: sample.materials,
      generationRequest: generationRequest(raw.approvedModelRequest, parameters), originalExecutionRequest: raw.approvedModelRequest,
      historicalR1: { original: raw.id, complex70: candidate.id }, repetitions: { historicalPerArm: 2, newSimple: 1 } };
  });
  if (cases.map(c => c.id).join() !== 'W70-N1,W70-N2') throw Error('Only previously approved synthetic cases are permitted');
  const productPaths = [
    'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalTaskScope.java',
    'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptCoreRepairInstructions.java',
    'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
    'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/NaturalEnhancementService.java',
    'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/NaturalEnhancementSettings.java'
  ];
  const freeze = {
    status: 'FROZEN', frozenAt: new Date().toISOString(), task: 'finite-simple-baseline-comparison', destination,
    authorization: '2026-10-30 user approved implementing the simplification plan; uses only remaining four calls of the existing48, approved synthetic cases and derivatives, no new budget',
    sharedMaximum: 48, previousActualCalls: 44, maximumNewCalls: 4, phaseLimits: { GENERATION: 2, EXECUTION: 2 },
    jobIds: ['SIMPLE_N1_GEN', 'SIMPLE_N1_EXEC', 'SIMPLE_N2_GEN', 'SIMPLE_N2_EXEC'],
    qualityRetries: false, protocolRetries: false, newAccounts: false, newPaidReview: false,
    scope: 'Offline product regression plus two known-case feasibility comparisons; not an independent holdout, human review or product adoption',
    casesSha256: sha(cases), instructionSha256: sha(instruction),
    bodyParameterSource,
    sources: await Promise.all(productPaths.map(async path => ({ path, sha256: await fileSha(resolve(repo, path)) }))),
    historicalCasesSha256: await fileSha(resolve(previous, 'works-bundle.json')),
    historicalReportSha256: await fileSha(resolve(previous, 'final-status.json')),
    criteria: {
      engineering: ['body stage reached for all response classes', 'no source concatenation', 'accurate bounded repair without invented evidence', 'real answers/permissions retained', 'explicit history unchanged', 'configuration rollback'],
      quality: ['original deliverable and hard requirements preserved', 'useful completion', 'no invented facts/authorization', 'actual work edit burden', 'generation time and token usage'],
      adoption: 'No automatic adoption from this two-case pilot; any confirmed new severe regression blocks the corresponding capability',
      primaryComparison: 'Prespecified R1 of ORIGINAL and70, plus first and only SIMPLE work. Historical R2 retained as sensitivity, never choose the better sample.',
      humanDecision: 'Prepare anonymous works and a blank review form; no human outcome may be invented'
    }
  };
  await saveNew(resolve(evidence, 'cases.json'), cases);
  await saveNew(protocolPath, freeze);
  console.log(JSON.stringify({ event: 'FROZEN', maximumNewCalls: 4, previousActualCalls: 44, cases: cases.map(c => c.id) }));
  process.exit(0);
}

if (mode !== 'run') throw Error('Usage: node compare.mjs freeze|run');
const protocol = await readJson(protocolPath);
if (protocol.status !== 'FROZEN' || protocol.sharedMaximum !== 48 || protocol.maximumNewCalls !== 4
    || protocol.destination !== destination || await exists(closurePath) || await exists(resolve(runtime, 'closure.json'))) throw Error('Frozen open comparison required');
const cases = await readJson(resolve(evidence, 'cases.json'));
if (sha(cases) !== protocol.casesSha256 || sha(instruction) !== protocol.instructionSha256
    || await fileSha(resolve(previous, 'works-bundle.json')) !== protocol.historicalCasesSha256
    || await fileSha(resolve(previous, 'final-status.json')) !== protocol.historicalReportSha256) throw Error('Frozen input or history changed');
for (const source of protocol.sources) if (await fileSha(resolve(repo, source.path)) !== source.sha256) throw Error('Product drift after freeze');
if (!process.env.MODEL_API_KEY || !await exists(resolve(repo, 'tmp/local-acceptance/account.json'))) throw Error('Existing canonical account and server-side credential required; no accounts are created');
await mkdir(runtime, { recursive: true });
const lock = resolve(taskRoot, '.simple-comparison.lock');
await writeFile(lock, JSON.stringify({ pid: process.pid, at: new Date().toISOString() }), { flag: 'wx' });
const outcomes = [];
try {
  if (await countAttempts(taskRoot) !== protocol.previousActualCalls) throw Error('Calls already made or another stage consumed remaining allowance');
  const key = process.env.MODEL_API_KEY;
  delete process.env.MODEL_API_KEY;
  const invoke = async job => {
    // 每次HTTP尝试之前预留；网络失败也占额度。关闭、总额和重复作业由同一总账检查，不增加任何重试。
    const { path, trace } = await reserve(taskRoot, runtime, job, protocol.sharedMaximum);
    const started = performance.now();
    let result;
    try {
      const response = await fetch(destination, { method: 'POST', redirect: 'error',
        headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
        body: JSON.stringify(job.request), signal: AbortSignal.timeout(180000) });
      const body = await response.json();
      // 只提取可见正文及用量，不查看、保存或输出reasoning_content，也不保存响应头/请求头。
      const choice = body.choices?.[0];
      const safe = { id: body.id, model: body.model, usage: body.usage,
        choices: choice ? [{ finish_reason: choice.finish_reason, message: { content: choice.message?.content ?? '' } }] : [] };
      result = { ...trace, state: response.ok ? 'RETURNED' : 'HTTP_FAILED', httpStatus: response.status,
        elapsedMs: Math.round(performance.now() - started), response: safe };
    } catch (failure) {
      result = { ...trace, state: 'TRANSPORT_FAILED', errorType: failure.name, elapsedMs: Math.round(performance.now() - started) };
    }
    await writeFile(path, JSON.stringify(result, null, 2) + '\n');
    await mkdir(resolve(evidence, 'traces'), { recursive: true });
    await saveNew(resolve(evidence, 'traces', job.id + '.json'), result);
    console.log(JSON.stringify({ jobId: job.id, state: result.state, actualTotal: await countAttempts(taskRoot) }));
    return result;
  };
  for (let i = 0; i < cases.length; i++) {
    const sample = cases[i];
    const id = 'SIMPLE_N' + (i + 1);
    const generated = await invoke({ id: id + '_GEN', caseId: sample.id, phase: 'GENERATION', request: sample.generationRequest });
    let prompt;
    try { if (generated.httpStatus !== 200) throw Error('GENERATION_FAILED'); prompt = parseBody(generated.response); }
    catch (failure) { outcomes.push({ caseId: sample.id, generation: 'FAILED', errorType: failure.message, execution: 'NOT_ATTEMPTED' }); continue; }
    // 依赖正文得到后，先写下唯一执行载荷再发送；既不人工改稿，也不筛掉质量较差的首次结果。
    const execution = { id: id + '_EXEC', caseId: sample.id, phase: 'EXECUTION', request: executionRequest(sample.originalExecutionRequest, prompt) };
    await saveNew(resolve(evidence, id + '-execution-payload.json'), execution);
    const work = await invoke(execution);
    outcomes.push({ caseId: sample.id, generation: 'RETURNED', prompt, execution: work.state, httpStatus: work.httpStatus });
  }
  const files = (await readdir(resolve(evidence, 'traces'))).sort();
  const closure = { status: 'CLOSED', closedAt: new Date().toISOString(), actualNewCalls: files.length,
    sharedActualTotal: await countAttempts(taskRoot), sharedMaximum: 48, original500Reopened: false,
    historical44SnapshotUnchanged: await fileSha(resolve(previous, 'final-status.json')) === protocol.historicalReportSha256,
    qualityRetried: false, outcomes, productAdopted: false, humanReview: 'PENDING',
    files: await Promise.all(files.map(async name => ({ path: 'traces/' + name, sha256: await fileSha(resolve(evidence, 'traces', name)) }))) };
  await saveNew(resolve(runtime, 'closure.json'), closure);
  await saveNew(closurePath, closure);
  console.log(JSON.stringify({ event: 'CLOSED', actualNewCalls: files.length, sharedActualTotal: closure.sharedActualTotal }));
} finally { await unlink(lock); }
