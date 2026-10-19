import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createInterface } from 'node:readline/promises';

const root = resolve('tmp/natural-output-focus-20261019');
const evidence = resolve('docs/testing/evidence/natural-output-focus-2026-10-19');
const origin = 'http://127.0.0.1:9002';
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const protocol = await json(resolve(evidence, 'protocol.json'));
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
    includeExamples: false, promptFormat: 'COMPACT' },
  conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] }, planConfirmation: null
});

async function run() {
  for (let index = 0; index < protocol.generation.length; index++) {
    const sample = cases.find(row => row.id === protocol.generation[index]);
    for (const version of index % 2 ? ['3', '2'] : ['2', '3']) {
      await job(sample.id + '_V' + version + '_GEN', 'GENERATION', sample, version,
        version === '2' ? '/api/v2/evaluation/baseline' : '/api/v2/optimizations', input(sample));
    }
  }
  for (const id of protocol.plan) {
    const sample = cases.find(row => row.id === id);
    currentJob = null;
    const prepared = await api('/api/v2/context/planning', { rawPrompt: sample.rawPrompt, context: input(sample).context,
      permissionPolicy: input(sample).permissionPolicy });
    if (prepared.status !== 200) throw Error('Planning context preparation failed');
    for (const version of ['3', '2']) {
      await job(sample.id + '_V' + version + '_PLAN', 'PLAN', sample, version, '/api/v2/optimizations/plan', {
        rawPrompt: sample.rawPrompt, contextDescription: '', conversationHistory: [], modelId: protocol.modelId,
        planningContext: { contextId: prepared.body.data.contextId, version: prepared.body.data.version }
      });
    }
  }
  for (const id of protocol.execution) {
    const sample = cases.find(row => row.id === id);
    for (const version of ['2', '3']) {
      const generated = await json(resolve(root, 'results', sample.id + '_V' + version + '_GEN.json'));
      if (generated.status !== 200) { log({ id, version, execution: 'NO_VALID_PROMPT' }); continue; }
      const request = { model: protocol.upstreamModel, stream: false, temperature: 0.2, max_tokens: 8192,
        thinking: { type: 'disabled' }, messages: [
          { role: 'system', content: '执行用户请求并交付其要求的作品。无法访问外部工具或文件，不声称已经运行、联网或完成未执行的操作。' },
          { role: 'user', content: generated.body.data.optimizedPrompt }
        ] };
      await job(sample.id + '_V' + version + '_EXEC', 'EXECUTION', sample, version,
        '/api/v2/evaluation/execute', request, request);
    }
  }
}

try {
  if (process.argv[2] === 'count') log(await count());
  else if (process.argv[2] === 'all') { await login(); await run(); }
  else throw Error('Unknown frozen operation');
} finally {
  terminal.close();
}
