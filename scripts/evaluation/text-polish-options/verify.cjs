/* 核对冻结实验的实际请求，并生成隐藏标签的评阅稿。仅执行本地文件检查。 */
const fs = require('node:fs');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');

const [, , dir, mode = 'pair', flag] = process.argv;
assert.ok(dir && ['pair', 'matrix'].includes(mode) && (!flag || flag === '--validate-only'),
  'Usage: node verify.cjs <evidence-directory> [pair|matrix] [--validate-only]');
const read=f=>JSON.parse(fs.readFileSync(dir+'/'+f+'.json','utf8'));
const sha=x=>crypto.createHash('sha256').update(x).digest('hex');
const {samples,comparison}=read('samples'),{jobs}=read('protocol'),ledger=read('ledger');
assert.equal(ledger.reserved,0);assert.equal(ledger.entries.length,jobs.length);
assert.equal(ledger.actualCalls,ledger.entries.reduce((n,x)=>n+x.calls,0));
const key={},pack=[],metrics={};
function validate(job){
 const r=read(job.id); assert.equal(r.status,'COMPLETED');
 assert.deepEqual(r.job.input,job.input);
 const m=r.executionMetadata;
 assert.equal(m.strategyVersion,job.strategy);assert.equal(m.resourceHash,sha(r.systemInstruction));
 assert.equal(m.routeKey,comparison.routeKey);assert.equal(m.upstreamModel,comparison.upstreamModel);
 assert.deepEqual(m.parameters,comparison.parameters);
 m.resources.forEach(p=>{
  const f='services/api/src/main/resources/textpolish/skills/'+p.id+'/SKILL.md';
  const raw=fs.readFileSync(f,'utf8').replace(/\r\n/g,'\n');
  const body=raw.slice(raw.indexOf('\n---\n',4)+5);
  assert.equal(p.sha256,sha(body));assert.ok(r.systemInstruction.includes(body));
 });
 assert.equal(m.attemptCount,r.observations.length);
 assert.equal(ledger.entries.find(x=>x.id===job.id).calls,m.attemptCount);
 metrics[job.id]={attempts:m.attemptCount,ms:r.elapsedMs,tokens:r.observations.reduce((n,o)=>n+(o.usage?.totalTokens??0),0)};
 return r;
}
for(const sample of samples){
 const entries=jobs.filter(j=>j.sample===sample.id);
 const out=Object.fromEntries(entries.map(j=>[j.id.slice(sample.id.length+1),validate(j)]));
 let groups;
 if(mode==='pair'){
   const b=out.baseline,c=out.candidate;assert.deepEqual(b.job.input,c.job.input);
   assert.ok(c.systemInstruction.startsWith(b.systemInstruction+'\n'));
   assert.deepEqual(c.executionMetadata.resources.slice(0,-1),b.executionMetadata.resources);
   groups={pair:['baseline','candidate']};
 } else {
   const b=out.formal, n=out.natural, c=out.conversational, w=out.restructure;
   for(const r of [n,c,w])assert.equal(r.executionMetadata.strategyVersion,b.executionMetadata.strategyVersion);
   assert.deepEqual(n.job.input,{...b.job.input,style:'NATURAL'});
   assert.deepEqual(c.job.input,{...b.job.input,style:'CONVERSATIONAL'});
   assert.deepEqual(w.job.input,{...b.job.input,depth:'RESTRUCTURE'});
   for(const r of [n,c,w]){
    assert.deepEqual(r.executionMetadata.resources,b.executionMetadata.resources);
    const hide = x=>x.replace(/Use the specified register, (FORMAL|NATURAL|CONVERSATIONAL)\. Editing depth: (LIGHT|RESTRUCTURE)\./,'OPTION');
    assert.equal(hide(r.systemInstruction),hide(b.systemInstruction));
   }
   groups={style:['formal','natural','conversational'],depth:['formal','restructure']};
 }
 const sheet={id:sample.id,language:sample.language,input:sample.input,reviewOnly:sample.reviewOnly};
 key[sample.id]={};
 for(const [id,options] of Object.entries(groups)){
   const shuffled=[...options];
   for(let i=shuffled.length-1;i>0;i--){ const j=crypto.randomInt(i+1);[shuffled[i],shuffled[j]]=[shuffled[j],shuffled[i]]; }
   key[sample.id][id]=Object.fromEntries(shuffled.map((name,i)=>['ABC'[i],name]));
   sheet[id]=Object.fromEntries(shuffled.map((name,i)=>['ABC'[i],out[name].polishedText]));
 }
 pack.push(sheet);
}
if (flag !== '--validate-only') {
 const outputs = {'blind-key': key, 'blind-packet': pack, verified: {actualCalls: ledger.actualCalls, metrics}};
 for (const name of Object.keys(outputs)) {
  assert.ok(!fs.existsSync(dir + '/' + name + '.json'), 'Preserve existing blind materials: ' + name);
 }
 for (const [name, contents] of Object.entries(outputs)) {
  fs.writeFileSync(dir + '/' + name + '.json', JSON.stringify(contents, null, 2) + '\n', {flag: 'wx'});
 }
}
console.log(mode+' checked '+jobs.length+' requests and '+ledger.actualCalls+' actual calls.');
