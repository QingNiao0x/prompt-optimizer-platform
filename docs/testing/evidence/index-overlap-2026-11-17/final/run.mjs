import { createRequire } from 'node:module';
import { readFile, writeFile, stat, mkdir } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
const root = process.cwd();
const require = createRequire(path.join(root, 'apps/web/package.json'));
const { chromium } = require('@playwright/test');
const dir = path.join(root, 'docs/testing/evidence/index-overlap-2026-11-17/final');
const manifestPath = path.join(root, 'docs/testing/evidence/folder-selection-index-2026-11-16/manifest.json');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const resultDir = path.join(root, 'tmp/index-overlap-replay');
await mkdir(resultDir, {recursive:true});
const inputs = [];
for (const file of manifest) {
  let buffer = await readFile(path.join(root, file.path));
  if (createHash('sha256').update(buffer).digest('hex') !== file.hash) {
    buffer = execFileSync('git', ['show', `e18a1171:${file.path}`], {maxBuffer:16e6});
  }
  if (createHash('sha256').update(buffer).digest('hex') !== file.hash) throw Error(`frozen input changed: ${file.path}`);
  inputs.push({path:file.path, bytes: Array.from(buffer)});
}
const server = http.createServer((req,res)=>{res.setHeader('Content-Type','text/html');res.end('<!doctype html><title>等范围索引性能诊断</title>');});
await new Promise(resolve=>server.listen(5230,'127.0.0.1',resolve));
const browser = await chromium.launch({channel:process.env.INDEX_BROWSER || 'msedge', headless:true});
try {
  const page = await browser.newPage();
  await page.goto('http://127.0.0.1:5230');
  await page.addScriptTag({path:path.join(dir,'before.js')});
  const candidates = true;
  if (candidates) await page.addScriptTag({path:path.join(dir,'after.js')});
  const rounds = process.argv.includes('--reverse') ? ['IndexAfter','IndexBefore'] : ['IndexBefore','IndexAfter'];
  const results = [];
  for (const variant of rounds) {
    const result = await page.evaluate(async ({inputs,variant})=>{
      const api=window[variant], repository=new api.RoutedProjectIndexRepository(), id=api.createIsolatedProjectId();
      await repository.prepareProject(id,new Date(Date.now()+3600000).toISOString(),'SESSION');
      async function* entries() { for (const source of inputs) yield {kind:'file',path:source.path,file:new File([new Uint8Array(source.bytes)],source.path,{lastModified:1})}; }
      const batches=[];
      const write=repository.writeBatch.bind(repository);
      repository.writeBatch=async (...args)=>{
        const start=performance.now();
        await write(...args);
        batches.push({files:args[0].length,chunks:args[1].length,terms:args[1].reduce((sum,c)=>sum+c.searchTerms.length,0),ms:performance.now()-start});
      };
      const started=performance.now();
      try {
        const summary=await api.indexProject({projectId:id,rootName:'frozen-repo-sample',repository,entries:entries()});
        const elapsedMs=performance.now()-started;
        let exactFiles=0, tailHits=0;
        const canonical=[];
        // 校验在索引计时之外直接批量读取实际存储，避免数百次检索调用拖长诊断；公开检索另有浏览器回归。
        const db = await repository.repository(id).openDatabase();
        const tx = db.transaction(['chunks','chunkSearch']);
        const read = request => new Promise((resolve,reject)=>{request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});
        const [bodies,terms] = await Promise.all([read(tx.objectStore('chunks').getAll()),read(tx.objectStore('chunkSearch').getAll())]);
        const byId = new Map(terms.map(row=>[row.id,row.searchTerms]));
        const all=bodies.map(body=>({...body,searchTerms:byId.get(body.id)}));
        const byPath = new Map();
        for(const chunk of all) { const group=byPath.get(chunk.path)||[];group.push(chunk);byPath.set(chunk.path,group); }
        for (const input of inputs) {
          const chunks=(byPath.get(input.path)||[]).sort((a,b)=>a.chunkIndex-b.chunkIndex);
          const expected=new TextDecoder().decode(new Uint8Array(input.bytes));
          if(chunks.map(c=>c.content).join('')===expected) exactFiles++;
          if(chunks.map(c=>c.content).join('').endsWith(expected.slice(-100))) tailHits++;
          canonical.push(chunks.map(c=>({path:c.path,chunkIndex:c.chunkIndex,content:c.content,searchTerms:c.searchTerms.map(t=>t.slice(id.length+1)),symbols:c.symbols,imports:c.imports})));
        }
        const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(canonical))))).map(b=>b.toString(16).padStart(2,'0')).join('');
        return {variant,elapsedMs,batches,timings:summary.timings,files:summary.indexedFiles,ignored:summary.ignoredFiles,failed:summary.failedFiles,chunks:summary.chunkCount,chars:summary.indexedCharacters,exactFiles,tailHits,digest};
      } finally { /* 每轮独立项目，当前无持久化的临时浏览器在末尾整体关闭清理。 */ }
    },{inputs,variant});
    results.push(result);console.log(JSON.stringify(result));
    await writeFile(path.join(resultDir,candidates ? `comparison-${process.env.INDEX_BROWSER||'msedge'}.json`:'baseline.json'),JSON.stringify({date:new Date().toISOString(),inputFiles:manifest.length,inputBytes:manifest.reduce((a,b)=>a+b.bytes,0),results},null,2));
  }
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
