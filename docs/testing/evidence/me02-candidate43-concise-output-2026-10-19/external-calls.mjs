// Four fixed copied-prompt/source-pack works and two independent reviews; no browsing tool.
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {resolve,dirname,basename} from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {completedExecutionWork} from '../../../../scripts/me00a-eval.mjs';
const pub=resolve('docs/testing/evidence/me02-candidate43-concise-output-2026-10-19');
const root=resolve('tmp/me02-candidate43-20261019'),ledger=resolve('tmp/me00a-20261012');
const version='candidate43-concise-output';
const parameters={temperature:0.2,max_tokens:8192,thinking:{type:'disabled'},stream:false};
const hash=v=>createHash('sha256').update(v).digest('hex');
const json=async p=>JSON.parse(await readFile(p,'utf8'));
const save=async(p,v)=>{await mkdir(dirname(p),{recursive:true});await writeFile(p,typeof v==='string'||Buffer.isBuffer(v)?v:JSON.stringify(v,null,2)+'\n',{flag:'wx'});};
async function preflight(){
  const protocol=await json(resolve(ledger,'protocol.json')),b=protocol.activeBatch;
  if(b.version!==version||b.sourceCandidate!=='candidate43'||b.status!=='OPEN'||b.authorizationStatus!=='APPROVED_BY_USER'
    ||b.callsBefore!==465||b.candidateCallsBefore!==395||b.maximumNewCalls!==24||b.maximumActualCallsAfterBatch!==489
    ||b.purposeMaximums.EXECUTION!==4||b.purposeMaximums.REVIEW!==2||b.qualityRetriesAllowed!==false
    ||protocol.maximumActualModelCalls!==500||protocol.phaseLimits.baseline!==80||protocol.phaseLimits.candidateValidation!==420)throw Error('Frozen approved scope required');
  for(const[file,key]of[['candidate43-source.json','sourceManifestSha256'],['request-cases.json','casesSha256'],['source-pack.md','commonSourcesSha256'],['qoder-reference.md','qoderReferenceSha256']])
    if(hash(await readFile(resolve(pub,file)))!==b[key])throw Error('Frozen input hash changed');
  for(const row of (await json(resolve(pub,'candidate43-source.json'))).sourceFiles)if(hash(await readFile(row.path))!==row.sha256)throw Error('Source changed');
  return b;
}
async function copy(format){
  const dir=resolve(root,'flow/AI-USAGE',format),platform=await json(resolve(dir,'platform.private.json'));
  const check=await json(resolve(dir,'check.json')),text=await readFile(resolve(dir,'native-copy.md'),'utf8');
  if(platform.status!==200||platform.body.data.provider.mock!==false||!check.nativeCopyMatches||!check.historyMatches
    ||hash(text)!==check.copySha256||text.replace(/\r\n/g,'\n')!==platform.body.data.optimizedPrompt.replace(/\r\n/g,'\n'))throw Error('Actual unchanged copy/history required');
  return text;
}
async function prompts(){
  const raw=(await json(resolve(pub,'request-cases.json'))).find(c=>c.id==='AI-USAGE').rawPrompt;
  return {RAW:raw,COMPACT:await copy('COMPACT'),STRUCTURED:await copy('STRUCTURED'),QODER_REFERENCE:await readFile(resolve(pub,'qoder-reference.md'),'utf8')};
}
async function prepareWorks(){
  await preflight();const values=await prompts(),sources=await readFile(resolve(pub,'source-pack.md'),'utf8'),jobs=[];
  for(const[id,prompt]of Object.entries(values)){
    // The first user message is the exact copied prompt. Identical sources are a separate input in all arms.
    const request={model:'deepseek-flash',...parameters,messages:[{role:'user',content:prompt},{role:'user',content:sources}]};
    await save(resolve(root,'works',id,'request.json'),request);
    jobs.push({id,model:request.model,requestSha256:hash(JSON.stringify(request)),promptSha256:hash(prompt),sourcesSha256:hash(sources)});
  }
  await save(resolve(root,'works/input-lock.json'),{sourceCandidate:'candidate43',jobs,searchAvailable:false,sameSourcePacket:true,
    qoderIsReferenceOnly:true,qualityRetriesAllowed:false});console.log(JSON.stringify({preparedWorks:4,modelCalls:0}));
}
async function prepareReviews(){
  await preflight();const locked=await json(resolve(root,'works/input-lock.json'));
  const order=[['COMPACT','W1'],['RAW','W2'],['QODER_REFERENCE','W3'],['STRUCTURED','W4']],works=[],privateMapping=[];
  for(const[id,label]of order){const content=await readFile(resolve(root,'works',id,'work.md'),'utf8');works.push({id:label,work:content});privateMapping.push({id,label,sha256:hash(content)});}
  const hard=await json(resolve(root,'hard-checks.json'));
  const packet={originalPrompt:(await prompts()).RAW,baselineWorkId:'W2',sameSources:await readFile(resolve(pub,'source-pack.md'),'utf8'),
    works,independentChecks:hard,comparisonScope:'W1/W4与W2比较原始问题的实际回答；W3仅作其他写法参考，不把其新增目标视作原始义务。'};
  const instruction='你是独立AI辅助评审，不是假称人工专家。四份作品同模型、同资料、无网页检索工具，不能宣称验证最新全球完整排名。先按原始问题比较是否直接回答哪些职业/行业、职业与应用是否具体、事实/数值/引用是否准确、阅读负担及可用性。准确性、完整性、清晰度、可执行性各1—5：1为严重不可用，3为基本可用但存在实质缺口，5为完整清楚且有证据。不要只因更长、更短或标题齐全加分，不强制写原文未要求的固定岗位数。对资料之外合理的应用解释与虚构统计分别判断。每个重要结论提供workId和逐字短引文；保留分歧，独立硬约束失败不能被平均分抵消。返回JSON：comparisons[{candidateWorkId,baselineWorkId,verdict:WIN|TIE|LOSS|BOTH_FAIL,dimensions,evidence:[{workId,quote,reason}],severeFailures}],referenceObservations,limitations。';
  const reviewers=[];
  for(const[id,model]of[['R1','deepseek-flash'],['R2','deepseek-v4-pro']]){
    const request={model,...parameters,response_format:{type:'json_object'},messages:[{role:'system',content:instruction},{role:'user',content:JSON.stringify(packet)}]};
    await save(resolve(root,'reviews',id,'request.json'),request);reviewers.push({id,model,requestSha256:hash(JSON.stringify(request))});
  }
  await save(resolve(root,'reviews/input-lock.json'),{reviewers,privateMapping,hardChecksSha256:hash(await readFile(resolve(root,'hard-checks.json'))),worksLockSha256:hash(JSON.stringify(locked))});
  console.log(JSON.stringify({preparedReviews:2,modelCalls:0}));
}
async function call(purpose,id){
  await preflight();const dir=resolve(root,purpose==='EXECUTION'?'works':'reviews',id);
  const request=await json(resolve(dir,'request.json')),locked=await json(resolve(root,purpose==='EXECUTION'?'works/input-lock.json':'reviews/input-lock.json'));
  const recordSpec=(purpose==='EXECUTION'?locked.jobs:locked.reviewers).find(r=>r.id===id);
  if(!recordSpec||hash(JSON.stringify(request))!==recordSpec.requestSha256||request.model!==recordSpec.model)throw Error('Frozen request changed');
  for(const[k,v]of Object.entries(parameters))if(JSON.stringify(request[k])!==JSON.stringify(v))throw Error('Parameter drift');
  if(purpose==='EXECUTION'){
    const expected=await prompts(),sources=await readFile(resolve(pub,'source-pack.md'),'utf8');
    if(request.messages.length!==2||request.messages.some(m=>m.role!=='user')||request.messages[0].content!==expected[id]||request.messages[1].content!==sources)throw Error('Copy or common source packet patched');
  }else{
    for(const item of locked.privateMapping)if(hash(await readFile(resolve(root,'works',item.id,'work.md')))!==item.sha256)throw Error('Reviewed work changed');
    if(hash(await readFile(resolve(root,'hard-checks.json')))!==locked.hardChecksSha256)throw Error('Hard check drift');
  }
  const key=process.env.MODEL_DEEPSEEK_API_KEY??process.env.MODEL_API_KEY;
  const endpoint=new URL(process.env.MODEL_DEEPSEEK_ENDPOINT??'https://api.deepseek.com/chat/completions');
  if(!key||endpoint.protocol!=='https:'||endpoint.hostname!=='api.deepseek.com'||endpoint.pathname!=='/chat/completions'||endpoint.username||endpoint.password||endpoint.search||endpoint.hash||(endpoint.port&&endpoint.port!=='443'))throw Error('Official server-side endpoint required');
  await save(resolve(dir,'call-once.json'),{purpose,id,qualityRetriesAllowed:false});
  const cp=(await readFile(resolve(root,'runner/classpath.txt'),'utf8')).trim();
  const trace=execFileSync('E:/Delelop/Java/jdk-21/bin/java.exe',['-Dfile.encoding=UTF-8','-cp',cp,'ReserveEvaluationCall43',purpose],{encoding:'utf8',windowsHide:true}).trim();
  if(!/^\d+\.json$/.test(basename(trace))||dirname(resolve(trace))!==resolve(ledger,'traces'))throw Error('Invalid reserved path');
  const record={version,sourceCandidate:'candidate43',phase:'candidateValidation',purpose,operation:id,startedAt:new Date().toISOString(),request};
  await writeFile(trace,JSON.stringify(record,null,2));const start=Date.now();
  try{
    const response=await fetch(endpoint,{method:'POST',redirect:'error',headers:{'Content-Type':'application/json',Authorization:`Bearer ${key}`},body:JSON.stringify(request),signal:AbortSignal.timeout(120000)});
    const bytes=Buffer.from(await response.arrayBuffer());await save(resolve(dir,'response.raw.bin'),bytes);
    record.httpStatus=response.status;record.response=JSON.parse(bytes.toString('utf8'));record.elapsedMs=Date.now()-start;
    await writeFile(trace,JSON.stringify(record,null,2));await save(resolve(dir,'response.json'),record);
    const work=completedExecutionWork(record);await save(resolve(dir,purpose==='EXECUTION'?'work.md':'review.md'),work.text);
    console.log(JSON.stringify({purpose,id,trace:basename(trace),status:response.status,tokens:record.response.usage,elapsedMs:record.elapsedMs}));
  }catch(error){record.failure=error.name;record.elapsedMs=Date.now()-start;await writeFile(trace,JSON.stringify(record,null,2));await save(resolve(dir,'failure.json'),{failure:error.name});throw Error('External call failed and counted: '+error.name);}
}
const[operation,id]=process.argv.slice(2);
if(operation==='prepare-works')await prepareWorks();else if(operation==='prepare-reviews')await prepareReviews();
else if(operation==='work')await call('EXECUTION',id);else if(operation==='review')await call('REVIEW',id);else throw Error('Explicit frozen operation required');
