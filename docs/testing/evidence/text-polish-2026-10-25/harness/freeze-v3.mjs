import {readFile, writeFile, copyFile} from 'node:fs/promises';
import {resolve} from 'node:path';
const out=resolve('docs/testing/evidence/text-polish-2026-10-25');
const previous=JSON.parse(await readFile(resolve(out,'protocol-v2.json'),'utf8'));
const ledger=JSON.parse(await readFile(resolve(out,'ledger.json'),'utf8'));
if (ledger.closed || ledger.actualCalls!==39 || ledger.reserved!==0) throw new Error('Unexpected budget baseline');
const jobs=['FORMAL','NATURAL','CONVERSATIONAL'].map(style=>({id:`v3-legal-${style}-flash`,sample:'legal',style,modelId:'deepseek:deepseek-flash'}));
jobs.push({id:'v3-legal-NATURAL-pro',sample:'legal',style:'NATURAL',modelId:'deepseek:deepseek-v4-pro'});
for(const sample of ['notice','clean']) jobs.push({id:`v3-${sample}-NATURAL-flash`,sample,style:'NATURAL',modelId:'deepseek:deepseek-flash'});
const protocol={frozenAt:new Date().toISOString(),authorization:previous.authorization,maxActualCalls:50,baselineActualCalls:39,
 strategyVersion:'text-polish-v3',hypothesis:'v2把泛化措辞中的独立服务承诺当作冗余删除；明确只压缩重复措辞，保留观点、感受、计划、承诺的独立含义。没有按题目或模型新增分支。',
 stopCondition:'同一机制的第二个候选仍丢失独立承诺时停止该假设，不继续同义重试；保留首次失败。',
 phase:{generationJobs:6,reservedProtocolRepairs:1,maximumAdditionalCalls:7,maximumCumulativeCalls:46},
 generationParameters:previous.generationParameters,models:previous.models,samples:previous.samples,jobs,accountHash:previous.accountHash};
await writeFile(resolve(out,'protocol-v3.json'),JSON.stringify(protocol,null,2),{flag:'wx'});
await copyFile('services/api/src/main/java/com/promptoptimizer/textpolish/service/TextPolishInstructions.java',resolve(out,'TextPolishInstructions-v3.java'));
console.log(JSON.stringify({frozen:true,jobs:jobs.length,baseline:ledger.actualCalls,phaseMax:7,totalMax:50}));
