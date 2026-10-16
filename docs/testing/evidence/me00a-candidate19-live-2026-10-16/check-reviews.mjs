// Bridge the frozen cases/A-B review schema to the existing literal-evidence auditor.
// Derived placeholder fields serve schema compatibility only; they are not new AI findings.
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {auditIndependentReview} from '../../../../scripts/me00a-review-checks.mjs';
import {parseStrictJson} from '../../../../scripts/me00a-work-checks.mjs';
const root=resolve('docs/testing/evidence/me00a-candidate19-live-2026-10-16/real');
const load=async p=>JSON.parse(await readFile(resolve(root,p),'utf8'));
const mapping=await load('review-mapping.json');
const measurements=(await load('work-measurements.json')).works;
const cases={
  'requests-title':{raw:['requests-title','raw'],enhanced:['requests-title','enhanced']},
  'mortality-2015-2025':{raw:['mortality-2015-2025','raw'],enhanced:['mortality-2015-2025','enhanced']},
  'Q05-direct':{raw:['Q05-raw-same-info','raw'],enhanced:['Q05-direct-same-info','enhanced']},
  'Q05-plan':{raw:['Q05-raw-same-info','raw'],enhanced:['Q05-plan','enhanced']},
};
const dim={accuracy:'准确性',completeness:'完整性',clarity:'清晰度',executability:'可执行性'};
const verdicts={'A胜':'LEFT_WIN','B胜':'RIGHT_WIN','平':'TIE','双方失败':'BOTH_FAIL'};
const summaries=[];
for(const model of ['deepseek-flash','deepseek-v4-pro']){
  const original=await readFile(resolve(root,`review-${model}.md`),'utf8');
  let review,strictJson=true;
  try{review=parseStrictJson(original);}catch{
    strictJson=false;const match=original.trim().match(/^```json\s*\n([\s\S]*?)\n```$/);
    if(!match)throw new Error('Review cannot be parsed even as a derived fenced view');
    review=parseStrictJson(match[1]);
  }
  if(review.cases?.length!==4||new Set(review.cases.map(c=>c.id)).size!==4||review.cases.some(c=>!cases[c.id]||!verdicts[c.verdict]))throw new Error('Original cases schema invalid');
  const works={},pairs=[],gates={},projection={reviewerType:'AI_ASSISTED',scores:{},issues:[],pairs:[]};
  for(const c of review.cases){
    const ids={A:c.id+'_A',B:c.id+'_B'};
    for(const side of ['A','B']){
      const [folder,arm]=cases[c.id][mapping[c.id][side]],id=ids[side];
      const input=await readFile(resolve(root,folder,`${arm}-actual-execution-input.md`),'utf8');
      works[id]={text:await readFile(resolve(root,folder,`${arm}-work.md`),'utf8'),input,inputSha256:createHash('sha256').update(input).digest('hex')};
      const scores=c['scores'+side];
      const keys=Object.keys(scores).sort().join(',');
      const english=Object.keys(dim).sort().join(','),chinese=Object.values(dim).sort().join(',');
      if(keys!==english&&keys!==chinese)throw new Error('Ambiguous or incomplete score-key mapping');
      projection.scores[id]=Object.fromEntries(Object.entries(dim).map(([k,ch])=>[k,scores[keys===english?k:ch]]));
      const observation=measurements.find(m=>m.id===folder&&m.arm===arm);
      gates[id]=observation?.length?.pass===false||folder==='requests-title'&&arm==='enhanced'?'FAIL':'OPEN';
    }
    const pair={left:ids.A,right:ids.B};pairs.push(pair);
    projection.pairs.push({...pair,verdict:verdicts[c.verdict],reason:'原评审结论：'+c.verdict+'；此派生字段仅供协议映射，不新增理由。'});
    for(const item of c.evidence){
      if(!ids[item.work]||typeof item.quote!=='string')throw new Error('Unbound evidence side');
      projection.issues.push({work:ids[item.work],origin:'WORK',severity:'MINOR',evidence:item.quote,reason:'原评审引用的机械核验；严重性占位不表示质量评级。'});
    }
  }
  const audit=auditIndependentReview(JSON.stringify(projection),{works,pairs,gates});
  const record={originalReviewSha256:createHash('sha256').update(original).digest('hex'),originalStrictJson:strictJson,
    fencedParseIsDiagnosticOnly:!strictJson,originalCasesAndVerdicts:review,derivedAudit:audit,
    normalization:'Only schema/ID/Chinese dimension-name mapping. Placeholder issue severity and reason are mechanical; no new semantic verdict. Raw original unchanged.',
    cannotValidateByItself:['Scientific accuracy','Verdict justification','Full requirement adherence','Length or code behaviour']};
  await writeFile(resolve(root,`review-${model}-audit-v2.json`),JSON.stringify(record,null,2)+'\n',{flag:'wx'});
  summaries.push({model,originalStrictJson:strictJson,derivedQuoteChecks:(audit.evidenceChecks??[]).length,quoteErrors:audit.errors,
    verdicts:review.cases.map(c=>({id:c.id,original:c.verdict,preferred:c.verdict==='平'?'TIE':c.verdict==='双方失败'?'BOTH_FAIL':mapping[c.id][c.verdict[0]],scoresA:c.scoresA,scoresB:c.scoresB,severeFailures:c.severeFailures}))});
}
await writeFile(resolve(root,'review-summary.json'),JSON.stringify({aiAssistedNotHumanExperts:true,sameProviderDifferentModels:true,independentIdenticalPacks:true,originalReviewsUnchanged:true,summaries},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(summaries.map(s=>({model:s.model,strictJson:s.originalStrictJson,quoteChecks:s.derivedQuoteChecks,quoteErrors:s.quoteErrors.length,verdicts:s.verdicts.map(v=>({id:v.id,preferred:v.preferred}))}))));
