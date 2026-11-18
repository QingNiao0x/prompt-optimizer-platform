import fs from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import os from 'node:os';
import {createProjectFileScope,isSensitiveFile} from './scope.mjs';
const repoRoot=await fs.realpath(process.cwd()),root=repoRoot,out=path.join(repoRoot,'tmp/index-capacity');
const require=createRequire(path.join(repoRoot,'apps/web/package.json'));
const {chromium}=require('@playwright/test');
const channel=process.env.INDEX_BROWSER||'msedge';
const report={startedAt:new Date().toISOString(),root,channel,baseline:'8ca270a7',rounds:[],criteria:{order:['after'],medianImprovement:0.05,maxCandidateRegression:0.10,fullContentRequired:true},modelCalls:0};
const save=()=>fs.writeFile(path.join(out,'report.json'),JSON.stringify(report,null,2));
const hashFile=async file=>{const hash=createHash('sha256');for await(const part of createReadStream(file))hash.update(part);return hash.digest('hex')};
async function manifest(hash){
 const scope=createProjectFileScope(await fs.readFile(path.join(root,'.gitignore'),'utf8')),files=[];
 async function walk(relative=''){
  for(const entry of await fs.readdir(path.join(root,relative),{withFileTypes:true})){
   const name=relative?relative+'/'+entry.name:entry.name;
   if(entry.isDirectory()){if(!scope.excludes(name,true))await walk(name)}
   else if(entry.isFile()&&!scope.excludes(name)){
    const info=await fs.stat(path.join(root,name));files.push({path:name,size:info.size,mtime:info.mtimeMs});
   }
  }
 }
 await walk();files.sort((a,b)=>a.path.localeCompare(b.path));
 let next=0;
 if(hash)await Promise.all(Array.from({length:6},async()=>{while(next<files.length){const file=files[next++];if(!isSensitiveFile(file.path))file.sha256=await hashFile(path.join(root,file.path));}}));
 return files;
}
const identity=files=>createHash('sha256').update(JSON.stringify(files)).digest('hex');
const shape=files=>files.map(({path,size,mtime})=>({path,size,mtime}));
const frozen=await manifest(true);report.manifest={files:frozen.length,bytes:frozen.reduce((sum,file)=>sum+file.size,0),sha256:identity(frozen)};
await fs.writeFile(path.join(out,'manifest.json'),JSON.stringify(frozen,null,2));await save();
console.log('FROZEN',JSON.stringify(report.manifest));
const server=http.createServer(async(req,res)=>{
 if(['/before.js','/after.js','/helpers.js'].includes(req.url)){res.setHeader('Content-Type','application/javascript');res.end(await fs.readFile(path.join(out,req.url.slice(1))))}
 else {res.setHeader('Content-Type','text/html');res.end('<!doctype html><meta charset="utf-8"><title>真实项目目录索引验收</title><style>html,body{margin:0;min-height:100vh}#progress{min-height:100vh;box-sizing:border-box}</style><div id="progress" style="font:16px sans-serif;padding:40px">准备读取用户指定目录</div>')}
});
await new Promise(resolve=>server.listen(5244,'127.0.0.1',resolve));
try {
 for(const [roundIndex,variant] of report.criteria.order.entries()){
  if(identity(shape(await manifest(false)))!==identity(shape(frozen)))throw Error('源目录范围、大小或修改时间已变化，停止对照');
  const profile=await fs.mkdtemp(path.join(os.tmpdir(),'prompt-native200-'));
  const context=await chromium.launchPersistentContext(profile,{channel,headless:true,viewport:{width:1280,height:900}});
  const page=context.pages()[0];page.on('console',message=>{if(message.text().startsWith('VERIFY '))console.log(message.text())});const round={number:roundIndex+1,variant,startedAt:new Date().toISOString(),progress:[]};
  report.rounds.push(round);await save();
  try {
   await page.goto('http://127.0.0.1:5244');await page.addScriptTag({url:'http://127.0.0.1:5244/helpers.js'});
   await page.evaluate(()=>{
    document.addEventListener('dragenter',event=>event.preventDefault());
    document.addEventListener('dragover',event=>event.preventDefault());
    document.addEventListener('drop',event=>{event.preventDefault();event.dataTransfer.items[0].getAsFileSystemHandle().then(handle=>window.nativeRoot=handle)});
   });
   const cdp=await context.newCDPSession(page);
   for(const type of ['dragEnter','dragOver','drop'])await cdp.send('Input.dispatchDragEvent',{type,x:100,y:100,data:{items:[],files:[root],dragOperationsMask:1}});
   await page.waitForFunction(()=>window.nativeRoot?.kind==='directory');
   round.native=await page.evaluate(()=>({real:window.nativeRoot instanceof FileSystemDirectoryHandle,name:window.nativeRoot.name}));
   await page.evaluate(async variant=>{
    window.progress=[];window.errors=[];
    window.projectId=IndexApi.createIsolatedProjectId();window.started=performance.now();
    await IndexApi.projectIndexRepository.prepareProject(window.projectId,new Date(Date.now()+7200000).toISOString(),'SESSION');
    window.worker=new Worker('/'+variant+'.js',{type:'module'});
    window.worker.onmessage=event=>{
     const data=event.data;
     if(data.type==='progress'){
      window.progress.push(data.progress);window.latest=data.progress;
      document.getElementById('progress').textContent=JSON.stringify(data.progress);
     }else{window.elapsed=performance.now()-window.started;window.end={...data,documents:data.documents?.map(doc=>({path:doc.path,size:doc.file.size}))};}
    };
    window.worker.onerror=event=>window.end={type:'error',message:event.message};
    window.worker.postMessage({type:'start',projectId:window.projectId,rootHandle:window.nativeRoot,mode:'FULL',respectGitignore:true,retention:'SESSION',sessionId:'native-acceptance',expiresAt:new Date(Date.now()+7200000).toISOString(),limits:{maxScanFiles:1000000,maxIndexBytes:2147483648,maxIndexableFileBytes:52428800}});
   },variant);
   const start=Date.now();let state;
   while(Date.now()-start<3600000){
    state=await page.evaluate(async()=>({latest:window.latest,end:window.end,elapsed:window.elapsed,storage:await navigator.storage.estimate()}));
    round.progress.push({elapsedMs:Date.now()-start,...state});await save();
    console.log('PROGRESS',round.number,variant,Math.round((Date.now()-start)/1000),state.latest?.processedFiles,state.latest?.totalFiles,state.latest?.percent,Math.round((state.storage.usage||0)/1048576)+'MiB');
    if(state.end)break;
    await new Promise(resolve=>setTimeout(resolve,15000));
   }
   if(!state.end)throw Error('整目录单轮超过60分钟，保留未完成原件');
   round.end=state.end;round.elapsedMs=state.elapsed;
   if(state.end.type!=='done')throw Error(state.end.message||'Worker未完成');
   round.verification=await page.evaluate(async()=>{
    const location=IndexApi.projectDatabaseLocation(window.projectId),factory=await location.factory();
    const request=value=>new Promise((resolve,reject)=>{value.onsuccess=()=>resolve(value.result);value.onerror=()=>reject(value.error)});
    const db=await request(factory.open(location.name));
    const records=await request(db.transaction('files').objectStore('files').getAll());
    const complete=records.filter(file=>!file.metadataOnly&&file.chunkCount>0).sort((a,b)=>a.path.localeCompare(b.path));
    const hash=async text=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text)))).map(byte=>byte.toString(16).padStart(2,'0')).join('');
    const directoryCache=new Map([['',window.nativeRoot]]),fileDigests=[],mismatches=[];
    let exact=0,tails=0,termReferences=0,verifiedChunks=0;
    for(let offset=0;offset<complete.length;offset+=32){
     const batch=complete.slice(offset,offset+32),tx=db.transaction(['chunks','chunkSearch']);
     const pending=batch.map(file=>{
      const prefix=`${window.projectId}\0${file.path}\0`;
      return Promise.all([request(tx.objectStore('chunks').index('projectPath').getAll(IDBKeyRange.only([window.projectId,file.path]))),request(tx.objectStore('chunkSearch').index('chunkId').getAll(IDBKeyRange.bound(prefix,prefix+'\uffff')))]);
     });
     const groups=await Promise.all(pending);
     let nextFile=0;
     await Promise.all(Array.from({length:Math.min(6,batch.length)},async()=>{
      while(nextFile<batch.length){
       const i=nextFile++;
      const file=batch[i],[chunks,terms]=groups[i];chunks.sort((a,b)=>a.chunkIndex-b.chunkIndex);
      let directory=window.nativeRoot,prefix='';const parts=file.path.split('/');
      for(const name of parts.slice(0,-1)){prefix=prefix?prefix+'/'+name:name;if(!directoryCache.has(prefix))directoryCache.set(prefix,await directory.getDirectoryHandle(name));directory=directoryCache.get(prefix)}
      const text=await(await(await directory.getFileHandle(parts.at(-1))).getFile()).text();
      const indexed=chunks.map(chunk=>chunk.content).join('');
      if(text===indexed)exact++;else mismatches.push(file.path);
      if(indexed.endsWith(text.slice(-200)))tails++;
      const byId=new Map(terms.map(row=>[row.id,row.formatVersion===2?row.searchTerms.map(term=>`${window.projectId}:${term}`):row.searchTerms]));
      verifiedChunks+=chunks.length;
      const canonical=chunks.map(chunk=>{
       const words=byId.get(chunk.id);if(!words)throw Error('词表缺失 '+file.path);
       termReferences+=words.length;
       return {path:chunk.path,chunkIndex:chunk.chunkIndex,content:chunk.content,language:chunk.language,priority:chunk.priority,searchTerms:words.map(word=>word.slice(window.projectId.length+1)),symbols:chunk.symbols,imports:chunk.imports};
      });
      fileDigests.push([file.path,await hash(JSON.stringify(canonical))]);
      }
     }));
     window.verificationProgress={verified:Math.min(offset+32,complete.length),total:complete.length};
    }
    const queries=[];
    for(const file of ['README.md','apps/web/src/features/project-index/projectIndexer.ts','services/api/src/main/java/com/promptoptimizer/PromptOptimizerApplication.java']){
     const started=performance.now();const selected=await IndexApi.retrieveProjectContext(IndexApi.projectIndexRepository,{projectId:window.projectId,query:'请检查 @'+file,maxChunks:4});
     queries.push({path:file,ms:performance.now()-started,found:selected.some(chunk=>chunk.path.startsWith(file+'#chunk-'))});
    }
    db.close();
    const summary=window.end.summary;
    return {records:records.length,completeFiles:complete.length,metadataOnly:records.filter(file=>file.metadataOnly).length,exact,tails,mismatches,verifiedChunks,termReferences,fullDigest:await hash(JSON.stringify(fileDigests.sort((a,b)=>a[0].localeCompare(b[0])))),queries,
      countsMatch:records.length===summary.eligibleFiles&&records.reduce((sum,file)=>sum+file.chunkCount,0)===summary.chunkCount&&records.reduce((sum,file)=>sum+file.indexedCharacters,0)===summary.indexedCharacters,
      premature100:window.progress.some(progress=>progress.phase!=='COMPLETED'&&progress.percent===100)};
   });
   round.complete=round.end.summary.status==='READY'&&!round.end.summary.storageLimitReached&&!round.end.summary.scanLimitReached&&round.end.summary.failedFiles===0&&round.end.summary.discoveredFiles===frozen.length&&round.verification.exact===round.verification.completeFiles&&round.verification.tails===round.verification.completeFiles&&round.verification.countsMatch&&!round.verification.premature100&&round.verification.queries.every(query=>query.found);
   await page.screenshot({path:path.join(out,`round-${round.number}.png`)});
   console.log('ROUND_END',JSON.stringify({number:round.number,variant,elapsedMs:round.elapsedMs,complete:round.complete,summary:round.end.summary,verification:round.verification}));await save();
  }catch(error){round.failure=error.message;round.complete=false;await save();console.log('ROUND_FAILURE',round.number,error.message)}
  finally{
   await context.close();
   const resolved=path.resolve(profile);
   if(path.dirname(resolved)===path.resolve(os.tmpdir())&&/^prompt-native200-[a-zA-Z0-9]+$/.test(path.basename(resolved)))await fs.rm(resolved,{recursive:true,force:true,maxRetries:3,retryDelay:1000});
  }
  if(roundIndex>=1&&report.rounds.slice(-2).every(round=>!round.complete)){report.stopped='连续两轮完整性门槛失败，停止重复采样并保留原因';break;}
 }
 report.sourceUnchanged=identity(await manifest(true))===report.manifest.sha256;
 const before=report.rounds.filter(round=>round.variant==='before'),after=report.rounds.filter(round=>round.variant==='after');
 const median=rows=>{const values=rows.map(round=>round.elapsedMs).sort((a,b)=>a-b);return values.length?values.reduce((a,b)=>a+b,0)/values.length:undefined};
 report.result={beforeMs:median(before),afterMs:median(after),qualityPassed:report.rounds.length===1&&report.rounds.every(round=>round.complete)&&report.sourceUnchanged&&new Set(report.rounds.map(round=>round.verification?.fullDigest)).size===1};
 report.result.stableImprovement=false;
 report.finishedAt=new Date().toISOString();await save();console.log('FINAL',JSON.stringify(report.result));
}catch(error){report.failure=error.message;await save();console.log('HARNESS_FAILURE',error.message);process.exitCode=1}
finally{await new Promise(resolve=>server.close(resolve));}
