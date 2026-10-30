import fs from 'node:fs/promises';import path from 'node:path';import {createRequire} from 'node:module';
const root=process.cwd(),require=createRequire(path.join(root,'apps/web/package.json')),{chromium}=require('@playwright/test'),{createServer}=require('vite');
const server=await createServer({configFile:false,envFile:false,root:path.join(root,'apps/web'),plugins:[require('@vitejs/plugin-vue').default()],resolve:{alias:{'@':path.join(root,'apps/web/src')}},logLevel:'error',server:{host:'127.0.0.1',port:5210,strictPort:true}});await server.listen();
const browser=await chromium.launch({channel:'chrome',headless:true});
try{
 const context=await browser.newContext(),page=await context.newPage();await page.route('**/__retrieval',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><title>真实文件检索定向复验</title><div style="padding:100px">drop</div>'}));await page.goto('http://127.0.0.1:5210/__retrieval');
 await page.evaluate(()=>{document.addEventListener('dragover',e=>e.preventDefault());document.addEventListener('drop',e=>{e.preventDefault();e.dataTransfer.items[0].getAsFileSystemHandle().then(h=>window.nativeRoot=h)})});
 const cdp=await context.newCDPSession(page);for(const type of ['dragEnter','dragOver','drop'])await cdp.send('Input.dispatchDragEvent',{type,x:150,y:150,data:{items:[],files:[root],dragOperationsMask:1}});await page.waitForFunction(()=>window.nativeRoot?.kind==='directory');
 const result=await page.evaluate(async()=>{
  const {IndexedDbProjectIndexRepository}=await import('/src/features/project-index/indexedDbProjectIndexRepository.ts'),{indexProject,retrieveProjectContextWithReport}=await import('/src/features/project-index/projectIndexer.ts');
  const repo=new IndexedDbProjectIndexRepository(),paths=['apps/web/src/features/project-index/projectIndexer.ts','apps/web/src/features/project-index/indexedDbProjectIndexRepository.ts','README.md'],entries=[];
  for(const p of paths){let h=window.nativeRoot;const parts=p.split('/');for(const d of parts.slice(0,-1))h=await h.getDirectoryHandle(d);entries.push({kind:'file',path:p,file:await(await h.getFileHandle(parts.at(-1))).getFile()})}
  const summary=await indexProject({projectId:'retrieval-supplement',rootName:window.nativeRoot.name,entries,repository:repo}),queries=[];
  for(const p of paths){const start=performance.now(),r=await retrieveProjectContextWithReport(repo,{projectId:'retrieval-supplement',query:p,maxChunks:4});queries.push({path:p,ms:performance.now()-start,returnedPaths:r.files.map(f=>f.path),references:r.references,selected:r.selections.some(s=>s.path===p),copiedPathFound:r.files.some(f=>f.path.startsWith(p+'#chunk-')&&f.content.length>0)})}
  return{scope:'three real files only; not a repetition of the whole-directory context-selection test',native:window.nativeRoot instanceof FileSystemDirectoryHandle,summary,queries,passed:queries.every(q=>q.selected&&q.copiedPathFound)};
 });await fs.writeFile(path.join(root,'tmp/index-storage-fix-20261030/retrieval-supplement.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));if(!result.passed)process.exitCode=1;
}finally{await browser.close();await server.close()}
