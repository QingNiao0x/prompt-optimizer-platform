import{readFile,writeFile}from'node:fs/promises';import{resolve}from'node:path';import{createHash}from'node:crypto';
import{checkResearchLength,BODY_HAN_POLICY}from'../../../scripts/me00a-work-checks.mjs';
const root=resolve('tmp/me00a-20261012'),output=resolve(root,'candidate15-real');
const hash=b=>createHash('sha256').update(b).digest('hex'),load=async p=>JSON.parse(await readFile(p,'utf8'));
const manifest=await load('docs/testing/evidence/me00a-candidate15-2026-10-15/candidate15-source.json');
const names=['doubao','deepseek'].flatMap(v=>['raw','direct','plan','current'].map(a=>v+'-'+a));
const mapping=Object.fromEntries(names.sort((a,b)=>hash(manifest.frozenAt+a).localeCompare(hash(manifest.frozenAt+b))).map((name,i)=>['ABCDEFGH'[i],name]));
const inverse=Object.fromEntries(Object.entries(mapping).map(([id,name])=>[name,id])),works={},lengths={};
for(const name of names){const[vendor,arm]=name.split('-'),folder=resolve(root,arm==='current'?'candidate15-real':'candidate14-real','works'),originalName=vendor+'-'+(arm==='current'?'plan':arm);const text=await readFile(resolve(folder,originalName+'.md'),'utf8'),meta=await load(resolve(folder,originalName+'.json'));if(hash(text)!==meta.workSha256||!meta.literalOutgoingPayloadMatches?.some(i=>i.exact))throw new Error('Literal work evidence drift');works[name]=text;lengths[name]=checkResearchLength(text);}
const inputs={};for(const arm of ['raw','direct','plan','current'])inputs[arm]=await readFile(resolve(root,arm==='current'?'candidate15-real':'candidate14-real',arm==='current'?'plan':arm,'execution-input.md'),'utf8');
const inputMapping={P1:'raw',P2:'direct',P3:'plan',P4:'current'},inputInverse=Object.fromEntries(Object.entries(inputMapping).map(([id,arm])=>[arm,id]));
const pairs=['doubao','deepseek'].flatMap(v=>['raw','direct','plan'].map(a=>({left:inverse[v+'-'+a],right:inverse[v+'-current']})));
const pack='你是独立AI辅助评审，不是人工专家。下面八份研究设计方法部分作品来自相同原题与同一实际确认信息，不同输入版本。六份为先前固定对照，两份为最新平台组装的诊断样本；原始增强模型响应复用，仅平台组装改变，没有新一轮增强推理。只做单份故障筛查，不推断稳定胜率或模型能力排行。供应商、模式与版本标签匿名，不提供另一位评审的意见。\n\n'
 +'原始任务与实际逐字题答：\n'+inputs.raw+'\n\n'
 +'任务语义：Plan由增强器提问、用户回答，再融合有效答案，不是自行生成执行计划。允许有依据的拟议推荐，实际参数仍未确认；推荐类别、尺度或分歧规则不等于编造已观测数据或用户已确认。原题没有要求所有可能层级随机化，不普遍禁止2×2或任务类别作为组间因素；要核对实际分配、每模式每任务3次、全部产物的双独立盲评及公平对照是否自洽。评分对象为提示词，必要时可辅以下游作品。\n\n'
 +'实际执行输入（逐字保存，没有替平台补答案或尺度）：\n'+Object.entries(inputMapping).map(([id,arm])=>'输入'+id+'：\n'+inputs[arm]).join('\n\n')+'\n\n'
 +Object.entries(mapping).map(([id,name])=>'作品'+id+'，执行输入'+inputInverse[name.split('-')[1]]+'：\n'+works[name]).join('\n\n')+'\n\n'
 +'独立篇幅判据固定为'+BODY_HAN_POLICY.id+'：Unicode汉字800—1200，排除Markdown标题、行首加粗小标题和明确附表；含标题替代计数保留，不切换能通过的口径，自报字数不证明合规。结果：\n'+JSON.stringify(Object.fromEntries(Object.entries(mapping).map(([id,name])=>[id,lengths[name]])))+'\n\n'
 +'逐份评价准确性、完整性、清晰度和可执行性，各1—5分；核对任务类别/来源主方案、评分尺度/操作定义/分歧规则、随机化与完整重复结构、双评覆盖、确认信息获取/封存/同一份分发及提问回答角色。区分明确错误和未充分说明，引用本份作品或其对应输入的完整原句，不能用别份作品或任意输入作证。严重错误和硬门禁独立阻断，不被平均分抵消；双方均失败必须BOTH_FAIL。原因可为输入、执行、混合或未定。\n\n'
 +'仅输出严格JSON，无代码围栏或额外文字：{"reviewerType":"AI_ASSISTED","scores":{"A":{"accuracy":1,"completeness":1,"clarity":1,"executability":1}},"issues":[{"work":"A","severity":"SEVERE|MAJOR|MINOR","evidence":"完整原句","reason":"依据","origin":"INPUT|WORK|MIXED|UNDETERMINED"}],"pairs":[{"left":"A","right":"B","verdict":"LEFT_WIN|RIGHT_WIN|TIE|BOTH_FAIL","reason":"依据"}]}。scores必须有A,B,C,D,E,F,G,H八项。verdict仅用LEFT_WIN/RIGHT_WIN/TIE/BOTH_FAIL，方向按left/right，不输出作品编号_WIN。pairs必须且仅有以下六对：'+JSON.stringify(pairs)+'。';
const save=(name,value)=>writeFile(resolve(output,name),typeof value==='string'?value:JSON.stringify(value,null,2),{flag:'wx'});
await save('review-mapping.json',{mapping,inputMapping,pairs,oldControlsVersion:'candidate14',currentAssemblyVersion:'candidate15',noFreshEnhancementInference:true});
await save('independent-length-check.json',{policy:BODY_HAN_POLICY,works:lengths,strictJson:'NOT_APPLICABLE',examples:'DISABLED_NOT_APPLICABLE'});
await save('review-pack.md',pack);await save('review-lock.json',{frozenAt:new Date().toISOString(),inputSha256:hash(pack),mappingSha256:hash(JSON.stringify({mapping,inputMapping,pairs})),samePack:true,newChats:true,distinctVendors:true,noOtherReviewerOpinion:true,interpretation:'Single-work source-fixed assembly diagnosis with historical controls; stochasticity, website routing and chronology remain confounds'});
console.log(JSON.stringify({works:8,freshWorks:2,reusedWorks:6,packChars:pack.length,inputSha256:hash(pack),lengths}));
