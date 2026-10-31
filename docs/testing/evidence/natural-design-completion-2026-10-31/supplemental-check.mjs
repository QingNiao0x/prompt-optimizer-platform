/** 补充核验原答案卡已有的“不假称全部成功”，保留原冻结检查器不改写。 */
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {readFile,writeFile,readdir} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const dir=dirname(fileURLToPath(import.meta.url));
const json=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^\uFEFF/,''));
const results=[];
for(const name of (await readdir(resolve(dir,'results'))).filter(n=>n.startsWith('D_QUEUE_')&&n.includes('_execute_')).sort()){
  const response=await json(resolve(dir,'results',name));
  const text=response.body.choices[0].message.content;let timer;
  try{
    const code=JSON.parse(text)['src/runTasks.mjs'];
    const context=vm.createContext({setTimeout,clearTimeout},{codeGeneration:{strings:false,wasm:false}});
    const module=new vm.SourceTextModule(code,{context});await module.link(()=>{throw Error('No imports');});await module.evaluate({timeout:1000});
    context.subject=module.namespace.runTasks;
    vm.runInContext('globalThis.result=subject([async()=>{throw Error("fixture-failure")},()=>new Promise(r=>setTimeout(()=>r(2),10))],2)',context,{timeout:1000});
    let value;try{value=await Promise.race([context.result,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('not-settled')),1500)})]);}
    catch(e){assert.notEqual(e.message,'not-settled');results.push({file:name,pass:true,handling:'rejects task failure'});continue;}
    // 逐项结果可用任意结构，但必须显式保留失败，而非把空洞/undefined当全部成功。
    const description=JSON.stringify(value);
    assert.ok(description&&(/rejected|fixture-failure|error|failed/i.test(description)),'Rejected task disappeared into a fulfilled success result');
    results.push({file:name,pass:true,handling:'explicit per-task failure result'});
  }catch(e){results.push({file:name,pass:false,error:e.message});}
  finally{clearTimeout(timer);}
}
await writeFile(resolve(dir,'supplemental-queue-check.json'),JSON.stringify({basis:'原rubric已要求失败不得假称成功；原检查器只检查不挂起，没有检查拒绝是否被悄悄吞掉。此处补录，不回改旧判定。',results},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(results));
