/** 实际代码作品离线检查；修复转义后的诊断单列，不改写原件或将人工处理后的通过算作交付通过。 */
import {createContext,SourceTextModule,Script} from 'node:vm';
import {posix,resolve} from 'node:path';
import {dir,json,save} from './common.mjs';
const sample=(await json(resolve(dir,'cases.json'))).cases.find(x=>x.id==='DATE');
const originals=Object.fromEntries(sample.context.files.filter(x=>x.path.startsWith('src/people/')&&x.path.endsWith('.mjs')).map(x=>[x.path,x.content]));
async function check(files){
  const context=createContext({}, {codeGeneration:{strings:false,wasm:false}}),modules=new Map();
  for(const [name,source] of Object.entries({...originals,...files}))modules.set(name,new SourceTextModule(source,{context,identifier:name}));
  const link=(specifier,from)=>{
    const target=posix.normalize(posix.join(posix.dirname(from.identifier),specifier));
    if(!specifier.startsWith('./')||!modules.has(target))throw Error('Import outside frozen fixture');return modules.get(target);
  };
  for(const module of modules.values())if(module.status==='unlinked')await module.link(link);
  for(const module of modules.values())if(module.status==='linked')await module.evaluate({timeout:1000});
  context.valid=modules.get('src/people/date.mjs').namespace.validSurveyDate;
  context.create=modules.get('src/people/create.mjs').namespace.createPerson;
  context.importRows=modules.get('src/people/import.mjs').namespace.importPeople;
  return new Script(`(()=>{
    const errors=[];let checks=0;const ok=(condition,label)=>{checks++;if(!condition)errors.push(label);};
    for(const allow of [false,true]){
      for(const [value,expected] of [['2026-10-30',true],['2026-10-31',allow],['2026-11-01',false],['2026-02-30',false],['2026-02-29',false],['',false],[null,false],[1,false],['2026-1-01',false]]){
        ok(valid(value,'2026-10-31',allow)===expected,'calendar:'+value+':'+allow);
        let accepted=true;try{create({surveyDate:value},'2026-10-31',allow);}catch(error){accepted=false;ok(error.name==='Error','create Error');}
        ok(accepted===expected,'create:'+value+':'+allow);
      }
      const rows=[{id:1,surveyDate:'2026-10-30'},{id:2,surveyDate:'2026-10-31'},{id:3,surveyDate:'2026-11-01'},{id:4,surveyDate:''}],before=JSON.stringify(rows);
      const result=importRows(rows,'2026-10-31',allow);
      ok(JSON.stringify(result.accepted.map(x=>x.id))===JSON.stringify(allow?[1,2]:[1]),'import accepted');
      ok(JSON.stringify(result.rejected.map(x=>x.row))===JSON.stringify(allow?[3,4]:[2,3,4]),'import rejected indices');
      ok(result.rejected.every(x=>x.reason==='调查日期无效'),'import reason');
      ok(JSON.stringify(rows)===before,'input mutation');
      ok(result.accepted[0]!==rows[0],'accepted clone');
    }
    ok(valid('2024-02-29','2024-03-01',true),'leap');
    ok(!valid('2026-01-01','2020-01-01',true),'use passed today');
    return {checks,errors};
  })()`).runInContext(context,{timeout:1000});
}
const results=[];
for(const job of ['DATE-original-1','DATE-candidate-1','DATE-candidate-2','DATE-original-2']){
  const trace=await json(resolve(dir,'works',job+'.json')),result={job,delivered:false};
  try{
    const files=JSON.parse(trace.response.choices[0].message.content);
    result.paths=Object.keys(files);result.outsideScope=result.paths.filter(x=>!Object.hasOwn(originals,x));
    if(result.outsideScope.length||Object.values(files).some(x=>typeof x!=='string'))throw Error('Invalid delivery contract');
    result.unchangedFiles=result.paths.filter(x=>files[x].trim()===originals[x].trim());
    try{result.behavior=await check(files);result.delivered=!result.behavior.errors.length&&!result.unchangedFiles.length;}
    catch(error){result.syntaxOrRuntimeError=error.name;}
    if(result.syntaxOrRuntimeError){
      // 只为定位多重转义的责任额外尝试一次解码，不写回源码、不改作品是否可直接使用的结论。
      try{
        const decoded=Object.fromEntries(Object.entries(files).map(([k,v])=>[k,JSON.parse('"'+v+'"')]));
        result.afterOneManualEscapeCorrection={behavior:await check(decoded),unchangedFiles:Object.keys(decoded).filter(x=>decoded[x].trim()===originals[x].trim())};
      }catch(error){result.diagnosticError=error.name;}
    }
  }catch(error){result.contractError=error.name;}
  results.push(result);
}
await save(resolve(dir,'works/assessment.json'),{method:'Frozen functional assertions and internal code review; no paid model judge; raw outputs never overwritten',results});
console.log(JSON.stringify(results));
