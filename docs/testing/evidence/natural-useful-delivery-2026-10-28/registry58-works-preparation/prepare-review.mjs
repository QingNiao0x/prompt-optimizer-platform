import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)), evidence=resolve(here,'..'), repo=resolve(evidence,'../../../..');
const json=async file=>JSON.parse(await readFile(file,'utf8'));
const sha=value=>createHash('sha256').update(value).digest('hex');
const stage=resolve(evidence,'registry58-works'), closure=await json(resolve(stage,'closure.json')); assert.equal(closure.status,'CLOSED');
const cases=(await json(resolve(stage,'cases.json'))).cases;
const key=await json(resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry58-works-review/coordinator-key.json'));
const criteria=await json(resolve(here,'criteria.json'));
const output=resolve(evidence,'registry58-anonymous-review-preparation'); await mkdir(output,{recursive:true});
const jobs=[],anonymous=[],provenance=[];
const system='你是内部作品审阅助手，只依据原始需求、共同材料和实际作品判断。作品顺序与编号不表示优劣，不推测生成版本。不把更多字数、标题、表格或引用当作收益。合理设计变体允许；必要修改须有原句和依据。先逐份判断，再比较预设配对；允许持平、双方失败及无法确定。不要为了评测凑错或凑胜。只返回JSON：{"works":[{"workId":"编号","outcome":"DIRECTLY_USABLE或NEEDS_EDIT或SERIOUS_FAILURE或UNRESOLVED","findings":[{"severity":"serious或materialEdit或minorPreference","quote":"作品原句","source":"材料/需求依据","reason":"具体问题","necessaryEdit":"交付前必须改什么"}],"usefulContent":"具体可用之处"}],"comparisons":[{"id":"配对编号","judgment":"LEFT_BETTER或TIE或RIGHT_BETTER或BOTH_FAIL或UNRESOLVED","reason":"具体交付区别","leftQuote":"左作品原句","rightQuote":"右作品原句","editDifference":"用户实际少改/多改什么；只是文风须写持平"}]}。不输出推理过程，不使用外部工具，不把未经核实的猜测当错误。';
for(const id of ['Q01','U02','U03']) {
  const sample=cases.find(value=>value.id===id), mapping=key.filter(value=>value.caseId===id), works=[];
  for(const item of mapping) {
    const file=resolve(stage,'results',item.jobId+'.json'), bytes=await readFile(file);
    assert.equal(closure.files.find(value=>resolve(repo,value.path)===file)?.sha256,sha(bytes));
    const response=JSON.parse(bytes);
    works.push({workId:item.workId,delivered:response.status===200&&response.body.data.finishReason==='stop',content:response.body?.data?.content??''});
    provenance.push({workId:item.workId,resultSha256:sha(bytes),contentSha256:sha(response.body?.data?.content??'')});
  }
  const pairs=[]; let pair=0;
  for(const repeat of [1,2]) for(const [a,b] of [['ORIGINAL','31'],['ORIGINAL','58'],['31','58']]) {
    const left=mapping.find(value=>value.arm===a&&value.repeat===repeat),right=mapping.find(value=>value.arm===b&&value.repeat===repeat);
    if(!left||!right)continue;
    const swap=parseInt(sha(`${id}:${repeat}:${a}:${b}`).slice(0,2),16)%2;
    pairs.push({id:`${id}-J${++pair}`,left:swap?right.workId:left.workId,right:swap?left.workId:right.workId});
  }
  const review={rawPrompt:sample.rawPrompt,materials:sample.executorMaterials,criteria:criteria.commonCriteria,
    taskCriteria:criteria[id],works,comparisons:pairs};
  anonymous.push({caseId:id,...review});
  jobs.push({id:`${id}_ANONYMOUS_REVIEW`,caseId:id,version:'58',phase:'REVIEW',purpose:'INTERNAL_BLIND_WORK_REVIEW_NOT_HUMAN_EXPERT',
    approvedModelRequest:{model:'deepseek-flash',temperature:0.2,max_tokens:8192,thinking:{type:'disabled'},stream:false,
      response_format:{type:'json_object'},messages:[{role:'system',content:system},{role:'user',content:JSON.stringify(review)}]}});
}
const bundle={syntheticOnly:true,cases:cases.filter(value=>['Q01','U02','U03'].includes(value.id)),jobs,
  phaseLimits:{REVIEW:3,EXECUTION:0,GENERATION:0,PLAN:0},parameters:{reviewMaxTokens:8192},qualityRetriesAllowed:false,
  internalAIReviewOnly:true,realHumanReview:false};
for(const [name,value] of Object.entries({'bundle.json':bundle,'anonymous-works.json':anonymous,'provenance.json':{actualModelCalls:0,provenance,sourceClosureSha256:sha(await readFile(resolve(stage,'closure.json')))}}))
  await writeFile(resolve(output,name),JSON.stringify(value,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({preparedOnly:true,reviewJobs:jobs.length,anonymousWorks:provenance.length,actualModelCalls:0}));
