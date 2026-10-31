/** 实际作品按用户要求验收；代码已人工查看，仅在无宿主API的VM中执行，并限定时间。 */
import {readFile, readdir, writeFile} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const dir=dirname(fileURLToPath(import.meta.url));
const checks=[];
for(const name of (await readdir(resolve(dir,'results'))).filter(x=>x.includes('_execute_')).sort()) {
  const r=JSON.parse(await readFile(resolve(dir,'results',name),'utf8'));
  const body=r.body?.choices?.[0]?.message?.content??'';
  const checksFor=[];
  const check=(label,pass)=>checksFor.push({label,pass:Boolean(pass)});
  check('HTTP完整交付',r.status===200 && r.body.choices[0].finish_reason==='stop');
  if(name.startsWith('H_CODE')) {
    const match=body.match(/^```(?:javascript|js)?\s*\n([\s\S]+)\n```\s*$/);
    check('只返回ES模块代码块',match);
    if(match) {
      const code=match[1].replace('export function latestMessages','function latestMessages');
      try {
        const actual=vm.runInNewContext(code+`
          const data=[{id:'a',timestamp:2},{id:'b',timestamp:4},{id:'c',timestamp:4},{id:'d',timestamp:1}];
          const before=JSON.stringify(data);
          const results=[latestMessages(data,2).map(x=>x.id),latestMessages(data,0),latestMessages([],3),latestMessages(data,9).length];
          let rejected=0; for(const n of [-1,1.2,NaN,Infinity,'2',null]) { try { latestMessages(data,n); } catch(e) { if(e instanceof RangeError) rejected++; } }
          JSON.stringify({results,unchanged:JSON.stringify(data)===before,rejected});`,{}, {timeout:1000});
        assert.deepEqual(JSON.parse(actual),{results:[['b','c'],[],[],4],unchanged:true,rejected:6});
        check('排序/稳定性/边界/不改输入',true);
      } catch { check('排序/稳定性/边界/不改输入',false); }
    }
  } else if(name.startsWith('W_NOTICE')) {
    check('120字内',Array.from(body).length<=120);
    check('具体事实完整',['11月4日','数据同步问题','11月3日18:00','已交付功能'].every(x=>body.includes(x)));
    check('无额外赔偿承诺或无限延期',!/(赔偿|无限延期|无法确定任何日期)/.test(body));
  } else if(name.startsWith('H_GUIDE')) {
    check('180字内',Array.from(body).length<=180);
    check('流程与故障处理完整',['内部日历','状态灯','白板','椅子','垃圾','前台','拆卸设备'].every(x=>body.includes(x)));
    check('无新增收费处罚',!/(罚款|收费|处罚)/.test(body));
  }
  checks.push({file:name,characters:Array.from(body).length,checks:checksFor,passed:checksFor.every(x=>x.pass)});
}
await writeFile(resolve(dir,'work-checks.json'),JSON.stringify({checks,manualReview:'逐份检查18份作品；三组各臂都可用，差异主要为措辞和篇幅，未证实少修改或实质交付提升。',overallGain:'NOT_DEMONSTRATED'},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({works:checks.length,passed:checks.filter(x=>x.passed).length}));
