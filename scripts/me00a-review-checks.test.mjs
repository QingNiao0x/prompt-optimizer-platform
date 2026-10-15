import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { auditIndependentReview } from './me00a-review-checks.mjs';
const hash=text=>createHash('sha256').update(text).digest('hex');
const work=(text,input)=>({text,input,inputSha256:hash(input)});
const bindings={works:{U:work('交付甲报告。','写甲报告。'),V:work('交付乙报告。','写乙报告，正文800字。')},pairs:[{left:'U',right:'V'}]};
const scores=Object.fromEntries(['U','V'].map(id=>[id,{accuracy:4,completeness:4,clarity:4,executability:4}]));
const review=(issues=[],verdict='RIGHT_WIN',reason='V优于U')=>JSON.stringify({reviewerType:'AI_ASSISTED',scores,issues,pairs:[{left:'U',right:'V',verdict,reason}]});
test('A true quote binds its own input or work without replacing the original review',()=>{
  const raw=review([{work:'U',severity:'MINOR',origin:'WORK',evidence:'交付甲报告。',reason:'可核对的原句'}]);
  const audit=auditIndependentReview(raw,bindings);assert.equal(audit.deterministicChecksPassed,true);assert.deepEqual(audit.originalReview,JSON.parse(raw));
});
test('Another arm input and a mislabeled origin fail provenance checks',()=>{
  const audit=auditIndependentReview(review([{work:'U',severity:'MAJOR',origin:'WORK',evidence:'正文800字',reason:'错误归属'}]),bindings);
  assert.equal(audit.deterministicChecksPassed,false);assert.equal(audit.errors[0].code,'EVIDENCE_WRONG_SOURCE');
  const mislabeled=auditIndependentReview(review([{work:'U',severity:'MAJOR',origin:'WORK',evidence:'写甲报告。',reason:'输入不是作品'}]),bindings);
  assert.equal(mislabeled.errors[0].code,'EVIDENCE_ORIGIN_MISMATCH');
});
test('Literal winner contradicting label is flagged without silently reversing it',()=>{
  const audit=auditIndependentReview(review([],'LEFT_WIN','篇幅合规，V优于U'),bindings);
  assert.equal(audit.errors[0].code,'VERDICT_REASON_DIRECTION_CONTRADICTION');assert.equal(audit.originalReview.pairs[0].verdict,'LEFT_WIN');
  assert.equal(auditIndependentReview(review([],'LEFT_WIN','不能认定V优于U'),bindings).errors.length,0);
});
test('Independent failures block acceptance despite a relative winner',()=>{
  const audit=auditIndependentReview(review(),{...bindings,gates:{U:'FAIL',V:'FAIL'}});
  assert.equal(audit.comparisons[0].independentGate,'BOTH_FAIL');assert.equal(audit.comparisons[0].originalVerdict,'RIGHT_WIN');
});
test('No fenced JSON repair, missing score repair or changed input binding is allowed',()=>{
  assert.equal(auditIndependentReview('```json\n'+review()+'\n```',bindings).strictJsonAndSchemaValid,false);
  const bad=JSON.parse(review());delete bad.scores.U;assert.equal(auditIndependentReview(JSON.stringify(bad),bindings).strictJsonAndSchemaValid,false);
  assert.throws(()=>auditIndependentReview(review(),{...bindings,works:{...bindings.works,U:{...bindings.works.U,input:'补入遗漏答案'}}}),/bindings/);
});
test('Candidate13 real reviews retain their exact content and expose the observed failures',async()=>{
  const root=new URL('../docs/testing/evidence/me00a-candidate13-2026-10-15/live/',import.meta.url);
  const {mapping,pairs}=JSON.parse(await readFile(new URL('review-mapping.json',root),'utf8'));
  const works={};
  for(const [id,name] of Object.entries(mapping)){
    const text=await readFile(new URL('works/'+name+'.md',root),'utf8');
    const input=await readFile(new URL(name.split('-')[1]+'/execution-input.md',root),'utf8');works[id]=work(text,input);
  }
  const doubao=await readFile(new URL('reviews/doubao-review.md',root),'utf8');
  const deepseek=await readFile(new URL('reviews/deepseek-review.md',root),'utf8');
  const a=auditIndependentReview(doubao,{works,pairs}),b=auditIndependentReview(deepseek,{works,pairs});
  assert.equal(a.strictJsonAndSchemaValid,true);assert.equal(b.strictJsonAndSchemaValid,true);
  assert.ok(a.errors.some(e=>e.code==='VERDICT_REASON_DIRECTION_CONTRADICTION'));
  assert.equal(b.errors.filter(e=>e.code==='EVIDENCE_WRONG_SOURCE').length,2);
  assert.equal(a.rawReviewSha256,hash(doubao));assert.equal(b.rawReviewSha256,hash(deepseek));
});
