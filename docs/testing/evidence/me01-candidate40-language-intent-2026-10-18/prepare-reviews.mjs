// Offline only: reuse the fixed platform rubric and blind the two works; no API call.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createHash } from 'node:crypto';
import { REVIEW_RUBRIC, rubricInstruction } from '../../../../scripts/me00a-rubric.mjs';
import { auditExecutionClaims } from '../../../../scripts/me00a-execution-claims.mjs';

const pub=resolve('docs/testing/evidence/me01-candidate40-language-intent-2026-10-18');
const root=resolve('tmp/me01-candidate40-live-20261018');
const hash=value=>createHash('sha256').update(value).digest('hex');
const json=async path=>JSON.parse(await readFile(path,'utf8'));
const save=async(path,value)=>{await mkdir(dirname(path),{recursive:true});await writeFile(path,typeof value==='string'?value:JSON.stringify(value,null,2)+'\n',{flag:'wx'});};
const lock=await json(resolve(root,'works/input-lock.json'));
const hardBytes=await readFile(resolve(root,'hard-checks.json'));const hard=JSON.parse(hardBytes.toString('utf8'));
const texts={RAW:await readFile(resolve(root,'works/TS/RAW/work.md'),'utf8'),PLAN:await readFile(resolve(root,'works/TS/PLAN/work.md'),'utf8')};
const mapping={A:'PLAN',B:'RAW'};
const claims=Object.fromEntries(Object.entries(texts).map(([arm,text])=>[arm,auditExecutionClaims(text,{executionToolsProvided:false,verifiedExecutionBeforeResponse:false})]));
await save(resolve(root,'execution-claims.json'),claims);
const sample=(await json(resolve(pub,'request-cases.json'))).find(row=>row.id==='TS-IMPLICIT');
const common=sample.rawPrompt+'\n'+lock.actualConfirmations.map(a=>`问题：${a.question}\n回答：${a.answer}`).join('\n');
const packet={task:common,scope:'Only this one independent task; no statistical win rate.',works:Object.entries(mapping).map(([id,arm])=>({id,text:texts[arm],independentChecks:{
  commonContract:hard.works[arm].commonContractState,independentTests:hard.works[arm].independent.tests,independentPassed:hard.works[arm].independent.passed,
  authoredTests:hard.works[arm].authoredTests,declaredTestCommitment:hard.works[arm].declaredTestCommitmentState,
  executionClaims:claims[arm].state,compilerProfile:hard.works[arm].controlledInteropProfileUsed?'interop-enabled-source-unchanged':'provided-flags',
  noNewLengthOrJsonConstraint:true}}))};
const system=`你是独立AI辅助评审，不是人工专家。两份匿名作品对应相同原题和实际题答，不知道哪份来自增强。不要看另一评审。
${rubricInstruction()}
仅比较共同任务；某份输入额外承诺可运行测试，则单独评估该承诺，不给另一份添加义务。独立工具指出的失败不能被评分抵消；后来运行不证明生成时已运行。
引用必须逐字来自相应作品，不能引题目、外部检查或另一份作品。每个维度提供一段短引文和理由，原文未覆盖则quotes空数组并明确缺项。
仅返回JSON：{"works":[{"id":"A或B","scores":{"accuracy":1,"completeness":1,"clarity":1,"executability":1},"evidence":{"accuracy":{"quotes":[],"reason":""},"completeness":{"quotes":[],"reason":""},"clarity":{"quotes":[],"reason":""},"executability":{"quotes":[],"reason":""}},"severeFailures":[],"uncertainties":[]}],"preferred":"A|B|TIE|BOTH_FAIL","reason":"","limitations":[]}。分数1至5，不按长度加分，不输出测试通过的无证据断言。`;
const packetText=JSON.stringify(packet);
const parameters={temperature:0.2,max_tokens:8192,thinking:{type:'disabled'},stream:false};
const requestSha256={};
for(const [id,model]of Object.entries({R1:'deepseek-flash',R2:'deepseek-v4-pro'})){
 const request={model,...parameters,response_format:{type:'json_object'},messages:[{role:'system',content:system},{role:'user',content:packetText}]};
 await save(resolve(root,'reviews',id,'request.json'),request);requestSha256[id]=hash(JSON.stringify(request));
}
const reviewLock={sourceCandidate:'candidate40',independentCalls:true,sameSupplier:true,casesSha256:lock.casesSha256,
 requestSha256,systemSha256:hash(system),packetSha256:hash(packetText),workHashes:{TS:Object.fromEntries(Object.entries(texts).map(([arm,text])=>[arm,hash(text)]))},
 hardChecksFileSha256:hash(hardBytes),rubric:REVIEW_RUBRIC.id};
await save(resolve(root,'reviews/input-lock.json'),reviewLock);await save(resolve(root,'reviews/blind-mapping.private.json'),mapping);
await save(resolve(root,'reviews/packet.json'),packet);await save(resolve(pub,'review-input-lock.json'),reviewLock);
console.log(JSON.stringify({reviewsPrepared:2,modelCalls:0,rubric:REVIEW_RUBRIC.id,claims:Object.fromEntries(Object.entries(claims).map(([arm,value])=>[arm,value.state]))}));
