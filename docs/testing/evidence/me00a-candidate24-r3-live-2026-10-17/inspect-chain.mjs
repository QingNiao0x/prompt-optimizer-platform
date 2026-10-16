// Model-free inspection of saved artifacts; observations do not certify professional validity.
import {readFile,writeFile,readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
const pub=resolve('docs/testing/evidence/me00a-candidate24-r3-live-2026-10-17');
const load=async p=>JSON.parse(await readFile(p,'utf8'));
const sha=b=>createHash('sha256').update(b).digest('hex');
const freeze=await load('docs/testing/evidence/me00a-candidate24-offline-2026-10-17/candidate24-source-r3.json');
for(const file of freeze.sourceFiles)if(sha(await readFile(file.path))!==file.sha256)throw new Error('Source drift');
for(const file of freeze.runtimeFiles)if(sha(await readFile(resolve(freeze.runtimeSnapshot,file.path)))!==file.sha256)throw new Error('Runtime drift');
const tools=await load(resolve(pub,'tools-frozen.json'));
for(const file of tools.files)if(sha(await readFile(file.path))!==file.sha256)throw new Error('Frozen execution tool drift');
const trace=await load(resolve(pub,'real/traces/325.json'));
const model=JSON.parse(trace.response.choices[0].message.content);
const platform=(await load(resolve(pub,'real/Q05-plan/platform.json'))).body.data;
const history=await load(resolve(pub,'real/Q05-plan/history-projection.json'));
const clipboard=await readFile(resolve(pub,'real/Q05-plan/execution-input.md'),'utf8');
const lock=await load(resolve(pub,'real/Q05-plan/clipboard-lock.json'));
const plan=(await load(resolve(pub,'real/Q05-plan/plan-platform.json'))).body.data;
const cases=await load(resolve(pub,'cases.json'));
const protocol=await load(resolve(pub,'protocol-approved.json'));
const taskMessage=trace.request.messages.find(m=>m.role==='user'&&m.content.includes('"taskView"'));
const payload=JSON.parse(taskMessage.content.slice(taskMessage.content.indexOf('{')));
const clipboardMatches=clipboard.replace(/\r\n/g,'\n')===platform.optimizedPrompt.replace(/\r\n/g,'\n');
if(!clipboardMatches||sha(clipboard)!==lock.sha256||history.optimizedPrompt!==platform.optimizedPrompt
  ||JSON.stringify(history.sections)!==JSON.stringify(platform.sections))throw new Error('Copy/history drift');
if(JSON.stringify(payload.taskView)!==JSON.stringify(protocol.activeBatch.expectedTaskView))throw new Error('Actual confirmation projection changed');
if(plan.provider.provider!=='CAPTURED_ACTUAL_321_REPLAY'||plan.provider.mock!==true)throw new Error('Unmarked captured planning');
if(cases[0].planAnswers.length!==5||plan.questions.length!==5)throw new Error('Question count changed');
const inputChecks=[];
for(const [number,id]of [[326,'Q05-plan-pro'],[327,'Q05-plan']]){
  const saved=await load(resolve(pub,`real/traces/${number}.json`));
  const actual=await readFile(resolve(pub,`real/${id}/enhanced-actual-execution-input.md`),'utf8');
  if(saved.request.messages.length!==1||saved.request.messages[0].content!==clipboard||actual!==clipboard)throw new Error('Downstream input changed');
  const work=await readFile(resolve(pub,`real/${id}/enhanced-work.md`),'utf8');
  if(saved.response.choices[0].message.content!==work)throw new Error('Model work rewritten');
  inputChecks.push({trace:number,id,exactNativeClipboard:true,noAdditionalAnswers:true,inputSha256:sha(actual),workSha256:sha(work),finishReason:saved.response.choices[0].finish_reason});
}
const section=(items,type)=>items.find(s=>s.type===type)?.content??'';
const occurrences=(text,value)=>text.split(value).length-1;
const taskModel=section(model.sections,'TASK'),taskFinal=section(platform.sections,'TASK');
const rules=platform.appliedConstraints.filter(v=>v!=='明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。');
const rulesObserved=rules.map(rule=>({rule,modelConstraintOccurrences:occurrences(section(model.sections,'CONSTRAINTS'),rule),finalConstraintOccurrences:occurrences(section(platform.sections,'CONSTRAINTS'),rule)}));
const delegation='请根据原始研究目标提出可开展的推荐设计并说明适用条件';
const result={kind:'SAVED_CHAIN_DETERMINISTIC_OBSERVATIONS',modelCalls:0,
  freeze:{sourceFilesVerified:freeze.sourceFiles.length,runtimeFilesVerified:freeze.runtimeFiles.length,sourceTreeSha256:freeze.sourceTreeSha256,runtimeTreeSha256:freeze.runtimeTreeSha256,executionToolsVerified:tools.files.length},
  chain:{realEnhancementMock:platform.provider.mock,firstAttemptOnly:true,historyPromptExact:true,historySectionsExact:true,nativeClipboardMatchesApiExceptCrLf:true,apiSha256:sha(platform.optimizedPrompt),nativeClipboardSha256:sha(clipboard),apiCharacters:[...platform.optimizedPrompt].length,nativeClipboardCharacters:[...clipboard].length,actualTaskViewMatchesFrozen:true,actualPendingDecisions:payload.taskView.pendingDecisions?.length,apiAmbiguities:platform.ambiguities.length,allAppliedConstraints:platform.appliedConstraints,capturedPlanningOnly:true,newNaturalPlanCalls:0,actualQuestions:5,actualBoundAnswers:5,downstreamInputs:inputChecks},
  rulesObserved,
  freshFixCoverage:'325 uses separate bullet lines for the two rules; this run preserves one occurrence each but does not exercise 322 shared-paragraph splitting. The frozen offline 322 replay remains the direct branch evidence.',
  remainingProjection:{genericDelegationFragmentInModelTask:occurrences(taskModel,delegation),genericDelegationFragmentInFinalTask:occurrences(taskFinal,delegation),addedDelegatedCategoryHeader:taskFinal.includes('对以下已委托设计的子项给出拟议主方案及适用条件'),role:'platform projection, assess complete scopes before changing coverage'},
  evaluationScope:{newAiReviews:0,newThreeArmComparison:false,newRawControl:false,causalNetQualityOrWinRateClaim:false,professionalValidity:'Not certified; bounded artifact inspection only',appendixRule:'Original says 附表另计; neither that phrase alone nor absence of Markdown tables proves a downstream violation'}};
await writeFile(resolve(pub,'chain-checks.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx'});
const names=(await readdir('tmp/me00a-20261012/traces')).filter(n=>/^\d+\.json$/.test(n));
console.log(JSON.stringify({chainPass:true,sourceVerified:result.freeze.sourceFilesVerified,runtimeVerified:result.freeze.runtimeFilesVerified,executionToolsVerified:result.freeze.executionToolsVerified,actualLedgerCount:names.length,rulesObserved,remainingProjection:result.remainingProjection}));
