/** 冻结的作品验收：只按共同材料中的契约检查，不以正文长度或模型评分判断收益。 */
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile, writeFile, readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {dirname, resolve, posix} from 'node:path';
import {fileURLToPath} from 'node:url';
const root=dirname(fileURLToPath(import.meta.url));
const json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const cases=(await json(resolve(root,'cases.json'))).cases;
const hash=x=>createHash('sha256').update(x).digest('hex');
if(process.argv[2]==='freeze') {
  await writeFile(resolve(root,'works-check-seal.json'),JSON.stringify({frozenAt:new Date().toISOString(),sha256:hash(await readFile(fileURLToPath(import.meta.url))),criteria:'Only shared source comments, scope and predeclared answer fixture; no provider response inspected for checker design'},null,2)+'\n',{flag:'wx'});
  process.exit(0);
}
assert.equal(hash(await readFile(fileURLToPath(import.meta.url))),(await json(resolve(root,'works-check-seal.json'))).sha256);

/** 受控合成源码无宿主句柄；禁用字符串代码生成、动态导入及仓库外模块。不是通用不可信代码沙箱。 */
async function inspect(sample, changed) {
  const results=[];
  const check=(name, action)=>{try{action();results.push({name,pass:true});}catch(e){results.push({name,pass:false,error:e.message});}};
  const allowed=sample.id==='SORT'?['src/sortNumbers.mjs']:['src/people/date.mjs','src/people/create.mjs','src/people/import.mjs'];
  check('交付仅含允许修改的源码文件',()=>{assert.ok(changed&&typeof changed==='object'&&!Array.isArray(changed));assert.ok(Object.keys(changed).length>0);for(const [p,s] of Object.entries(changed)){assert.ok(allowed.includes(p));assert.equal(typeof s,'string');}});
  if(!results[0].pass)return results;
  const files=Object.fromEntries(sample.context.files.map(f=>[f.path,f.content]));Object.assign(files,changed);
  const context=vm.createContext({}, {codeGeneration:{strings:false,wasm:false}}), modules=new Map();
  function module(path) {if(!modules.has(path)){assert.ok(allowed.includes(path));modules.set(path,new vm.SourceTextModule(files[path],{context,identifier:path,importModuleDynamically:()=>{throw Error('Dynamic imports not allowed');}}));}return modules.get(path);}
  try{
    for(const path of allowed)module(path);
    for(const m of modules.values())if(m.status==='unlinked')await m.link((name,parent)=>{assert.ok(name.startsWith('./'));return module(posix.normalize(posix.join(posix.dirname(parent.identifier),name)));});
    for(const m of modules.values())if(m.status==='linked')await m.evaluate({timeout:1000});
    const call=(path,name,args)=>{context.subject=module(path).namespace[name];context.args=structuredClone(args);return vm.runInContext('subject(...args)',context,{timeout:1000});};
    const same=(actual,expected)=>assert.equal(JSON.stringify(actual),JSON.stringify(expected));
    if(sample.id==='SORT') {
      const run=args=>call(allowed[0],'sortNumbers',args);
      check('数字升序含负数小数',()=>same(run([[10,2,-1,1.5,0]]),[-1,0,1.5,2,10]));
      check('保留重复值',()=>same(run([[2,2,1]]),[1,2,2]));
      check('支持空数组',()=>same(run([[]]),[]));
      check('新数组且不改变输入',()=>{run([[3,1,2]]);same(context.args[0],[3,1,2]);assert.equal(vm.runInContext('subject(...args) === args[0]',context,{timeout:1000}),false);});
      check('非数组抛TypeError',()=>{for(const v of [null,undefined,1,'123',{}])assert.throws(()=>run([v]),e=>e.name==='TypeError');});
      check('非有限数字抛TypeError',()=>{for(const v of [[NaN],[Infinity],[-Infinity],['2'],[null]])assert.throws(()=>run([v]),e=>e.name==='TypeError');});
    }else{
      const valid=(v,t,a)=>call(allowed[0],'validSurveyDate',[v,t,a]);
      check('昨天接受',()=>assert.equal(valid('2026-10-30','2026-10-31',true),true));
      check('允许今天',()=>assert.equal(valid('2026-10-31','2026-10-31',true),true));
      check('兼容严格早于今天的调用',()=>assert.equal(valid('2026-10-31','2026-10-31',false),false));
      check('未来日期拒绝',()=>assert.equal(valid('2026-11-01','2026-10-31',true),false));
      check('跨年仍按调用方today比较',()=>{assert.equal(valid('2030-01-01','2030-01-02',true),true);assert.equal(valid('2025-01-01','2024-12-31',true),false);});
      check('空值及非字符串拒绝',()=>{for(const v of ['',null,undefined,20261031])assert.equal(valid(v,'2026-10-31',true),false);});
      check('无效日历日期拒绝',()=>{for(const v of ['2026-02-30','2026-2-01','2026-13-01'])assert.equal(valid(v,'2026-10-31',true),false);});
      check('闰日正确',()=>{assert.equal(valid('2024-02-29','2024-03-01',true),true);assert.equal(valid('2025-02-29','2025-03-01',true),false);});
      check('新增未来日期抛Error',()=>assert.throws(()=>call(allowed[1],'createPerson',[{surveyDate:'2026-11-01'},'2026-10-31',true]),e=>e.name==='Error'&&e.message==='调查日期无效'));
      check('新增今天成功且保留数据',()=>same(call(allowed[1],'createPerson',[{id:3,surveyDate:'2026-10-31'},'2026-10-31',true]),{id:3,surveyDate:'2026-10-31'}));
      check('导入逐行接受拒绝与行号',()=>same(call(allowed[2],'importPeople',[[{surveyDate:'2026-10-30'},{surveyDate:'2026-11-01'},{surveyDate:'2026-10-31'},{surveyDate:''}],'2026-10-31',true]),{accepted:[{surveyDate:'2026-10-30'},{surveyDate:'2026-10-31'}],rejected:[{row:2,reason:'调查日期无效'},{row:4,reason:'调查日期无效'}]}));
      check('导入兼容严格日期边界',()=>same(call(allowed[2],'importPeople',[[{surveyDate:'2026-10-31'}],'2026-10-31',false]),{accepted:[],rejected:[{row:1,reason:'调查日期无效'}]}));
    }
  }catch(e){results.push({name:'源码解析与受控加载',pass:false,error:e.message});}
  return results;
}
const baseline=[];
for(const sample of cases) {
  const p=sample.id==='SORT'?'src/sortNumbers.mjs':'src/people/date.mjs';
  const checks=await inspect(sample,{[p]:sample.context.files.find(f=>f.path===p).content});
  assert.ok(checks.some(x=>!x.pass),'Checker must detect original bug');baseline.push({caseId:sample.id,checks});
}
if(process.argv[2]==='baseline') {console.log(JSON.stringify(baseline));process.exit(0);}
const output=[];
for(const name of (await readdir(resolve(root,'results'))).filter(n=>n.includes('_execute_')).sort()) {
  const response=await json(resolve(root,'results',name)), id=name.split('_')[0];
  let checks;try{assert.equal(response.status,200);checks=await inspect(cases.find(c=>c.id===id),JSON.parse(response.body.choices[0].message.content));}
  catch(e){checks=[{name:'原始输出JSON交付格式',pass:false,error:e.message}];}
  output.push({file:name,passed:checks.filter(c=>c.pass).length,total:checks.length,checks});
}
await writeFile(resolve(root,'works-checks.json'),JSON.stringify({baseline,works:output},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(output.map(({file,passed,total})=>({file,passed,total}))));
