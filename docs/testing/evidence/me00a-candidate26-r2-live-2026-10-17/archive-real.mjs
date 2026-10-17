// Publish only this batch's controlled synthetic evidence; retain complete private history and credentials locally.
import {readFile,writeFile,readdir,mkdir,copyFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
const root=resolve('tmp/me00a-candidate26-r2-live-20261017/real');
const ledger=resolve('tmp/me00a-20261012');
const evidence=resolve('docs/testing/evidence/me00a-candidate26-r2-live-2026-10-17');
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
const current=all.filter(item=>item.value.version==='candidate26-r2-live');
if(current.length!==5||all.length!==332)throw Error('Ledger changed; stop archive instead of changing counts');
const table=[];
for(const {name,value:trace}of current){
  if(trace.response?.choices?.some(choice=>choice.message?.reasoning_content))throw Error('Unexpected reasoning content');
  await copy(resolve(ledger,'traces',name),resolve(dest,'traces',name));
  await save(resolve(dest,'model-content',name.replace('.json','.txt')),trace.response.choices[0].message.content);
  const usage=trace.response.usage;
  table.push({trace:Number(name.slice(0,-5)),purpose:trace.purpose,requestId:trace.requestId,
    requestModel:trace.request.model,responseModel:trace.response.model,httpStatus:trace.httpStatus,
    elapsedMs:trace.elapsedMs,inputTokens:usage?.prompt_tokens??null,outputTokens:usage?.completion_tokens??null,
    totalTokens:usage?.total_tokens??null,finishReason:trace.response.choices[0].finish_reason});
}
const allowed=new Set(['input.json','platform.json','copied.md','execution-input.md','checks.json','clipboard-lock.json','native-copy.png',
  'actual-answers.json','confirmation.json','plan-input.json','plan-platform.json','planning-lineage.json','expected-task-view.json','answer-binding.json',
  'protocol-before-answer-binding.json','protocol-after-answer-binding.json']);
for(const id of ['Q05-NATURAL','Q05-PARTIAL-SCORING']){
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
const purposes=Object.fromEntries(['PLANNING','PLATFORM_GENERATION','EXECUTION','REVIEW'].map(purpose=>[purpose,current.filter(item=>item.value.purpose===purpose).length]));
const budget={actual:{total:all.length,baseline:all.filter(item=>item.value.phase==='baseline').length,
  candidateValidation:all.filter(item=>item.value.phase==='candidateValidation').length},formalLimits:{total:353,baseline:80,candidateValidation:273},
  batch:{version:'candidate26-r2-live',maximum:15,actual:current.length,purposes,platformRequests:{successfulEnhancements:1,failedEnhancements:1},
    transportOrHttpFailures:current.filter(item=>item.value.httpStatus!==200).length,protocolOrParseRejectedResponses:3,
    unusedClosedNotTransferable:10,downstreamWorksNotRun:4,independentReviewsNotRun:2,qualityRetries:0},
  knownTotalTokens:table.reduce((sum,row)=>sum+(row.totalTokens??0),0),table,priceAndCurrencyCost:'UNKNOWN',
  scope:'HTTP200 does not mean application validation passed; no downstream quality conclusion from this batch'};
await save(resolve(evidence,'budget-final.json'),budget);
await save(resolve(evidence,'real-archive-manifest.json'),{files:saved,completePrivateHistoriesPublished:false,credentialsPublished:false,
  rawTraceCopiesByteIdentical:true,sourceModelContentUnmodified:true,modelCallsFromArchival:0});
console.log(JSON.stringify({calls:budget.actual.total,batchCalls:current.length,purposes,knownTokens:budget.knownTotalTokens,archivedFiles:saved.length}));
