// Archive only this batch's synthetic inputs, model responses and safe metadata.
// Account credentials, browser profiles and full private histories stay in tmp.
import { readFile, writeFile, mkdir, readdir, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
const project=resolve('.'), own=resolve('tmp/me00a-candidate16-20261016');
const src=resolve(own,'real'), evidence=resolve('docs/testing/evidence/task-constraint-boundary-2026-10-16');
const dest=resolve(evidence,'real'), runtime=resolve('tmp/me00a-20261012');
const load=async path=>JSON.parse(await readFile(path,'utf8'));
const hash=value=>createHash('sha256').update(value).digest('hex');
const save=async(path,value)=>{await mkdir(resolve(path,'..'),{recursive:true});
  await writeFile(path,typeof value==='string'?value:JSON.stringify(value,null,2),{flag:'wx'});};
const copy=async(from,to)=>{await mkdir(resolve(to,'..'),{recursive:true});await copyFile(from,to,1);};
const manifest=await load(resolve(evidence,'candidate16-source.json'));
const verified={};
for(const key of ['sourceFiles','fixtures','classFiles']){
  const changed=[];
  for(const file of manifest[key])if(hash(await readFile(resolve(file.path)))!==file.sha256)changed.push(file.path);
  verified[key]={count:manifest[key].length,changed};
  if(changed.length)throw new Error('Frozen source/runtime changed');
}
await save(resolve(evidence,'post-live-freeze-check.json'),{...verified,allMatch:true,verifiedAt:new Date().toISOString()});
for(const name of ['preflight.json','copies-lock.json','native-copy-checks.json','review-input.md','review-input-lock.json',
  'review-deepseek-flash.md','review-deepseek-pro-failure.json','review-deepseek-v4-pro.md'])
  await copy(resolve(src,name),resolve(dest,name));
const traceNames=(await readdir(resolve(runtime,'traces'))).filter(name=>/^\d+\.json$/.test(name));
const all=await Promise.all(traceNames.map(async name=>({number:Number(name.slice(0,-5)),record:await load(resolve(runtime,'traces',name))})));
const current=all.filter(item=>item.record.version==='candidate16').sort((a,b)=>a.number-b.number);
const table=[];
for(const {number,record}of current){
  // Thinking is explicitly disabled. Refuse a public archive containing hidden
  // reasoning or authorization material instead of silently relabelling it raw.
  for(const choice of record.response?.choices??[]){
    if(choice.message?.reasoning_content)throw new Error('Nonempty hidden reasoning needs a separate private capture');
  }
  const text=JSON.stringify(record,null,2);
  if(/Bearer\s+[A-Za-z0-9._-]{10,}/.test(text)||/"Authorization"\s*:/.test(text))throw new Error('Authorization material in trace');
  await save(resolve(dest,'traces',`${number}.json`),text);
  const usage=record.response?.usage;
  table.push({trace:number,purpose:record.purpose,label:record.label??null,requestId:record.requestId??null,
    model:record.request.model,responseModel:record.response?.model??null,httpStatus:record.httpStatus,
    elapsedMs:record.elapsedMs,inputTokens:usage?.prompt_tokens??null,outputTokens:usage?.completion_tokens??null,
    totalTokens:usage?.total_tokens??null,failure:record.failure??null});
}
const measured=[];
for(const id of ['requests-title','mortality-2015-2025']){
  const folder=resolve(src,id),target=resolve(dest,id);
  for(const name of ['input.json','platform.json','checks.json','copied.md','execution-input.md','clipboard-observed.md',
    'raw-work.md','enhanced-work.md','raw-actual-execution-input.md','enhanced-actual-execution-input.md']){
    // The native clipboard file name is discoverable only in this fixed scope.
    try{await copy(resolve(folder,name),resolve(target,name));}
    catch(error){if(error.code!=='ENOENT')throw error;}
  }
  if(id==='requests-title'){
    for(const name of ['input.json','platform.json'])
      await copy(resolve(folder,'recovered-after-harness',name),resolve(target,'recovered-after-harness',name));
    for(const name of (await readdir(resolve(folder,'code-check'))).filter(name=>name.endsWith('.py')||name==='result.json'))
      await copy(resolve(folder,'code-check',name),resolve(target,'code-check',name));
  }
  for(const arm of ['raw','enhanced']){
    const text=await readFile(resolve(folder,`${arm}-work.md`),'utf8');
    measured.push({id,arm,sha256:hash(text),characters:[...text].length,
      hanCharacters:(text.match(/\p{Script=Han}/gu)??[]).length,nonWhitespaceCharacters:[...text.replace(/\s/gu,'')].length,
      userLengthLimit:null,lengthGate:'NOT_APPLICABLE_NO_USER_LIMIT',
      missingStatePhraseOccurrences:(text.match(/现有资料未提供/g)??[]).length,
      annualBlankDataRows:(text.match(/^\|\s*20(?:1[5-9]|2[0-5])\s*\|.*现有资料未提供/gm)??[]).length});
  }
}
await save(resolve(dest,'work-measurements.json'),{policy:'Unicode Han code points / non-whitespace code points; counts are descriptive, not a new length limit',works:measured});
const mapping=await load(resolve(src,'review-mapping.private.json'));
const reviews=[];
for(const model of ['deepseek-flash','deepseek-v4-pro']){
  const data=JSON.parse(await readFile(resolve(src,`review-${model}.md`),'utf8'));
  if(data.cases?.length!==2)throw new Error('Review case coverage incomplete');
  reviews.push({model,cases:data.cases.map(item=>{
    for(const scores of [item.scoresA,item.scoresB])
      if(!['准确性','完整性','清晰度','可执行性'].every(key=>Number.isInteger(scores[key])&&scores[key]>=1&&scores[key]<=5))
        throw new Error('Review scale invalid');
    return{id:item.id,mapping:mapping[item.id],rawScores:mapping[item.id].A==='raw'?item.scoresA:item.scoresB,
      enhancedScores:mapping[item.id].A==='enhanced'?item.scoresA:item.scoresB,originalVerdict:item.verdict,
      interpretation:item.verdict==='A胜'?(mapping[item.id].A==='enhanced'?'ENHANCED_PREFERRED':'RAW_PREFERRED'):'OTHER',
      originalSevereFailures:item.severeFailures};
  })});
}
await save(resolve(dest,'review-interpretation.json'),{aiAssistedNotHumanExperts:true,independentInputsIdentical:true,
  sameSupplierDifferentModels:true,mapping,reviews,qualityGateOverriddenByScores:false,
  localCautions:['Both reviewers missed the wrong Arriaga journal citation in the raw work',
    'Missing code, calculation examples or executable templates are not independently mandated by the original research prompt',
    'Raw preference does not make the raw work factually qualified; no majority-vote release decision']});
const protocol=await load(resolve(runtime,'protocol.json'));
await save(resolve(evidence,'budget-final.json'),{actual:{total:all.length,baseline:all.filter(t=>t.record.phase==='baseline').length,
  candidateValidation:all.filter(t=>t.record.phase==='candidateValidation').length},formalLimits:{total:protocol.maximumActualModelCalls,...protocol.phaseLimits},
  batch:{version:'candidate16',maximum:12,actual:current.length,unusedClosedNotTransferable:12-current.length,
    purposes:table.reduce((counts,t)=>(counts[t.purpose]=(counts[t.purpose]??0)+1,counts),{}),
    http200:table.filter(t=>t.httpStatus===200).length,http400:table.filter(t=>t.httpStatus===400).length,
    status:'CLOSED',qualityRetries:0},
  uncountedLocalPreIoFailure:{case:'requests-title',requestId:'d3094bee-6561-4808-bd1d-5d28b4bc17e4',status:502,actualModelCalls:0,
    attribution:'Evaluation harness template whitelist rejected GENERAL before upstream reservation; preserved separately'},
  table,knownTokens:table.reduce((sum,t)=>sum+(t.totalTokens??0),0),failureTokenUsage:'Pro HTTP400 not supplied; not claimed zero billed tokens',
  runtimeScope:'Local source, isolated Java port9001, existing web5173/Redis/PostgreSQL, canonical account; no Docker',
  accountCreation:0,newSuccessfulOptimizationHistories:2,priceAndCurrencyCost:'UNKNOWN'});
// Latest harness and the rejected initial guard are retained separately.
for(const name of ['MeEvaluationServer.java','HarnessInputScopeTest.java','start-candidate16.ps1','real-revalidation.mjs','real-clipboard.mjs'])
  await copyFile(resolve(own,'harness',name),resolve(evidence,'harness',name));
await copyFile(resolve(own,'harness/MeEvaluationServer-r1.java'),resolve(evidence,'harness/MeEvaluationServer-r1.java.txt'));
for(const name of ['harness-input-scope-red.json','harness-input-scope-green.json'])
  await copyFile(resolve(own,name),resolve(evidence,name));
console.log(JSON.stringify({archivedModelCalls:current.length,knownTokens:table.reduce((sum,t)=>sum+(t.totalTokens??0),0),freezeMatch:true}));
