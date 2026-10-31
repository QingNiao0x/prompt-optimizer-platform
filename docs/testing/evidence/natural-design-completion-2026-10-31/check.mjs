/** 同信息作品检查：严格交付与业务行为分别记录，支持合理设计变体，不事后覆盖原判据。 */
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile,writeFile,readdir} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const dir=dirname(fileURLToPath(import.meta.url)),read=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const cases=(await read(resolve(dir,'cases.json'))).cases;
if(process.argv[2]==='baseline'){
  const results=[];for(const sample of cases.filter(c=>['D_CSV','D_QUEUE','R_CONTEXT'].includes(c.id))){const f=sample.context.files[0];results.push({caseId:sample.id,...await check(sample,JSON.stringify({[f.path]:f.content}),true)});}
  assert.ok(results.every(r=>r.behavior.some(c=>!c.pass)),'Original bugs must be detected');console.log(JSON.stringify(results));process.exit(0);
}
const output=[];for(const name of (await readdir(resolve(dir,'results'))).filter(n=>n.includes('_execute_')).sort()){
  const result=await read(resolve(dir,'results',name));const id=name.slice(0,name.indexOf('_core-')>=0?name.indexOf('_core-'):name.indexOf('_original'));
  let sample=cases.find(c=>c.id===id);if(!sample){const extra=await read(resolve(dir,'validation-cases.json'));sample=extra.cases.find(c=>c.id===id);}
  try{assert.equal(result.status,200);output.push({file:name,caseId:id,...await check(sample,result.body.choices[0].message.content)});}
  catch(e){output.push({file:name,caseId:id,delivery:[{name:'有效作品',pass:false,error:e.message}],behavior:[]});}
}
await writeFile(resolve(dir,'checks-'+process.argv[2]+'.json'),JSON.stringify(output,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(output.map(r=>({file:r.file,delivery:r.delivery.every(x=>x.pass),behavior:r.behavior.filter(x=>x.pass).length,behaviorTotal:r.behavior.length}))));

/** 仅执行已人工检查的合成纯函数，无文件/网络/进程接口，拒绝任何导入；不是通用不可信源码沙箱。 */
async function check(sample,text,baseline=false){
  const delivery=[],behavior=[];
  const test=(list,name,fn)=>{try{fn();list.push({name,pass:true});}catch(e){list.push({name,pass:false,error:e.message});}};
  const asyncTest=async(name,fn)=>{try{await fn();behavior.push({name,pass:true});}catch(e){behavior.push({name,pass:false,error:e.message});}};
  if(!['D_CSV','D_QUEUE','R_CONTEXT'].includes(sample.id)){
    test(delivery,'有实际内容',()=>assert.ok(text.trim()));
    if(sample.id==='D_INVITE')test(delivery,'明确篇幅上限',()=>assert.ok(text.length<=420,'含标题与标点的宽容上限420字符，超过需人工按正文字数复核'));
    if(sample.id==='D_PILOT')test(delivery,'明确篇幅上限',()=>assert.ok(text.length<=720,'含标点的宽容上限720字符，超过需复核'));
    return {delivery,behavior,semanticReviewRequired:true};
  }
  let files;
  test(delivery,'原始响应是有效JSON',()=>{files=JSON.parse(text);});
  if(!files){const match=text.match(/^```(?:json)?\s*\n([\s\S]*)\n```\s*$/);if(match)try{files=JSON.parse(match[1]);}catch{};}
  if(!files)return {delivery,behavior};
  const original=sample.context.files[0];
  test(delivery,'只含允许的完整源码文件',()=>{assert.ok(files&&!Array.isArray(files));assert.deepEqual(Object.keys(files),[original.path]);assert.equal(typeof files[original.path],'string');});
  test(delivery,'只返回实际修改文件',()=>{if(!baseline)assert.notEqual(files[original.path],original.content);});
  if(!delivery.find(x=>x.name==='只含允许的完整源码文件').pass)return {delivery,behavior};
  const context=vm.createContext({setTimeout,clearTimeout},{codeGeneration:{strings:false,wasm:false}});
  try{
    const module=new vm.SourceTextModule(files[original.path],{context,importModuleDynamically:()=>{throw Error('Imports not allowed');}});
    await module.link(()=>{throw Error('Imports not allowed');});await module.evaluate({timeout:1000});
    const name=sample.id==='D_CSV'?'exportCsv':sample.id==='D_QUEUE'?'runTasks':'sortNumbers';context.subject=module.namespace[name];
    const call=(args)=>{context.args=structuredClone(args);return vm.runInContext('subject(...args)',context,{timeout:1000});};
    const same=(a,b)=>assert.equal(JSON.stringify(a),JSON.stringify(b));
    if(sample.id==='D_CSV'){
      const rows=[{name:'甲',note:'a,b',count:0},{name:'乙',note:'说"好"\n第二行',count:null},{name:'=1+1',note:'普通内容',count:3}];
      const csv=call([rows]);
      test(behavior,'返回字符串',()=>assert.equal(typeof csv,'string'));
      const table=parseCsv(String(csv));
      test(behavior,'所有行与字段可读取',()=>{assert.equal(table.length,4);const headers=table[0];assert.deepEqual([...headers].sort(),['count','name','note']);for(let i=0;i<rows.length;i++)for(const key of headers){let value=table[i+1][headers.indexOf(key)];if(key==='name'&&i===2)value=value.replace(/^[\t']/,'');assert.equal(value,rows[i][key]==null?'':String(rows[i][key]));}});
      test(behavior,'不修改输入',()=>same(context.args[0],rows));
      test(behavior,'空输入可结束并返回字符串',()=>assert.equal(typeof call([[]]),'string'));
      test(behavior,'外部公式值避免直接执行风险',()=>{const index=table[0]?.indexOf('name');assert.ok(index>=0);assert.ok(!/^[=+@-]/.test(table[3]?.[index]??''));});
    }else if(sample.id==='D_QUEUE'){
      const run=async code=>{vm.runInContext(code,context,{timeout:1000});let timer;try{return await Promise.race([context.result,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Promise did not settle')),1500);})]);}finally{clearTimeout(timer);}};
      await asyncTest('并发上限且所有任务执行一次',async()=>{await run('globalThis.active=0;globalThis.peak=0;globalThis.done=[];globalThis.tasks=Array.from({length:5},(_,i)=>()=>new Promise(resolve=>{active++;peak=Math.max(peak,active);setTimeout(()=>{active--;done.push(i);resolve(i)},5)}));globalThis.result=subject(tasks,2);');assert.ok(context.peak<=2);same([...context.done].sort(),[0,1,2,3,4]);});
      await asyncTest('空任务队列结束',async()=>{const value=await run('globalThis.result=subject([],2);');assert.ok(value!==undefined);});
      await asyncTest('并发为1仍完成',async()=>{const value=await run('globalThis.result=subject([async()=>1,async()=>2],1);');assert.ok(value!==undefined);});
      await asyncTest('任务失败不会无限等待',async()=>{try{await run('globalThis.result=subject([async()=>{throw Error("fixture")},async()=>2],2);');}catch(e){assert.notEqual(e.message,'Promise did not settle');}});
      await asyncTest('无效并发不会无限等待',async()=>{try{await run('globalThis.result=subject([async()=>1],0);');}catch(e){assert.notEqual(e.message,'Promise did not settle');}});
    }else{
      test(behavior,'数值升序含负数和小数',()=>same(call([[10,-1,2,1.5]]),[-1,1.5,2,10]));
      test(behavior,'重复与空数组',()=>{same(call([[2,1,2]]),[1,2,2]);same(call([[]]),[]);});
      test(behavior,'新数组且输入不变',()=>{call([[3,1]]);same(context.args[0],[3,1]);assert.equal(vm.runInContext('subject(...args)===args[0]',context),false);});
      test(behavior,'非有限数字抛TypeError',()=>{for(const x of [[NaN],[Infinity],['2']])assert.throws(()=>call([x]),e=>e.name==='TypeError');});
    }
  }catch(e){behavior.push({name:'源码可加载执行',pass:false,error:e.message});}
  return {delivery,behavior,semanticReviewRequired:true};
}

/** 根据表头选择常见分隔符，正确处理CSV双引号、换行及BOM，避免按唯一排版判错。 */
function parseCsv(csv){
  csv=csv.replace(/^\uFEFF/,'');const first=csv.split(/\r?\n/)[0];const delimiter=[',',';','\t'].sort((a,b)=>first.split(b).length-first.split(a).length)[0];
  const rows=[],row=[];let field='',quoted=false;
  for(let i=0;i<csv.length;i++){const ch=csv[i];if(ch==='"'){if(quoted&&csv[i+1]==='"'){field+='"';i++;}else quoted=!quoted;}else if(ch===delimiter&&!quoted){row.push(field);field='';}else if((ch==='\n'||ch==='\r')&&!quoted){if(ch==='\r'&&csv[i+1]==='\n')i++;row.push(field);rows.push([...row]);row.length=0;field='';}else field+=ch;}
  assert.equal(quoted,false,'Unclosed CSV quote');if(field||row.length){row.push(field);rows.push([...row]);}return rows;
}
