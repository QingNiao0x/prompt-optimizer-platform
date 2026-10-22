import { randomUUID, createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { isDeepStrictEqual } from 'node:util';

const stage = process.argv[3];
if (!/^[a-z0-9-]+$/.test(stage ?? '')) throw Error('Explicit safe stage required');
const root = resolve('tmp/natural-guarded-fourth-2026-10-23', stage);
const evidence = resolve('docs/testing/evidence/natural-guarded-fourth-2026-10-23', stage);
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const protocol = await json(resolve(evidence, 'protocol.json'));
const origin = protocol.origin;
const cases = (await json(resolve(evidence, 'cases.json'))).cases;
const account = await json(resolve(process.env.POP_CANONICAL_ROOT, 'tmp/local-acceptance/account.json'));
const terminal = createInterface({ input: process.stdin, output: process.stdout });
const cookies = new Map();
let csrf;
let currentJob;
let currentRequestId;
const log = value => console.log(JSON.stringify(value));
const encode = value => JSON.stringify(value, null, 2) + '\n';
const save = async (path, value) => {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, typeof value === 'string' ? value : encode(value), { flag: 'wx' });
};

/** 正常CSRF、验证码及固定合成账号登录；密码和Cookie仅在进程内存，不进入公开证据。 */
async function api(path, body) {
  const headers = { Cookie: [...cookies].map(([key, value]) => key + '=' + value).join('; ') };
  if (currentJob) headers['X-Evaluation-Job'] = currentJob;
  if(currentRequestId) headers['X-Request-Id']=currentRequestId;
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    headers[csrf?.headerName ?? 'X-XSRF-TOKEN'] = csrf?.token ?? '';
  }
  const response = await fetch(origin + path, { method: body === undefined ? 'GET' : 'POST', headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(240_000) });
  for (const line of response.headers.getSetCookie()) {
    const pair = line.split(';', 1)[0]; const index = pair.indexOf('=');
    cookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
  return { status: response.status, body: await response.json() };
}

async function login() {
  if (!process.env.POP_EVAL_PASSWORD) throw Error('Canonical credential must be injected');
  csrf = (await api('/api/v1/auth/csrf')).body.data;
  const challenge = await fetch(origin + '/api/v1/auth/captcha', {
    headers: { Cookie: [...cookies].map(([key, value]) => key + '=' + value).join('; ') }
  });
  for (const line of challenge.headers.getSetCookie()) {
    const pair = line.split(';', 1)[0]; const index = pair.indexOf('=');
    cookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
  const path = resolve(root, 'auth/captcha-' + Date.now() + '.png');
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, Buffer.from(await challenge.arrayBuffer()), { flag: 'wx' });
  log({ event: 'CAPTCHA_READY', path });
  const captcha = (await terminal.question('CAPTCHA_INPUT> ')).trim();
  const result = await api('/api/v1/auth/login', { identifier: account.identifier,
    password: process.env.POP_EVAL_PASSWORD, captcha });
  delete process.env.POP_EVAL_PASSWORD;
  if (result.status !== 200) throw Error('Canonical login failed: HTTP ' + result.status);
  csrf = (await api('/api/v1/auth/csrf')).body.data;
}


const taskRoot=resolve(root,'..');
async function total(){
 let n=0;
 for(const name of await readdir(taskRoot)){
  try{n+=(await readdir(resolve(taskRoot,name,'traces'))).filter(x=>x.endsWith('.json')).length;}catch(e){if(!['ENOENT','ENOTDIR'].includes(e.code))throw e;}
 }
 return n;
}
function field(line,name){const m=line.match(new RegExp('(?:^| )'+name+'=([^ ]+)'));return m?.[1];}
await mkdir(resolve(root,'traces'),{recursive:true});
await mkdir(resolve(root,'results'),{recursive:true});
try{
 await login();
 for(const sample of protocol.cases){
  const before=await total();
  if(protocol.taskMaximumActualCalls-before<protocol.maximumCallsPerBusinessRequest){log({id:sample.id,state:'NO_SAFE_RESERVATION',actual:before});break;}
  const recorded=resolve(root,'results',sample.id+'.json');
  try{await readFile(recorded);throw Error('Already attempted; no quality retry');}catch(e){if(e.code!=='ENOENT')throw e;}
  currentJob=sample.id;currentRequestId=randomUUID();
  await save(resolve(root,'reservations',sample.id+'.json'),{requestId:currentRequestId,maximumReserved:4,taskActualBefore:before});
  const request={rawPrompt:sample.rawPrompt,modelId:protocol.modelId,context:{customDescription:'',files:[]},enhancement:{templateCode:'AUTO',includeConversationHistory:false,includePermissionBoundaries:true,promptFormat:'COMPACT'},conversationHistory:[],permissionPolicy:{protectedPaths:[],requireConfirmationFor:[]},planConfirmation:null};
  const result=await api('/api/v2/optimizations',request);
  const responseId=result.body.requestId??currentRequestId;
  let observations=[];
  for(let tries=0;tries<30;tries++){
   const lines=(await readFile(process.env.POP_BACKEND_STDOUT,'utf8')).split(/\r?\n/);
   observations=lines.filter(line=>line.includes('event=model.call.')&&(line.includes('requestId='+responseId)||line.includes('requestId='+currentRequestId)));
   if(observations.length)break;
   await new Promise(r=>setTimeout(r,100));
  }
  if(!observations.length){
   for(let n=0;n<4;n++)await save(resolve(root,'traces',sample.id+'-'+n+'.json'),{phase:'GENERATION',jobId:sample.id,requestId:responseId,accounting:'UNKNOWN_RESERVED_UPPER_BOUND',transportFailure:'Model audit not available'});
   await save(recorded,{id:sample.id,request,result,auditVerified:false});throw Error('Audit unavailable; charged upper bound4 and stopped');
  }
  if(observations.length>4)throw Error('Unexpected extra model calls');
  for(let n=0;n<observations.length;n++){
   const line=observations[n];
   await save(resolve(root,'traces',sample.id+'-'+n+'.json'),{phase:'GENERATION',jobId:sample.id,requestId:responseId,requestCapture:'Public API body only; upstream payload not intercepted on daily runtime',auditSha256:createHash('sha256').update(line).digest('hex'),response:{source:'normal service ModelCallLogger',event:field(line,'event'),operation:field(line,'operation'),providerRoute:field(line,'providerRoute'),model:field(line,'model'),attempt:field(line,'attempt'),usage:{prompt_tokens:Number(field(line,'inputTokens')),completion_tokens:Number(field(line,'outputTokens')),total_tokens:Number(field(line,'totalTokens'))}}});
  }
  const data=result.body.data;
  const generated=data?.optimizedPrompt;
  const checks={status:result.status===200,default31:data?.chainVersion==='31',natural:data?.generationPipeline==='NATURAL',text:data?.resultKind==='TEXT',realModel:data?.provider?.mock===false,noFixedRules:data?.constraintGroups?.platformMandatory?.length===0,originalGoal:sample.expectPreserved?generated===sample.rawPrompt:generated?.includes('50')&&generated?.includes('二楼会议室')};
  let history=null;
  if(data?.recordId){history=await api('/api/v2/optimization-history/'+data.recordId);checks.historyEqual=history.body.data?.optimizedPrompt===generated;checks.historyVersion=history.body.data?.chainVersion==='31';}
  await save(recorded,{id:sample.id,request,result,history,checks,actualModelCalls:observations.length,requestId:responseId});
  await save(resolve(evidence,sample.id+'.json'),{id:sample.id,checks,actualModelCalls:observations.length,requestId:responseId,recordId:data?.recordId,optimizedPrompt:generated});
  log({id:sample.id,checks,actualModelCalls:observations.length,taskActual:await total()});
  if(Object.values(checks).some(x=>x!==true))throw Error('Live application check failed');
 }
}finally{terminal.close();}
