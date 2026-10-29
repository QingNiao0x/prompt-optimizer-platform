import {readFile,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..');
const old=JSON.parse(await readFile(resolve(evidence,'registry58-development-preparation/bundle.json'),'utf8'));
const ids=['Q01','U02'];
const bundle={syntheticOnly:true,cases:old.cases.filter(value=>ids.includes(value.id)),
  jobs:ids.map(id=>({...old.jobs.find(value=>value.id===`${id}_V58_GEN`),id:`${id}_V59_GEN`,version:'59',purpose:'BODY_FIRST_RECORD_SEPARATION_MECHANISM'})),
  phaseLimits:{GENERATION:8,PLAN:0,EXECUTION:0,REVIEW:0},qualityRetriesAllowed:false,
  hypothesis:'正文写作不填写框架/来源/覆盖表，后续只建立记录且不能改写首稿；是否改善内容由首次正文和同信息作品判断。',
  stopCondition:'若只增加成本而无有用内容变化，不扩充题集或消耗封存新题；默认仍31。'};
await writeFile(resolve(here,'bundle.json'),JSON.stringify(bundle,null,2)+'\n',{flag:'wx'}); console.log(JSON.stringify({prepared:true,jobs:2,modelCalls:0}));
