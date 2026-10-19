import pathlib,json,hashlib,collections,datetime,xml.etree.ElementTree as ET,shutil,re
R=pathlib.Path(__file__).resolve().parent
while not (R/'.git').is_dir(): R=R.parent
P=R/'docs/testing/evidence/me02-candidate44-direct-contract-2026-10-19';C=R/'tmp/me00a-20261012/protocol.json'
def sha(b):return hashlib.sha256(b).hexdigest()
def put(path,value):
 path.parent.mkdir(parents=True,exist_ok=True)
 data=value if isinstance(value,bytes) else (value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
 path.write_bytes(data)
def load(path):return json.loads(path.read_text('utf-8-sig'))
d=load(C);b=d['activeBatch'];assert b['version']=='candidate44r1-direct-contract' and b['status']=='OPEN'
rows=[(p,load(p)) for p in (C.parent/'traces').glob('*.json') if p.stem.isdecimal()]
assert len(rows)==490 and sum(r.get('phase')=='candidateValidation' for p,r in rows)==420
latest=[r for p,r in rows if r.get('version')==b['version']];assert len(latest)==1
b.update(status='CLOSED',actualNewCalls=1,unusedCalls=0,actualCallsAfterBatch=490,actualPurposeCounts={'PLATFORM_GENERATION':1},closedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),closureReason='最终直接增强首次成功，输出原句只在OUTPUT呈现一次，原任务未投影为约束，复制历史一致。候选阶段420额度已达上限，不再新增实际请求。最新版本无新作品或双评审，不关闭整体质量门禁。')
put(C,d);put(P/'batch-close-r1.json',b)
publicFiles=[];calls=[];caseData={r['id']:r for r in load(P/'request-cases.json')}
for p,t in sorted(rows,key=lambda row:int(row[0].stem)):
 if t.get('version') not in ['candidate44-direct-contract','candidate44r1-direct-contract']:continue
 response=t.get('response',{});entry={k:t[k] for k in ['purpose','caseId','version','phase','requestId','startedAt','httpStatus','elapsedMs','failure'] if k in t};entry['response']=response
 dest=P/'model-responses'/(p.stem+'.json');put(dest,entry)
 content=response.get('choices',[{}])[0].get('message',{}).get('content','');put(P/'model-responses'/(p.stem+'-content.txt'),content)
 scope={'trace':p.stem,'purpose':t['purpose'],'version':t['version'],'model':t.get('request',{}).get('model'),'temperature':t.get('request',{}).get('temperature'),'max_tokens':t.get('request',{}).get('max_tokens')}
 if t['purpose']=='PLATFORM_GENERATION':
  message=next(m['content'] for m in t['request']['messages'] if m['role']=='user' and '{' in m['content']);payload=json.loads(message[message.index('{'):]);scope.update(rawPrompt=payload['rawPrompt'],rawPromptMatches=payload['rawPrompt']==caseData[t['caseId']]['rawPrompt'],requirementReferences=payload.get('requirementReferences',[]),options=payload['options'],planConfirmed=payload['planConfirmed']);assert scope['rawPromptMatches'] and not scope['planConfirmed']
 put(P/'request-scope'/(p.stem+'.json'),scope)
 calls.append({'trace':int(p.stem),'version':t['version'],'purpose':t['purpose'],'caseId':t.get('caseId'),'httpStatus':t.get('httpStatus'),'elapsedMs':t.get('elapsedMs'),'usage':response.get('usage',{})})
flows=[]
for name,folder,ids in [('candidate44','me02-candidate44-20261019',['AI-USAGE','EXPLICIT-OUTPUT']),('candidate44r1','me02-candidate44r1-20261019',['EXPLICIT-OUTPUT'])]:
 root=R/'tmp'/folder
 for id in ids:
  source=root/'flow'/id/'COMPACT';platform=load(source/'platform.private.json')['body']['data'];history=load(source/'history.private.json')['body']['data'];copy=(source/'native-copy.md').read_bytes()
  assert copy.decode('utf-8').replace('\r\n','\n')==platform['optimizedPrompt'].replace('\r\n','\n')
  for key in ['optimizedPrompt','sections','promptFormat','ambiguities','appliedConstraints','constraintGroups']:assert platform[key]==history[key],key
  out=P/'flows'/name/id;put(out/'native-copy.md',copy)
  allowed=['optimizedPrompt','sections','promptFormat','ambiguities','appliedConstraints','constraintGroups','warnings']
  put(out/'assembled.json',{k:platform[k] for k in allowed if k in platform});put(out/'history.json',{k:history[k] for k in ['rawPrompt',*allowed] if k in history});put(out/'check.json',load(source/'check.json'))
  constraints='\n'.join(s['content'] for s in platform['sections'] if s['type']=='CONSTRAINTS');output='\n'.join(s['content'] for s in platform['sections'] if s['type']=='OUTPUT')
  checks={'version':name,'caseId':id,'nativeCopyMatches':True,'historyMatches':True,'rawTaskInConstraints':'请帮我查询全球使用AI最多的职业/行业' in constraints,'outputRankingDirectiveCount':output.count('用排名表呈现'),'requiredFieldsPresent':all(word in output for word in ['排名','职业或行业','AI应用场景','统计口径','数据来源','统计时间']) if id=='EXPLICIT-OUTPUT' else None,'copySha256':sha(copy)}
  flows.append(checks);put(out/'content-check.json',checks)
 # Capture only preparation failures; no identities or full platform requests.
 failure=root/'flow/AI-USAGE/direct-COMPACT-failure.json'
 if failure.exists():
  detail=load(failure);put(P/'tool-preflight-failure.json',{'stage':'before authentication was ready','reason':detail.get('reason'),'modelCalls':0,'resolved':'same account authenticated normally after captcha; no replacement account'})
root=R/'tmp/me02-candidate44-20261019';work=(root/'works/COMPACT/work.md').read_bytes();put(P/'work/initial-copy-work.md',work)
request=load(root/'works/COMPACT/request.json');copy=(root/'flow/EXPLICIT-OUTPUT/COMPACT/native-copy.md').read_bytes().decode('utf-8')
assert request['messages'][0]['content']==copy and request['messages'][1]['content']==(P/'source-pack.md').read_bytes().decode('utf-8')
put(P/'work/input-lock.json',{'model':request['model'],'parameters':{k:request[k] for k in ['temperature','max_tokens','thinking','stream']},'promptSha256':sha(copy.encode()),'sourcesSha256':sha(request['messages'][1]['content'].encode()),'nativeCopyExecutedWithoutPatch':True,'sourceVersion':'candidate44','newRawBaseline':False,'newReviewers':0,'appliesToFinalCandidate':False})
text=work.decode('utf-8');quote='| 3 | 办公行政支持 | 邮件、文档、客户关系与排程 | API记录占13%'
assert quote in text
put(P/'work/hard-checks.json',{'method':'independent column presence and agent source/denominator review, not human expert review','rankingTablePresent':True,'requiredColumnHeadingsPresent':all(s in text for s in ['排名','职业或行业','AI应用场景','统计口径','数据来源','统计时间']),'severeFailure':{'quote':quote,'reason':'One table assigns ordinal 2 from Claude.ai 15% and ordinal 3 from API13%; product/sample denominators differ despite caveat. The copied prompt already prohibits combining incompatible metrics.'},'attribution':'downstream execution breach; no proof that prompt redundancy is the sole cause','qualityGate':'FAIL','newFinalVersionWorks':0,'newIndependentReviews':0})
names='DirectEnhancementRequirementTest ExplicitOutputRequirementsTest ExecutionRuleCompactorOwnershipTest RequirementFidelityGuardTest SourceOwnershipProjectionTest RequirementReferenceRecoveryTest OptionalSectionAssemblyTest OptimizationResultAssemblerTest OpenAiCompatiblePromptEnhancementProviderTest OpenAiCompatibleValidatedResponseTest RequirementBoundaryProviderTest PlanQuestionFilterTest PlanScopeSafetyRegressionTest EffectiveTaskViewTest DynamicPlatformConstraintTest TemplateGuidanceBoundaryTest ReportDraftDeliveryAssemblyTest MybatisOptimizationHistoryServiceTest PromptFrameworkGenerationContractTest PlatformConstraintPresentationTest'.split();suites=[]
for name in names:
 p=next((R/'services/api/target/surefire-reports').glob('TEST-*.'+name+'.xml'));x=ET.parse(p).getroot();suites.append({'name':name,**{k:int(x.attrib[k]) for k in ['tests','failures','errors','skipped']},'xmlSha256':sha(p.read_bytes())})
assert not sum(t[k] for t in suites for k in ['failures','errors','skipped'])
totals={k:sum(t[k] for t in suites) for k in ['tests','failures','errors','skipped']}
put(P/'verification.json',{'productHead':'435dacc9','java':{'selectedSuites':len(suites),**totals,'suites':suites},'initialRed':{'tests':17,'failures':8,'errors':0,'skipped':0,'logSha256':sha((R/'tmp/direct-enhancement-red.log').read_bytes())},'replay488RedThenGreen':True,'flows':flows,'actualNewCalls':5,'actualTotal':490,'baseline':70,'candidateValidation':420,'formalLimits':[500,80,420],'newAccounts':0,'latestVersionHasNoNewWorkOrReview':True,'allHistoricalFailuresRemain':True})
put(P/'calls.json',{'calls':calls,'totalTokens':sum(c['usage'].get('total_tokens',0) for c in calls),'maximumCalls':5,'actualCalls':5,'qualityRetries':0,'priceAndCurrency':'UNKNOWN'})
for filename in ['tool-adapter-hashes.json','tool-adapter-hashes-r1.json']:
 data=load(P/filename)
 for row in data['generated']:row['sha256']=sha((R/row['path']).read_bytes())
 put(P/filename,data)
print(json.dumps({'tests':totals,'successfulFlows':len(flows),'actualCalls':len(calls),'total':490,'candidate':420,'finalCheck':flows[-1]},ensure_ascii=False))
