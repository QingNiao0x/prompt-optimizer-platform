import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)), evidence=resolve(here,'..'), repo=resolve(evidence,'../../../..');
const sha=value=>createHash('sha256').update(value).digest('hex');
const json=async path=>JSON.parse((await readFile(resolve(evidence,path),'utf8')).replace(/^\uFEFF/,''));
async function closed(stage,name) {
  const path=resolve(evidence,stage,name), bytes=await readFile(path), closure=await json(`${stage}/closure.json`);
  assert.equal(closure.status,'CLOSED');
  assert.equal(closure.files.find(file=>resolve(repo,file.path)===path)?.sha256,sha(bytes));
  return {value:JSON.parse(bytes),ref:{path:`${stage}/${name}`,sha256:sha(bytes)}};
}
const development=await json('registry58-development/jobs.json');
const bundleCases=(await json('registry58-development/cases.json')).cases;
const sourcePack=await readFile(resolve(evidence,'query-source-pack.json'),'utf8');
const cases=[],jobs=[],provenance=[],excluded=[];
const baselines={Q01:'development-r2',U02:'completion47',U03:'completion47',C01:'registry58-development',C02:'registry58-development'};
const commonSystem='请直接完成用户的任务，交付可阅读且能实际使用的结果。可使用自然段、标题、要点或表格。仅使用本次提供的共同材料和通用知识，没有外部工具；不得声称已联网或已运行。';
const baseCode='完成用户当前代码修改需求。只能使用所给项目材料，不能访问外部工具或声称已经运行。以JSON对象返回需要修改文件的完整内容，格式为{"files":[{"path":"相对路径","content":"完整文件正文"}],"summary":"可选简短说明"}；files必填，summary可省略。不要在JSON外附Markdown或解释，不需修改的文件可省略。没有执行工具时，不得声称已运行测试。';
const information=input=>({rawPrompt:input.rawPrompt,context:input.context??{customDescription:'',files:[]},conversationHistory:input.conversationHistory??[],permissionPolicy:input.permissionPolicy??null,planConfirmation:input.planConfirmation??null});
for(const id of ['C01','C02','Q01','U02','U03']) {
  const candidateJob=development.find(job=>job.id===`${id}_V58_GEN`), input=candidateJob.approvedEnhancementInput;
  const candidate=await closed('registry58-development',`results/${id}_V58_GEN.json`);
  assert.equal(candidate.value.status,200); assert.ok(candidate.value.body.data.processing.completedStages.includes('BODY_GENERATION'));
  const stage=baselines[id], baseline=await closed(stage,`results/${id}_V31_GEN.json`);
  const oldJob=(await json(`${stage}/jobs.json`)).find(job=>job.id===`${id}_V31_GEN`);
  assert.deepEqual(information(oldJob.approvedEnhancementInput),information(input),'Baseline original information differs');
  const materials=id==='Q01'?[{path:'query-source-pack.json',content:sourcePack}]:input.context.files.map(({path,content})=>({path,content}));
  const arms=[['ORIGINAL',input.rawPrompt],['58',candidate.value.body.data.optimizedPrompt]];
  if(baseline.value.status===200&&baseline.value.body.data.processing?.completedStages.includes('BODY_GENERATION')) arms.push(['31',baseline.value.body.data.optimizedPrompt]);
  else excluded.push({caseId:id,arm:'31',reason:'NO_BODY_GENERATION_AND_RAW_MATERIAL_PRESERVATION_PRODUCT_GATE_FAILURE',source:baseline.ref});
  const sample={...bundleCases.find(value=>value.id===id),executorMaterials:materials,repeatsPerArm:2,
    materialVisibility:id==='Q01'?'EXECUTION_ONLY_ALL_ARMS_IDENTICAL':'ENHANCEMENT_AND_EXECUTION_IDENTICAL',
    onlyPromptDiffers:true,eligibleArms:arms.map(([arm])=>arm)};
  cases.push(sample);
  for(const [version,prompt] of arms) for(const repeat of [1,2]) {
    const code=id.startsWith('C');
    const scope=code?input.rawPrompt.slice(input.rawPrompt.indexOf('\n\n')+2):'';
    const request={model:'deepseek-flash',temperature:0.2,max_tokens:8192,thinking:{type:'disabled'},stream:false,
      messages:[{role:'system',content:code?baseCode+scope:commonSystem},{role:'user',content:prompt},
        {role:'user',content:'本次共同材料（所有作品臂完全相同）：\n'+materials.map(file=>`--- ${file.path} ---\n${file.content}`).join('\n\n')}]};
    if(code) request.response_format={type:'json_object'};
    const job={id:`${id}_${version==='ORIGINAL'?'RAW':'V'+version}_EXEC_R${repeat}`,caseId:id,phase:'EXECUTION',version,repeat,
      promptSha256:sha(prompt),approvedModelRequest:request}; jobs.push(job);
  }
  provenance.push({caseId:id,candidate:candidate.ref,baseline:baseline.ref,informationEqual:true,executorMaterialsSha256:sha(JSON.stringify(materials))});
}
const seed='registry58-works-frozen-before-first-execution';
jobs.sort((a,b)=>sha(seed+'\0'+a.id).localeCompare(sha(seed+'\0'+b.id)));
const blind=[...jobs].sort((a,b)=>sha(seed+'\0blind\0'+a.id).localeCompare(sha(seed+'\0blind\0'+b.id)));
const mapping=blind.map((job,index)=>({workId:`W${String(index+1).padStart(2,'0')}`,jobId:job.id,caseId:job.caseId,arm:job.version,repeat:job.repeat}));
const privateRoot=resolve(repo,'tmp/natural-useful-delivery-2026-10-28/registry58-works-review');
await (await import('node:fs/promises')).mkdir(privateRoot,{recursive:true});
await writeFile(resolve(privateRoot,'coordinator-key.json'),JSON.stringify(mapping,null,2)+'\n',{flag:'wx'});
const bundle={syntheticOnly:true,cases,jobs,phaseLimits:{EXECUTION:jobs.length,GENERATION:0,PLAN:0,REVIEW:0},
  parameters:{executionMaxTokens:8192},qualityRetriesAllowed:false,excludedProductFailures:excluded,executionOrderSeed:seed,
  criterionSha256:sha(await readFile(resolve(here,'criteria.json'))),purpose:'相同材料与限制的实际作品对照，非自动发布'};
await writeFile(resolve(here,'bundle.json'),JSON.stringify(bundle,null,2)+'\n',{flag:'wx'});
await writeFile(resolve(here,'provenance.json'),JSON.stringify({actualModelCalls:0,provenance,excluded,
  anonymousOrdering:mapping.map(({workId,caseId})=>({workId,caseId})),coordinatorKeySha256:sha(JSON.stringify(mapping,null,2)+'\n')},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({prepared:true,jobs:jobs.length,excluded:excluded.map(value=>value.caseId+':'+value.arm),actualModelCalls:0}));
