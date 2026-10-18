// Uses only frozen synthetic work inputs and the existing server-side model configuration.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname, basename } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { rawExecutionInput, enhancedExecutionInput, completedExecutionWork } from '../../../../scripts/me00a-eval.mjs';

const pub=resolve('docs/testing/evidence/me02-candidate42-source-ownership-2026-10-19');
const root=resolve('tmp/me02-candidate42r2-20261019'), ledger=resolve('tmp/me00a-20261012');
const parameters={temperature:0.2,max_tokens:8192,thinking:{type:'disabled'},stream:false};
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const json=async path=>JSON.parse(await readFile(path,'utf8'));
const save=async(path,value)=>{await mkdir(dirname(path),{recursive:true});await writeFile(path,
  typeof value==='string'||Buffer.isBuffer(value)?value:`${JSON.stringify(value,null,2)}\n`,{flag:'wx'});};
const newline=value=>value.replace(/\r\n/g,'\n');
async function preflight(){
  const protocol=await json(resolve(ledger,'protocol.json')),batch=protocol.activeBatch;
  if(batch.version!=='candidate42r2-source-ownership'||batch.sourceCandidate!=='candidate42r2'||batch.status!=='OPEN'
    ||batch.authorizationStatus!=='APPROVED_BY_USER'||batch.maximumNewCalls!==36||batch.callsBefore!==442
    ||protocol.maximumActualModelCalls!==500||protocol.phaseLimits.baseline!==80||protocol.phaseLimits.candidateValidation!==420
    ||batch.purposeMaximums.EXECUTION!==6||batch.purposeMaximums.REVIEW!==2||batch.qualityRetriesAllowed!==false)throw Error('Frozen authorization mismatch');
  const manifestBytes=await readFile(resolve(pub,'candidate42r2-source.json'));
  if(hash(manifestBytes)!==batch.sourceManifestSha256||hash(await readFile(resolve(pub,'request-cases.json')))!==batch.casesSha256)throw Error('Input manifest drift');
  for(const row of JSON.parse(manifestBytes).sourceFiles)if(hash(await readFile(row.path))!==row.sha256)throw Error('Source drift');
  return batch;
}
async function copy(caseId,format){
  const folder=resolve(root,'flow',caseId,format),body=await json(resolve(folder,'platform.private.json'));
  const check=await json(resolve(folder,'check.json')),text=await readFile(resolve(folder,'native-copy.md'),'utf8');
  if(body.status!==200||body.body.data.provider.mock!==false||!check.nativeCopyMatches||!check.historyMatches
    ||hash(text)!==check.copySha256||newline(text)!==newline(body.body.data.optimizedPrompt))throw Error('Unchanged verified native copy required');
  return text;
}
async function prepareWorks(){
  await preflight();const cases=await json(resolve(pub,'request-cases.json'));
  const source=caseId=>({...cases.find(row=>row.id===caseId),context:{files:[]}});
  const refund=await json(resolve(root,'flow/RESERVED-REFUND/actual-confirmation.private.json'));
  const defs=[['REQUESTS_RAW','REQUESTS','RAW'],['REQUESTS_COMPACT','REQUESTS','COMPACT'],['REQUESTS_STRUCTURED','REQUESTS','STRUCTURED'],
    ['REFUND_RAW','RESERVED-REFUND','RAW'],['REFUND_PLAN','RESERVED-REFUND','COMPACT'],['JSON_STRUCTURED','STRICT-JSON','STRUCTURED']];
  const jobs=[];
  for(const[id,caseId,arm]of defs){
    const sample=source(caseId),answers=caseId==='RESERVED-REFUND'?refund.answers:[];
    const prompt=arm==='RAW'?rawExecutionInput(sample,answers):enhancedExecutionInput(await copy(caseId,arm),sample);
    if(arm!=='RAW'&&prompt!==await copy(caseId,arm))throw Error('Execution helper patched platform copy');
    const request={model:'deepseek-flash',...parameters,messages:[{role:'user',content:prompt}]};
    await save(resolve(root,'works',id,'request.json'),request);
    jobs.push({id,caseId,arm,model:request.model,requestSha256:hash(JSON.stringify(request)),promptSha256:hash(prompt),actualAnswers:answers});
  }
  await save(resolve(root,'works/input-lock.json'),{sourceCandidate:'candidate42r2',sourceManifestSha256:hash(await readFile(resolve(pub,'candidate42r2-source.json'))),
    casesSha256:hash(await readFile(resolve(pub,'request-cases.json'))),jobs,qualityRetriesAllowed:false});
  console.log(JSON.stringify({frozenWorks:jobs.length,modelCalls:0}));
}
async function prepareReviews(){
  await preflight();const lock=await json(resolve(root,'works/input-lock.json')),works=[];
  for(const job of lock.jobs){const text=await readFile(resolve(root,'works',job.id,'work.md'),'utf8');works.push({id:job.id,caseId:job.caseId,arm:job.arm,work:text,sha256:hash(text)});}
  const hard=await json(resolve(root,'hard-checks.json'));
  const packet={originalCases:await json(resolve(pub,'request-cases.json')),actualConfirmations:(await json(resolve(root,'flow/RESERVED-REFUND/actual-confirmation.private.json'))).answers,
    copiedPrompts:{REQUESTS_COMPACT:await copy('REQUESTS','COMPACT'),REQUESTS_STRUCTURED:await copy('REQUESTS','STRUCTURED'),REFUND_PLAN:await copy('RESERVED-REFUND','COMPACT'),JSON_STRUCTURED:await copy('STRICT-JSON','STRUCTURED')},works,independentHardChecks:hard};
  const system='你是独立AI辅助评审。只根据给定原文、逐字确认、实际复制正文与作品评价，不假称人工专家。分别评价准确性、完整性、清晰度、可执行性（1—5，1严重不可用，3基本可用但有实质缺口，5完整可执行且有证据）。原始/增强同信息比较；新增测试要求与原题义务分别判断。硬约束与评分独立，严重失败不能平均抵消。每个结论给出workId和逐字短引文，不凭未实际运行的模型自报认定通过。独立检查中未知项保持未知，允许分歧。返回JSON：comparisons[{pair,verdict:WIN|TIE|LOSS|BOTH_FAIL,dimensions,evidence:[{workId,quote,reason}],severeFailures}],limitations。';
  const records=[];
  for(const[id,model]of[['R1','deepseek-flash'],['R2','deepseek-v4-pro']]){
    const request={model,...parameters,response_format:{type:'json_object'},messages:[{role:'system',content:system},{role:'user',content:JSON.stringify(packet)}]};
    await save(resolve(root,'reviews',id,'request.json'),request);records.push({id,model,requestSha256:hash(JSON.stringify(request))});
  }
  await save(resolve(root,'reviews/input-lock.json'),{sourceCandidate:'candidate42r2',independentCalls:true,models:records,workHashes:works.map(({id,sha256})=>({id,sha256})),hardChecksSha256:hash(await readFile(resolve(root,'hard-checks.json')))});
  console.log(JSON.stringify({independentReviewsFrozen:2,modelCalls:0}));
}
async function call(purpose,id){
  await preflight();const folder=resolve(root,purpose==='EXECUTION'?'works':'reviews',id),request=await json(resolve(folder,'request.json'));
  const lock=await json(resolve(root,purpose==='EXECUTION'?'works/input-lock.json':'reviews/input-lock.json'));
  const spec=(purpose==='EXECUTION'?lock.jobs:lock.models).find(row=>row.id===id);
  if(!spec||hash(JSON.stringify(request))!==spec.requestSha256||request.model!==spec.model)throw Error('Frozen request changed');
  for(const[key,value]of Object.entries(parameters))if(JSON.stringify(request[key])!==JSON.stringify(value))throw Error('Parameter drift');
  if(purpose==='EXECUTION'){
    const cases=await json(resolve(pub,'request-cases.json')),sample={...cases.find(row=>row.id===spec.caseId),context:{files:[]}};
    const expected=spec.arm==='RAW'?rawExecutionInput(sample,spec.actualAnswers):await copy(spec.caseId,spec.arm);
    if(request.messages.length!==1||request.messages[0].role!=='user'||request.messages[0].content!==expected)throw Error('Execution prompt patched');
  }else{
    for(const work of lock.workHashes)if(hash(await readFile(resolve(root,'works',work.id,'work.md')))!==work.sha256)throw Error('Reviewed work changed');
    if(hash(await readFile(resolve(root,'hard-checks.json')))!==lock.hardChecksSha256)throw Error('Hard-check input changed');
  }
  const key=process.env.MODEL_DEEPSEEK_API_KEY??process.env.MODEL_API_KEY;
  const endpoint=new URL(process.env.MODEL_DEEPSEEK_ENDPOINT??'https://api.deepseek.com/chat/completions');
  if(!key||endpoint.protocol!=='https:'||endpoint.hostname!=='api.deepseek.com'||endpoint.pathname!=='/chat/completions'
    ||endpoint.username||endpoint.password||endpoint.search||endpoint.hash||(endpoint.port&&endpoint.port!=='443'))throw Error('Official server-side configuration required');
  await save(resolve(folder,'call-once.json'),{purpose,id,qualityRetriesAllowed:false});
  const cp=(await readFile(resolve(root,'runner/classpath.txt'),'utf8')).trim();
  const trace=execFileSync('E:/Delelop/Java/jdk-21/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',cp,'ReserveEvaluationCallR2',purpose],{encoding:'utf8',windowsHide:true}).trim();
  if(!/^\d+\.json$/.test(basename(trace))||dirname(resolve(trace))!==resolve(ledger,'traces'))throw Error('Invalid reservation path');
  const record={version:'candidate42r2-source-ownership',sourceCandidate:'candidate42r2',phase:'candidateValidation',purpose,operation:id,startedAt:new Date().toISOString(),request};
  await writeFile(trace,JSON.stringify(record,null,2));const started=Date.now();
  try{
    const response=await fetch(endpoint,{method:'POST',redirect:'error',headers:{'Content-Type':'application/json',Authorization:`Bearer ${key}`},body:JSON.stringify(request),signal:AbortSignal.timeout(120000)});
    const bytes=Buffer.from(await response.arrayBuffer());await save(resolve(folder,'response.raw.bin'),bytes);
    record.httpStatus=response.status;record.response=JSON.parse(bytes.toString('utf8'));record.elapsedMs=Date.now()-started;
    await writeFile(trace,JSON.stringify(record,null,2));await save(resolve(folder,'response.json'),record);
    const completed=completedExecutionWork(record);await save(resolve(folder,purpose==='EXECUTION'?'work.md':'review.md'),completed.text);
    console.log(JSON.stringify({purpose,id,trace:basename(trace),httpStatus:response.status,finishReason:completed.finishReason,tokens:record.response.usage,elapsedMs:record.elapsedMs}));
  }catch(error){record.failure=error.name;record.elapsedMs=Date.now()-started;await writeFile(trace,JSON.stringify(record,null,2));await save(resolve(folder,'failure.json'),{failure:error.name});throw Error(`Recorded external call failed: ${error.name}`);}
}
const[command,id]=process.argv.slice(2);
if(command==='prepare-works')await prepareWorks();else if(command==='prepare-reviews')await prepareReviews();
else if(command==='work')await call('EXECUTION',id);else if(command==='review')await call('REVIEW',id);else throw Error('Explicit frozen operation required');
