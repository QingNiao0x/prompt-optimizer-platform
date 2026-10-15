import { readFile, writeFile, readdir, stat } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { createHash } from 'node:crypto';
const root = resolve('docs/testing/evidence/me00a-candidate13-2026-10-15');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const mode=process.argv[2];
const files=[];
async function walk(folder) { for(const item of await readdir(folder,{withFileTypes:true})) {
  const path=resolve(folder,item.name); if(item.isDirectory())await walk(path); else if(path!==resolve(root,'artifact-index.json'))files.push(path);
} }
await walk(root);
if(mode==='index') {
  const records=await Promise.all(files.sort().map(async path=>({path:relative(root,path).replaceAll('\\','/'),bytes:(await stat(path)).size,sha256:hash(await readFile(path))})));
  await writeFile(resolve(root,'artifact-index.json'),JSON.stringify({verifiedAt:new Date().toISOString(),records,excludesSelf:true},null,2));
  console.log(JSON.stringify({indexedEvidenceFiles:records.length}));
}else if(mode==='verify') {
  const index=JSON.parse(await readFile(resolve(root,'artifact-index.json'),'utf8'));
  if(index.records.length!==files.length)throw new Error('Artifact set changed');
  for(const item of index.records)if(hash(await readFile(resolve(root,item.path)))!==item.sha256)throw new Error('Evidence hash changed: '+item.path);
  const source=JSON.parse(await readFile(resolve(root,'candidate13-source.json'),'utf8'));
  for(const item of [...source.sourceFiles,...source.classFiles,...source.policyFiles])if(hash(await readFile(resolve(item.path)))!==item.sha256)throw new Error('Candidate source drift');
  const traces=(await readdir(resolve('tmp/me00a-20261012/traces'))).filter(n=>/^\d+\.json$/.test(n));
  if(traces.length!==239)throw new Error('Live ledger drift');
  const protocol=JSON.parse(await readFile(resolve('tmp/me00a-20261012/protocol.json'),'utf8'));
  if(protocol.activeBatch.status!=='CLOSED_AFTER_USER_NARROWED_TWO_VENDOR_ONE_ROUND')throw new Error('Batch not closed');
  const report=await readFile(resolve('docs/testing/ME00A候选十三资料状态修复与双模型网页作品复验-2026-10-15.md'),'utf8');
  const broken=[];
  for(const match of report.matchAll(/\]\((\.\/[^)]+)\)/g))try{await stat(resolve('docs/testing',match[1]));}catch{broken.push(match[1]);}
  if(broken.length)throw new Error('Broken report evidence link: '+broken.join(','));
  console.log(JSON.stringify({evidenceHashesChecked:index.records.length,frozenSourceAndRuntimeChecked:source.sourceFiles.length+source.classFiles.length+source.policyFiles.length,ledger:239,batchClosed:true,reportLinksResolved:true}));
}else throw new Error('Explicit index or verify stage required');
