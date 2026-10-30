import fs from 'node:fs/promises';import path from 'node:path';import {createRequire} from 'node:module';import {execFileSync} from 'node:child_process';
const root=process.cwd(),require=createRequire(path.join(root,'apps/web/package.json')),{chromium}=require('@playwright/test'),{createServer}=require('vite');
const server=await createServer({configFile:false,envFile:false,root:path.join(root,'apps/web'),plugins:[require('@vitejs/plugin-vue').default()],resolve:{alias:{'@':path.join(root,'apps/web/src')}},logLevel:'error',server:{host:'127.0.0.1',port:5208,strictPort:true}});await server.listen();
const source=execFileSync('git',['show','HEAD:apps/web/src/features/project-index/indexedDbProjectIndexRepository.ts'],{cwd:root,encoding:'utf8'});
const oldJs=(await require('esbuild').transform(source,{loader:'ts',format:'esm'})).code.replaceAll('"./projectPathReferences"','"http://127.0.0.1:5208/src/features/project-index/projectPathReferences.ts"');
const browser=await chromium.launch({channel:'chrome',headless:true}),results=[];
try{for(const mode of ['current','baseline','current']){
 const context=await browser.newContext(),page=await context.newPage();await page.route('**/__same_run',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><title>同轮容量与耗时对照</title>'}));await page.goto('http://127.0.0.1:5208/__same_run');
 const result=await page.evaluate(async({mode,oldJs})=>{
  const module=mode==='baseline'?await import(URL.createObjectURL(new Blob([oldJs],{type:'text/javascript'}))):await import('/src/features/project-index/indexedDbProjectIndexRepository.ts');
  const repo=new module.IndexedDbProjectIndexRepository(),p='p-1f8293b4-20e6-47ae-aac7-81de68bb392e',started=performance.now();
  for(let start=0;start<500;start+=100){const chunks=Array.from({length:100},(_,offset)=>{const i=start+offset,path=`docs/testing/evidence/research-professional-platform-acceptance-2026-10-30/deepseek/${String(i).padStart(4,'0')}-output-body.json`;
   return{id:`${p}\0${path}\0${i}`,projectId:p,path,chunkIndex:i,language:'text',priority:10,symbols:[],imports:[],searchTerms:[...Array.from({length:1000},(_,j)=>`${p}:检索内容字段${j}`),`${p}:tail_${i}`],content:'正文需保留。'.repeat(1000)+`tail_${i}`}});await repo.writeBatch([],chunks)}
  const writeMs=performance.now()-started,storage=await navigator.storage.estimate(),query=performance.now();
  const hits=await repo.findCandidateChunks(p,['检索内容字段999'],8);return{mode,writeMs,queryMs:performance.now()-query,storage,ids:hits.map(c=>c.id)};
 },{mode,oldJs});results.push(result);console.log(JSON.stringify(result));await context.close();
}await fs.writeFile(path.join(root,'tmp/index-storage-fix-20261030/same-run-comparison.json'),JSON.stringify(results,null,2));}finally{await browser.close();await server.close()}
