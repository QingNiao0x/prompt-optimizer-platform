import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
const root=process.cwd(), out=path.join(root,'tmp/index-upload-20261030',process.env.UPLOAD_ACCEPTANCE_RUN || 'full-live');
await fs.mkdir(out,{recursive:true});
const require=createRequire(path.join(root,'apps/web/package.json'));
const {chromium,expect}=require('@playwright/test');
const {createServer}=await import(pathToFileURL(require.resolve('vite')).href);
const vue=(await import(pathToFileURL(require.resolve('@vitejs/plugin-vue')).href)).default;
const diagnosticPlugin={name:'acceptance-errors-only',enforce:'pre',transform(code,id){if(process.env.UPLOAD_ACCEPTANCE_DIAGNOSTIC==='1'&&id.includes('projectIndex.worker.ts'))return code.replace("error instanceof Error ? error.message : '本地项目索引失败'", "String((error as Error)?.name) + ': ' + String((error as Error)?.message) + ' ' + String((error as Error)?.stack).slice(0,1600)");}};
const server=await createServer({root:path.join(root,'apps/web'),configFile:false,envFile:false,plugins:[diagnosticPlugin,vue()],
 resolve:{alias:{'@':path.join(root,'apps/web/src')}},worker:{format:'es'},server:{host:'127.0.0.1',port:5198,strictPort:true,proxy:{'/api':'http://127.0.0.1:9000'}},logLevel:'error'});
await server.listen();
const browser=await chromium.launch({channel:'chrome',headless:true,args:['--remote-debugging-port=9337','--remote-debugging-address=127.0.0.1']});
const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});
const page=await context.newPage(); const report={startedAt:new Date().toISOString(),head:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),modelCalls:0,errors:[]};
const save=()=>fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));
let phase='setup';
page.on('pageerror',e=>report.errors.push(e.message));
await page.route(/\/api\/v1\/(?:optimization|enhancement|image-prompt-generate|text-polish)/,route=>{report.modelCalls++;return route.abort()});
try {
 await page.goto('http://127.0.0.1:5198/login');
 await context.request.get('http://127.0.0.1:5198/api/v1/auth/csrf');
 const account=JSON.parse(await fs.readFile(path.join(root,'tmp/local-acceptance/account.json'),'utf8'));
 const decrypt="$ErrorActionPreference='Stop'; $secure=(Get-Content -LiteralPath 'tmp/local-acceptance/credential.dpapi' -Raw).Trim() | ConvertTo-SecureString; $pointer=[IntPtr]::Zero; try { $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure); [Console]::Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)) } finally { if($pointer -ne [IntPtr]::Zero){ [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) } }";
 let password=execFileSync('pwsh.exe',['-NoProfile','-NonInteractive','-Command',decrypt],{encoding:'utf8',windowsHide:true,stdio:['ignore','pipe','pipe']});
 for(let attempt=1;attempt<=3;attempt++) {
   const captcha=await context.request.get('http://127.0.0.1:5198/api/v1/auth/captcha');
   if(!captcha.ok())throw new Error('Captcha status '+captcha.status());
   await fs.writeFile(path.join(out,`captcha-${attempt}.png`),await captcha.body());
   console.log('CAPTCHA_READY',attempt); let answer;
   for(let i=0;i<300;i++){try{answer=(await fs.readFile(path.join(out,`answer-${attempt}.txt`),'utf8')).trim();break}catch{await new Promise(r=>setTimeout(r,1000))}}
   if(!answer)throw new Error('Captcha answer timeout');
   const status=await page.evaluate(async payload=>{const csrf=decodeURIComponent(document.cookie.split('; ').find(x=>x.startsWith('XSRF-TOKEN='))?.slice(11)||'');const r=await fetch('/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':csrf},body:JSON.stringify(payload)});return {status:r.status,code:(await r.json()).error?.code}}, {identifier:account.identifier,password,captcha:answer});
   if(status.status===200)break; if(status.code!=='CAPTCHA_INVALID')throw new Error('Login failed '+status.code); if(attempt===3)throw new Error('Captcha attempts exhausted');
 }
 password=undefined;
 const me=await context.request.get('http://127.0.0.1:5198/api/v1/auth/me');const identity=(await me.json()).data;
 if(!me.ok()||identity.userId!==account.userId||identity.tenantId!==account.tenantId)throw new Error('Canonical identity mismatch');
 report.sameAccount=true;await page.goto('http://127.0.0.1:5198/workbench');
 await expect(page.getByRole('button',{name:'添加上下文'})).toBeVisible();
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
 phase='waiting_documents';await fs.writeFile(path.join(out,'ready-for-documents'),new Date().toISOString());
 // 同一真实登录会话供文档验收脚本连接；本进程负责最终关闭浏览器。
 for(let i=0;i<3600;i++){try{await fs.access(path.join(out,'close-browser'));break}catch{await new Promise(r=>setTimeout(r,1000))}}
}catch(e){report.failure={phase,message:e.message};console.log('ACCEPTANCE_FAILURE',phase,e.message);await page.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});await save();if(report.sameAccount){await fs.writeFile(path.join(out,'ready-for-documents'),new Date().toISOString());for(let i=0;i<3600;i++){try{await fs.access(path.join(out,'close-browser'));break}catch{await new Promise(r=>setTimeout(r,1000))}}}}
finally{await save();await browser.close();await server.close();}
