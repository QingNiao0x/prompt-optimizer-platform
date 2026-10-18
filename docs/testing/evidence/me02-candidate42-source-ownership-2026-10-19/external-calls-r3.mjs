import { readFile,writeFile,mkdir } from 'node:fs/promises';
import { resolve,dirname,basename } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { completedExecutionWork } from '../../../../scripts/me00a-eval.mjs';
const pub=resolve('docs/testing/evidence/me02-candidate42-source-ownership-2026-10-19');
const root=resolve('tmp/me02-candidate42r3-20261019'),ledger=resolve('tmp/me00a-20261012');
const hash=x=>createHash('sha256').update(x).digest('hex');
const json=async p=>JSON.parse(await readFile(p,'utf8'));
const params={temperature:0.2,max_tokens:8192,thinking:{type:'disabled'},stream:false};
const jobs={'PURE-TRANSLATION':'COMPACT','STRICT-JSON':'STRUCTURED'};
const save=async(p,v)=>{await mkdir(dirname(p),{recursive:true});await writeFile(p,typeof v==='string'||Buffer.isBuffer(v)?v:JSON.stringify(v,null,2)+'\n',{flag:'wx'});};
async function verify(){
 const p=await json(resolve(ledger,'protocol.json')),b=p.activeBatch;
 if(b.version!=='candidate42r3-source-ownership'||b.status!=='OPEN'||b.callsBefore!==461||b.maximumNewCalls!==6
   ||b.purposeMaximums.PLATFORM_GENERATION!==4||b.purposeMaximums.EXECUTION!==2||b.qualityRetriesAllowed!==false
   ||p.maximumActualModelCalls!==500||p.phaseLimits.baseline!==80||p.phaseLimits.candidateValidation!==420)throw Error('Final frozen allocation required');
 const bytes=await readFile(resolve(pub,'candidate42r3-source.json'));
 if(hash(bytes)!==b.sourceManifestSha256)throw Error('Manifest drift');
 for(const row of JSON.parse(bytes).sourceFiles)if(hash(await readFile(row.path))!==row.sha256)throw Error('Source drift');
}
async function verifiedCopy(id){
 const f=resolve(root,'flow',id,jobs[id]),p=await json(resolve(f,'platform.private.json')),c=await json(resolve(f,'check.json')),t=await readFile(resolve(f,'native-copy.md'),'utf8');
 if(p.status!==200||p.body.data.provider.mock!==false||!c.nativeCopyMatches||!c.historyMatches||hash(t)!==c.copySha256
   ||t.replace(/\r\n/g,'\n')!==p.body.data.optimizedPrompt.replace(/\r\n/g,'\n'))throw Error('Unchanged native copy required');
 return t;
}
const [command,id]=process.argv.slice(2);await verify();
if(command==='prepare'){
 const records=[];
 for(const name of Object.keys(jobs)){const request={model:'deepseek-flash',...params,messages:[{role:'user',content:await verifiedCopy(name)}]};
  await save(resolve(root,'works',name,'request.json'),request);records.push({id:name,requestSha256:hash(JSON.stringify(request))});}
 await save(resolve(root,'works/input-lock.json'),{sourceCandidate:'candidate42r3',jobs:records,qualityRetriesAllowed:false});console.log('Two final fixed-copy works prepared');
}else if(command==='work'&&Object.hasOwn(jobs,id)){
 const folder=resolve(root,'works',id),request=await json(resolve(folder,'request.json')),lock=await json(resolve(root,'works/input-lock.json'));
 if(hash(JSON.stringify(request))!==lock.jobs.find(x=>x.id===id)?.requestSha256||request.messages[0].content!==await verifiedCopy(id))throw Error('Execution input changed');
 const key=process.env.MODEL_DEEPSEEK_API_KEY??process.env.MODEL_API_KEY,endpoint=new URL(process.env.MODEL_DEEPSEEK_ENDPOINT??'https://api.deepseek.com/chat/completions');
 if(!key||endpoint.protocol!=='https:'||endpoint.hostname!=='api.deepseek.com'||endpoint.pathname!=='/chat/completions'||endpoint.username||endpoint.password||endpoint.search||endpoint.hash||(endpoint.port&&endpoint.port!=='443'))throw Error('Official server-side endpoint required');
 await save(resolve(folder,'call-once.json'),{id,qualityRetriesAllowed:false});
 const cp=(await readFile(resolve(root,'runner/classpath.txt'),'utf8')).trim();const trace=execFileSync('E:/Delelop/Java/jdk-21/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',cp,'ReserveEvaluationCallR3','EXECUTION'],{encoding:'utf8',windowsHide:true}).trim();
 if(dirname(resolve(trace))!==resolve(ledger,'traces')||!/^\d+\.json$/.test(basename(trace)))throw Error('Invalid reservation');
 const record={version:'candidate42r3-source-ownership',sourceCandidate:'candidate42r3',phase:'candidateValidation',purpose:'EXECUTION',operation:id,request,startedAt:new Date().toISOString()};await writeFile(trace,JSON.stringify(record,null,2));const started=Date.now();
 try{const r=await fetch(endpoint,{method:'POST',redirect:'error',headers:{'Content-Type':'application/json',Authorization:`Bearer ${key}`},body:JSON.stringify(request),signal:AbortSignal.timeout(120000)});
  const bytes=Buffer.from(await r.arrayBuffer());await save(resolve(folder,'response.raw.bin'),bytes);record.httpStatus=r.status;record.response=JSON.parse(bytes.toString('utf8'));record.elapsedMs=Date.now()-started;
  await writeFile(trace,JSON.stringify(record,null,2));await save(resolve(folder,'response.json'),record);await save(resolve(folder,'work.md'),completedExecutionWork(record).text);
  console.log(JSON.stringify({id,trace:basename(trace),httpStatus:r.status,tokens:record.response.usage}));
 }catch(e){record.failure=e.name;await writeFile(trace,JSON.stringify(record,null,2));throw Error('Recorded final work attempt failed: '+e.name);}
}else throw Error('Frozen final operation required');
