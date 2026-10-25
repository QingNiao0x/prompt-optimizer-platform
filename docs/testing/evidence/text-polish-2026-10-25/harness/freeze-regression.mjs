import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
const out=resolve('docs/testing/evidence/text-polish-2026-10-25');
const previous=JSON.parse(await readFile(resolve(out,'protocol-v3.json'),'utf8'));
const ledger=JSON.parse(await readFile(resolve(out,'ledger.json'),'utf8'));
if(ledger.closed||ledger.actualCalls!==46||ledger.reserved!==0)throw new Error('Unexpected task budget');
const jobs=['medical','material'].map(sample=>({id:`v3-${sample}-NATURAL-flash`,sample,style:'NATURAL',modelId:'deepseek:deepseek-flash'}));
const protocol={...previous,frozenAt:new Date().toISOString(),baselineActualCalls:46,
 hypothesis:'不再调整指导词；以最终v3默认模型定向回归医学摘要中的证据限定与文章中的指令式引文。不是新增专业能力或新质量候选。',
 phase:{generationJobs:2,reservedProtocolRepairs:2,maximumAdditionalCalls:4,maximumCumulativeCalls:50},jobs};
await writeFile(resolve(out,'protocol-regression.json'),JSON.stringify(protocol,null,2),{flag:'wx'});
console.log(JSON.stringify({frozen:true,jobs:jobs.length,baseline:ledger.actualCalls,maxCumulative:50}));
