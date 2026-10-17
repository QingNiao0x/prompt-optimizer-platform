// Publish only this batch's controlled synthetic evidence; retain complete private history and credentials locally.
import {readFile,writeFile,readdir,mkdir,copyFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
const root=resolve('tmp/me00a-candidate27-live-20261017/real');
const ledger=resolve('tmp/me00a-20261012');
const evidence=resolve('docs/testing/evidence/me00a-candidate27-live-2026-10-17');
const dest=resolve(evidence,'real');
const load=async path=>JSON.parse(await readFile(path,'utf8'));
const hash=text=>createHash('sha256').update(text).digest('hex');
const saved=[];
const scan=bytes=>{
  const text=bytes.toString('utf8');
  const configured=[process.env.MODEL_DEEPSEEK_API_KEY,process.env.MODEL_API_KEY].filter(value=>value?.length>=8);
  if(configured.some(value=>text.includes(value))||/Bearer\s+[A-Za-z0-9._-]{12,}|"Authorization"\s*:|sk-[A-Za-z0-9]{24,}/.test(text))
    throw Error('Protected credential pattern in archive; publication stopped');
};
const save=async(path,value)=>{
  const bytes=Buffer.from(typeof value==='string'?value:JSON.stringify(value,null,2)+'\n');scan(bytes);
  await mkdir(resolve(path,'..'),{recursive:true});await writeFile(path,bytes,{flag:'wx'});
  saved.push({path:path.slice(evidence.length+1).replaceAll('\\','/'),sha256:hash(bytes),exactOriginal:false});
};
const copy=async(from,to)=>{
  const bytes=await readFile(from);scan(bytes);await mkdir(resolve(to,'..'),{recursive:true});await copyFile(from,to,1);
  saved.push({path:to.slice(evidence.length+1).replaceAll('\\','/'),sha256:hash(bytes),exactOriginal:true});
};
const names=(await readdir(resolve(ledger,'traces'))).filter(name=>/^\d{3}\.json$/.test(name)).sort();
const all=await Promise.all(names.map(async name=>({name,value:await load(resolve(ledger,'traces',name))})));
const current=all.filter(item=>item.value.version==='candidate27-live');
const protocol=await load(resolve(ledger,'protocol.json')),batch=protocol.activeBatch;
if(batch.version!=='candidate27-live'||batch.status!=='CLOSED'||all.length!==332+current.length||current.length>18
  ||protocol.maximumActualModelCalls!==371||protocol.phaseLimits.candidateValidation!==291)throw Error('Closed frozen batch required');
const table=[];
for(const {name,value:trace}of current){
  if(trace.response?.choices?.some(choice=>choice.message?.reasoning_content))throw Error('Unexpected reasoning content');
  await copy(resolve(ledger,'traces',name),resolve(dest,'traces',name));
  if(trace.response?.choices?.[0]?.message?.content!==undefined)
    await save(resolve(dest,'model-content',name.replace('.json','.txt')),trace.response.choices[0].message.content);
  const usage=trace.response?.usage;
  table.push({trace:Number(name.slice(0,-5)),purpose:trace.purpose,requestId:trace.requestId,
    requestModel:trace.request?.model,responseModel:trace.response?.model,httpStatus:trace.httpStatus,
    elapsedMs:trace.elapsedMs,inputTokens:usage?.prompt_tokens??null,outputTokens:usage?.completion_tokens??null,
    totalTokens:usage?.total_tokens??null,finishReason:trace.response?.choices?.[0]?.finish_reason});
}
const allowed=new Set(['input.json','platform.json','copied.md','execution-input.md','checks.json','clipboard-lock.json','native-copy.png',
  'actual-answers.json','confirmation.json','plan-input.json','plan-platform.json','planning-lineage.json','expected-task-view.json','answer-binding.json',
  'protocol-before-answer-binding.json','protocol-after-answer-binding.json']);
const platforms=[];
for(const id of ['Q05-NATURAL','Q05-PARTIAL-SCORING','Q05-PARTIAL-SCORING-R2']){
  try{platforms.push({id,...await load(resolve(root,id,'platform.json'))});}catch(error){if(error.code!=='ENOENT')throw error;}
  for(const name of await readdir(resolve(root,id)))if(allowed.has(name))await copy(resolve(root,id,name),resolve(dest,id,name));
  try{
    const original=await readFile(resolve(root,id,'history.private.json'));
    const data=JSON.parse(original).body.data;
    await save(resolve(dest,id,'history-projection.json'),{kind:'PUBLIC_SYNTHETIC_HISTORY_PROJECTION',privateOriginalRetained:true,
      privateOriginalSha256:hash(original),rawPrompt:data.rawPrompt,optimizedPrompt:data.optimizedPrompt,sections:data.sections,
      ambiguities:data.ambiguities,appliedConstraints:data.appliedConstraints,mock:data.mock,modelName:data.modelName,providerName:data.providerName});
  }catch(error){if(error.code!=='ENOENT')throw error;}
}
await copy(resolve(root,'native-copy-checks.json'),resolve(dest,'native-copy-checks.json'));
// Only frozen work/review filenames are publishable; complete account history and credentials stay private.
for(const id of ['F1','F2','P1','P2'])for(const name of ['input.md','binding.json','work.md','execution.json','failure.json']){
  try{await copy(resolve(root,'works',id,name),resolve(dest,'works',id,name));}catch(error){if(error.code!=='ENOENT')throw error;}
}
for(const name of ['review-input.md','review-lock.json','review-mapping.private.json','independent-checks.json',
  ...['deepseek-flash','deepseek-v4-pro'].flatMap(model=>[`review-${model}.md`,`review-${model}-execution.json`,`review-${model}-failure.json`])]){
  try{await copy(resolve(root,name),resolve(dest,name.replace('.private','')));}catch(error){if(error.code!=='ENOENT')throw error;}
}
const purposes=Object.fromEntries(['PLANNING','PLATFORM_GENERATION','EXECUTION','REVIEW'].map(purpose=>[purpose,current.filter(item=>item.value.purpose===purpose).length]));
const budget={actual:{total:all.length,baseline:all.filter(item=>item.value.phase==='baseline').length,
  candidateValidation:all.filter(item=>item.value.phase==='candidateValidation').length},formalLimits:{total:371,baseline:80,candidateValidation:291},
  batch:{version:'candidate27-live',maximum:18,actual:current.length,purposes,platformRequests:{successfulEnhancements:platforms.filter(p=>p.status===200).length,failedEnhancements:platforms.filter(p=>p.status!==200).length},
    transportOrHttpFailures:current.filter(item=>item.value.httpStatus!==200).length,
    unusedClosedNotTransferable:18-current.length,downstreamCalls:purposes.EXECUTION,independentReviewCalls:purposes.REVIEW,qualityRetries:0},
  knownTotalTokens:table.reduce((sum,row)=>sum+(row.totalTokens??0),0),table,priceAndCurrencyCost:'UNKNOWN',
  scope:'HTTP200 alone cannot pass quality; hard checks, model response, assembly and independent review evidence remain separate'};
await save(resolve(evidence,'budget-final.json'),budget);
await save(resolve(evidence,'real-archive-manifest.json'),{files:saved,completePrivateHistoriesPublished:false,credentialsPublished:false,
  rawTraceCopiesByteIdentical:true,sourceModelContentUnmodified:true,modelCallsFromArchival:0});
console.log(JSON.stringify({calls:budget.actual.total,batchCalls:current.length,purposes,knownTokens:budget.knownTotalTokens,archivedFiles:saved.length}));
