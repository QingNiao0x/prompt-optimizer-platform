import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';

export const NATIVE_PRO52_PROFILE = 'NATIVE_PRO52_PREPARE_MECHANISM_V1';
const hash = value => createHash('sha256').update(value).digest('hex');
const json = async file => JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));

/** Only two immutable52 PREPARE payloads with a top-level upstream-model change; no product Pro Plan is claimed. */
export async function validateNativePro52Bundle(bundle, { evidence }) {
  const p = bundle.modelSelectionProfile;
  assert.deepEqual(Object.keys(p ?? {}).sort(), ['name','modelId','baselineStage','baselineJobsSha256','baselineProtocolSha256'].sort());
  assert.equal(p.name, NATIVE_PRO52_PROFILE); assert.equal(p.modelId, 'deepseek:deepseek-v4-pro');
  assert.equal(p.baselineStage, 'registry52-plan-completion');
  assert.equal(bundle.captureOnly, undefined); assert.equal(bundle.experimentProfile, undefined);
  assert.deepEqual(bundle.phaseLimits, { GENERATION:0, PLAN:0, EXECUTION:2, REVIEW:0 });
  assert.deepEqual(bundle.parameters, { generationMaxTokens:8192, executionMaxTokens:8192, temperature:.2, thinking:'disabled', stream:false });
  assert.equal(bundle.jobs.length,2); assert.deepEqual(bundle.cases.map(item=>item.id), ['P02_CONFIRMED','P02_UNKNOWN']);
  for (const entry of await readdir(evidence,{withFileTypes:true})) {
    if (!entry.isDirectory() || entry.name.startsWith('sealed')) continue;
    try { assert.notEqual((await json(resolve(evidence,entry.name,'protocol.json'))).modelSelectionProfile?.name, NATIVE_PRO52_PROFILE, 'One-pass mechanism already frozen'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  const base = resolve(evidence,p.baselineStage), closure = await json(resolve(base,'closure.json'));
  assert.equal(closure.status,'CLOSED'); assert.equal(closure.entryLockedByClosure,true);
  assert.equal(hash(await readFile(resolve(base,'jobs.json'))),p.baselineJobsSha256);
  assert.equal(hash(await readFile(resolve(base,'protocol.json'))),p.baselineProtocolSha256);
  for (const [index,caseId] of ['P02_CONFIRMED','P02_UNKNOWN'].entries()) {
    const number=index===0?'001':'003', file=`traces/${number}.json`, bytes=await readFile(resolve(base,file));
    const locked=closure.files.find(item=>item.path.replaceAll('\\','/').endsWith('/registry52-plan-completion/'+file));
    assert.equal(hash(bytes),locked?.sha256);
    const trace=JSON.parse(bytes), original=trace.request, job=bundle.jobs[index];
    assert.equal(trace.registry49.callStage,'REQUIREMENT_PREPARATION');
    assert.equal(job.id,caseId+'_V52_PRO_PREPARE'); assert.equal(job.caseId,caseId); assert.equal(job.version,'52'); assert.equal(job.phase,'EXECUTION');
    assert.equal(job.approvedEnhancementInput,undefined); assert.equal(job.entrypoint,undefined);
    assert.equal(job.approvedModelRequest.model,'deepseek-v4-pro');
    const reverted=structuredClone(job.approvedModelRequest); reverted.model='deepseek-flash'; assert.deepEqual(reverted,original);
    const text=original.messages[1].content, input=JSON.parse(text.slice(text.indexOf('{')));
    assert.equal(input.strategyVersion,'52'); assert.equal(input.modelId,'deepseek:deepseek-flash');
    assert.equal(bundle.cases[index].rawPrompt,input.rawPrompt);
  }
  return {name:NATIVE_PRO52_PROFILE,modelId:'deepseek:deepseek-v4-pro',upstreamModel:'deepseek-v4-pro',routeKey:'deepseek',
    endpoint:'https://api.deepseek.com/chat/completions',baselineStage:p.baselineStage,baselineJobsSha256:p.baselineJobsSha256,
    baselineProtocolSha256:p.baselineProtocolSha256,maximumActualCalls:2};
}
