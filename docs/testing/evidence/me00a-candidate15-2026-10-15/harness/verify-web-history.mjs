import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const require=createRequire(resolve('apps/web/package.json'));
const account=JSON.parse(await readFile(resolve('tmp/local-acceptance/account.json'),'utf8'));
const live=resolve('tmp/me00a-20261012/candidate15-real');
const checks=[];
const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
try {
  const page=browser.contexts()[0].pages().find(p=>p.url().startsWith('http://127.0.0.1:9001/'));
  if(!page)throw new Error('Canonical account browser missing');
  const api=path=>page.evaluate(async path=>{const r=await fetch(path);return {status:r.status,body:await r.json()};},path);
  const me=await api('/api/v1/auth/me');
  if(me.status!==200||me.body.data.userId!==account.userId||me.body.data.tenantId!==account.tenantId)throw new Error('Canonical identity drift or session expired');
  for(const arm of ['plan']) {
    const original=JSON.parse(await readFile(resolve(live,arm,'history.json'),'utf8'));
    const copied=await readFile(resolve(live,arm,'copied.md'),'utf8');
    const input=JSON.parse(await readFile(resolve(live,arm,'input.json'),'utf8'));
    const current=await api(`/api/v1/optimization-history/${original.body.data.id}`);
    if(current.status!==200||current.body.data.optimizedPrompt!==copied||current.body.data.rawPrompt!==input.rawPrompt)throw new Error('History no longer matches actual input/copy');
    checks.push({arm,accessible:true,rawPromptMatches:true,copiedBodyMatches:true});
  }
  const list=await api('/api/v1/optimization-history?current=1&size=1');
  const result={verifiedAt:new Date().toISOString(),sameCanonicalAccount:true,newAccountsCreated:0,checks,
    visibleHistoryTotal:list.status===200?list.body.data.total:null,modelRequestsFromVerification:0};
  await writeFile(resolve('docs/testing/evidence/me00a-candidate15-2026-10-15/live-history-verification.json'),JSON.stringify(result,null,2),{flag:'wx'});
  console.log(JSON.stringify(result));
} finally {await browser.close();}
