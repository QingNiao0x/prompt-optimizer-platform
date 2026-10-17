// Server-side only. Secrets are inherited from local server configuration, never archived.
import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash,randomInt} from 'node:crypto';
import {rawExecutionInput,enhancedExecutionInput,completedExecutionWork} from '../../../../scripts/me00a-eval.mjs';
import {rubricInstruction} from '../../../../scripts/me00a-rubric.mjs';
const pub=resolve('docs/testing/evidence/me00a-candidate31-contract-lookup-2026-10-17');
const work=resolve('tmp/me00a-candidate31-live-20261017');
const root=resolve('tmp/me00a-20261012');
const hash=s=>createHash('sha256').update(s).digest('hex');
const json=async p=>JSON.parse(await readFile(p,'utf8'));
const save=async(p,v)=>{await mkdir(resolve(p,'..'),{recursive:true});await writeFile(p,typeof v==='string'?v:JSON.stringify(v,null,2)+'\n',{flag:'wx'});};
const parameters={temperature:0.2,max_tokens:10000,thinking:{type:'disabled'},stream:false};
const system='你是执行给定任务的工程师。依据本次工作区文件完成实现及可执行JUnit 5测试，不实际访问数据库或网络。输出完整的修改文件；每个Java代码块前单独写出src/main/java/或src/test/java/开头的工作区相对文件路径，代码块使用```java。不要重复输出未修改文件。';
async function materials(){
 const lock=await json(resolve(pub,'execution-project-lock.json'));const files=[];
 for(const row of lock.files){const bytes=await readFile(resolve(pub,'execution-project',row.path));if(hash(bytes)!==row.sha256)throw Error('Project source drift');files.push({path:row.path,content:bytes.toString('utf8')});}
 return files;
}
async function verifiedCopy(){
 const platform=await json(resolve(work,'live-results/EXISTING-PLAN-ENHANCE/platform.json'));
 const copy=await readFile(resolve(work,'live-results/EXISTING-PLAN-ENHANCE/native-copy.md'),'utf8');
 const check=await json(resolve(work,'live-results/EXISTING-PLAN-ENHANCE/native-copy-check.json'));
 const plan=await json(resolve(work,'live-results/EXISTING-PLAN/platform.json'));
 if(platform.status!==200||plan.status!==200||plan.body.data.questions.length!==0||!check.nativeCopyMatches||copy.replace(/\r\n/g,'\n')!==platform.body.data.optimizedPrompt.replace(/\r\n/g,'\n'))throw Error('Successful native Plan copy required; no answer fabrication');
 return copy;
}
async function prepareWorks(){
 const cases=await json(resolve(pub,'cases.json'));const sample={...cases[0],context:{files:await materials()}};
 const copy=await verifiedCopy();
 const inputs={RAW:rawExecutionInput(sample,[]),PLAN:enhancedExecutionInput(copy,sample)};
 const lock={sourceCandidate:'candidate31r2',parameters,model:'deepseek-flash',commonSystemSha256:hash(system),actualAnswers:[],sharedProjectSha256:hash(JSON.stringify(sample.context.files)),nativeCopySha256:hash(copy),arms:{}};
 for(const arm of ['RAW','PLAN']){
  const request={model:'deepseek-flash',...parameters,messages:[{role:'system',content:system},{role:'user',content:inputs[arm]}]};
  await save(resolve(work,'works',arm,'request.json'),request);lock.arms[arm]={requestSha256:hash(JSON.stringify(request)),promptSha256:hash(inputs[arm])};
 }
 await save(resolve(work,'works/input-lock.json'),lock);
 console.log('TWO_WORK_INPUTS_FROZEN; actual native copy unchanged; equal project evidence');
}
async function prepareReviews(){
 const mapping=randomInt(2)?{A:'RAW',B:'PLAN'}:{A:'PLAN',B:'RAW'};
 const cases=await json(resolve(pub,'cases.json'));const files=await materials();
 const packet={originalTask:cases[0].rawPrompt,actualConfirmations:[],project:files,works:{}};
 for(const [blind,arm] of Object.entries(mapping)){
  const req=await json(resolve(work,'works',arm,'request.json'));
  const text=await readFile(resolve(work,'works',arm,'work.md'),'utf8');
  const check=await json(resolve(work,'compiled-'+arm,'result.json'));
  packet.works[blind]={prompt:req.messages[1].content,work:text,independentChecks:{compileExitCode:check.mavenExit,passed:check.passed,reports:check.reports,checkerFailure:check.failure}};
 }
 const reviewSystem='你是独立AI辅助评审；不是人类专家。不看另一位评审。两份匿名作品在相同项目资料和实际确认信息下生成。先核对原任务，再核对代码与测试，不因更多标题或篇幅给高分。'+rubricInstruction()+'\n只返回JSON：{"scores":{"A":{"accuracy":1,"completeness":1,"clarity":1,"executability":1},"B":{"accuracy":1,"completeness":1,"clarity":1,"executability":1}},"evidence":{"A":["原句和代码依据、必要修改"],"B":["原句和代码依据、必要修改"]},"severeFailures":{"A":[],"B":[]},"verdict":"WIN_A或WIN_B或TIE或BOTH_FAIL","promptAmbiguities":[],"uncertainties":[]}。分数1至5，编译、安全或核心契约失败独立阻断，不被平均分抵消；无法核实的运行结果明确为未知。';
 await save(resolve(work,'reviews/blind-mapping.private.json'),mapping);
 const lock={packetSha256:hash(JSON.stringify(packet)),systemSha256:hash(reviewSystem),models:['deepseek-flash','deepseek-v4-pro'],parameters,independent:true,sameSupplier:true};
 for(const [id,model] of [['R1','deepseek-flash'],['R2','deepseek-v4-pro']])await save(resolve(work,'reviews',id,'request.json'),{model,...parameters,response_format:{type:'json_object'},messages:[{role:'system',content:reviewSystem},{role:'user',content:JSON.stringify(packet)}]});
 await save(resolve(work,'reviews/input-lock.json'),lock);console.log('TWO_INDEPENDENT_BLIND_REVIEWS_FROZEN');
}
async function call(purpose,id){
 if(purpose==='EXECUTION'&&!['RAW','PLAN'].includes(id)||purpose==='REVIEW'&&!['R1','R2'].includes(id))throw Error('Frozen arm required');
 const group=purpose==='EXECUTION'?'works':'reviews';const dir=resolve(work,group,id);
 const request=await json(resolve(dir,'request.json'));const lock=await json(resolve(work,group,'input-lock.json'));
 const protocol=await json(resolve(root,'protocol.json'));const batch=protocol.activeBatch;
 if(batch.version!=='candidate31-live'||batch.status!=='OPEN'||batch.authorizationStatus!=='APPROVED_BY_USER'||batch.callsBefore!==351||batch.maximumNewCalls!==16)throw Error('Closed or incorrect approved batch');
 if(purpose==='EXECUTION'){
  await verifiedCopy();if(request.model!=='deepseek-flash'||hash(JSON.stringify(request))!==lock.arms[id].requestSha256)throw Error('Work request drift');
 }else if(request.model!==({R1:'deepseek-flash',R2:'deepseek-v4-pro'})[id]||hash(request.messages[0].content)!==lock.systemSha256||hash(request.messages[1].content)!==lock.packetSha256)throw Error('Review request drift');
 for(const [key,value] of Object.entries(parameters))if(JSON.stringify(request[key])!==JSON.stringify(value))throw Error('Parameter drift');
 const key=process.env.MODEL_DEEPSEEK_API_KEY??process.env.MODEL_API_KEY;
 const endpoint=new URL(process.env.MODEL_DEEPSEEK_ENDPOINT??'https://api.deepseek.com/chat/completions');
 if(!key||endpoint.protocol!=='https:'||endpoint.hostname!=='api.deepseek.com'||endpoint.pathname!=='/chat/completions'||endpoint.username||endpoint.password||endpoint.search)throw Error('Official server-side model configuration unavailable');
 // Duplicate operation guards are separate from the shared, canonical per-attempt reservation.
 await save(resolve(dir,'call-once.json'),{purpose,id,scheduledOnce:true,qualityRetriesAllowed:false});
 const cp=(await readFile(resolve(work,'runner/classpath.txt'),'utf8')).trim();
 const tracePath=execFileSync('E:/Delelop/Java/jdk-21/bin/java.exe',['-cp',cp,'ReserveEvaluationCall',purpose],{encoding:'utf8',windowsHide:true}).trim();
 if(!/^\d+\.json$/.test(tracePath.split(/[\\/]/).at(-1))||resolve(tracePath,'..')!==resolve(root,'traces'))throw Error('Invalid shared reservation path');
 const record={version:'candidate31-live',phase:'candidateValidation',purpose,operation:id,sourceCandidate:'candidate31r2',startedAt:new Date().toISOString(),request};
 await writeFile(tracePath,JSON.stringify(record,null,2)+'\n');const started=Date.now();
 try{
  const response=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+key},body:JSON.stringify(request),signal:AbortSignal.timeout(120000)});
  record.httpStatus=response.status;const responseText=await response.text();
  try{record.response=JSON.parse(responseText);for(const choice of record.response.choices??[])if(choice.message)delete choice.message.reasoning_content;}catch{record.responseText=responseText;}
  record.elapsedMs=Date.now()-started;await writeFile(tracePath,JSON.stringify(record,null,2)+'\n');
  await save(resolve(dir,'response.json'),record);
  const completed=completedExecutionWork(record);await save(resolve(dir,purpose==='EXECUTION'?'work.md':'review.md'),completed.text);
  console.log(JSON.stringify({purpose,id,trace:tracePath.split(/[\\/]/).at(-1),status:response.status,finishReason:completed.finishReason,tokens:record.response.usage,elapsedMs:record.elapsedMs}));
 }catch(error){
  record.elapsedMs=Date.now()-started;record.failure=error.name;await writeFile(tracePath,JSON.stringify(record,null,2)+'\n');
  try{await save(resolve(dir,'failure.json'),{failure:error.name,elapsedMs:record.elapsedMs});}catch{}
  throw Error('Recorded external attempt failed: '+error.name);
 }
}
const mode=process.argv[2];
if(mode==='prepare-works')await prepareWorks();
else if(mode==='prepare-reviews')await prepareReviews();
else if(mode==='work')await call('EXECUTION',process.argv[3]);
else if(mode==='review')await call('REVIEW',process.argv[3]);
else throw Error('Unknown frozen external operation');
