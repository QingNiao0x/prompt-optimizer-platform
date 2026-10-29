import assert from 'node:assert/strict';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { checkR2 } from './code-work-r2.mjs';
const parent = resolve('tmp/natural-useful-delivery-2026-10-28/checker-r2-selftest'); await mkdir(parent, {recursive:true});
const root = await mkdtemp(resolve(parent, 'run-'));
const control = await checkR2({files:[]}, 'C01', resolve(root, 'unchanged'));
assert.equal(control.passed, false); assert.equal(control.optionalToday.passed, false);
const content = `export function validateSurveyDate(value,today) {
  if(typeof value!=='string'||!/^\\d{4}-\\d{2}-\\d{2}$/.test(value)) return 'INVALID_DATE';
  const date=new Date(value+'T00:00:00.000Z');
  if(Number.isNaN(date.valueOf())||date.toISOString().slice(0,10)!==value) return 'INVALID_DATE';
  if(today===undefined) { const now=new Date(); today=[now.getFullYear(),String(now.getMonth()+1).padStart(2,'0'),String(now.getDate()).padStart(2,'0')].join('-'); }
  return value>today?'FUTURE_DATE':null;
}`;
const valid = await checkR2({files:[{path:'src/views/records/dateRules.js',content}]}, 'C01', resolve(root, 'valid'));
assert.equal(valid.passed,true); assert.equal(valid.optionalToday.passed,true);
const summary={fixtureOnly:true,actualModelCalls:0,positivePassed:valid.passed,unchangedRejected:!control.passed,
  independentOmittedTodayChecks:valid.optionalToday.checks.length};
await writeFile(resolve(root,'result.json'),JSON.stringify({summary,control,valid},null,2)); console.log(JSON.stringify(summary));
