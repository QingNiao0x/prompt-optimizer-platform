import fs from 'node:fs/promises';import path from 'node:path';import os from 'node:os';import {createRequire} from 'node:module';import {execFileSync} from 'node:child_process';
const root=process.cwd(),require=createRequire(path.join(root,'apps/web/package.json')),{chromium}=require('@playwright/test'),{createServer}=require('vite');
const server=await createServer({configFile:false,envFile:false,root:path.join(root,'apps/web'),plugins:[require('@vitejs/plugin-vue').default()],resolve:{alias:{'@':path.join(root,'apps/web/src')}},logLevel:'error',server:{host:'127.0.0.1',port:5208,strictPort:true}});await server.listen();
const source=execFileSync('git',['show','HEAD:apps/web/src/features/project-index/indexedDbProjectIndexRepository.ts'],{cwd:root,encoding:'utf8'});
const oldJs=(await require('esbuild').transform(source,{loader:'ts',format:'esm'})).code.replaceAll('"./projectPathReferences"','"http://127.0.0.1:5208/src/features/project-index/projectPathReferences.ts"');
const owned=[],results=[];
try{for(const mode of ['baseline','current']){
 const profile=await fs.mkdtemp(path.join(os.tmpdir(),'prompt-index-quota-')),context=await chromium.launchPersistentContext(profile,{channel:'chrome',headless:true}),page=context.pages()[0];owned.push({context,profile});await page.route('**/__quota',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><title>受控容量耗尽复现</title>'}));await page.goto('http://127.0.0.1:5208/__quota');
 const cdp=await context.newCDPSession(page);await cdp.send('Storage.overrideQuotaForOrigin',{origin:'http://127.0.0.1:5208',quotaSize:12*1024*1024});
 const actualQuota=await cdp.send('Storage.getUsageAndQuota',{origin:'http://127.0.0.1:5208'});if(!actualQuota.overrideActive||actualQuota.quota!==12*1024*1024)throw new Error('未能建立测试配额，本次不作产品容量结论');
 const result=await page.evaluate(async({mode,oldJs})=>{
  const module=mode==='baseline'?await import(URL.createObjectURL(new Blob([oldJs],{type:'text/javascript'}))):await import('/src/features/project-index/indexedDbProjectIndexRepository.ts');
  const repo=new module.IndexedDbProjectIndexRepository(),p='p-1f8293b4-20e6-47ae-aac7-81de68bb392e',started=performance.now(),before=await navigator.storage.estimate();let committed=0,error;
  try{for(let start=0;start<200;start+=25){const chunks=Array.from({length:25},(_,offset)=>{const i=start+offset,path=`docs/testing/evidence/research-professional-platform-acceptance-2026-10-30/deepseek/${String(i).padStart(4,'0')}-output-body.json`;
   return{id:`${p}\0${path}\0${i}`,projectId:p,path,chunkIndex:i,language:'text',priority:10,symbols:[],imports:[],searchTerms:[...Array.from({length:1000},(_,j)=>`${p}:检索内容字段${j}`),`${p}:tail_${i}`],content:'正文需保留。'.repeat(1000)+`tail_${i}`}});await repo.writeBatch([],chunks);committed+=chunks.length}}
  catch(e){error={name:e.name,message:e.message}}
  const hits=await repo.findCandidateChunks(p,['tail_199'],1),storage=await navigator.storage.estimate();return{mode,before,committed,error,elapsedMs:performance.now()-started,tailFound:hits.some(c=>c.content.endsWith('tail_199')),storage};
 },{mode,oldJs});result.actualQuota=actualQuota;results.push(result);console.log(JSON.stringify(result));await context.close();
}await fs.writeFile(path.join(root,'tmp/index-storage-fix-20261030/quota-boundary-probe.json'),JSON.stringify(results,null,2));}finally{for(const{context,profile}of owned){await context.close();if(path.dirname(profile)===path.resolve(os.tmpdir())&&path.basename(profile).startsWith('prompt-index-quota-'))await fs.rm(profile,{recursive:true,force:true})}await server.close()}
