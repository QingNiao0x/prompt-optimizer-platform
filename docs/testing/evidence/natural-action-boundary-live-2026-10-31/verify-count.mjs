// 固定业务规则来自原始材料；仅在人工检查生成源码后运行，不以作品自报测试结论计通过。
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { resolve, relative } from 'node:path';
const root=resolve('tmp/natural-action-boundary-live-2026-10-31');
const file=resolve(process.argv[2]);
assert.ok(!relative(root,file).startsWith('..')&&!relative(root,file).includes(':'));
const {countReady}=await import(pathToFileURL(file).href);
const row={status:'DONE',rounds:2,checks:4,reviewed:true,archived:false};
const cases=[
 ['empty',[],0],['threshold',[row],1],['two',[row,{...row,rounds:3,checks:5,archived:true}],2],
 ['too few rounds',[{...row,rounds:1}],0],['too few checks',[{...row,checks:3}],0],
 ['not done',[{...row,status:'OPEN'}],0],['unreviewed',[{...row,reviewed:false}],0],
 ['archived irrelevant',[{...row,archived:true}],1],['bigger threshold',[{...row,rounds:4,checks:6}],1],
 ['conjunction',[row,{...row,rounds:1},{...row,checks:3},{...row,reviewed:false}],1],
 ['no mutation',[row],1]
];
const results=[];for(const [id,rows,expected] of cases){const original=JSON.stringify(rows);try{assert.equal(countReady(rows),expected);assert.equal(JSON.stringify(rows),original);results.push({id,passed:true});}catch{results.push({id,passed:false});}}
console.log(JSON.stringify({passed:results.filter(x=>x.passed).length,total:results.length,results}));
if(results.some(x=>!x.passed))process.exitCode=1;
