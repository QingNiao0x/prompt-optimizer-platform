import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath,pathToFileURL } from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),repo=resolve(evidence,'../../../..');
const json=async p=>JSON.parse(await readFile(p,'utf8')),sha=x=>createHash('sha256').update(x).digest('hex');
const stage='engineering63-works',root=resolve(evidence,stage),closure=await json(resolve(root,'closure.json'));assert.equal(closure.status,'CLOSED');
const bundle=await json(resolve(here,'works-bundle.json')),jobs=await json(resolve(root,'jobs.json'));assert.deepEqual(jobs,bundle.jobs);
const sealed=resolve(evidence,'sealed-engineering-holdout'),manifestBytes=await readFile(resolve(sealed,'manifest.json'));
assert.equal(sha(manifestBytes),'a7f9c19244af011966e6558e2aeb7877e3d8d466b7a6f4f6a2fa3be2be4311af');
for(const item of JSON.parse(manifestBytes).files)assert.equal(sha(await readFile(resolve(sealed,item.path))),item.sha256);
const {evaluateArtifact}=await import(pathToFileURL(resolve(sealed,'answer-cards/checker.mjs')));
const originals=new Map(bundle.cases[0].executorMaterials.map(x=>[x.path,x.content.replaceAll('\r\n','\n')]));
const entries=[...jobs.map(x=>({jobId:x.id,arm:x.version,repeat:x.repeat,path:`${stage}/results/${x.id}.json`,reused:false})),...bundle.baselineReuse.map(x=>({...x,reused:true}))];
const output=resolve(here,'work-results');await mkdir(output,{recursive:true});const checks=[];
for(const item of entries){
  const file=resolve(evidence,item.path),bytes=await readFile(file),result=JSON.parse(bytes);if(item.reused)assert.equal(sha(bytes),item.sha256);
  else assert.equal(closure.files.find(x=>resolve(repo,x.path)===file)?.sha256,sha(bytes));
  let evaluation={passed:false,stage:'DELIVERY',checks:[]},identicalFiles=[],returnedFiles=[];
  if(result.status===200&&result.body.data.finishReason==='stop'){
    const artifact=JSON.parse(result.body.data.content);evaluation=evaluateArtifact(artifact,{dependencyRoot:resolve(repo,'apps/web')});
    returnedFiles=artifact.files.map(x=>x.path);identicalFiles=artifact.files.filter(x=>typeof x.content==='string'&&x.content.replaceAll('\r\n','\n')===originals.get(x.path)).map(x=>x.path);
  }
  const check={...item,responseSha256:sha(bytes),finishReason:result.body?.data?.finishReason,functionalPass:evaluation.passed,checksPassed:evaluation.checks.filter(x=>x.passed).length,
    checksExecuted:evaluation.checks.length,returnedFiles,identicalFiles,necessaryEdits:identicalFiles.map(path=>`Remove unchanged entry ${path}`),evaluation};
  await writeFile(resolve(output,item.jobId+'-R'+item.repeat+'-checks.json'),JSON.stringify(check,null,2)+'\n',{flag:'wx'});checks.push(check);
}
for(const item of JSON.parse(manifestBytes).files)assert.equal(sha(await readFile(resolve(sealed,item.path))),item.sha256);
await writeFile(resolve(output,'summary.json'),JSON.stringify({actualCalls:0,newWorks:4,reusedWorks:2,hostArtifactExecution:false,checks},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({actualCalls:0,checks:checks.map(({jobId,arm,repeat,functionalPass,checksPassed,checksExecuted,identicalFiles})=>({jobId,arm,repeat,functionalPass,checksPassed,checksExecuted,identicalFiles}))}));
