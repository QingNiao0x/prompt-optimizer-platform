// Saved-artifact checks only. No model call, prompt repair, work trimming, or reinterpretation of a review.
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {checkResearchLength} from '../../../../scripts/me00a-work-checks.mjs';
import {auditIndependentReview} from '../../../../scripts/me00a-review-checks.mjs';
import {scoreDisagreements} from '../../../../scripts/me00a-rubric.mjs';
const root=resolve('tmp/me00a-candidate26-r2-live-20261017/real');
const hash=value=>createHash('sha256').update(value).digest('hex');
const mapping={U:'F2',V:'F1',X:'P1',Y:'P2'},works={},hardChecks={};
for(const[label,id]of Object.entries(mapping)){
  const input=await readFile(resolve(root,'works',id,'input.md'),'utf8'),text=await readFile(resolve(root,'works',id,'work.md'),'utf8');
  const binding=JSON.parse(await readFile(resolve(root,'works',id,'binding.json'),'utf8'));
  if(hash(input)!==binding.inputSha256)throw Error('Work/input binding drift');
  works[label]={input,text,inputSha256:binding.inputSha256};
  const length=checkResearchLength(text);
  hardChecks[label]={length,unicodeCharacters:[...text].length,hanTotal:[...text.matchAll(/\p{Script=Han}/gu)].length,
    noCodeFence:!text.includes('```'),semanticAndSafetyGate:'OPEN'};
}
const reviews={};
for(const model of ['deepseek-flash','deepseek-v4-pro']){
  const text=await readFile(resolve(root,`review-${model}.md`),'utf8');
  // Length may independently fail, while a passing length alone cannot clear the other hard gates.
  const gates=Object.fromEntries(Object.entries(hardChecks).map(([id,value])=>[id,value.length.pass&&value.noCodeFence?'OPEN':'FAIL']));
  reviews[model]=auditIndependentReview(text,{works,pairs:[{left:'U',right:'V'},{left:'X',right:'Y'}],gates});
}
let disagreements='UNAVAILABLE_UNTIL_BOTH_REVIEW_SCHEMAS_VALID';
const first=reviews['deepseek-flash'],second=reviews['deepseek-v4-pro'];
if(first.strictJsonAndSchemaValid&&second.strictJsonAndSchemaValid)disagreements={
  scores:scoreDisagreements(first.originalReview.scores,second.originalReview.scores),
  pairs:first.originalReview.pairs.filter(pair=>second.originalReview.pairs.find(other=>other.left===pair.left&&other.right===pair.right)?.verdict!==pair.verdict),
};
await writeFile(resolve(root,'independent-checks.json'),JSON.stringify({hardChecks,reviews,disagreements,
  noModelCalls:true,originalWorksAndReviewsUnchanged:true,overallQuality:'OPEN_PENDING_EVIDENCE_REVIEW'},null,2)+'\n',{flag:'wx'});
console.log('Saved independent length, review evidence and disagreement checks; no automatic semantic pass');
