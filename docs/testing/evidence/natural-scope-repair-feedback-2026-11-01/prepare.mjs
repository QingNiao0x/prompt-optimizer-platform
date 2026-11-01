/** 固定错误原件后，只改变服务端诊断反馈；初次请求、正文策略、模型和材料保持原样。 */
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const dir=dirname(fileURLToPath(import.meta.url)),repo=resolve(dir,'../../../..'),prior=resolve(dir,'../natural-daily-v4-2026-11-01');
const save=(name,x)=>writeFile(resolve(dir,name),JSON.stringify(x,null,2)+'\n',{flag:'wx'});
for(const name of ['reservations','traces','product','works','diagnostic'])await mkdir(resolve(dir,name),{recursive:true});
const old=JSON.parse(await readFile(resolve(prior,'cases.json'),'utf8'));
await save('cases.json',{cases:old.cases.filter(x=>x.id==='D_NOTICE')});
for(const name of ['common.mjs','browser.mjs','browser.ps1','works.mjs']){
  const text=(await readFile(resolve(prior,name),'utf8')).replaceAll('natural-daily-v4-2026-11-01','natural-scope-repair-feedback-2026-11-01');
  await writeFile(resolve(dir,name),text,{flag:'wx'});
}
await writeFile(resolve(dir,'diagnostic/case.json'),await readFile(resolve(prior,'diagnostic/case.json')),{flag:'wx'});
await writeFile(resolve(dir,'ScopeRepairEvaluation.java'),(await readFile(resolve(prior,'DailyDiagnosticEvaluation.java'),'utf8')).replaceAll('DailyDiagnosticEvaluation','ScopeRepairEvaluation'),{flag:'wx'});
const names=['prepare.mjs','controlled-repair.mjs','controlled-repair.ps1','common.mjs','browser.mjs','browser.ps1','works.mjs','cases.json','diagnostic/case.json','ScopeRepairEvaluation.java'];
const paths=names.map(x=>'docs/testing/evidence/natural-scope-repair-feedback-2026-11-01/'+x);
paths.push('docs/testing/evidence/natural-daily-v4-2026-11-01/diagnostic/call-1.json');
for(const p of ['provider/domain/NaturalTaskScopeSources.java','provider/domain/ProviderResponseValidationException.java','provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java','provider/domain/NaturalPromptPlanContextInstructions.java','provider/domain/NaturalPromptRevision.java','enhancement/service/impl/NaturalEnhancementSettings.java','common/logging/ModelCallObservation.java'])paths.push('services/api/src/main/java/com/promptoptimizer/'+p);
const files=[];for(const path of paths)files.push({path,sha256:createHash('sha256').update(await readFile(resolve(repo,path))).digest('hex')});
await save('protocol.json',{status:'FROZEN',frozenAt:new Date().toISOString(),previousActual:48,totalMaximum:200,stageMaximum:20,productMaximum:16,worksMaximum:4,qualityRetries:0,
  endpoint:'https://api.deepseek.com/chat/completions',model:'deepseek-flash',temperature:0.2,max_tokens:10000,thinking:'disabled',frontend:'http://localhost:5173',backend:'http://localhost:9000',files,
  hypothesis:'Precisely identifying the invalid assistant source helps the existing single protocol repair; no validation weakening or new generation strategy.',
  sequence:['Replay identical saved first failure at zero model cost; live corrected preparation and body','Restart daily after offline and controlled checks pass','D_NOTICE actual generation/revision/copy/history','Same-information notice works only if delivered prompt passes review'],
  limits:'Development regression only; no unseen-sample or universal quality claim. Original uncaptured browser failure remains unproven exact cause; captured diagnosis is direct evidence.'});
console.log('FROZEN stage20 within task200; previous48');
