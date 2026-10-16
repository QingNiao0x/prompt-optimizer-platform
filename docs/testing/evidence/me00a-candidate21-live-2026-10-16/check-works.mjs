// Deterministic saved-work observations. Counts never replace semantic or professional review.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {checkResearchLength} from '../../../../scripts/me00a-work-checks.mjs';
const root=resolve('tmp/me00a-candidate21-live-20261016/real');
const output=resolve('docs/testing/evidence/me00a-candidate21-live-2026-10-16/real');
await mkdir(output,{recursive:true});
const jobs=[['requests-title','raw'],['requests-title','enhanced'],['mortality-2015-2025','raw'],['mortality-2015-2025','enhanced'],['Q05-raw-same-info','raw'],['Q05-direct-same-info','enhanced'],['Q05-plan','enhanced'],['requests-title-pro','enhanced'],['Q05-raw-pro-same-info','raw'],['Q05-plan-pro','enhanced']];
const rows=[];
for(const[id,arm]of jobs){
  const work=await readFile(resolve(root,id,`${arm}-work.md`),'utf8');
  const input=await readFile(resolve(root,id,`${arm}-actual-execution-input.md`),'utf8');
  const execution=JSON.parse(await readFile(resolve(root,id,`${arm}-execution.json`),'utf8'));
  const sha=s=>createHash('sha256').update(s).digest('hex');
  if(sha(input)!==execution.inputSha256)throw new Error('Actual execution input differs from frozen record');
  rows.push({id,arm,inputSha256:sha(input),workSha256:sha(work),characters:[...work].length,hanCharacters:[...work.matchAll(/\p{Script=Han}/gu)].length,
    nonWhitespaceCharacters:[...work.replace(/\s/gu,'')].length,finishReason:execution.finishReason,
    length:id.startsWith('Q05')?checkResearchLength(work):{gate:'NOT_APPLICABLE_NO_USER_LIMIT'},
    missingStateExact:[...work.matchAll(/现有资料未提供/gu)].length,pendingPhrase:[...work.matchAll(/待确认/gu)].length,
    annualBlankRows:[...work.matchAll(/^\|\s*20(?:1[5-9]|2[0-5])\s*\|.*(?:未提供|待确认|待补|待填)/gm)].length,
    markdownTableRows:work.split(/\r?\n/).filter(line=>/^\s*\|.*\|\s*$/.test(line)&&!/^\s*\|[\s:|\-]+\|\s*$/.test(line)).length,
    professionalValidity:'REQUIRES_SEPARATE_EVIDENCE_CHECK'});
}
await writeFile(resolve(output,'work-measurements.json'),JSON.stringify({countPolicy:'Existing q05-body-han-v1, no retrospective threshold change',works:rows,modelCalls:0},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(rows.map(({id,arm,length,missingStateExact,pendingPhrase,annualBlankRows})=>({id,arm,length,missingStateExact,pendingPhrase,annualBlankRows}))));
