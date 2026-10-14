import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
const root = resolve('tmp/me00a-20261012'), out = resolve(root, 'candidate11-direct-copy'); await mkdir(out, { recursive: true });
const protocol = JSON.parse(await readFile(resolve(root, 'protocol.json'), 'utf8'));
if (protocol.activeBatch.version !== 'candidate11' || protocol.activeBatch.authorizationStatus !== 'APPROVED_BY_USER'
    || protocol.activeBatch.maximumNewCalls !== 1) throw new Error('Last fixed generation allowance required');
const account = JSON.parse(await readFile(resolve('tmp/local-acceptance/account.json'), 'utf8'));
const require = createRequire(resolve('apps/web/package.json'));
const browser = await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
const page = browser.contexts()[0].pages().find(page => page.url().startsWith('http://127.0.0.1:9001/'));
const save = (path, value) => writeFile(path, typeof value === 'string' ? value : JSON.stringify(value, null, 2), { flag: 'wx' });
async function api(path, body) {
  return page.evaluate(async ({ path, body }) => {
    const csrf = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
    const response = await fetch(path, { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json',
      ...(csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf) } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: response.status, body: await response.json() };
  }, { path, body });
}
const me = await api('/api/v1/auth/me');
if (me.status !== 200 || me.body.data.userId !== account.userId || me.body.data.tenantId !== account.tenantId) throw new Error('Fixed account mismatch');
const request = JSON.parse(await readFile(resolve(root, 'candidate9-plan-r1/Q05-flash/direct-equal-information/input.json'), 'utf8'));
await save(resolve(out, 'input.json'), request);
const result = await api('/api/v1/optimizations', request); await save(resolve(out, 'platform.json'), result);
if (result.status !== 200) throw new Error(`Last generation HTTP ${result.status}; no retries beyond allowance`);
const copied = result.body.data.optimizedPrompt; await save(resolve(out, 'copied.md'), copied);
const history = await api('/api/v1/optimization-history?current=1&size=10');
let matched = false;
for (const row of history.body.data.records) {
  const detail = await api(`/api/v1/optimization-history/${row.id}`);
  if (detail.body.data.rawPrompt === request.rawPrompt && detail.body.data.optimizedPrompt === copied) {
    await save(resolve(out, 'history.json'), detail); matched = true; break;
  }
}
if (!matched) throw new Error('Copy/history mismatch');
await save(resolve(out, 'checks.json'), { sameAccount: true, copiedEqualsHistory: true,
  copySha256: createHash('sha256').update(copied).digest('hex'),
  repeatedDelegationSourceLines: copied.split(/\r?\n/).filter(line => /^- 请根据原始研究目标提出可开展的推荐设计/.test(line)),
  actualDataStillUnknown: /(?:未确认|不确认|尚未|没有额外)/.test(copied),
  downstreamWorkNotGenerated: true, noNewBaseline: true, noScriptAnswerRepair: true });
console.log('DONE candidate11 direct copy and history');
