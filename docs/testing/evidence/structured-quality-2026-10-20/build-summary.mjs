import { readFile, writeFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { parseStrictJson } from '../../../../scripts/me00a-work-checks.mjs';
const evidence = resolve('docs/testing/evidence/structured-quality-2026-10-20');
const root = resolve('tmp/structured-quality-20261020');
const json = async path => JSON.parse(await readFile(path, 'utf8'));
const save = async (name, value) => writeFile(resolve(evidence, name), JSON.stringify(value, null, 2) + '\n', {flag:'wx'});
const cases = (await json(resolve(evidence,'cases.json'))).cases;
const protocol = await json(resolve(evidence,'protocol.json'));
const results = [];
for (const file of await readdir(resolve(root,'results'))) if (file.endsWith('.json')) results.push(await json(resolve(root,'results',file)));
const works = [];
for (const sample of cases) for (const arm of ['RAW','STRUCTURED']) {
  const result = results.find(row => row.id === sample.id + '_' + arm + '_EXEC');
  if (!result) continue;
  const content = result.body.data?.content ?? '';
  const check = {caseId:sample.id,arm,completeResponse:result.status===200 && result.body.data?.finishReason==='stop',
    han:[...content.matchAll(/\p{Script=Han}/gu)].length,nonWhitespaceCharacters:[...content.replace(/\s/gu,'')].length};
  if (sample.id==='JSON_REGISTRATION') {
    try {check.strictJsonAndExactValues=JSON.stringify(parseStrictJson(content))===JSON.stringify([{name:'李梅',date:'2026-09-01'},{name:'周安',date:null}]);}
    catch {check.strictJsonAndExactValues=false;}
  }
  if (sample.id==='EVENT_PLAN') {
    check.hanLimit=120; check.lengthPassed=check.han<=120;
    check.datePreserved=/11月13日|11\/13|2026-11-13/.test(content);
    check.inPersonPreserved=/线下/.test(content);
    check.inventedSpecificLocation=/会议室|酒店|中心[楼厅]/.test(content);
  }
  // 事实、跨样本排序和退款语义需要审阅原文；不以关键词检查冒充专业结论。
  works.push(check);
}
if (process.argv[2]==='checks') {
  await save('independent-checks.json',{policy:'Full response strict JSON; event <=120 Han; facts independently reviewed',works});
} else if (process.argv[2]==='close') {
  const traces=[];
  for (const file of (await readdir(resolve(root,'traces'))).filter(name=>name.endsWith('.json')).sort()) traces.push(await json(resolve(root,'traces',file)));
  const counts={}; let inputTokens=0,outputTokens=0,elapsedMs=0;
  for(const trace of traces) {counts[trace.phase]=(counts[trace.phase]??0)+1;inputTokens+=trace.response?.usage?.prompt_tokens??0;outputTokens+=trace.response?.usage?.completion_tokens??0;elapsedMs+=trace.elapsedMs??0;}
  const reviews=[];
  const mapping=await json(resolve(evidence,'review-mapping.json'));
  for(const id of ['REVIEW_FLASH','REVIEW_PRO']) {
    const result=results.find(row=>row.id===id); const quotes=[]; let parsed;
    try {parsed=parseStrictJson(result.body.data.content);}
    catch {reviews.push({id,status:result?.status,parsed:false});continue;}
    for(const row of parsed.cases??[]) for(const work of row.works??[]) {
      const map=mapping.find(item=>item.reviewer===id&&item.caseId===row.caseId&&item.label===work.label);
      const original=results.find(item=>item.id===row.caseId+'_'+map?.arm+'_EXEC')?.body.data?.content??'';
      for(const issue of work.issues??[]) quotes.push({caseId:row.caseId,label:work.label,claim:issue.claim,quote:issue.quote,
        exactQuote:!issue.quote||original.includes(issue.quote)});
    }
    reviews.push({id,status:result.status,parsed:true,quotes,review:parsed});
  }
  await save('http-results.json',results);
  await writeFile(resolve(evidence,'transports.jsonl'),traces.map(row=>JSON.stringify(row)).join('\n')+'\n',{flag:'wx'});
  await save('review-audit.json',reviews);
  const closure={status:'CLOSED',actualNewCalls:traces.length,maximumNewCalls:protocol.maximumNewActualCalls,
    previousClosedActualCalls:607,cumulativeActualCalls:607+traces.length,unusedCalls:protocol.maximumNewActualCalls-traces.length,
    phaseCounts:counts,qualityRetries:0,protocolRetries:traces.length-results.length,
    usage:{inputTokens,outputTokens,totalTokens:inputTokens+outputTokens,upstreamElapsedMs:elapsedMs},
    failedJobs:results.filter(row=>row.status!==200).map(row=>({id:row.id,status:row.status})),
    newAccounts:0,closedAt:new Date().toISOString(),reason:'All frozen available jobs recorded; unused calls closed without transfer'};
  await save('budget-closure.json',closure);
  await writeFile(resolve(root,'closure.json'),JSON.stringify(closure),{flag:'wx'});
  console.log(JSON.stringify(closure));
} else throw Error('Explicit checks or close required');
