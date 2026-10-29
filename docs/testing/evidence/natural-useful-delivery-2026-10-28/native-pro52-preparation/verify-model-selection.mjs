import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile,writeFile,stat,mkdir} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {validatePublicResponses} from '../model-selection50-preparation/verify-model-selection.mjs';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),repo=resolve(evidence,'../../../..');
const json=async path=>JSON.parse((await readFile(path,'utf8')).replace(/^\uFEFF/,''));
const hash=x=>createHash('sha256').update(x).digest('hex');
const absent=async file=>{try{await stat(file);throw Error('Existing or closed artifact');}catch(error){if(error.code!=='ENOENT')throw error;}};
export function validateProtocol(p,jobs){
 assert.equal(p.status,'FROZEN');assert.equal(p.modelSelectionProfile?.name,'NATIVE_PRO52_PREPARE_MECHANISM_V1');
 for(const object of[p,p.modelSelectionProfile]){assert.equal(object.modelId,'deepseek:deepseek-v4-pro');assert.equal(object.upstreamModel,'deepseek-v4-pro');assert.equal(object.endpoint,'https://api.deepseek.com/chat/completions');}
 assert.equal(p.modelSelectionProfile.routeKey,'deepseek');assert.equal(p.modelSelectionProfile.baselineStage,'registry52-plan-completion');
 assert.equal(p.maximumNewActualCalls,2);assert.equal(p.modelSelectionProfile.maximumActualCalls,2);
 assert.deepEqual(p.phaseLimits,{GENERATION:0,PLAN:0,EXECUTION:2,REVIEW:0});assert.deepEqual(p.versions,['52']);
 assert.deepEqual(p.candidateVersions,['52']);assert.equal(p.qualityRetriesAllowed,false);assert.equal(p.accountCreationAllowed,false);assert.equal(p.captureOnly,false);
 assert.equal(p.parameters.executionMaxTokens,8192);assert.equal(p.parameters.temperature,.2);assert.equal(p.parameters.thinking,'disabled');assert.equal(p.parameters.stream,false);
 const ids=['P02_CONFIRMED_V52_PRO_PREPARE','P02_UNKNOWN_V52_PRO_PREPARE'];assert.deepEqual(jobs.map(x=>x.id),ids);assert.deepEqual(p.allowedJobs,ids);
 for(const job of jobs){assert.equal(job.version,'52');assert.equal(job.phase,'EXECUTION');assert.equal(job.approvedModelRequest.model,'deepseek-v4-pro');}
 return jobs[0];
}
async function main(){
 if(process.argv.includes('--help')){console.log('node verify-model-selection.mjs native-pro52-prepare --label BEFORE|AFTER [--execute-get]. Default preflight only; execute-get is exactly three loopback GETs and no model POST.');return;}
 const stage=process.argv[2];assert.equal(stage,'native-pro52-prepare');const flags=process.argv.slice(3),i=flags.indexOf('--label'),label=i<0?'BEFORE':flags[i+1];
 assert.ok(['BEFORE','AFTER'].includes(label));assert.ok(flags.every(x=>['--label',label,'--execute-get'].includes(x)));
 const runtime=resolve(repo,'tmp/natural-useful-delivery-2026-10-28',stage),source=resolve(evidence,stage);
 for(const root of[evidence,dirname(runtime),runtime,source])await absent(resolve(root,'closure.json'));
 const pb=await readFile(resolve(source,'protocol.json')),jb=await readFile(resolve(source,'jobs.json')),p=JSON.parse(pb),jobs=JSON.parse(jb),first=validateProtocol(p,jobs);
 const result=resolve(runtime,'results',label==='BEFORE'?'MODEL_SELECTION_CHECK.json':'MODEL_SELECTION_CHECK_AFTER.json');await absent(result);
 let before;if(label==='AFTER'){before=await json(resolve(runtime,'results/MODEL_SELECTION_CHECK.json'));assert.equal(before.protocolSha256,hash(pb));assert.equal(before.jobsSha256,hash(jb));}
 if(!flags.includes('--execute-get')){console.log(JSON.stringify({stage,label,modelCalls:0,httpRequests:0,requiresExecuteGet:true}));return;}
 const account=await json(resolve(repo,'tmp/local-acceptance/account.json')),session=await json(resolve(runtime,'auth/session.json'));
 assert.ok(Array.isArray(session.cookies)&&session.cookies.length>0);
 for(const row of session.cookies)assert.ok(Array.isArray(row)&&row.length===2&&row.every(x=>typeof x==='string')&&/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(row[0])&&!/[\r\n;]/.test(row[1]));
 const cookie=session.cookies.map(([name,value])=>name+'='+value).join('; ');let requests=0;
 const get=async(path,guarded=false)=>{assert.ok(['/api/v1/auth/me','/api/v1/models','/api/v2/evaluation/model-selection'].includes(path));assert.ok(++requests<=3);
  const response=await fetch('http://127.0.0.1:9014'+path,{method:'GET',redirect:'manual',headers:{Cookie:cookie,...(guarded?{'X-Evaluation-Job':first.id}:{})},signal:AbortSignal.timeout(30000)});
  assert.equal(response.status,200);const body=await response.json();assert.ok(Object.hasOwn(body,'data'));return body.data;};
 const identity=await get('/api/v1/auth/me');assert.ok(['userId','tenantId','workspaceId'].every(key=>account[key]&&identity[key]===account[key]));
 const catalog=await get('/api/v1/models');await writeFile(resolve(runtime,'active-job.json'),JSON.stringify(first,null,2)+'\n');
 const selection=await get('/api/v2/evaluation/model-selection',true),safe=validatePublicResponses(account,identity,catalog,selection,before);
 await mkdir(dirname(result),{recursive:true});await writeFile(result,JSON.stringify({id:label==='BEFORE'?'MODEL_SELECTION_CHECK':'MODEL_SELECTION_CHECK_AFTER',label,status:200,stage,
  profile:p.modelSelectionProfile.name,...safe,checkedAt:new Date().toISOString(),protocolSha256:hash(pb),jobsSha256:hash(jb),guardedJobId:first.id,httpRequests:requests,modelCalls:0,
  catalogMutationPerformed:false,historicalInnerFlashModelIdNotRewritten:true},null,2)+'\n',{flag:'wx'});
 console.log(JSON.stringify({stage,label,httpRequests:requests,modelCalls:0,nativeProRouteMatched:true,catalogMutationPerformed:false}));
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(()=>{console.error(JSON.stringify({event:'NATIVE_PRO52_READONLY_CHECK_FAILED',rawErrorOmitted:true}));process.exitCode=1;});
