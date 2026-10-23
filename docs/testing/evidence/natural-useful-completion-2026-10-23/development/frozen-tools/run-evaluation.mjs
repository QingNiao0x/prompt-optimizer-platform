import { readFile, writeFile, mkdir, readdir, stat } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { isDeepStrictEqual } from 'node:util';

const stage = process.argv[3];
if (!/^[a-z0-9-]+$/.test(stage ?? '')) throw Error('Explicit safe stage required');
const repo = resolve(process.env.POP_CANONICAL_ROOT ?? '.');
const taskRoot = resolve(repo, 'tmp/natural-useful-completion-2026-10-23');
const root = resolve(taskRoot, stage);
const taskEvidence = resolve(repo, 'docs/testing/evidence/natural-useful-completion-2026-10-23');
const evidence = resolve(taskEvidence, stage);
const origin = 'http://127.0.0.1:9003';
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const protocol = await json(resolve(evidence, 'protocol.json'));
const cases = (await json(resolve(evidence, 'cases.json'))).cases;
const account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
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

/** 闭批拒绝在登录前及每个作业前执行；count仅作只读累计，不解密凭据。 */
async function assertOpen() {
  const task = await json(resolve(taskEvidence, 'task-protocol.json'));
  if (task.status !== 'FROZEN' || protocol.status !== 'FROZEN'
      || protocol.taskMaximumActualCalls !== task.selectedMaximumActualCalls) throw Error('Batch is not frozen');
  for (const path of [taskRoot, root, taskEvidence, evidence]) {
    let closed = false;
    try { await stat(resolve(path, 'closure.json')); closed = true; }
    catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
    if (closed) throw Error('Closed batch cannot execute');
  }
}

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
    let phase = 'UNRESOLVED_RESERVATION';
    try { phase = (await json(resolve(root, 'traces', name))).phase ?? phase; }
    catch { /* 中断留下的空预留也占预算，不能因JSON缺失释放额度。 */ }
    phases[phase] = (phases[phase] ?? 0) + 1;
  }
  let taskActual = 0;
  for (const entry of await readdir(taskRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    try { taskActual += (await readdir(resolve(taskRoot, entry.name, 'traces'))).filter(name => name.endsWith('.json')).length; }
    catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  }
  return { actual: names.length, maximum: protocol.maximumNewActualCalls, phases,
    taskActual, taskMaximum: protocol.taskMaximumActualCalls, taskRemaining: protocol.taskMaximumActualCalls - taskActual };
}

/** 每次付费尝试由服务端先预留；作业不得覆盖或按质量重试，所有失败均留原件。 */
async function job(id, phase, sample, version, path, body, approvedModelRequest) {
  await assertOpen();
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
  // HTTP成功但正文为空仍原样保存，并明确标记交付失败，不补造作品或付费重试。
  const text = phase === 'GENERATION' ? result.body?.data?.optimizedPrompt : result.body?.data?.content;
  const deliveryFailure = result.status === 200 && phase !== 'PLAN'
    && (typeof text !== 'string' || text.trim() === '') ? 'EMPTY_CONTENT' : undefined;
  result = { ...result, ...(deliveryFailure ? { deliveryFailure } : {}) };
  await save(output, { id, caseId: sample.id, version, phase, request: body, ...result });
  log({ id, status: result.status, code: result.body.error?.code, ...(await count()) });
  return result;
}

const input = sample => ({
  rawPrompt: sample.rawPrompt, modelId: protocol.modelId, context: { customDescription: sample.contextDescription ?? '', files: sample.files ?? [] },
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
  const material = (sample.files ?? []).length ? '\n\n用户同时提供的材料：\n' + JSON.stringify(sample.files) : '';
  return { model: protocol.upstreamModel, stream: false, temperature: protocol.parameters.temperature,
    max_tokens: protocol.parameters.executionMaxTokens, thinking: { type: 'disabled' }, messages: [
      { role: 'system', content: '完成用户当前请求，遵守用户明确的输出范围、输出形式与阶段边界。仅使用本次提供的材料；无法访问外部工具或文件，不声称已经运行、联网或完成未执行的操作。' },
      { role: 'user', content: prompt + material + (sample.contextDescription ? '\n\n用户提供的背景：\n' + sample.contextDescription : '') + answers },
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

/** 独立 Plan 使用真实题号；有问由事后映射选择事前冻结事实，禁止把完整答案复制给每题。 */
async function versionConfirmation(sample, version) {
  const result = await optionalJson(resolve(root, 'results', sample.id + '_V' + version + '_PLAN.json'));
  const questions = result?.body?.data?.questions;
  const expectation = sample.planExpectation;
  const zeroAllowed = expectation === 'ZERO_QUESTIONS';
  const path = resolve(evidence, 'plan-paths', sample.id + '_V' + version + '.json');
  if (result?.status !== 200 || !Array.isArray(questions)) {
    await saveConsistent(path, { expected: expectation, verified: false, reason: 'Actual Plan did not deliver questions array' });
    return null;
  }
  if (questions.length === 0 && !zeroAllowed) {
    await saveConsistent(path, { expected: expectation, verified: false, actualQuestions: 0,
      reason: 'Important unknown was not asked; frozen answer bundle cannot be invented as a Plan answer' });
    return null;
  }
  if (questions.length && (!sample.answerFacts?.length || questions.some(q => q.type !== 'FREE_TEXT' && !q.allowCustomAnswer))) {
    await saveConsistent(path, { expected: expectation, verified: false, actualQuestions: questions.length,
      reason: 'No authorized frozen answer can be bound to actual questions' });
    return null;
  }
  let bindings = [];
  if (questions.length) {
    const mapping = await optionalJson(resolve(evidence, 'answers-by-version.json'));
    bindings = mapping?.[sample.id]?.[version];
    if (!bindings) {
      log({id:sample.id,version,state:'WAITING_REAL_ANSWER_MAP',actualQuestions:questions.length});
      return null;
    }
    if (!Array.isArray(bindings) || bindings.length !== questions.length
        || new Set(bindings.map(b => b.questionId)).size !== questions.length
        || bindings.some(b => !questions.some(q => q.id === b.questionId)
          || !Array.isArray(b.factIds) || !b.factIds.length
          || new Set(b.factIds).size !== b.factIds.length
          || b.factIds.some(id => !sample.answerFacts.some(f => f.id === id)))) {
      throw Error('Actual questions must each map to existing frozen facts exactly once');
    }
  }
  const confirmation = { planId: result.body.data.planId, planningContext: result.request.planningContext,
    answers: questions.map(q => ({ questionId: q.id, question: q.question,
      answer: bindings.find(b => b.questionId === q.id).factIds.map(id => sample.answerFacts.find(f => f.id === id).answer).join('\n') })) };
  await saveConsistent(path, { expected: expectation, verified: true, actualQuestions: questions.length,
    matchesExpectedCount: zeroAllowed ? questions.length === 0 : questions.length > 0, factBindings:bindings, confirmation });
  return confirmation;
}

async function run() {
  const operation = process.argv[2];
  if (operation === 'author') {
    const request={model:protocol.upstreamModel,stream:false,temperature:protocol.parameters.temperature,
      max_tokens:protocol.parameters.generationMaxTokens,thinking:{type:'disabled'},response_format:{type:'json_object'},
      messages:[{role:'system',content:protocol.authorSystem},{role:'user',content:protocol.authorSpec}]};
    const result=await job('SEALED_AUTHOR','REVIEW',cases[0],'AUTHOR','/api/v2/evaluation/execute',request,request);
    if(result.status!==200||result.body.data.finishReason!=='stop') throw Error('Sealed author did not complete');
    const value=JSON.parse(result.body.data.content);
    if(!Array.isArray(value.cases)||value.cases.length!==protocol.authorExpectedCount) throw Error('Sealed author protocol invalid');
    await save(resolve(root,'sealed-followup.json'),value);
    log({event:'NEW_HOLDOUT_SEALED',caseCount:value.cases.length,caseTextsPrinted:false});
    return;
  }
  if (operation === 'review') {
    for (const review of protocol.reviews) {
      const pack=[];
      const mappings=[];
      for (const id of review.caseIds) {
        const sample=cases.find(c=>c.id===id);
        const suffix=protocol.confirmedPlanGeneration.includes(id)?'_PLAN_GEN':'_GEN';
        const sides=[];
        // 两次评审优先平衡基线/候选的左右位置；原始提示词作为同信息中位锚点仍独立评分。
        const arms = ['V' + protocol.versions[0], 'ORIGINAL', 'V' + protocol.versions[1]];
        const order = review.reverse ? [...arms].reverse() : arms;
        for (const arm of order) {
          const version = arm.startsWith('V') ? arm.slice(1) : null;
          const generated = version ? await optionalJson(resolve(root,'results',id+'_'+arm+suffix+'.json')) : null;
          const plan = version ? await optionalJson(resolve(root,'results',id+'_'+arm+'_PLAN.json')) : null;
          const works=[];
          const deliveries=[];
          for(let repeat=1;repeat<=protocol.executionRepeats;repeat++) {
            const result=await optionalJson(resolve(root,'results',id+'_'+arm+'_EXEC_R'+repeat+'.json'));
            works.push(result?.status===200 ? result.body?.data?.content ?? null : null);
            deliveries.push({repeat,status:result?.status??null,deliveryFailure:result?.deliveryFailure??null,
              finishReason:result?.body?.data?.finishReason??null});
          }
          sides.push({prompt:arm==='ORIGINAL'?sample.rawPrompt:generated?.status===200?generated.body?.data?.optimizedPrompt??null:null,
            questions:plan?.status===200?plan.body?.data?.questions??null:null,works,deliveries});
        }
        mappings.push({id,left:order[0],center:order[1],right:order[2]});
        pack.push({id,rawPrompt:sample.rawPrompt,materials:sample.files??[],context:sample.contextDescription??'',
          answers:sample.answerBundle??'',acceptance:sample.acceptance,left:sides[0],center:sides[1],right:sides[2]});
      }
      await saveConsistent(resolve(root,'review-map',review.id+'.json'),mappings);
      const request={model:protocol.upstreamModel,stream:false,temperature:protocol.parameters.temperature,
        max_tokens:protocol.parameters.reviewMaxTokens,thinking:{type:'disabled'},response_format:{type:'json_object'},
        messages:[{role:'system',content:protocol.reviewSystem},{role:'user',content:JSON.stringify({cases:pack})}]};
      await job(review.id,'REVIEW',cases.find(c=>c.id===review.caseIds[0]),'ANONYMOUS',
        '/api/v2/evaluation/execute',request,request);
    }
    return;
  }
  if (operation !== 'execute') {
  for (let index=0; index<protocol.generation.length; index++) {
    const sample=cases.find(x=>x.id===protocol.generation[index]);
    const order=protocol.versions;
    for(const version of [...order.slice(index%order.length),...order.slice(0,index%order.length)]) {
      const result=await job(sample.id+'_V'+version+'_GEN','GENERATION',sample,version,
        version===protocol.versions[0]?'/api/v2/evaluation/baseline':'/api/v2/evaluation/candidate',input(sample));
      await checkHistory(result);
    }
  }
  for (const id of protocol.regressionGeneration ?? []) {
    const sample=cases.find(x=>x.id===id);
    const version=protocol.candidateVersions[0];
    const result=await job(id+'_V'+version+'_GEN','GENERATION',sample,version,'/api/v2/evaluation/candidate',input(sample));
    await checkHistory(result);
  }
  for(const id of protocol.plan) {
    const sample=cases.find(x=>x.id===id);
    const prior=await optionalJson(resolve(root,'results',id+'_V'+protocol.versions[0]+'_PLAN.json'));
    let reference=prior?.request.planningContext;
    if(!reference) {
      currentJob=null;
      const prepared=await api('/api/v2/context/planning',{rawPrompt:sample.rawPrompt,
        context:input(sample).context,permissionPolicy:input(sample).permissionPolicy});
      if(prepared.status!==200) throw Error('Context preparation failed');
      reference={contextId:prepared.body.data.contextId,version:prepared.body.data.version};
    }
    for(const version of protocol.versions) {
      await job(id+'_V'+version+'_PLAN','PLAN',sample,version,'/api/v2/evaluation/plan-baseline',{
        rawPrompt:sample.rawPrompt,contextDescription:'',conversationHistory:[],modelId:protocol.modelId,planningContext:reference});
    }
    if(operation !== 'generate' && protocol.confirmedPlanGeneration.includes(id)) {
      for(const version of protocol.versions) {
        const confirmation = await versionConfirmation(sample,version);
        if (!confirmation) { log({id,version,generation:'NO_VALID_BOUND_PLAN'}); continue; }
        const result=await job(id+'_V'+version+'_PLAN_GEN','GENERATION',sample,version,
          version===protocol.versions[0]?'/api/v2/evaluation/baseline':'/api/v2/evaluation/candidate',
          {...input(sample),planConfirmation:confirmation});
        await checkHistory(result);
      }
    }
  }
  for (const check of historyChecks) await saveConsistent(resolve(evidence,'history-checks',check.recordId+'.json'),check);
  if(historyChecks.some(x=>x.status!==200||!x.bodyEqual||!x.versionEqual||!x.noDefaultRules||!x.rulesEqual||!x.noInternalReviewOnWire))
    throw Error('History consistency failed');
  }
  if (operation === 'generate' || operation === 'confirm') return;
  for(let index=0;index<protocol.execution.length;index++) {
    const sample=cases.find(x=>x.id===protocol.execution[index]);
    const arms=[...(['ORIGINAL']),...protocol.versions.map(version=>'V'+version)];
    for(const arm of [...arms.slice(index%arms.length),...arms.slice(0,index%arms.length)]) {
      let prompt=sample.rawPrompt;
      if(arm!=='ORIGINAL') {
        const suffix=protocol.confirmedPlanGeneration.includes(sample.id)?'_PLAN_GEN':'_GEN';
        const result=await optionalJson(resolve(root,'results',sample.id+'_'+arm+suffix+'.json'));
        if(result?.status!==200 || typeof result.body?.data?.optimizedPrompt !== 'string' || !result.body.data.optimizedPrompt.trim()) {log({id:sample.id,arm,execution:'NO_VALID_PROMPT'});continue;}
        prompt=result.body.data.optimizedPrompt;
      }
      const request=executionRequest(sample,prompt);
      for(let repeat=1;repeat<=protocol.executionRepeats;repeat++) {
        await job(sample.id+'_'+arm+'_EXEC_R'+repeat,'EXECUTION',sample,arm,'/api/v2/evaluation/execute',request,request);
      }
    }
  }
}

try {
  if (process.argv[2] === 'count') log(await count());
  else if (['all','generate','confirm','execute','review','author'].includes(process.argv[2])) { await assertOpen(); await login(); await run(); }
  else throw Error('Unknown frozen single-pass evaluation operation');
} finally { terminal.close(); }
