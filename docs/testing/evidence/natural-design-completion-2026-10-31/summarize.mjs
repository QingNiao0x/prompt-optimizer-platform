/** 只汇总实际原件；交换顺序评阅合成一组，不把同模型双顺序当两名独立专家。 */
import {readFile,writeFile,readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const dir=dirname(fileURLToPath(import.meta.url)),json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const preview=process.argv[2]==='preview';
const calls=[];for(const name of (await readdir(resolve(dir,'traces'))).filter(n=>/^\d{4}\.json$/.test(n)).sort()){const t=await json(resolve(dir,'traces',name));calls.push({trace:name,phase:t.phase,version:t.version,job:t.job,httpStatus:t.httpStatus,totalTokens:t.response?.usage?.total_tokens??null,elapsedMs:t.elapsedMs??null});}
const grades=[];
for(const name of (await readdir(resolve(dir,'results'))).filter(n=>n.includes('_grade_')).sort()){
  const r=await json(resolve(dir,'results',name));const text=r.body?.choices?.[0]?.message?.content??'';
  let parsed;try{parsed=JSON.parse(text.replace(/^```json\s*\n|\n```\s*$/g,''));}catch{}
  const opponent=name.includes('_original_')?'original':'core-repair-v3';
  const suffix='_'+opponent+'_grade_',id=name.slice(0,name.indexOf(suffix));
  const round=name.slice(name.indexOf(suffix)+suffix.length).split('_')[0];
  const verdict=parsed?.verdict;
  const winner=verdict==='A'||verdict==='B'?r.blinding[verdict]=== 'core-design-completion-v2'?'CANDIDATE':'OPPONENT':verdict??'INVALID_GRADER_JSON';
  grades.push({file:name,id,opponent,round,winner,substantiveGain:parsed?.substantiveGain,reason:parsed?.reason,issues:parsed?{A:parsed.aIssues,B:parsed.bIssues}:null,evidence:parsed?.evidence});
}
const groups=new Map();for(const g of grades){const key=[g.id,g.opponent,g.round].join('/');if(!groups.has(key))groups.set(key,[]);groups.get(key).push(g);}
const pairs=[...groups].map(([pair,items])=>({pair,complete:items.length===2,agreement:items.length===2&&items[0].winner===items[1].winner,verdict:items.length===2&&items[0].winner===items[1].winner?items[0].winner:'DISAGREEMENT',votes:items.map(x=>({file:x.file,winner:x.winner,reason:x.reason,substantiveGain:x.substantiveGain}))}));
const phases={};for(const c of calls){if(!phases[c.phase])phases[c.phase]={calls:0,tokens:0,elapsedMs:0};phases[c.phase].calls++;phases[c.phase].tokens+=c.totalTokens??0;phases[c.phase].elapsedMs+=c.elapsedMs??0;}
const result={generatedAt:new Date().toISOString(),authorizationMaximum:500,actualCalls:calls.length,unused:500-calls.length,phases,grades,pairs,
  graderLimits:'同一DeepSeek模型的独立请求，交换顺序；实现者再核对，不是独立专家或真人评阅。主观胜负不能覆盖代码安全/原要求违约。',
  deterministicFindings:{CSV:'候选两份均未处理外部公式风险；v3两份通过5/5；原始一份无有效JSON、一份5/5。',QUEUE:'原检查三臂均5/5；补核原要求发现原始第一份吞掉失败，其他五份保留失败。候选相对v3未显示收益。',REPORT:'候选把事实称为已证实原因的指导传播到作品；v3明确已证实原因为无，保留现象与待验证项。'},
  decision:'NOT_ADOPTED',reason:'候选没有满足无新增严重退步要求。保留失败，停止本假设，不用写作评分抵消代码安全与报告保真问题。'};
if(!preview)await writeFile(resolve(dir,'summary.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({calls:result.actualCalls,phases,pairs}));
if(!preview){
  const samples=(await json(resolve(dir,'cases.json'))).cases.concat((await json(resolve(dir,'validation-cases.json'))).cases);
  let pack='# 匿名作品评阅包\n\n全部为合成材料。只按用户要求、事实、可直接使用程度和必要修改判断。版本映射另存，不把模型意见当人工结论。此包按事前固定的第1次作品展示；第2次原件仍完整保留在results。\n';const mapping={};
  for(const id of ['D_INVITE','D_PILOT','V_FAQ','V_PROPOSAL','R_REPORT']){
    const sample=samples.find(x=>x.id===id);pack+='\n## '+id+'\n\n原始需求：\n\n'+sample.rawPrompt+'\n\n共同材料：\n\n'+sample.context.files.map(f=>f.content).join('\n\n')+'\n';
    const arms=['original','core-repair-v3','core-design-completion-v2'].sort((a,b)=>createHash('sha256').update(id+a).digest('hex').localeCompare(createHash('sha256').update(id+b).digest('hex')));
    for(let i=0;i<arms.length;i++){const label=String.fromCharCode(65+i),r=await json(resolve(dir,'results',`${id}_${arms[i]}_execute_1.json`));pack+='\n### 作品'+label+'\n\n'+r.body.choices[0].message.content+'\n';mapping[id+'-'+label]=arms[i];}
  }
  await writeFile(resolve(dir,'匿名作品评阅包.md'),pack,{flag:'wx'});await writeFile(resolve(dir,'anonymous-mapping.json'),JSON.stringify(mapping,null,2)+'\n',{flag:'wx'});
}
