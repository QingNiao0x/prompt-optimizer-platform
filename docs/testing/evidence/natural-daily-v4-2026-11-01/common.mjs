/** 日常HTTP出站前预留每业务请求最多4次模型调用；返回后按同requestId日志结算真实消耗。 */
import {readFile,writeFile,readdir,access} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
export const dir=dirname(fileURLToPath(import.meta.url)),repo=resolve(dir,'../../../..');
export const runtime=resolve(repo,'tmp/natural-daily-v4-2026-11-01');
export const json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
export const save=(p,v)=>writeFile(p,JSON.stringify(v,null,2)+'\n',{flag:'wx'});
export const exists=async p=>{try{await access(p);return true;}catch{return false;}};
export async function frozen(){
  if(await exists(resolve(dir,'closure.json')))throw Error('Batch closed');
  const p=await json(resolve(dir,'protocol.json'));
  for(const f of p.files)if(createHash('sha256').update(await readFile(resolve(repo,f.path))).digest('hex')!==f.sha256)throw Error('Frozen input/source changed: '+f.path);
  return p;
}
export async function reserve(job,phase,maximum,requestId){
  const p=await frozen(),records=[];
  for(const name of await readdir(resolve(dir,'reservations')))records.push(await json(resolve(dir,'reservations',name)));
  const charged=r=>r.actual??r.reserved;
  if(records.reduce((n,r)=>n+charged(r),0)+maximum>p.stageMaximum)throw Error('Stage limit reached');
  const phaseMaximum=phase==='product'?p.productMaximum:p.worksMaximum;
  if(records.filter(r=>r.phase===phase).reduce((n,r)=>n+charged(r),0)+maximum>phaseMaximum)throw Error('Phase limit reached');
  const path=resolve(dir,'reservations',job+'.json');
  await save(path,{job,phase,requestId,reserved:maximum,startedAt:new Date().toISOString()});return path;
}
export async function settleProduct(path){
  const entry=await json(path),launch=await json(resolve(runtime,'launch.json'));
  const lines=(await readFile(launch.stdoutLog,'utf8')).split(/\r?\n/).filter(line=>line.includes('event=model.call.')&&line.includes('requestId='+entry.requestId+' '));
  if(!lines.length)throw Error('No call audit for '+entry.job+'; reservation remains charged');
  const calls=lines.map(line=>Object.fromEntries([...line.matchAll(/([A-Za-z]+)=([^\s]+)/g)].map(m=>[m[1],m[2]])));
  if(calls.length>entry.reserved)throw Error('Unexpected stage count');
  await save(resolve(dir,'traces',entry.job+'-calls.json'),calls);
  await writeFile(path,JSON.stringify({...entry,actual:calls.length,settledAt:new Date().toISOString()},null,2)+'\n');
  return calls;
}
