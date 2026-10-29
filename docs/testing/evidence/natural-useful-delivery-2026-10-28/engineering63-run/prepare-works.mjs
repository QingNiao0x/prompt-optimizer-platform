import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),repo=resolve(evidence,'../../../..');
const json=async p=>JSON.parse((await readFile(resolve(evidence,p),'utf8')).replace(/^\uFEFF/,''));
const sha=x=>createHash('sha256').update(x).digest('hex');
async function closed(stage,name){const file=resolve(evidence,stage,name),bytes=await readFile(file),c=await json(`${stage}/closure.json`);assert.equal(c.status,'CLOSED');assert.equal(c.files.find(x=>resolve(repo,x.path)===file)?.sha256,sha(bytes));return{value:JSON.parse(bytes),path:`${stage}/${name}`,sha256:sha(bytes)};}
const old=await json('engineering-run/works-bundle.json'),candidate=await closed('engineering63','results/E01_V63_GEN.json');
const actual=await json('engineering63-run/live/engineering63/E01_V63_GEN/verdict.json');
assert.ok(actual.bodyGenerationCompleted&&actual.displayEqualsResponse&&actual.copyEqualsResponseAfterClipboardNewlineNormalization&&actual.actualHistoryBodyVersionProcessingEqual);
assert.equal(candidate.value.status,200);assert.equal(candidate.value.body.data.chainVersion,'63');
const input=(await json('engineering63/jobs.json'))[0].approvedEnhancementInput;
const oldInputs=await json('engineering47/jobs.json');assert.deepEqual(oldInputs.find(x=>x.version==='31').approvedEnhancementInput,input);
const original=old.jobs.find(x=>x.version==='ORIGINAL');
const raw=old.cases[0].originalSealedPrompt;
assert.ok(original.approvedModelRequest.messages[1].content.startsWith(raw));
const suffix=original.approvedModelRequest.messages[1].content.slice(raw.length);
const materials=(await json('engineering-run/execution-project.json')).files;
assert.equal(materials.length,30);for(const m of materials)assert.ok(suffix.includes(m.content));
const jobs=[],reuse=[];
for(const arm of ['ORIGINAL','31']){
  const prior=old.jobs.find(x=>x.version===arm),result=await closed('engineering-works47',`results/${prior.id}.json`);
  assert.equal(result.value.status,200);assert.equal(result.value.body.data.finishReason,'stop');
  reuse.push({arm,repeat:1,jobId:prior.id,path:result.path,sha256:result.sha256});
  jobs.push({...structuredClone(prior),id:`E01_${arm==='ORIGINAL'?'RAW':'V31'}_EXEC_R2`,repeat:2});
}
for(const repeat of [1,2]){
  const request=structuredClone(original.approvedModelRequest);request.messages[1].content=candidate.value.body.data.optimizedPrompt+suffix;
  jobs.push({id:`E01_V63_EXEC_R${repeat}`,caseId:'E01',version:'63',phase:'EXECUTION',repeat,approvedModelRequest:request});
}
jobs.sort((a,b)=>sha('engineering63-works'+a.id).localeCompare(sha('engineering63-works'+b.id)));
const criteria={frozenAt:new Date().toISOString(),source:'Original sealed E01 requirement and unchanged28-check Vue/TS isolated VM checker',
  functional:'Run all28 original checks; do not alter old outcomes or run model code on host.',
  fileScope:'Only originally permitted9 paths; no added/deleted files or dependency changes.',
  actualChangedFiles:'Original user says files only lists genuinely changed files. Separately compare each returned content with its original material, normalize CRLF only. Identical entries require removal; keep this supplementary audit separate from original28-check verdicts.',
  fairness:'Same full30 files, system, model, .2 temperature,10000 tokens, disabled thinking. All arms have two declared observations: RAW/31 first closed originals reused, second new;63 two new first works. No quality retry.',
  limits:'Known engineering regression, not unseen. Functional ties are allowed; necessary edit reduction requires concrete repeated evidence, not more comments or longer code.'};
await writeFile(resolve(here,'work-criteria.json'),JSON.stringify(criteria,null,2)+'\n',{flag:'wx'});
await writeFile(resolve(here,'works-bundle.json'),JSON.stringify({syntheticOnly:true,cases:[{...old.cases[0],executorMaterials:materials,repeatsPerArm:2,knownRegressionNotUnseen:true}],jobs,
  phaseLimits:{EXECUTION:4,GENERATION:0,PLAN:0,REVIEW:0},parameters:{executionMaxTokens:10000},qualityRetriesAllowed:false,baselineReuse:reuse,
  candidateSource:{path:candidate.path,sha256:candidate.sha256},criteriaSha256:sha(JSON.stringify(criteria,null,2)+'\n')},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({prepared:true,newWorks:4,reusedWorks:2,projectFiles:30,actualCalls:0}));
