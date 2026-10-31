/** 确定性检查后的文本匿名对照；双顺序、单次首次评阅，代码退步不靠付费评分改判。 */
import {writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const jobs=[];
for(const caseId of ['D_INVITE','D_PILOT','V_FAQ','V_PROPOSAL','R_REPORT'])
  for(const round of caseId.startsWith('D_')?['1','2']:['1'])
    for(const version of ['original','core-repair-v3'])
      for(const order of ['forward','reverse'])jobs.push({caseId,version,round,order});
let seed=20261032;
for(let i=jobs.length-1;i>0;i--){seed=(Math.imul(seed,1664525)+1013904223)>>>0;const j=seed%(i+1);[jobs[i],jobs[j]]=[jobs[j],jobs[i]];}
await writeFile(fileURLToPath(new URL('./grade-jobs.json',import.meta.url)),JSON.stringify(jobs,null,2)+'\n',{flag:'wx'});
console.log('Frozen anonymous text pairs: '+jobs.length);
