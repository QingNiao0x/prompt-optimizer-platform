import { createInterface } from 'node:readline/promises';
import { readFile, writeFile, mkdir, readdir, stat } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const stage = process.argv[2];
const id = process.argv[3];
if (!/^[a-z0-9-]+$/.test(stage ?? '') || !id) throw Error('Usage: node run-live.mjs <stage> <COUNT|LOGIN|job-id>');
const stageEvidence = resolve(evidence, stage);
const root = resolve(repo, 'tmp/natural-writer-input70-2026-10-29', stage);
const origin = 'http://127.0.0.1:9014';
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const protocol = await json(resolve(stageEvidence, 'protocol.json'));
const runnerTimeout = protocol.naturalInferenceProfile?.name === 'BODY_FIRST_REASONING_V1'
  && ['62','63','64','65','66','67','68','69','70'].includes(protocol.naturalInferenceProfile?.version) && protocol.naturalInferenceProfile?.runnerTimeoutMs === 1140000 ? 1140000
  : protocol.naturalInferenceProfile?.name === 'BODY_FIRST_REASONING_V1'
  && ['59','61'].includes(protocol.naturalInferenceProfile?.version) && protocol.naturalInferenceProfile?.runnerTimeoutMs === 780000 ? 780000
  : protocol.naturalInferenceProfile?.name === 'SINGLE_PASS_REASONING_V1'
  && protocol.naturalInferenceProfile?.runnerTimeoutMs === 420000 ? 420000
  : protocol.experimentProfile === undefined ? 240000
  : protocol.experimentProfile === 'WRITER_THINKING_ABLATION_V1' && protocol.writerThinkingProfile?.runnerTimeoutMs === 360000
    ? 360000 : (() => { throw Error('Unknown future experiment timeout profile'); })();
const count = async () => {
  let total = 0;
  const phases = {};
  let directories = [];
  try { directories = await readdir(dirname(root), { withFileTypes: true }); }
  catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  for (const directory of directories) {
    if (!directory.isDirectory()) continue;
    try {
      for (const trace of (await readdir(resolve(dirname(root), directory.name, 'traces'))).filter(file => file.endsWith('.json'))) {
        total++;
        let phase = 'UNRESOLVED_RESERVATION';
        try { phase = (await json(resolve(dirname(root), directory.name, 'traces', trace))).phase ?? phase; } catch { /* 失败或中断预留同样计数。 */ }
        phases[phase] = (phases[phase] ?? 0) + 1;
      }
    } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  }
  return { priorActual: 0, newActual: total, taskActual: total, taskMaximum: 48, remaining: 48 - total, phases };
};
if (id === 'COUNT') { console.log(JSON.stringify(await count())); process.exit(0); }
for (const path of [evidence, stageEvidence, dirname(root), root]) {
  try { await stat(resolve(path, 'closure.json')); throw Error('Closed batch'); } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
}
if (protocol.status !== 'FROZEN' || (await count()).remaining < 1) throw Error('Not frozen or budget exhausted');
const job = id === 'LOGIN' ? null : (await json(resolve(stageEvidence, 'jobs.json'))).find(value => value.id === id);
if (id !== 'LOGIN' && (!job || !protocol.allowedJobs.includes(id))) throw Error('Unknown frozen job');
const output = resolve(root, 'results', id + '.json');
if (id !== 'LOGIN') {
  try { await stat(output); throw Error('Job result already exists; quality retry prohibited'); } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  for (const file of (await readdir(resolve(root, 'traces'))).filter(path => path.endsWith('.json'))) {
    if ((await json(resolve(root, 'traces', file))).jobId === id) throw Error('Job already attempted');
  }
}
const account = await json(resolve(repo, 'tmp/local-acceptance/account.json'));
const cookies = new Map();
const browserCookies = new Map();
const privateSessionPath = resolve(root, 'auth/session.json');
const browserStoragePath = resolve(root, 'auth/browser-storage-state.json');
let csrf;
const headers = () => ({ Cookie: [...cookies].map(([key, value]) => key + '=' + value).join('; ') });
const captureCookies = response => {
  for (const line of response.headers.getSetCookie()) {
    const parts = line.split(';').map(part => part.trim());
    const equals = parts[0].indexOf('=');
    const name = parts[0].slice(0, equals);
    const value = parts[0].slice(equals + 1);
    const attributes = new Map(parts.slice(1).map(part => { const split = part.indexOf('='); return split < 0 ? [part.toLowerCase(), true] : [part.slice(0, split).toLowerCase(), part.slice(split + 1)]; }));
    if (attributes.get('max-age') === '0') { cookies.delete(name); browserCookies.delete(name); continue; }
    cookies.set(name, value);
    const sameSite = String(attributes.get('samesite') ?? 'Lax').toLowerCase();
    const expiration = typeof attributes.get('expires') === 'string' ? Date.parse(attributes.get('expires')) / 1000 : -1;
    browserCookies.set(name, { name, value, domain: String(attributes.get('domain') ?? '127.0.0.1'), path: String(attributes.get('path') ?? '/'),
      expires: Number.isFinite(expiration) ? expiration : -1, httpOnly: attributes.has('httponly'), secure: attributes.has('secure'),
      sameSite: sameSite === 'strict' ? 'Strict' : sameSite === 'none' ? 'None' : 'Lax' });
  }
};
async function savePrivateSession() {
  await mkdir(dirname(privateSessionPath), { recursive: true });
  await writeFile(privateSessionPath, JSON.stringify({ cookies: [...cookies], browserCookies: [...browserCookies.values()], csrf }) + '\n', { mode: 0o600 });
  await writeFile(browserStoragePath, JSON.stringify({ cookies: [...browserCookies.values()], origins: [] }) + '\n', { mode: 0o600 });
}
async function api(path, body, modelJob = false) {
  const requestHeaders = headers();
  if (modelJob) requestHeaders['X-Evaluation-Job'] = id;
  if (body !== undefined) { requestHeaders['Content-Type'] = 'application/json'; requestHeaders[csrf?.headerName ?? 'X-XSRF-TOKEN'] = csrf?.token ?? ''; }
  const response = await fetch(origin + path, { method: body === undefined ? 'GET' : 'POST', headers: requestHeaders,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(runnerTimeout) });
  captureCookies(response);
  return { status: response.status, body: await response.json() };
}
if (id === 'LOGIN') {
  if (!process.env.POP_EVAL_PASSWORD) throw Error('Canonical credential must be injected locally; do not use command-line password');
  csrf = (await api('/api/v1/auth/csrf')).body.data;
  const challenge = await fetch(origin + '/api/v1/auth/captcha', { headers: headers() });
  captureCookies(challenge);
  if (!challenge.ok) throw Error('Captcha unavailable; no model call was made');
  await mkdir(resolve(root, 'auth'), { recursive: true });
  const captchaPath = resolve(root, 'auth', `captcha-${Date.now()}.png`);
  await writeFile(captchaPath, Buffer.from(await challenge.arrayBuffer()), { flag: 'wx' });
  console.log(JSON.stringify({ event: 'CAPTCHA_READY', path: captchaPath }));
  const terminal = createInterface({ input: process.stdin, output: process.stdout });
  let captcha;
  try { captcha = (await terminal.question('CAPTCHA_INPUT> ')).trim(); } finally { terminal.close(); }
  const login = await api('/api/v1/auth/login', { identifier: account.identifier, password: process.env.POP_EVAL_PASSWORD, captcha });
  delete process.env.POP_EVAL_PASSWORD;
  if (login.status !== 200) throw Error('Canonical login failed: HTTP ' + login.status);
} else {
  const session = await json(privateSessionPath);
  for (const [name, value] of session.cookies) cookies.set(name, value);
  for (const cookie of session.browserCookies) browserCookies.set(cookie.name, cookie);
  csrf = session.csrf;
}
const identity = await api('/api/v1/auth/me');
if (identity.status !== 200 || identity.body?.data?.userId !== account.userId || identity.body?.data?.tenantId !== account.tenantId || identity.body?.data?.workspaceId !== account.workspaceId) throw Error('Canonical session expired or identity mismatch; login once before continuing');
csrf = (await api('/api/v1/auth/csrf')).body.data;
await savePrivateSession();
if (id === 'LOGIN') { console.log(JSON.stringify({ event: 'CANONICAL_LOGIN_READY', privateBrowserStoragePath: browserStoragePath, modelCalls: 0 })); process.exit(0); }
await writeFile(resolve(root, 'active-job.json'), JSON.stringify(job, null, 2) + '\n');
const path = job.entrypoint === 'DEFAULT' ? (job.phase === 'PLAN' ? '/api/v2/optimizations/plan' : '/api/v2/optimizations') : job.phase === 'PLAN' ? '/api/v2/evaluation/plan-baseline' : ['EXECUTION','REVIEW'].includes(job.phase) ? '/api/v2/evaluation/execute' : '/api/v2/evaluation/candidate';
let result;
try { result = await api(path, job.approvedModelRequest ?? job.approvedEnhancementInput, true); }
catch (failure) { result = { status: 0, transportFailure: failure.name }; }
await mkdir(dirname(output), { recursive: true });
await writeFile(output, JSON.stringify({ id, phase: job.phase, version: job.version, caseId: job.caseId, ...result }, null, 2) + '\n', { flag: 'wx' });
if (job.phase === 'GENERATION' && result.status === 200 && result.body?.data?.recordId) {
  const generated = result.body.data;
  const history = await api('/api/v2/optimization-history/' + generated.recordId);
  await writeFile(resolve(root, 'results', id + '-history.json'), JSON.stringify({ status: history.status,
    bodyEqual: history.body?.data?.optimizedPrompt === generated.optimizedPrompt,
    versionEqual: history.body?.data?.chainVersion === generated.chainVersion,
    processingEqual: JSON.stringify(history.body?.data?.processing) === JSON.stringify(generated.processing) }, null, 2) + '\n', { flag: 'wx' });
}
console.log(JSON.stringify({ id, status: result.status, ...(await count()) }));
