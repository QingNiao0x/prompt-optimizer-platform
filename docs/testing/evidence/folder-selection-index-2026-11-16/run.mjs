import { createRequire } from 'node:module';
import { readFile, writeFile, stat } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import { createHash } from 'node:crypto';
const root = process.cwd();
const require = createRequire(path.join(root, 'apps/web/package.json'));
const { chromium } = require('@playwright/test');
const dir = path.join(root, 'tmp/index-perf-20261116');
const manifestPath = path.join(dir, 'manifest.json');
let manifest;
try { manifest = JSON.parse(await readFile(manifestPath, 'utf8')); }
catch {
  const paths = execFileSync('git', ['ls-files','docs/testing/evidence','apps/web/src'], {encoding:'utf8',maxBuffer:16e6}).trim().split('\n');
  manifest = [];
  for (const prefix of ['docs/testing/evidence/', 'apps/web/src/']) {
    let count = 0;
    for (const name of paths.filter(p => p.startsWith(prefix) && (prefix.startsWith('docs') ? p.endsWith('.json') : p.endsWith('.ts')))) {
      const info = await stat(path.join(root, name));
      if (info.size < 100 || info.size > 65536) continue;
      const buffer = await readFile(path.join(root, name));
      manifest.push({ path: name, bytes: buffer.length, hash: createHash('sha256').update(buffer).digest('hex') });
      if (++count >= (prefix.startsWith('docs') ? 500 : 200)) break;
    }
  }
  await writeFile(manifestPath, JSON.stringify(manifest,null,2));
}
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
await new Promise(resolve=>server.listen(5221,'127.0.0.1',resolve));
const browser = await chromium.launch({channel:process.env.INDEX_BROWSER || 'msedge', headless:true});
try {
  const page = await browser.newPage();
  await page.goto('http://127.0.0.1:5221');
  await page.addScriptTag({path:path.join(dir,'before.js')});
  const candidates = process.argv.includes('--compare');
  if (candidates) await page.addScriptTag({path:path.join(dir,'after.js')});
  const rounds = candidates ? ['IndexBefore','IndexAfter','IndexAfter','IndexBefore'] : ['IndexBefore'];
  const results = [];
  for (const variant of rounds) {
    const result = await page.evaluate(async ({inputs,variant})=>{
      const api=window[variant], repository=new api.RoutedProjectIndexRepository(), id=api.createIsolatedProjectId();
      await repository.prepareProject(id,new Date(Date.now()+3600000).toISOString(),'SESSION');
      async function* entries() { for (const source of inputs) yield {kind:'file',path:source.path,file:new File([new Uint8Array(source.bytes)],source.path,{lastModified:1})}; }
      const started=performance.now();
      try {
        const summary=await api.indexProject({projectId:id,rootName:'frozen-repo-sample',repository,entries:entries()});
        const elapsedMs=performance.now()-started;
        let exactFiles=0, tailHits=0;
        const canonical=[];
        for (const input of inputs) {
          const chunks=(await repository.findChunksByPaths(id,[input.path],200)).sort((a,b)=>a.chunkIndex-b.chunkIndex);
          const expected=new TextDecoder().decode(new Uint8Array(input.bytes));
          if(chunks.map(c=>c.content).join('')===expected) exactFiles++;
          if(chunks.map(c=>c.content).join('').endsWith(expected.slice(-100))) tailHits++;
          canonical.push(chunks.map(c=>({path:c.path,chunkIndex:c.chunkIndex,content:c.content,searchTerms:c.searchTerms.map(t=>t.slice(id.length+1)),symbols:c.symbols,imports:c.imports})));
        }
        const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(canonical))))).map(b=>b.toString(16).padStart(2,'0')).join('');
        return {variant,elapsedMs,timings:summary.timings,files:summary.indexedFiles,ignored:summary.ignoredFiles,failed:summary.failedFiles,chunks:summary.chunkCount,chars:summary.indexedCharacters,exactFiles,tailHits,digest};
      } finally {await repository.deleteProject(id);}
    },{inputs,variant});
    results.push(result);console.log(JSON.stringify(result));
    await writeFile(path.join(dir,candidates ? `comparison-${process.env.INDEX_BROWSER||'msedge'}.json`:'baseline.json'),JSON.stringify({date:new Date().toISOString(),inputFiles:manifest.length,inputBytes:manifest.reduce((a,b)=>a+b.bytes,0),results},null,2));
  }
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
