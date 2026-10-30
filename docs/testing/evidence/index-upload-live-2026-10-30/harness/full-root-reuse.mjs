import fs from 'node:fs/promises';import path from 'node:path';import {createRequire} from 'node:module';
const root=process.cwd(),out=path.join(root,'tmp/index-upload-20261030/full-diagnostic-index');await fs.mkdir(out,{recursive:true});
const require=createRequire(path.join(root,'apps/web/package.json'));const {chromium,expect}=require('@playwright/test');
const browser=await chromium.connectOverCDP('http://127.0.0.1:9337');const context=browser.contexts()[0],page=context.pages()[0];
const report={startedAt:new Date().toISOString(),modelCalls:0,errors:[],sameAccount:true};const save=()=>fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));let phase='setup';
try {await page.goto('http://127.0.0.1:5198/workbench');await expect(page.getByRole('button',{name:'添加上下文'})).toBeVisible({timeout:60000});
 phase='native_directory';
 await page.evaluate(()=>{
   window.liveProgress=[];window.liveStarts=[];
   const NativeWorker=window.Worker;
   window.Worker=class extends NativeWorker{constructor(...args){super(...args);this.addEventListener('message',e=>{if(e.data?.type==='progress')window.liveProgress.push(e.data.progress);if(['done','error','paused'].includes(e.data?.type))window.liveEnd={...e.data,documents:e.data.documents?.map(x=>({path:x.path,size:x.file.size}))}});const send=this.postMessage.bind(this);this.postMessage=(m,...other)=>{if(m?.type==='start')window.liveStarts.push({projectId:m.projectId,limits:m.limits,rootName:m.rootHandle.name});return send(m,...other)}}};
   document.addEventListener('dragover',e=>{if(!window.nativeRoot){e.preventDefault();e.stopImmediatePropagation()}},true);
   document.addEventListener('drop',e=>{if(window.nativeRoot)return;e.preventDefault();e.stopImmediatePropagation();e.dataTransfer.items[0].getAsFileSystemHandle().then(h=>{window.nativeRoot=h;Object.defineProperty(window,'showDirectoryPicker',{configurable:true,value:async()=>h})})},true);
 });
 const cdp=await context.newCDPSession(page);await cdp.send('Performance.enable');
 for(const type of ['dragEnter','dragOver','drop'])await cdp.send('Input.dispatchDragEvent',{type,x:200,y:300,data:{items:[],files:[root],dragOperationsMask:1}});
 await page.waitForFunction(()=>window.nativeRoot?.kind==='directory');
 report.nativeRoot=await page.evaluate(()=>({kind:window.nativeRoot.kind,name:window.nativeRoot.name,real:window.nativeRoot instanceof FileSystemDirectoryHandle}));
 phase='full_index';let started=Date.now();
 await page.getByRole('button',{name:'添加上下文'}).click();await page.getByRole('menuitem',{name:'选择文件夹'}).click();
 const samples=[];let finished=false;
 for(let i=0;i<1800;i++) {
   const state=await page.evaluate(()=>({latest:window.liveProgress.at(-1),end:window.liveEnd,starts:window.liveStarts}));
   if(state.end){report.index={elapsedMs:Date.now()-started,...state};finished=true;break;}
   if(i%10===0){const metrics=await cdp.send('Performance.getMetrics');samples.push({elapsedMs:Date.now()-started,heapBytes:metrics.metrics.find(x=>x.name==='JSHeapUsedSize')?.value,...state.latest});await fs.writeFile(path.join(out,'index-progress.json'),JSON.stringify(samples,null,2));console.log('INDEX_PROGRESS',state.latest?.phase,state.latest?.processedFiles,state.latest?.totalFiles,state.latest?.percent);}
   if(i===8||i===40)await page.locator('.folder-dropzone').screenshot({path:path.join(out,`index-${i}.png`)});
   await new Promise(r=>setTimeout(r,1000));
 }
 if(!finished)throw new Error('Full import exceeded 30 minutes');
 await save();await page.screenshot({path:path.join(out,'index-finished.png'),fullPage:true});
 const docConfirm=page.getByRole('dialog',{name:'确认上传需要解析的文档'});if(await docConfirm.isVisible())await docConfirm.getByRole('button',{name:'暂不上传'}).click();
 if(report.index.end.type!=='done')throw new Error('Index '+report.index.end.type+': '+(report.index.end.message||''));
 phase='index_verification';
 report.verification=await page.evaluate(async()=>{
   const {projectIndexRepository:repo}=await import('/src/features/project-index/indexedDbProjectIndexRepository.ts');
   const {retrieveProjectContext}=await import('/src/features/project-index/projectIndexer.ts');
   const projectId=window.liveStarts.at(-1).projectId;
   const database=await new Promise((resolve,reject)=>{const r=indexedDB.open('prompt-optimizer-project-index');r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)});
   const records=await new Promise((resolve,reject)=>{const t=database.transaction('files','readonly');const r=t.objectStore('files').getAll();r.onsuccess=()=>resolve(r.result.filter(x=>x.projectId===projectId));r.onerror=()=>reject(r.error)});database.close();
   const hash=async t=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(t)))).map(x=>x.toString(16).padStart(2,'0')).join('');
   const results=[];const complete=records.filter(x=>!x.metadataOnly&&x.chunkCount>0);
   // 分层抽查全长正文；另对全部已存元数据核对数量、字符数与片段数。
   const wanted=['apps/web/src/features/project-index/projectIndexer.ts','apps/web/src/pages/PromptWorkbenchPage.vue','services/api/src/main/java/com/promptoptimizer/context/service/impl/TemporaryDocumentIndexService.java','docs/13-百万级文件扫描与上下文采样方案.md','README.md'];
   const selected=[...new Set([...wanted,...Array.from({length:12},(_,i)=>complete[Math.floor(i*complete.length/12)]?.path)].filter(p=>complete.some(x=>x.path===p)))];
   for(const p of selected){let h=window.nativeRoot;const parts=p.split('/');for(const d of parts.slice(0,-1))h=await h.getDirectoryHandle(d);const source=await(await h.getFileHandle(parts.at(-1))).getFile();const chunks=await repo.findChunksByPaths(projectId,[p],100000);chunks.sort((a,b)=>a.chunkIndex-b.chunkIndex);const sourceText=await source.text(),indexed=chunks.map(x=>x.content).join('');results.push({path:p,bytes:source.size,sourceHash:await hash(sourceText),indexedHash:await hash(indexed),equal:sourceText===indexed,chunks:chunks.length,tailPresent:indexed.endsWith(sourceText.slice(-200))});}
   const queries=[];for(const p of wanted.filter(p=>complete.some(x=>x.path===p))){const t=performance.now();const result=await retrieveProjectContext(repo,{projectId,query:p,maxChunks:8});queries.push({path:p,elapsedMs:performance.now()-t,found:JSON.stringify(result).includes(p),resultCharacters:JSON.stringify(result).length});}
   return {records:records.length,fullRecords:complete.length,incompleteRecords:records.length-complete.length,chunks:records.reduce((n,r)=>n+r.chunkCount,0),characters:records.reduce((n,r)=>n+(r.indexedCharacters||0),0),sampledFullContent:results,queries,storage:await navigator.storage.estimate(),lastProgress:window.liveProgress.at(-1),premature100:window.liveProgress.some(x=>x.phase!=='COMPLETED'&&x.percent===100)};
 });
 await save();console.log('FULL_INDEX_DONE',JSON.stringify({elapsedMs:report.index.elapsedMs,summary:report.index.end.summary,verification:report.verification}));
}catch(e){report.failure={phase,message:e.message};console.log('ACCEPTANCE_FAILURE',phase,e.message);await page.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});}finally{await save();process.exitCode=report.failure?1:0;await browser._connection?.close?.();}