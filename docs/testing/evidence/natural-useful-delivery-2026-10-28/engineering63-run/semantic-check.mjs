import assert from 'node:assert/strict';
import { readFile,writeFile,mkdir,realpath,lstat,symlink } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { dirname,resolve,relative,isAbsolute,sep } from 'node:path';
import { fileURLToPath } from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)),repo=resolve(here,'../../../../..'),evidence=resolve(here,'..');
const json=async p=>JSON.parse(await readFile(p,'utf8')),sha=x=>createHash('sha256').update(x).digest('hex');
const within=(p,root)=>{const r=relative(root,p);return r!=='..'&&!r.startsWith('..'+sep)&&!isAbsolute(r);};
async function absent(p){try{await lstat(p);throw Error('Refuse reusing or deleting existing paths');}catch(e){if(e.code!=='ENOENT')throw e;}}
const isolatedRoot=resolve(repo,'tmp/e01-semantic63-2026-10-29'),resultRoot=resolve(here,'semantic-results');
assert.ok(within(isolatedRoot,repo));await absent(isolatedRoot);await absent(resultRoot);
const require=createRequire(resolve(repo,'apps/web/package.json')),dependencyRoot=await realpath(resolve(repo,'apps/web/node_modules'));
assert.ok(within(dependencyRoot,repo));const packageFile=await realpath(require.resolve('vue-tsc/package.json'));
const pkg=await json(packageFile),cli=await realpath(resolve(dirname(packageFile),pkg.bin['vue-tsc']));assert.ok(within(cli,repo));
const bundle=await json(resolve(here,'works-bundle.json')),closure=await json(resolve(evidence,'engineering63-works/closure.json'));assert.equal(closure.status,'CLOSED');
const allowed=(await json(resolve(evidence,'engineering-run/execution-contract.json'))).allowedReplacementPaths;
await mkdir(isolatedRoot);await mkdir(resultRoot);
const prepared=[];
for(const job of bundle.jobs){
  const path=resolve(evidence,'engineering63-works/results',job.id+'.json'),bytes=await readFile(path);assert.equal(closure.files.find(x=>resolve(repo,x.path)===path)?.sha256,sha(bytes));
  const result=JSON.parse(bytes);assert.equal(result.status,200);assert.equal(result.body.data.finishReason,'stop');
  const artifact=JSON.parse(result.body.data.content),tree=new Map(bundle.cases[0].executorMaterials.map(x=>[x.path,Buffer.from(x.content)])),seen=new Set();
  for(const f of artifact.files){assert.ok(allowed.includes(f.path)&&!seen.has(f.path));seen.add(f.path);tree.set(f.path,Buffer.from(f.content));}
  assert.equal(tree.size,30);const directory=resolve(isolatedRoot,job.id);assert.ok(within(directory,isolatedRoot));await mkdir(directory);
  for(const [name,content]of tree){const path=resolve(directory,name);assert.ok(within(path,directory));await mkdir(dirname(path),{recursive:true});await writeFile(path,content,{flag:'wx'});}
  const link=resolve(directory,'node_modules');assert.ok(within(link,isolatedRoot));await absent(link);await symlink(dependencyRoot,link,'junction');assert.equal(await realpath(link),dependencyRoot);
  prepared.push({jobId:job.id,arm:job.version,repeat:job.repeat,directory,tree,responseSha256:sha(bytes)});
}
// Execute only the existing trusted compiler, never artifact package scripts, vite config or application code.
const compilerEnv={NO_COLOR:'1'};for(const key of ['SystemRoot','SYSTEMROOT','WINDIR','TEMP','TMP'])if(typeof process.env[key]==='string')compilerEnv[key]=process.env[key];
const results=await Promise.all(prepared.map(async p=>{
  const args=[cli,'--noEmit','--project',resolve(p.directory,'tsconfig.json'),'--pretty','false'];
  const result=await new Promise((fulfill,reject)=>{const c=spawn(process.execPath,args,{cwd:p.directory,shell:false,windowsHide:true,env:compilerEnv,stdio:['ignore','pipe','pipe']});const out=[],err=[];c.stdout.on('data',x=>out.push(x));c.stderr.on('data',x=>err.push(x));c.on('error',reject);c.on('close',(exitCode,signal)=>fulfill({exitCode,signal,stdout:Buffer.concat(out).toString('utf8'),stderr:Buffer.concat(err).toString('utf8')}));});
  for(const [name,content]of p.tree)assert.ok((await readFile(resolve(p.directory,name))).equals(content));
  for(const kind of ['stdout','stderr'])await writeFile(resolve(resultRoot,p.jobId+'-'+kind+'.txt'),result[kind],{flag:'wx'});
  return{jobId:p.jobId,arm:p.arm,repeat:p.repeat,passed:result.exitCode===0,exitCode:result.exitCode,diagnostics:result.stdout+result.stderr,responseSha256:p.responseSha256,sourceFiles:30,tsconfigUnchanged:true};
}));
await writeFile(resolve(resultRoot,'summary.json'),JSON.stringify({actualCalls:0,trustedCompiler:{cli,version:pkg.version,sha256:sha(await readFile(cli))},packageScriptsExecuted:false,applicationCodeExecuted:false,parentCredentialsInherited:false,dependencyInstalls:0,existingPathsRemoved:false,results,oldBaselineTypechecksRetained:'engineering-run/semantic-results-r2/summary.json'},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({actualCalls:0,compiler:pkg.version,results}));
