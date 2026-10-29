import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),repo=resolve(evidence,'../../../..');
const json=async p=>JSON.parse(await readFile(p,'utf8')),sha=x=>createHash('sha256').update(x).digest('hex');
const cases=(await json(resolve(evidence,'registry61-works/cases.json'))).cases;
const key=await json(resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry61-works-review/coordinator-key.json'));
const criteria=await json(resolve(here,'criteria.json'));
const output=resolve(evidence,'registry61-anonymous-review-preparation');await mkdir(output,{recursive:true});
const system='你是内部作品审阅助手，只依据原始需求、共同材料和实际作品判断。作品顺序与编号不表示优劣，不推测生成版本。不把更多字数、标题、表格或引用当作收益。合理设计变体允许；必要修改须有原句和依据。先逐份判断，再比较预设配对；允许持平、双方失败及无法确定。不要为了评测凑错或凑胜。只返回JSON：{"works":[{"workId":"编号","outcome":"DIRECTLY_USABLE或NEEDS_EDIT或SERIOUS_FAILURE或UNRESOLVED","findings":[{"severity":"serious或materialEdit或minorPreference","quote":"作品原句","source":"材料/需求依据","reason":"具体问题","necessaryEdit":"交付前必须改什么"}],"usefulContent":"具体可用之处"}],"comparisons":[{"id":"配对编号","judgment":"LEFT_BETTER或TIE或RIGHT_BETTER或BOTH_FAIL或UNRESOLVED","reason":"具体交付区别","leftQuote":"左作品原句","rightQuote":"右作品原句","editDifference":"用户实际少改/多改什么；只是文风须写持平"}]}。不输出推理过程，不使用外部工具，不把未经核实的猜测当错误。';
const jobs=[],anonymous=[],provenance=[];
for(const id of ['Q01','U02']){
  const sample=cases.find(x=>x.id===id),mapping=key.filter(x=>x.caseId===id),works=[];
  for(const item of mapping){
    const source=item.reused?item.source.path:`registry61-works/results/${item.jobId}.json`;
    const path=resolve(evidence,source),bytes=await readFile(path),stage=item.reused?'registry58-works':'registry61-works';
    const closure=await json(resolve(evidence,stage,'closure.json'));assert.equal(closure.status,'CLOSED');
    assert.equal(closure.files.find(x=>resolve(repo,x.path)===path)?.sha256,sha(bytes));
    if(item.reused)assert.equal(item.source.sha256,sha(bytes));
    const result=JSON.parse(bytes);works.push({workId:item.workId,delivered:result.status===200&&result.body.data.finishReason==='stop',content:result.body?.data?.content??''});
    provenance.push({workId:item.workId,resultSha256:sha(bytes),contentSha256:sha(result.body?.data?.content??'')});
  }
  const pairs=[];let pair=0;
  for(const repeat of [1,2])for(const [a,b]of[['ORIGINAL','31'],['ORIGINAL','61'],['31','61']]){
    const left=mapping.find(x=>x.arm===a&&x.repeat===repeat),right=mapping.find(x=>x.arm===b&&x.repeat===repeat);if(!left||!right)continue;
    const swap=parseInt(sha(`${id}:${repeat}:${a}:${b}`).slice(0,2),16)%2;
    pairs.push({id:`${id}-J${++pair}`,left:swap?right.workId:left.workId,right:swap?left.workId:right.workId});
  }
  const review={rawPrompt:sample.rawPrompt,materials:sample.executorMaterials,criteria:criteria.commonCriteria,taskCriteria:criteria[id],works,comparisons:pairs};
  anonymous.push({caseId:id,...review});
  jobs.push({id:`${id}_ANONYMOUS_REVIEW`,caseId:id,version:'61',phase:'REVIEW',purpose:'INTERNAL_BLIND_AI_REVIEW_ROOT_HAS_PRIOR_BASELINE_KNOWLEDGE',approvedModelRequest:{model:'deepseek-flash',temperature:0.2,max_tokens:8192,thinking:{type:'disabled'},stream:false,response_format:{type:'json_object'},messages:[{role:'system',content:system},{role:'user',content:JSON.stringify(review)}]}});
}
await writeFile(resolve(output,'bundle.json'),JSON.stringify({syntheticOnly:true,cases:cases.filter(x=>['Q01','U02'].includes(x.id)),jobs,phaseLimits:{REVIEW:2,EXECUTION:0,GENERATION:0,PLAN:0},parameters:{reviewMaxTokens:8192},qualityRetriesAllowed:false,internalAIReviewOnly:true,realHumanReview:false},null,2)+'\n',{flag:'wx'});
await writeFile(resolve(output,'anonymous-works.json'),JSON.stringify(anonymous,null,2)+'\n',{flag:'wx'});
await writeFile(resolve(output,'provenance.json'),JSON.stringify({actualCalls:0,provenance,rootAlreadyReadBaseline:true,onlyReviewerModelIsBlinded:true},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({prepared:true,reviewJobs:jobs.length,works:provenance.length,actualCalls:0}));
