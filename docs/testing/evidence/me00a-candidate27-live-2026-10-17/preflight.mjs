// This stage authenticates only the canonical fixture and checks immutable artifacts; it never invokes a model.
import {readFile,writeFile,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
const require=createRequire(resolve('apps/web/package.json'));
const evidence=resolve('docs/testing/evidence/me00a-candidate27-live-2026-10-17');
const output=resolve('tmp/me00a-candidate27-live-20261017');
const read=async file=>JSON.parse(await readFile(file,'utf8'));
const hash=value=>createHash('sha256').update(value).digest('hex');
const manifest=await read('docs/testing/evidence/me00a-candidate27-offline-2026-10-17/candidate27-source.json');
for(const [items,roots] of [[manifest.sourceFiles,['.',manifest.sourceSnapshot]],
  [manifest.runtimeFiles,[manifest.runtimeSnapshot]]]){
  for(const root of roots)for(const file of items){
    if(hash(await readFile(resolve(root,file.path)))!==file.sha256)throw Error(`Frozen artifact drift: ${file.path}`);
  }
}
const before=(await readdir('tmp/me00a-20261012/traces')).filter(name=>/^\d{3}\.json$/.test(name)).length;
const account=await read('tmp/local-acceptance/account.json');
const browser=await require('@playwright/test').chromium.connectOverCDP('http://127.0.0.1:9325');
try{
  const context=browser.contexts()[0];
  const meResponse=await context.request.get('http://127.0.0.1:9001/api/v1/auth/me');
  const me=(await meResponse.json()).data;
  if(!meResponse.ok()||me.userId!==account.userId||me.tenantId!==account.tenantId)throw Error('Canonical identity mismatch');
  const history=await context.request.get('http://127.0.0.1:9001/api/v1/optimization-history?current=1&size=10');
  if(!history.ok())throw Error(`History not available: ${history.status()}`);
  const historyData=(await history.json()).data;
  const page=await context.newPage();
  await context.grantPermissions(['clipboard-read','clipboard-write'],{origin:'http://127.0.0.1:5174'});
  // No browser-side generation is allowed in preflight, including accidental calls from restored state.
  await page.route('**/api/v1/optimizations**',route=>route.abort('blockedbyclient'));
  await page.goto('http://127.0.0.1:5174/workbench',{waitUntil:'domcontentloaded'});
  await page.locator('.workbench-grid').waitFor({state:'visible',timeout:30000});
  await page.screenshot({path:resolve(output,'preflight-workbench.png'),fullPage:true});
  const after=(await readdir('tmp/me00a-20261012/traces')).filter(name=>/^\d{3}\.json$/.test(name)).length;
  if(after!==before)throw Error('Unexpected model trace during preflight');
  const report={status:'PREFLIGHT_ONLY_NOT_REAL_MODEL_ACCEPTANCE',sameAccount:true,newAccountsCreated:0,
    userHash:hash(account.userId),sourceTreeSha256:manifest.sourceTreeSha256,runtimeTreeSha256:manifest.runtimeTreeSha256,
    sourceFilesVerified:manifest.sourceFiles.length,runtimeFilesVerified:manifest.runtimeFiles.length,
    sourceAndRuntimeMatch:true,historyReadable:true,historyBefore:historyData.total,
    workbenchVisible:true,isolatedBackend:9001,isolatedFrontend:5174,modelCalls:0,actualCallsBefore:before,actualCallsAfter:after};
  await writeFile(resolve(evidence,'preflight.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify(report));
  await page.close();
}finally{await browser.close();}
