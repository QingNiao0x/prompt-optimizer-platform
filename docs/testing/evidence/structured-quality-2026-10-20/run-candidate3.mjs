import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createInterface } from 'node:readline/promises';

const root = resolve('tmp/structured-quality-20261020');
const evidence = resolve('docs/testing/evidence/structured-quality-2026-10-20');
const origin = 'http://127.0.0.1:9002';
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const protocol = await json(resolve(evidence, 'protocol-candidate3.json'));
const cases = (await json(resolve(evidence, 'cases.json'))).cases;
const account = await json(resolve('tmp/local-acceptance/account.json'));
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
  try { const result = await json(output); log({ id, state: 'ALREADY_RECORDED' }); return result; }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  if ((await readdir(resolve(root, 'traces'))).filter(name => name.endsWith('.json')).length >= protocol.maximumNewActualCalls) {
    throw Error('Actual call allowance exhausted');
  }
  for (const name of (await readdir(resolve(root, 'traces'))).filter(name => name.endsWith('.json'))) {
    const row = await json(resolve(root, 'traces', name));
    if (row.jobId === id) throw Error('Attempt already reserved: ' + id);
  }
  currentJob = id;
  await writeFile(resolve(root, 'active-job.json'), encode({ id, phase, caseId: sample.id, version, approvedOriginalPrompt: sample.rawPrompt,
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
    includeExamples: false, promptFormat: 'STRUCTURED' },
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
    structured: generated.promptFormat === 'STRUCTURED',
    rulesEqual: JSON.stringify(history.body.data?.constraintGroups) === JSON.stringify(generated.constraintGroups),
    noInternalReviewOnWire: !Object.hasOwn(history.body.data ?? {}, 'taskUnderstanding') });
}

/** 每一臂都携带同一份材料，不向原始臂隐去增强时已经给定的信息。 */
function executionRequest(sample, prompt, review = false) {
  const material = sample.files.length ? '\n\n用户同时提供的材料：\n' + JSON.stringify(sample.files) : '';
  return { model: protocol.upstreamModel, stream: false, temperature: protocol.parameters.temperature,
    max_tokens: review ? protocol.parameters.reviewMaxTokens : protocol.parameters.executionMaxTokens,
    thinking: { type: 'disabled' }, messages: [
      { role: 'system', content: review
        ? '你是从实际用户角度检查作品的辅助评审。仅依据原始任务和给定材料，不以篇幅、术语或额外承诺打高分。输出要求的JSON，不输出推理过程。'
        : '执行用户请求并交付其要求的作品。无法访问外部工具或文件，不声称已经运行、联网或完成未执行的操作。' },
      { role: 'user', content: prompt + material },
    ] };
}


async function run() {
 const sample=cases.find(row=>row.id==='EVENT_PLAN');
 const body={...input(sample),planConfirmation:await json(resolve(evidence,'actual-confirmation.json'))};
 const result=await job('EVENT_PLAN_C3_GEN','GENERATION',sample,'STRUCTURED_C3','/api/v2/optimizations',body);
 await checkHistory(result);
 await save(resolve(evidence,'history-checks-candidate3.json'),{checks:historyChecks,newAccounts:0});
 if(result.status!==200) return;
 await save(resolve(evidence,'EVENT_PLAN-candidate3-native-copy.md'),result.body.data.optimizedPrompt);
 const prompt=await readFile(resolve(evidence,'EVENT_PLAN-candidate3-native-copy.md'),'utf8');
 if(prompt!==result.body.data.optimizedPrompt) throw Error('Native body drift');
 const request=executionRequest(sample,prompt);
 await job('EVENT_PLAN_C3_STRUCTURED_EXEC','EXECUTION',sample,'C3_STRUCTURED','/api/v2/evaluation/execute',request,request);
}
async function review() {
  const { rubricInstruction } = await import('../../../../scripts/me00a-rubric.mjs');
  const audit = await json(resolve(evidence, 'independent-checks-candidate3.json'));
  const mapping = [];
  for (const [reviewer, id] of ['FINAL_REVIEW_FLASH', 'FINAL_REVIEW_PRO'].entries()) {
    const suite = [];
    for (const [index, sample] of cases.entries()) {
      const arms=sample.id==='EVENT_PLAN'?['RAW','STRUCTURED','C2_STRUCTURED','C3_STRUCTURED']:['RAW','STRUCTURED','C2_STRUCTURED']; const offset=(reviewer+index)%arms.length; const order=[...arms.slice(offset),...arms.slice(0,offset)];
      const works = [];
      for (const [k, arm] of order.entries()) {
        let result;
        try { result = await json(resolve(root, 'results', sample.id + '_' + arm + '_EXEC.json')); }
        catch (failure) { if (failure.code === 'ENOENT') continue; throw failure; }
        const label = String.fromCharCode(65+k);
        const mechanical = audit.works.find(row => row.caseId === sample.id && row.arm === arm);
        // 复制检查记录后移除臂标签，既保持盲评，也不改变下一位评审看到的原检查。
        const checks = mechanical ? Object.fromEntries(Object.entries(mechanical).filter(([key]) => key !== 'arm')) : undefined;
        works.push({ label, content: result.body.data?.content ?? '', checks });
        mapping.push({ reviewer: id, caseId: sample.id, label, arm });
      }
      suite.push({ caseId: sample.id, originalRequest: sample.rawPrompt, materials: sample.files,
        confirmedInformation: sample.planAnswer ?? null, criteria: sample.checks, works });
    }
    const prompt = rubricInstruction() + '\n检查以下实际作品，不猜测生成方式。只返回JSON：'
      + '{"cases":[{"caseId":"题号","works":[{"label":"A、B、C或D","scores":{"accuracy":1,"completeness":1,"clarity":1,"executability":1},'
      + '"issues":[{"claim":"问题及影响","quote":"作品逐字原句；遗漏时可空"}],"directlyUsable":true}],'
      + '"preferredLabels":["可并列，也可为空"],"reason":"依据"}]}。\n' + JSON.stringify(suite);
    const request = executionRequest({files:[]}, prompt, true);
    if (reviewer) request.model = 'deepseek-v4-pro';
    await job(id, 'REVIEW', {id:'SUITE'}, 'BLINDED', '/api/v2/evaluation/execute', request, request);
  }
  await save(resolve(evidence, 'review-mapping-candidate3.json'), mapping);
}

try {
  if (process.argv[2] === 'count') log(await count());
  else if (process.argv[2] === 'all') { await login(); await run(); }
  else if (process.argv[2] === 'review') { await login(); await review(); }
  else throw Error('Unknown frozen operation');
} finally { terminal.close(); }
