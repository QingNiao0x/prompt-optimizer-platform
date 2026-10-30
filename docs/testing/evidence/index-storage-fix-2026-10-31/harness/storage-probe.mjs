import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
const root=process.cwd(), require=createRequire(path.join(root,'apps/web/package.json'));
const {createServer}=require('vite'); const {chromium}=require('@playwright/test');
const server=await createServer({configFile:false,envFile:false,root:path.join(root,'apps/web'),plugins:[require('@vitejs/plugin-vue').default()],resolve:{alias:{'@':path.join(root,'apps/web/src')}},logLevel:'error',server:{host:'127.0.0.1',port:5208,strictPort:true}});
await server.listen(); const browser=await chromium.launch({channel:'chrome',headless:true});
try {
  const results=[];
  for(const mode of ['packed-postings']) {
    const context=await browser.newContext(), page=await context.newPage();
    await page.route('**/__storage',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><title>Local index storage probe</title>'}));
    await page.goto('http://127.0.0.1:5208/__storage');
    const result=await page.evaluate(async mode=>{
      const db=await new Promise((resolve,reject)=>{const r=indexedDB.open('size-probe',1);r.onupgradeneeded=()=>{const s=r.result.createObjectStore('chunks',{keyPath:'key'});if(mode!=='packed-postings')s.createIndex('terms','terms',{multiEntry:true});r.result.createObjectStore('postings',{keyPath:'key'})};r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)});
      const started=performance.now(), count=500, terms=1000, batch=mode==='small-batch'?10:100;
      for(let offset=0;offset<count;offset+=batch) await new Promise((resolve,reject)=>{const t=db.transaction(['chunks','postings'],'readwrite'),s=t.objectStore('chunks'),ids=[];for(let i=offset;i<Math.min(offset+batch,count);i++){const id=`p-1f8293b4-20e6-47ae-aac7-81de68bb392e\0docs/testing/evidence/research-professional-platform-acceptance-2026-10-30/deepseek/${String(i).padStart(4,'0')}-output-body.json\0${i}`;ids.push(id);s.put({key:mode==='short-key'?i:id,id,terms:Array.from({length:terms},(_,j)=>`p-1f8293b4-20e6-47ae-aac7-81de68bb392e:检索内容字段${j}`),content:'正文需保留。'.repeat(1000)})}if(mode==='packed-postings')for(let j=0;j<terms;j++)t.objectStore('postings').put({key:[`p-1f8293b4-20e6-47ae-aac7-81de68bb392e:检索内容字段${j}`,offset],ids});t.oncomplete=resolve;t.onabort=()=>reject(t.error);t.onerror=()=>reject(t.error)});
      const storage=await navigator.storage.estimate();db.close();return{mode,count,termEntries:count*terms,writeMs:performance.now()-started,storage};
    },mode);
    results.push(result);console.log(JSON.stringify(result));await context.close();
  }
  await fs.writeFile(path.join(root,'tmp/index-storage-fix-20261030/storage-probe-packed.json'),JSON.stringify(results,null,2));
}finally{await browser.close();await server.close()}
