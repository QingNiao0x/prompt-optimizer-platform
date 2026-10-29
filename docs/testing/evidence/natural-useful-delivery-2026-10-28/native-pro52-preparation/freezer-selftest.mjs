import assert from 'node:assert/strict';
import {copyFile,mkdir,mkdtemp,readFile,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {validateProtocol} from './verify-model-selection.mjs';
const here=dirname(fileURLToPath(import.meta.url)),evidence=resolve(here,'..'),repo=resolve(evidence,'../../../..');
const temp=await mkdtemp(resolve(repo,'tmp/native-pro52-finite-check-'));
async function fixture(name,bundle){
 const root=resolve(temp,name),target=resolve(root,'docs/testing/evidence/fixture');await mkdir(resolve(target,'registry52-plan-completion/traces'),{recursive:true});
 for(const file of['freeze-live.mjs','native-pro52-profile.mjs','writer-thinking-profile.mjs','official-model-selection-profile.mjs'])await copyFile(resolve(evidence,file),resolve(target,file));
 for(const file of['jobs.json','protocol.json','closure.json','traces/001.json','traces/003.json'])await copyFile(resolve(evidence,'registry52-plan-completion',file),resolve(target,'registry52-plan-completion',file));
 await writeFile(resolve(root,'bundle.json'),JSON.stringify(bundle));
 const r=spawnSync(process.execPath,[resolve(target,'freeze-live.mjs'),'offline-fixture','bundle.json'],{cwd:root,encoding:'utf8'});assert.equal(r.status,0,r.stderr);
 return JSON.parse(await readFile(resolve(target,'offline-fixture/protocol.json'),'utf8'));
}
const bundle=JSON.parse(await readFile(resolve(here,'bundle.draft.json'),'utf8'));
const protocol=await fixture('native',bundle);validateProtocol(protocol,bundle.jobs);let checks=3;
for(const mutate of[p=>p.modelId='deepseek:deepseek-flash',p=>p.phaseLimits.EXECUTION=3,p=>p.endpoint='https://example.invalid']){
 const x=structuredClone(protocol);mutate(x);assert.throws(()=>validateProtocol(x,bundle.jobs));checks++;
}
const old=JSON.parse(await readFile(resolve(evidence,'registry51-works-diagnostic-infra/registry51-query-works-bundle.json'),'utf8'));
const legacy=await fixture('legacy-flash',old);assert.equal(legacy.upstreamModel,'deepseek-flash');assert.equal(legacy.modelSelectionProfile,undefined);checks++;
const report={status:'PRIVATE_FIXTURE_ONLY_NOT_FORMAL_FREEZE',actualModelCalls:0,checksPassed:checks,privateTemporaryFixture:temp,oldFlashGuardPreserved:true};
await writeFile(resolve(here,'freezer-offline-validation.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify(report));
