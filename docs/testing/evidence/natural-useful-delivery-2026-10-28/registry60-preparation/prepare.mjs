import {readFile,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..');
const json=async path=>JSON.parse(await readFile(resolve(evidence,path),'utf8'));
const development=await json('registry58-development-preparation/bundle.json');
const boundary=await json('registry58-boundary-preparation/bundle.json');
const ids=['U02','Q01','V04','V05','C01','C02'];
const cases=[],jobs=[];
for(const id of ids){const source=['V04','V05'].includes(id)?boundary:development;
  cases.push(source.cases.find(value=>value.id===id));
  const original=source.jobs.find(value=>value.id===`${id}_V58_GEN`);
  jobs.push({...original,id:`${id}_V60_GEN`,version:'60',purpose:'DESIGN_OWNERSHIP_CONFLICT_REPAIR'});
}
await writeFile(resolve(here,'bundle.json'),JSON.stringify({syntheticOnly:true,cases,jobs,phaseLimits:{GENERATION:12,PLAN:0,EXECUTION:0,REVIEW:0},
  hypothesis:'相对58只将提前给拟议设计改为整理下游设计任务，保持做法可选，不新增禁令。复用58单次正文而不携带59的额外登记成本。',
  stopCondition:'先两道开发题判断是否减少无依据定案及无意义展开；无收益不扩矩阵。原范围与已有硬限制仍保留。',qualityRetriesAllowed:false},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({prepared:true,jobs:jobs.length,actualCalls:0}));
