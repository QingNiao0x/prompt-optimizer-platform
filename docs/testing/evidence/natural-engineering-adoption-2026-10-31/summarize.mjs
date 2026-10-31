/** 不重写冻结判定；补充人工发现的交付契约缺口及逐字作品归因，失败原件保留。 */
import {readFile,writeFile,readdir} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const root=dirname(fileURLToPath(import.meta.url)),json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const cases=(await json(resolve(root,'cases.json'))).cases,hash=x=>createHash('sha256').update(x).digest('hex');
const checks=(await json(resolve(root,'works-checks.json'))).works;
const works=[];
for(const name of (await readdir(resolve(root,'results'))).filter(n=>n.includes('_execute_')).sort()){
  const response=await json(resolve(root,'results',name)),text=response.body.choices[0].message.content;
  const sample=cases.find(c=>name.startsWith(c.id+'_'));
  // 移除围栏仅用来定位业务源码是否相同，严格格式失败不改判。
  const fenced=text.startsWith('```json\n')&&text.endsWith('\n```');
  const files=JSON.parse(fenced?text.slice(8,-4):text);
  const unchanged=Object.entries(files).filter(([p,s])=>sample.context.files.some(f=>f.path===p&&f.content===s)).map(([p])=>p);
  const frozen=checks.find(x=>x.file===name);
  works.push({file:name,rawJsonAccepted:!fenced,unchangedFilesReturned:unchanged,originalFrozenCheck:{passed:frozen.passed,total:frozen.total},
    fullyCompliesWithDelivery:!fenced&&unchanged.length===0&&frozen.passed===frozen.total,
    neededEdits:[...(fenced?['去掉JSON外的Markdown围栏']:[]),...(unchanged.length?['移除未修改文件条目']:[])],
    sourceHashes:Object.fromEntries(Object.entries(files).map(([p,s])=>[p,hash(s)]))});
}
for(const work of works){
  work.sameSourceAs=works.filter(x=>x.file!==work.file&&JSON.stringify(x.sourceHashes)===JSON.stringify(work.sourceHashes)).map(x=>x.file);
  work.behaviorInterpretation=work.originalFrozenCheck.passed===work.originalFrozenCheck.total?'所有冻结行为检查通过':
    work.sameSourceAs.some(name=>{const x=works.find(w=>w.file===name);return x.originalFrozenCheck.passed===x.originalFrozenCheck.total;})?'去围栏后源码与已通过行为检查作品逐字相同；原始交付格式仍失败':'行为未验';
}
const calls=[];for(const file of (await readdir(resolve(root,'traces'))).filter(n=>/^\d{3}\.json$/.test(n))){const t=await json(resolve(root,'traces',file));calls.push({file,job:t.job,version:t.version,operation:t.operation,status:t.httpStatus,tokens:t.response?.usage?.total_tokens??0,elapsedMs:t.elapsedMs??0});}
const costs={};for(const v of ['core-repair-v3','core-repair-single-pass']){const items=calls.filter(x=>x.operation==='enhance'&&x.version===v);costs[v]={calls:items.length,tokens:items.reduce((s,x)=>s+x.tokens,0),outboundElapsedMs:items.reduce((s,x)=>s+x.elapsedMs,0)};}
await writeFile(resolve(root,'supplemental-review.json'),JSON.stringify({reviewedAt:new Date().toISOString(),reviewer:'Internal AI + deterministic source comparison, no human editing-time claim',
  checkerGap:'冻结检查器核对允许路径却漏核对只返回确有修改文件；按事前已有交付契约补录此问题，不改写原检查结果。',works,costs,
  repair:{trace:'008.json',kind:'additionCoverage引用不在正文中',subsequentTrace:'009.json',delivered:true,qualityRetry:false},
  adoption:'不采用单阶段。两题作品无业务行为提升，仍有共同交付违约；两题总tokens接近，耗时较高，日期题一次协议修复抵消省去scope的调用收益。停止本假设采样并撤回生产实验分支。'},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({calls:calls.length,costs,works:works.map(w=>({file:w.file,complies:w.fullyCompliesWithDelivery,edits:w.neededEdits}))}));
