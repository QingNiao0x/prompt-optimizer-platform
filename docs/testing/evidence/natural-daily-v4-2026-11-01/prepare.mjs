/** 复用已获准的需求和合成代码，冻结日常入口验证；只准备文件，不发模型请求。 */
import {readFile, writeFile, mkdir, readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const dir=dirname(fileURLToPath(import.meta.url)),repo=resolve(dir,'../../../..');
const read=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const save=(name,value)=>writeFile(resolve(dir,name),JSON.stringify(value,null,2)+'\n',{flag:'wx'});
for(const folder of ['product','works','traces','reservations'])await mkdir(resolve(dir,folder),{recursive:true});
const prior=await read(resolve(dir,'../natural-plan-context-repair-2026-11-01/closure.json'));
if(prior.status!=='CLOSED'||prior.actualCalls!==32)throw Error('Prior task ledger changed');
const notice=(await read(resolve(dir,'../natural-plan-context-repair-2026-11-01/product-cases.json'))).cases[0];
const date=(await read(resolve(dir,'../natural-engineering-adoption-2026-10-31/cases.json'))).cases[0];
date.rawPrompt+='\n'+date.executionContract;
date.feedback='整理得紧凑一些，保留调查日期规则、新增和导入两个入口、逐行导入行为、allowToday兼容以及原有文件交付限制。';
date.planEnabled=true;
date.materialNature='Only synthetic source. Requirement based on real user feedback; not an independent holdout or real business repository.';
// 构造可辨别的相似/无关模块，检验目标目录召回；不修改原目标模块或预先替模型修复。
for(let module=1;module<=32;module++){
  const functions=Array.from({length:22},(_,n)=>`export function archiveField${n}(row) { return { id: row.id, surveyDate: row.surveyDate, revision: ${n}, label: 'archive-${module}' }; }`).join('\n');
  date.context.files.push({path:`src/archive/module-${String(module).padStart(2,'0')}/records.mjs`,language:'javascript',content:`// Synthetic archived records module ${module}; excluded from the requested people-module change.\n${functions}\n`});
}
notice.planEnabled=false;
await save('cases.json',{source:'Previously approved synthetic DATE and D_NOTICE fixtures; DATE adds synthetic distractors only.',cases:[notice,date]});
const sourcePaths=[
  'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/NaturalEnhancementSettings.java',
  'services/api/src/main/resources/application.yml',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptPlanContextInstructions.java',
  'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalTaskScopeSources.java',
  'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
  'apps/web/src/features/project-index/projectIndexer.ts',
  'apps/web/src/pages/PromptWorkbenchPage.vue',
];
const files=[];
for(const path of [...sourcePaths,...(await readdir(dir)).filter(n=>/\.(mjs|ps1|json)$/.test(n)).map(n=>'docs/testing/evidence/natural-daily-v4-2026-11-01/'+n)]){
  files.push({path,sha256:createHash('sha256').update(await readFile(resolve(repo,path))).digest('hex')});
}
await save('protocol.json',{
  status:'FROZEN',frozenAt:new Date().toISOString(),sourceCommit:'dda5a3cd',totalMaximum:200,previousActual:32,stageMaximum:32,
  productMaximum:24,worksMaximum:8,qualityRetries:0,endpoint:'https://api.deepseek.com/chat/completions',
  model:'deepseek-flash',temperature:0.2,max_tokens:10000,thinking:'disabled',frontend:'http://localhost:5173',backend:'http://localhost:9000',
  files,authorization:'User approved daily v4 activation, ordinary workbench checks and same-information works; continuation of 200-call authorization. Synthetic materials only; prior closed stage not reopened.',
  sequence:['Daily restart and readiness','D_NOTICE direct generation, revision, copy, history','DATE project selection, retrieval, real Plan, answer, generation, revision, copy, history','Review delivered prompts','Two original/candidate works per task','Close ledger; preserve all failures'],
  acceptance:['Real response version v4 and BODY_GENERATION','DATE target files selected despite similar archived modules','No source bundle in copied prompt','Bound Plan answer retained and not asked again','Copy equals display and history; previous record unchanged','Code handles past/today/future and both allowToday values with shared validation, valid date/empty/error cases and per-row import results','Only actually modified allowed files; no dependencies or tests added','Notice facts retained and final 80-character feedback given equally to both arms'],
  nonClaims:['No universal quality or independent holdout claim','Synthetic engineering fixture is not the real business repository','Model first responses and all failures retained; no retry-to-win','No new prose strategy, model, framework or retrieval rewrite in this stage'],
  review:'Deterministic checks and internal developer review; independent human review not performed.',
});
console.log(JSON.stringify({frozen:true,stageMaximum:32,taskRemaining:168,projectFiles:date.context.files.length,projectCharacters:date.context.files.reduce((n,f)=>n+f.content.length,0)}));
