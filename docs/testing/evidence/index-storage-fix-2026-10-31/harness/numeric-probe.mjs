import fs from 'node:fs/promises';import path from 'node:path';import {createRequire} from 'node:module';
const root=process.cwd(),require=createRequire(path.join(root,'apps/web/package.json')),{chromium}=require('@playwright/test');
const browser=await chromium.launch({channel:'chrome',headless:true}),results=[];
try{for(const unique of [true,false]){
 const context=await browser.newContext(),page=await context.newPage();
 await page.route('**/__numeric',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><title>数字主键索引验证</title>'}));await page.goto('http://127.0.0.1:5210/__numeric');
 const result=await page.evaluate(async unique=>{
  const p='p-1f8293b4-20e6-47ae-aac7-81de68bb392e',count=unique?200:500,terms=1000;
  const req=r=>new Promise((ok,fail)=>{r.onsuccess=()=>ok(r.result);r.onerror=()=>fail(r.error)});
  const db=await new Promise((ok,fail)=>{const r=indexedDB.open('numeric-probe',1);r.onupgradeneeded=()=>{
   const c=r.result.createObjectStore('chunks',{keyPath:'id'});c.createIndex('projectId','projectId');c.createIndex('projectPath',['projectId','path']);c.createIndex('projectPriority',['projectId','priority']);
   const t=r.result.createObjectStore('chunkTerms',{keyPath:'key',autoIncrement:true});t.createIndex('chunkId','id',{unique:true});t.createIndex('searchTerms','searchTerms',{multiEntry:true});r.result.createObjectStore('keyBlocks',{keyPath:'block'});
  };r.onsuccess=()=>ok(r.result);r.onerror=()=>fail(r.error)});
  const started=performance.now();
  for(let start=0;start<count;start+=100){
   const tx=db.transaction(['chunks','chunkTerms','keyBlocks'],'readwrite'),done=new Promise((ok,fail)=>{tx.oncomplete=ok;tx.onabort=()=>fail(tx.error)});
   const pairs=await Promise.all(Array.from({length:100},async(_,offset)=>{
    const i=start+offset,path=`docs/testing/evidence/research-professional-platform-acceptance-2026-10-30/deepseek/${String(i).padStart(4,'0')}-output-body.json`,id=`${p}\0${path}\0${i}`,searchTerms=Array.from({length:terms},(_,j)=>`${p}:检索内容字段${unique?i+'_':''}${j}`);
    tx.objectStore('chunks').put({id,projectId:p,path,chunkIndex:i,language:'text',priority:10,symbols:[],imports:[],searchTerms,content:'正文需保留。'.repeat(1000)});
    const key=await req(tx.objectStore('chunkTerms').put({id,searchTerms}));return[key,id];
   }));
   const groups=new Map();for(const[key,id]of pairs){const b=Math.floor(key/256),g=groups.get(b)||[];g.push([key%256,id]);groups.set(b,g)}
   for(const[b,items]of groups){const row=await req(tx.objectStore('keyBlocks').get(b))||{block:b,ids:{}};for(const[k,id]of items)row.ids[k]=id;tx.objectStore('keyBlocks').put(row)}
   await done;
  }
  const writeMs=performance.now()-started,storage=await navigator.storage.estimate(),t=performance.now(),tx=db.transaction(['chunkTerms','keyBlocks']);
  const keys=await req(tx.objectStore('chunkTerms').index('searchTerms').getAllKeys(`${p}:检索内容字段${unique?'199_':''}999`));
  let ids=[];for(let start=0;start<keys.length;start+=256){const batch=keys.slice(start,start+256),blocks=await Promise.all([...new Set(batch.map(k=>Math.floor(k/256)))].map(b=>req(tx.objectStore('keyBlocks').get(b))));const map=new Map(blocks.map(b=>[b.block,b.ids]));ids.push(...batch.map(k=>map.get(Math.floor(k/256))[k%256]));ids=ids.sort().slice(0,8)}
  db.close();return{mode:'numeric-index',unique,count,termEntries:count*terms,writeMs,queryMs:performance.now()-t,found:ids.length,storage};
 },unique);results.push(result);console.log(JSON.stringify(result));await context.close();
}await fs.writeFile(path.join(root,'tmp/index-storage-fix-20261030/numeric-probe.json'),JSON.stringify(results,null,2));}finally{await browser.close()}
