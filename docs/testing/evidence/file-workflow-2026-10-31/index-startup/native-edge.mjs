import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
const root=process.cwd(), out=path.join(root,'tmp/index-startup-20261031');
const require=createRequire(path.join(root,'apps/web/package.json'));
const {createServer}=require('vite'),{chromium}=require('@playwright/test');
const server=await createServer({configFile:false,envFile:false,root:path.join(root,'apps/web'),plugins:[require('@vitejs/plugin-vue').default()],resolve:{alias:{'@':path.join(root,'apps/web/src')}},logLevel:'error',server:{host:'127.0.0.1',port:5211,strictPort:true}});
await server.listen();
const browser=await chromium.launch({channel:'msedge',headless:true});
const context=await browser.newContext(), page=await context.newPage();
const report={startedAt:new Date().toISOString(),browser:browser.version(),directory:root,modelCalls:0,errors:[]};
page.on('pageerror',e=>report.errors.push(e.message));
try{
 await page.route('**/__native_start',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><title>Edge 实际目录启动复验</title><div style="padding:100px">仅测试目录统计与开始索引</div>'}));
 await page.goto('http://127.0.0.1:5211/__native_start');
 await page.evaluate(()=>{window.progress=[];document.addEventListener('dragover',e=>e.preventDefault());document.addEventListener('drop',e=>{e.preventDefault();e.dataTransfer.items[0].getAsFileSystemHandle().then(h=>window.nativeRoot=h)})});
 const cdp=await context.newCDPSession(page);
 for(const type of ['dragEnter','dragOver','drop'])await cdp.send('Input.dispatchDragEvent',{type,x:100,y:100,data:{items:[],files:[root],dragOperationsMask:1}});
 await page.waitForFunction(()=>window.nativeRoot?.kind==='directory');
 await page.evaluate(()=>{
  window.worker=new Worker('/src/workers/projectIndex.worker.ts',{type:'module'});
  window.worker.onmessage=e=>{const m=e.data;if(m.type==='progress'){
   window.progress.push(m.progress);
   if(m.progress.phase==='INDEXING'&&!window.pauseSent){window.pauseSent=true;window.worker.postMessage({type:'pause'})}
  }else window.end={type:m.type,message:m.message,summary:m.summary};};
  window.worker.onerror=e=>window.end={type:'error',message:e.message};
  window.worker.postMessage({type:'start',rootHandle:window.nativeRoot,projectId:'edge-startup',mode:'FULL',respectGitignore:true,retention:'SESSION',sessionId:'edge-startup',expiresAt:new Date(Date.now()+7200000).toISOString(),limits:{maxScanFiles:1000000,maxIndexBytes:2147483648,maxIndexableFileBytes:52428800}});
 });
 const start=Date.now();
 while(Date.now()-start<180000){
  const s=await page.evaluate(()=>({progress:window.progress.at(-1),end:window.end}));
  if(s.end)break;
  console.log('EDGE_START',JSON.stringify(s.progress));
  await new Promise(r=>setTimeout(r,2000));
 }
 Object.assign(report,await page.evaluate(()=>({progress:window.progress,end:window.end,nativeHandle:window.nativeRoot instanceof FileSystemDirectoryHandle})));
 report.passed=report.nativeHandle&&report.progress.some(p=>p.phase==='SCANNING'&&p.discoveredFiles>0)&&report.progress.some(p=>p.phase==='INDEXING'&&p.totalFiles>0)&&report.end?.type==='paused'&&report.errors.length===0;
 report.completedAt=new Date().toISOString();
 console.log('EDGE_RESULT',JSON.stringify({passed:report.passed,discovered:report.end?.summary?.totalFiles,processed:report.end?.summary?.processedFiles,scanDurationMs:report.end?.summary?.stageTimings?.scanMs}));
 if(!report.passed)process.exitCode=1;
}catch(e){report.failure=e.message;process.exitCode=1}
finally{await fs.writeFile(path.join(out,'native-edge-report.json'),JSON.stringify(report,null,2));await context.close();await browser.close();await server.close();}
