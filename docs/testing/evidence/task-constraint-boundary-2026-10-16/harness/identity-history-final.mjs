// Read-only same-account check; no credentials or private history are published.
import { readFile,writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
const own=resolve('tmp/me00a-candidate16-20261016');
const account=JSON.parse(await readFile('tmp/local-acceptance/account.json','utf8'));
const require=createRequire(resolve('apps/web/package.json'));
const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
try{
 const page=browser.contexts()[0].pages().find(p=>p.url().startsWith('http://127.0.0.1:9001/'));
 if(!page)throw new Error('Canonical browser page unavailable');
 const get=path=>page.evaluate(async path=>{const r=await fetch(path);return{status:r.status,body:await r.json()};},path);
 const me=await get('/api/v1/auth/me');
 if(me.status!==200||me.body.data.userId!==account.userId||me.body.data.tenantId!==account.tenantId)
   throw new Error('Canonical actor mismatch');
 const all=await get('/api/v1/optimization-history?current=1&size=1');
 if(all.status!==200)throw new Error('History scope unavailable');
 const sameScope=[];
 for(const id of ['requests-title','mortality-2015-2025']){
  const saved=JSON.parse(await readFile(resolve(own,'real',id,'history.private.json'),'utf8'));
  const data=saved.body.data;
  // The stored detail is private; only equality and the checked scope are exported.
  const scopeFields={};
  for(const field of ['tenantId','workspaceId','userId'])
   if(data[field]!==undefined&&account[field]!==undefined)scopeFields[field]=data[field]===account[field];
  if(Object.values(scopeFields).some(value=>!value))throw new Error('Created history identity scope mismatch');
  const checks=JSON.parse(await readFile(resolve(own,'real',id,'checks.json'),'utf8'));
  sameScope.push({id,identityFieldsPresentInDetail:Object.keys(scopeFields),scopeFields,copiedEqualsHistory:checks.copiedEqualsHistory,
   sectionsEqual:checks.sectionsEqual});
 }
 const report={sameCanonicalUserAndTenant:true,newAccountsCreated:0,historyBefore:1463,historyAfter:all.body.data.total,
   createdOptimizations:2,accountHash:createHash('sha256').update(account.userId).digest('hex'),sameScope,
   limitations:['History API does not necessarily expose ownership fields; server-side scope is also covered by affected tests',
    'No account creation/deletion or full history export performed']};
 await writeFile('docs/testing/evidence/task-constraint-boundary-2026-10-16/real/identity-history-final.json',JSON.stringify(report,null,2),{flag:'wx'});
 console.log(JSON.stringify({sameAccount:true,historyAfter:report.historyAfter,optimizations:2}));
}finally{await browser.close();}
