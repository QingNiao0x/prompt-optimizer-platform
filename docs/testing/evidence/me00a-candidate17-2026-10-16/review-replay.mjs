// Offline re-audit of unchanged candidate15 works/reviews; no model or product calls.
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { auditIndependentReview } from '../../../../scripts/me00a-review-checks.mjs';
const output=dirname(fileURLToPath(import.meta.url));
const old=resolve(output,'../me00a-candidate14-2026-10-15/live');
const current=resolve(output,'../me00a-candidate15-2026-10-15/live');
const hash=s=>createHash('sha256').update(s).digest('hex');
const {mapping,pairs}=JSON.parse(await readFile(resolve(current,'review-mapping.json'),'utf8'));
const works={};
for(const [id,name] of Object.entries(mapping)) {
  const [vendor,arm]=name.split('-');
  const root=arm==='current'?current:old;
  const actualArm=arm==='current'?'plan':arm;
  const text=await readFile(resolve(root,'works',vendor+'-'+actualArm+'.md'),'utf8');
  const input=await readFile(resolve(root,actualArm,'execution-input.md'),'utf8');
  works[id]={text,input,inputSha256:hash(input)};
}
const summary={modelCalls:0,originalReviewsUnchanged:true,hardAndSemanticGates:'UNCHANGED_OPEN',vendors:{}};
for(const vendor of ['deepseek','doubao']) {
  const text=await readFile(resolve(current,'reviews',vendor+'-review.md'),'utf8');
  const audit=auditIndependentReview(text,{works,pairs});
  await writeFile(resolve(output,'review-'+vendor+'-v2.json'),JSON.stringify(audit,null,2)+'\n');
  summary.vendors[vendor]={rawSha256:audit.rawReviewSha256,errors:audit.errors,
    outerWrapperMatches:audit.evidenceChecks.filter(q=>q.matchMode==='ONE_OUTER_PAIR'&&(q.workPosition>=0||q.inputPosition>=0)).length};
}
await writeFile(resolve(output,'review-replay-summary.json'),JSON.stringify(summary,null,2)+'\n');
console.log(JSON.stringify(summary));
