import {readFile,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..');
const json=async path=>JSON.parse(await readFile(resolve(evidence,path),'utf8'));
const initial=await json('registry60-preparation/bundle.json'), development=await json('registry58-development-preparation/bundle.json'), boundary=await json('registry58-boundary-preparation/bundle.json');
const cases=[...initial.cases,development.cases.find(value=>value.id==='V03'),boundary.cases.find(value=>value.id==='V01')];
const jobs=initial.jobs.map(job=>({...job,id:`${job.caseId}_V61_GEN`,version:'61',purpose:'CONTENT_FIRST_BODY_WITH_INDEPENDENT_RECORD'}));
for(const [source,id] of [[development,'V03'],[boundary,'V01']]) {
  const job=source.jobs.find(value=>value.id===`${id}_V58_GEN`); jobs.push({...job,id:`${id}_V61_GEN`,version:'61'});
}
await writeFile(resolve(here,'bundle.json'),JSON.stringify({syntheticOnly:true,cases,jobs,phaseLimits:{GENERATION:32,PLAN:0,EXECUTION:0,REVIEW:0},
  hypothesis:'更短且内容优先的正文指导，完整记录在后段建立，是否令执行要求更实质且少无依据定案。相比59只改写正文指导，记录/输入/推理不变。',
  stopCondition:'先两题观察；任何严重意图/事实/边界错误停止扩测。无实质变化不靠增加采样凑通过。所有正常变体通过后才使用封存新题和实际作品。',
  defaultVersion:'31',qualityRetriesAllowed:false},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({prepared:true,jobs:jobs.length,actualCalls:0}));
