import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { isDeepStrictEqual } from 'node:util';

const root = resolve('tmp/natural-phase-protocol-20261022');
const evidence = resolve('docs/testing/evidence/natural-phase-protocol-2026-10-22');
const origin = 'http://127.0.0.1:9003';
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const protocol = await json(resolve(evidence, 'protocol.json'));
const cases = (await json(resolve(evidence, 'cases.json'))).cases;
const account = await json(resolve(process.env.POP_CANONICAL_ROOT, 'tmp/local-acceptance/account.json'));
const terminal = createInterface({ input: process.stdin, output: process.stdout });
const cookies = new Map();
let csrf;
let currentJob;
const log = value => console.log(JSON.stringify(value));
const encode = value => JSON.stringify(value, null, 2) + '\n';
const save = async (path, value) => {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, typeof value === 'string' ? value : encode(value), { flag: 'wx' });
};

/** 正常CSRF、验证码及固定合成账号登录；密码和Cookie仅在进程内存，不进入公开证据。 */
async function api(path, body) {
  const headers = { Cookie: [...cookies].map(([key, value]) => key + '=' + value).join('; ') };
  if (currentJob) headers['X-Evaluation-Job'] = currentJob;
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    headers[csrf?.headerName ?? 'X-XSRF-TOKEN'] = csrf?.token ?? '';
  }
  const response = await fetch(origin + path, { method: body === undefined ? 'GET' : 'POST', headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(240_000) });
  for (const line of response.headers.getSetCookie()) {
    const pair = line.split(';', 1)[0]; const index = pair.indexOf('=');
    cookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
  return { status: response.status, body: await response.json() };
}

async function login() {
  if (!process.env.POP_EVAL_PASSWORD) throw Error('Canonical credential must be injected');
  csrf = (await api('/api/v1/auth/csrf')).body.data;
  const challenge = await fetch(origin + '/api/v1/auth/captcha', {
    headers: { Cookie: [...cookies].map(([key, value]) => key + '=' + value).join('; ') }
  });
  for (const line of challenge.headers.getSetCookie()) {
    const pair = line.split(';', 1)[0]; const index = pair.indexOf('=');
    cookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
  const path = resolve(root, 'auth/captcha-' + Date.now() + '.png');
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, Buffer.from(await challenge.arrayBuffer()), { flag: 'wx' });
  log({ event: 'CAPTCHA_READY', path });
  const captcha = (await terminal.question('CAPTCHA_INPUT> ')).trim();
  const result = await api('/api/v1/auth/login', { identifier: account.identifier,
    password: process.env.POP_EVAL_PASSWORD, captcha });
  delete process.env.POP_EVAL_PASSWORD;
  if (result.status !== 200) throw Error('Canonical login failed: HTTP ' + result.status);
  csrf = (await api('/api/v1/auth/csrf')).body.data;
}

async function count() {
  const names = (await readdir(resolve(root, 'traces'))).filter(name => name.endsWith('.json'));
  const phases = {};
  for (const name of names) {
    const row = await json(resolve(root, 'traces', name));
    phases[row.phase] = (phases[row.phase] ?? 0) + 1;
  }
  return { actual: names.length, maximum: protocol.maximumNewActualCalls, phases };
}

/** 每次付费尝试由服务端先预留；作业不得覆盖或按质量重试，所有失败均留原件。 */
async function job(id, phase, sample, version, path, body, approvedModelRequest) {
  if (!protocol.allowedJobs.includes(id)) throw Error('Job outside frozen scope');
  const output = resolve(root, 'results', id + '.json');
  try {
    const result = await json(output);
    if (result.id !== id || result.caseId !== sample.id || result.version !== version || result.phase !== phase
        || !isDeepStrictEqual(result.request, body)) throw Error('Frozen job input changed: ' + id);
    log({ id, state: 'ALREADY_RECORDED' });
    return result;
  }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  if ((await readdir(resolve(root, 'traces'))).filter(name => name.endsWith('.json')).length >= protocol.maximumNewActualCalls) {
    throw Error('Actual call allowance exhausted');
  }
  for (const name of (await readdir(resolve(root, 'traces'))).filter(name => name.endsWith('.json'))) {
    const row = await json(resolve(root, 'traces', name));
    if (row.jobId === id) throw Error('Attempt already reserved: ' + id);
  }
  currentJob = id;
  await writeFile(resolve(root, 'active-job.json'), encode({ id, phase, caseId: sample.id, version,
    ...(approvedModelRequest ? { approvedModelRequest } : {}) }));
  let result;
  try { result = await api(path, body); }
  catch (failure) { result = { status: 0, body: { transportFailure: failure.name } }; }
  await save(output, { id, caseId: sample.id, version, phase, request: body, ...result });
  log({ id, status: result.status, code: result.body.error?.code, ...(await count()) });
  return result;
}

const input = sample => ({
  rawPrompt: sample.rawPrompt, modelId: protocol.modelId, context: { customDescription: '', files: sample.files },
  enhancement: { templateCode: 'AUTO', includeConversationHistory: false, includePermissionBoundaries: true,
    promptFormat: 'COMPACT' },
  conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] }, planConfirmation: null,
});

const historyChecks = [];
async function checkHistory(result) {
  if (result.status !== 200) return;
  currentJob = null;
  const generated = result.body.data;
  const history = await api('/api/v2/optimization-history/' + generated.recordId);
  historyChecks.push({ recordId: generated.recordId, status: history.status,
    bodyEqual: history.body.data?.optimizedPrompt === generated.optimizedPrompt,
    versionEqual: history.body.data?.chainVersion === generated.chainVersion,
    noDefaultRules: generated.constraintGroups?.platformMandatory?.length === 0,
    rulesEqual: JSON.stringify(history.body.data?.constraintGroups) === JSON.stringify(generated.constraintGroups),
    noInternalReviewOnWire: !Object.hasOwn(history.body.data ?? {}, 'taskUnderstanding') });
}

/** 每一臂都携带同一份材料，不向原始臂隐去增强时已经给定的信息。 */
function executionRequest(sample, prompt) {
  const answers = sample.answerBundle ? '\n\n用户已提供的本次答复（未知项保持未知）：\n' + sample.answerBundle : '';
  const material = sample.files.length ? '\n\n用户同时提供的材料：\n' + JSON.stringify(sample.files) : '';
  return { model: protocol.upstreamModel, stream: false, temperature: protocol.parameters.temperature,
    max_tokens: protocol.parameters.executionMaxTokens, thinking: { type: 'disabled' }, messages: [
      { role: 'system', content: '执行用户请求并交付其要求的作品。无法访问外部工具或文件，不声称已经运行、联网或完成未执行的操作。' },
      { role: 'user', content: prompt + material + answers },
    ] };
}

async function optionalJson(path) {
  try { return await json(path); }
  catch (failure) { if (failure.code === 'ENOENT') return null; throw failure; }
}

/** 中断后只复用完全一致的原件，不能覆盖旧结果或重新付费生成已尝试的作业。 */
async function saveConsistent(path, value) {
  const recorded = await optionalJson(path);
  if (recorded !== null) {
    if (!isDeepStrictEqual(recorded, value)) throw Error('Recorded evidence differs: ' + path);
    return;
  }
  await save(path, value);
}

/** 同一份真实Plan和题答供4/21使用；恢复时保留原context引用，不新建一个引用去套旧Plan。 */
async function commonConfirmation(sample) {
  const recordedPlanPath = resolve(root, 'results', sample.id + '_V4_PLAN.json');
  const confirmationPath = resolve(evidence, 'common-confirmation-' + sample.id + '.json');
  let result = await optionalJson(recordedPlanPath);
  let reference;
  if (result !== null) {
    if (result.id !== sample.id + '_V4_PLAN' || result.caseId !== sample.id || result.version !== '4'
        || result.phase !== 'PLAN' || result.request.rawPrompt !== sample.rawPrompt
        || result.request.modelId !== protocol.modelId) throw Error('Recorded Plan differs from frozen task');
    reference = result.request.planningContext;
  } else {
    currentJob = null;
    const prepared = await api('/api/v2/context/planning', { rawPrompt: sample.rawPrompt,
      context: input(sample).context, permissionPolicy: input(sample).permissionPolicy });
    if (prepared.status !== 200) throw Error('Planning context preparation failed');
    reference = { contextId: prepared.body.data.contextId, version: prepared.body.data.version };
    result = await job(sample.id + '_V4_PLAN', 'PLAN', sample, '4', '/api/v2/evaluation/plan-baseline', {
      rawPrompt: sample.rawPrompt, contextDescription: '', conversationHistory: [],
      modelId: protocol.modelId, planningContext: reference });
  }
  if (result.status !== 200) throw Error('No actual shared Plan, no invented confirmation');
  const plan = result.body.data;
  if (!Array.isArray(plan.questions) || plan.questions.length === 0) {
    await saveConsistent(resolve(evidence, 'plan-answer-path-' + sample.id + '.json'), {
      verified: false, reason: 'Actual Plan returned no questions; the answer bundle was not bound to generation'
    });
    throw Error('Actual bound-answer path was not exercised; do not simulate confirmation');
  }
  if (plan.questions.some(q => q.type !== 'FREE_TEXT' && !q.allowCustomAnswer)) {
    throw Error('Frozen answer bundle cannot be supplied to an actual question');
  }
  const confirmation = { planId: plan.planId, planningContext: reference,
    answers: plan.questions.map(q => ({ questionId: q.id, question: q.question, answer: sample.answerBundle })) };
  await saveConsistent(confirmationPath, {
    anchorVersion: '4', originalTask: sample.rawPrompt, modelId: protocol.modelId, confirmation
  });
  return confirmation;
}

async function run() {
  for (let index=0; index<protocol.generation.length; index++) {
    const sample=cases.find(x=>x.id===protocol.generation[index]);
    const order=['4','21','22','23'];
    for(const version of [...order.slice(index%4),...order.slice(0,index%4)]) {
      const result=await job(sample.id+'_V'+version+'_GEN','GENERATION',sample,version,
        version==='4'?'/api/v2/evaluation/baseline':'/api/v2/evaluation/candidate',input(sample));
      await checkHistory(result);
    }
  }
  for(const id of protocol.plan) {
    const sample=cases.find(x=>x.id===id);
    const prior=await optionalJson(resolve(root,'results',id+'_V4_PLAN.json'));
    let reference=prior?.request.planningContext;
    if(!reference) {
      currentJob=null;
      const prepared=await api('/api/v2/context/planning',{rawPrompt:sample.rawPrompt,
        context:input(sample).context,permissionPolicy:input(sample).permissionPolicy});
      if(prepared.status!==200) throw Error('Context preparation failed');
      reference={contextId:prepared.body.data.contextId,version:prepared.body.data.version};
    }
    for(const version of ['4','21']) {
      await job(id+'_V'+version+'_PLAN','PLAN',sample,version,'/api/v2/evaluation/plan-baseline',{
        rawPrompt:sample.rawPrompt,contextDescription:'',conversationHistory:[],modelId:protocol.modelId,planningContext:reference});
    }
    if(protocol.confirmedPlanGeneration.includes(id)) {
      const confirmation=await commonConfirmation(sample);
      for(const version of ['4','21']) {
        const result=await job(id+'_V'+version+'_PLAN_GEN','GENERATION',sample,version,
          version==='4'?'/api/v2/evaluation/baseline':'/api/v2/evaluation/candidate',
          {...input(sample),planConfirmation:confirmation});
        await checkHistory(result);
      }
    }
  }
  await saveConsistent(resolve(evidence,'history-checks.json'),{checks:historyChecks,newAccounts:0});
  if(historyChecks.some(x=>x.status!==200||!x.bodyEqual||!x.versionEqual||!x.rulesEqual||!x.noInternalReviewOnWire))
    throw Error('History consistency failed');
  for(let index=0;index<protocol.execution.length;index++) {
    const sample=cases.find(x=>x.id===protocol.execution[index]);
    const arms=protocol.confirmedPlanGeneration.includes(sample.id)?['RAW','V4','V21']:['RAW','V4','V21','V22','V23'];
    for(const arm of [...arms.slice(index%arms.length),...arms.slice(0,index%arms.length)]) {
      let prompt=sample.rawPrompt;
      if(arm!=='RAW') {
        const suffix=protocol.confirmedPlanGeneration.includes(sample.id)?'_PLAN_GEN':'_GEN';
        const result=await json(resolve(root,'results',sample.id+'_'+arm+suffix+'.json'));
        if(result.status!==200) {log({id:sample.id,arm,execution:'NO_VALID_PROMPT'});continue;}
        prompt=result.body.data.optimizedPrompt;
      }
      const request=executionRequest(sample,prompt);
      await job(sample.id+'_'+arm+'_EXEC','EXECUTION',sample,arm,'/api/v2/evaluation/execute',request,request);
    }
  }
}

try {
  if (process.argv[2] === 'count') log(await count());
  else if (process.argv[2] === 'all') { await login(); await run(); }
  else throw Error('Unknown frozen single-pass evaluation operation');
} finally { terminal.close(); }
