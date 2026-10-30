import fs from 'node:fs/promises';
import path from 'node:path';
import {createRequire} from 'node:module';
const root=process.cwd(),require=createRequire(path.join(root,'apps/web/package.json'));
const {chromium}=require('@playwright/test'),browser=await chromium.launch({channel:'chrome',headless:true});
const results=[];
try {
  for(const mode of ['legacy','current']) {
    const context=await browser.newContext(),page=await context.newPage();
    await page.route('**/__unique_storage',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><title>不重复关键词容量对照</title>'}));
    await page.goto('http://127.0.0.1:5210/__unique_storage');
    const result=await page.evaluate(async mode=>{
      const projectId='p-1f8293b4-20e6-47ae-aac7-81de68bb392e',count=200,terms=1000;
      let db,repo;
      if(mode==='legacy') db=await new Promise((resolve,reject)=>{
        const r=indexedDB.open('legacy-unique',1);
        r.onupgradeneeded=()=>{const s=r.result.createObjectStore('chunks',{keyPath:'id'});
          s.createIndex('projectId','projectId');s.createIndex('projectPath',['projectId','path']);
          s.createIndex('projectPriority',['projectId','priority']);s.createIndex('searchTerms','searchTerms',{multiEntry:true});};
        r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);
      });
      else {const m=await import('/src/features/project-index/indexedDbProjectIndexRepository.ts');repo=new m.IndexedDbProjectIndexRepository();}
      const started=performance.now();
      for(let start=0;start<count;start+=100){
        const chunks=Array.from({length:100},(_,offset)=>{
          const i=start+offset,path=`docs/testing/evidence/research-professional-platform-acceptance-2026-10-30/deepseek/${String(i).padStart(4,'0')}-output-body.json`;
          return {id:`${projectId}\0${path}\0${i}`,projectId,path,chunkIndex:i,language:'text',priority:10,symbols:[],imports:[],
            searchTerms:Array.from({length:terms},(_,j)=>`${projectId}:唯一标识${i}_${j}`),content:'正文需保留。'.repeat(1000)};
        });
        if(repo)await repo.writeBatch([],chunks);
        else await new Promise((resolve,reject)=>{const t=db.transaction('chunks','readwrite');for(const c of chunks)t.objectStore('chunks').put(c);t.oncomplete=resolve;t.onabort=()=>reject(t.error)});
      }
      const writeMs=performance.now()-started,storage=await navigator.storage.estimate();db?.close();
      return {mode,count,termEntries:count*terms,writeMs,storage};
    },mode);
    results.push(result);console.log(JSON.stringify(result));await context.close();
  }
  await fs.writeFile(path.join(root,'tmp/index-storage-fix-20261030/unique-probe.json'),JSON.stringify(results,null,2));
}finally{await browser.close()}
