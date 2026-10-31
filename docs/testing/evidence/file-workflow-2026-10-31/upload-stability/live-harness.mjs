import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
const {ensureLogin}=await import(pathToFileURL(path.join(process.cwd(),'docs/testing/evidence/index-upload-live-2026-10-30/harness/ensure-live-login.mjs')).href);
const root=process.cwd(),supplement=process.env.UPLOAD_SUPPLEMENT==='true',out=path.join(root,'tmp/upload-stability-20261031',supplement?'supplement':'live');
await fs.mkdir(out,{recursive:true});
const require=createRequire(path.join(root,'apps/web/package.json'));
const {chromium,expect}=require('@playwright/test'),{createServer,transformWithEsbuild}=require('vite');
const baseline=execFileSync('git',['show','779a3d8c:apps/web/src/features/document-upload/documentUploader.ts'],{encoding:'utf8',windowsHide:true});
const server=await createServer({configFile:path.join(root,'apps/web/vite.config.ts'),root:path.join(root,'apps/web'),
 plugins:[{name:'upload-baseline',resolveId(id){if(id==='/baseline-uploader.ts')return '\0baseline-uploader.ts'},async load(id){if(id==='\0baseline-uploader.ts')return (await transformWithEsbuild(baseline,'baseline.ts',{loader:'ts'})).code}}],
 server:{host:'127.0.0.1',port:5198,strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:19000',changeOrigin:true}}},logLevel:'error'});
await server.listen();
const report={startedAt:new Date().toISOString(),baseline:'779a3d8c',baselineSha256:createHash('sha256').update(baseline).digest('hex'),
 mockHttp:false,paidModels:false,comparisons:[],faults:[],crossSession:[],errors:[]};
const browsers=[],sessions=[],save=()=>fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));
let retained;
try{
 for(const channel of ['chrome','msedge']){
  const browser=await chromium.launch({channel,headless:true});browsers.push(browser);
  const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'}),page=await context.newPage();
  page.on('pageerror',e=>report.errors.push({channel,message:e.message}));
  await ensureLogin(context,page,out);await page.goto('http://127.0.0.1:5198/workbench');
  await expect(page.getByRole('button',{name:'添加上下文'})).toBeVisible({timeout:60000});
  const api=async(method,url,data)=>{const csrf=decodeURIComponent((await context.cookies()).find(x=>x.name==='XSRF-TOKEN')?.value||'');
    const r=await context.request.fetch('http://127.0.0.1:5198'+url,{method,data,headers:{'X-XSRF-TOKEN':csrf}});return {status:r.status(),body:await r.json()}};
  sessions.push({context,api,ids:[]});const group=sessions.at(-1);
  if(retained){const observed=await api('GET',`/api/v1/context/documents/${retained}`);report.crossSession.push({channel,status:observed.status,phase:observed.body.data?.phase});}
  let delayAck=0,active=0,maxActive=0,dropComplete=false,dropStatus=false,completeLost=false,statusLost=false;
  await page.route('**/api/v1/context/documents/**',async route=>{
    const u=new URL(route.request().url());
    if(/\/chunks\/\d+$/.test(u.pathname)){
      active++;maxActive=Math.max(maxActive,active);
      try{const response=await route.fetch();if(delayAck)await new Promise(r=>setTimeout(r,delayAck));await route.fulfill({response});}
      finally{active--;}return;
    }
    if(u.pathname.endsWith('/complete')&&dropComplete&&!completeLost){completeLost=true;await route.fetch();await route.abort('connectionreset');return;}
    if(route.request().method()==='GET'&&dropStatus&&!statusLost&&!u.pathname.includes('/pages/')){statusLost=true;await route.abort('connectionreset');return;}
    await route.continue();
  });
  const run=async(mode,ackDelay,bytes=12*1024*1024)=>{
    delayAck=ackDelay;active=0;maxActive=0;
    const result=await page.evaluate(async({mode,bytes})=>{
      const module=await import(mode==='baseline'?'/baseline-uploader.ts':'/src/features/document-upload/documentUploader.ts');
      const encoder=new TextEncoder(),head='HEAD_VERIFICATION_20261031\n',tail='\nTAIL_VERIFICATION_20261031';
      const content=head+'bounded complete document line\n'.repeat(Math.ceil(bytes/31)).slice(0,bytes-head.length-tail.length)+tail;
      const source=new File([encoder.encode(content)],'synthetic-full.txt',{type:'text/plain'}),events=[];
      const start=performance.now();let uploadMs=0;
      const result=await module.uploadAndIndexDocument(source,source.name,'text',p=>{if(p.phase==='UPLOADING')uploadMs=performance.now()-start;events.push({phase:p.phase,percent:p.percent,bytes:p.uploadedBytes,time:Math.round(performance.now()-start)})});
      return {status:result.status,sourceBytes:source.size,sourceCharacters:content.length,totalMs:Math.round(performance.now()-start),uploadMs:Math.round(uploadMs),events};
    },{mode,bytes});
    group.ids.push(result.status.documentId);
    const q=await api('POST','/api/v1/context/analyze',{customDescription:'查找 TAIL_VERIFICATION_20261031 尾部核验内容',files:[{path:result.status.path,language:'text',content:'',documentId:result.status.documentId,sizeBytes:result.sourceBytes}]});
    const entry={channel,mode,ackDelayMs:ackDelay,maxConcurrentChunks:maxActive,...result,
      tailFound:JSON.stringify(q.body.data?.fileSnippets||[]).includes('TAIL_VERIFICATION_20261031'),analysisStatus:q.status};
    delete entry.status.documentId;
    entry.complete=entry.status.phase==='READY'&&entry.status.uploadedBytes===entry.sourceBytes
      &&entry.status.extractedCharacters===entry.sourceCharacters&&entry.tailFound;
    const id=group.ids.at(-1);
    await save();return {entry,id};
  };
  // 同浏览器内按 ABBA 轮换，80ms 是受控响应延迟，不冒充生产网络实测。
  for(const mode of supplement?['baseline','candidate']:['baseline','candidate','candidate','baseline']){
    const {entry,id}=await run(mode,supplement?0:80);report.comparisons.push(entry);
    console.log('COMPARE',channel,mode,entry.totalMs,entry.uploadMs,entry.maxConcurrentChunks,entry.complete);
    if(channel==='chrome'&&!retained&&mode==='candidate')retained=id;else await api('DELETE',`/api/v1/context/documents/${id}`);
    await save();
  }
  for(const fault of supplement?['completion','status']:['both']){
   dropComplete=fault!=='status';dropStatus=fault!=='completion';completeLost=false;statusLost=false;
   const {entry,id}=await run('candidate',0,2*1024*1024);
   report.faults.push({...entry,fault,completeLost,statusLost});
   await api('DELETE',`/api/v1/context/documents/${id}`);await save();
  }
  dropComplete=false;dropStatus=false;
  // 页面刷新后同一账号仍可读取后端文档，索引不是页面计时器驱动。
  await page.reload();
  if(retained){const observed=await api('GET',`/api/v1/context/documents/${retained}`);report.crossSession.push({channel,afterReload:true,status:observed.status,phase:observed.body.data?.phase});}
  await page.screenshot({path:path.join(out,channel+'-workbench.png'),fullPage:true});
 }
 report.passed=report.comparisons.every(x=>x.complete&&x.maxConcurrentChunks<=(x.mode==='baseline'?1:3))
   &&report.faults.every(x=>x.complete&&(x.fault==='completion'?x.completeLost:x.fault==='status'?x.statusLost:x.completeLost&&x.statusLost))
   &&report.crossSession.every(x=>x.status===200&&x.phase==='READY')&&report.errors.length===0;
 console.log('LIVE_RESULT',report.passed);
 if(!report.passed)process.exitCode=1;
}catch(error){report.failure=error.message;console.log('LIVE_FAILED',error.message);process.exitCode=1;}
finally{report.endedAt=new Date().toISOString();await save();for(const group of sessions)for(const id of group.ids)await group.api('DELETE',`/api/v1/context/documents/${id}`).catch(()=>{});for(const b of browsers)await b.close();await server.close();}
