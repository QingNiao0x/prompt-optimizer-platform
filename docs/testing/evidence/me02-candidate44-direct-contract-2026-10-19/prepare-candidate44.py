import pathlib,json,hashlib,shutil,subprocess,datetime,xml.etree.ElementTree as ET
R=pathlib.Path(__file__).resolve().parent
while not (R/'.git').is_dir(): R=R.parent
P=R/'docs/testing/evidence/me02-candidate44-direct-contract-2026-10-19'
O=R/'tmp/me02-candidate43r1-20261019/runner'
V=R/'tmp/me02-candidate44-20261019';N=V/'runner'
P.mkdir(parents=True,exist_ok=True);N.mkdir(parents=True,exist_ok=True)
def sha(b):return hashlib.sha256(b).hexdigest()
def put(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(v if isinstance(v,str) else json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
cases=[{'id':'AI-USAGE','rawPrompt':'请帮我查询全球使用AI最多的职业/行业','planConfirmed':False,'promptFormats':['COMPACT'],'includeExamples':False,'enhancementAllowed':True},
 {'id':'EXPLICIT-OUTPUT','rawPrompt':'请帮我查询全球使用AI最多的职业/行业。\n输出要求：用排名表呈现，列出排名、职业或行业、AI应用场景、统计口径、数据来源和统计时间。不同口径不能直接合并排名，无法可靠确定名次时说明依据与范围。','planConfirmed':False,'promptFormats':['COMPACT'],'includeExamples':False,'enhancementAllowed':True}]
put(P/'request-cases.json',cases)
for name in ['source-pack.md','qoder-reference.md']:shutil.copyfile(R/'docs/testing/evidence/me02-candidate43-concise-output-2026-10-19'/name,P/name)
def adapt(s):
 for a,b in [('Candidate43r1EvaluationServer','Candidate44EvaluationServer'),('ReserveEvaluationCall43r1','ReserveEvaluationCall44'),('ScopePreflight43r1','ScopePreflight44'),('candidate43r1-concise-output','candidate44-direct-contract'),('candidate43r1-source.json','candidate44-source.json'),('candidate43r1','candidate44'),('me02-candidate43r1-20261019','me02-candidate44-20261019'),('me02-candidate43-concise-output-2026-10-19',P.name),('request-cases-r1.json','request-cases.json'),('run-candidate43r1.ps1','run-candidate44.ps1'),('external-calls-r1.mjs','external-calls.mjs')]:s=s.replace(a,b)
 return s
server=adapt((O/'Candidate43r1EvaluationServer.java').read_text('utf-8-sig'))
for a,b in [('asInt() != 481','asInt() != 485'),('asInt() != 411','asInt() != 415'),('batch.path("maximumNewCalls").asInt() != 8','batch.path("maximumNewCalls").asInt() != 5'),('caps.path("PLANNING").asInt() != 1','caps.path("PLANNING").asInt() != 0'),('caps.path("PLATFORM_GENERATION").asInt() != 6','caps.path("PLATFORM_GENERATION").asInt() != 4')]:server=server.replace(a,b)
put(N/'Candidate44EvaluationServer.java',server)
for a,b in [('ReserveEvaluationCall43r1.java','ReserveEvaluationCall44.java'),('ScopePreflight43r1.java','ScopePreflight44.java')]:put(N/b,adapt((O/a).read_text('utf-8-sig')))
flow=adapt((O/'run-flow.mjs').read_text('utf-8-sig')).replace('maximumNewCalls !== 8','maximumNewCalls !== 5').replace('callsBefore !== 481','callsBefore !== 485').replace('files.length >= 489','files.length >= 490')
put(N/'run-flow.mjs',flow)
browser=adapt((O/'account-browser.mjs').read_text('utf-8-sig')).replace('maximumNewCalls !== 8','maximumNewCalls !== 5').replace('callsBefore !== 481','callsBefore !== 485')
put(N/'account-browser.mjs',browser)
runner=adapt((O/'run-live.ps1').read_text('utf-8-sig')).replace('maximumNewCalls -ne 8','maximumNewCalls -ne 5').replace("'AI-USAGE','HANDOVER'","'AI-USAGE','EXPLICIT-OUTPUT'").replace("'scope-preflight-r1.json'","'scope-preflight.json'")
put(P/'run-candidate44.ps1',runner)
external=adapt((O/'external-calls-r1.mjs').read_text('utf-8-sig'))
for a,b in [('b.callsBefore!==481','b.callsBefore!==485'),('b.candidateCallsBefore!==411','b.candidateCallsBefore!==415'),('b.maximumNewCalls!==8','b.maximumNewCalls!==5'),('b.maximumActualCallsAfterBatch!==489','b.maximumActualCallsAfterBatch!==490'),("flow/AI-USAGE","flow/EXPLICIT-OUTPUT")]:external=external.replace(a,b)
external=external.replace('await verifyArchivedRaw();','')
# Keep only execution entry points. The old archived RAW verification is never used as a new baseline.
put(N/'external-calls.mjs',external)
put(P/'tool-adapter-hashes.json',{'bases':[{'path':p.relative_to(R).as_posix(),'sha256':sha(p.read_bytes())} for p in O.iterdir() if p.suffix in ['.java','.mjs','.ps1']],
 'generated':[{'path':p.relative_to(R).as_posix(),'sha256':sha(p.read_bytes())} for p in [*N.iterdir(),P/'run-candidate44.ps1'] if p.is_file()],
 'scope':'Two direct enhancements and at most one execution of the EXPLICIT-OUTPUT native copy. No Plan/reviews/new RAW or Qoder comparison. Original r1 safety checks retained; new exact caps 5/0/4/1/0.'})
sources=[]
for folder in ['services/api/src','apps/web/src','apps/web/e2e']:sources += [p for p in (R/folder).rglob('*') if p.is_file()]
snap=V/'frozen';src=snap/'source';runtime=snap/'classes'
if src.exists() or runtime.exists():raise RuntimeError('Do not overwrite freeze')
entries=[]
for p in sorted(sources):
 q=src/p.relative_to(R);q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q);entries.append({'path':p.relative_to(R).as_posix(),'sha256':sha(p.read_bytes())})
shutil.copytree(R/'services/api/target/classes',runtime)
compiled=[{'path':p.relative_to(runtime).as_posix(),'sha256':sha(p.read_bytes())} for p in sorted(runtime.rglob('*')) if p.is_file()]
put(P/'candidate44-source.json',{'version':'candidate44','baseHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),'frozenAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceSnapshot':src.relative_to(R).as_posix(),'runtimeSnapshot':runtime.relative_to(R).as_posix(),'sourceFiles':entries,'runtimeFiles':compiled})
protocol=json.loads((R/'tmp/me00a-20261012/protocol.json').read_text('utf-8-sig'))
assert protocol['maximumActualModelCalls']==500 and protocol['phaseLimits']=={'baseline':80,'candidateValidation':420}
assert protocol['activeBatch']['version']=='candidate43r1-concise-output' and protocol['activeBatch']['status']=='CLOSED' and protocol['activeBatch']['unusedCalls']==4
rows=[json.loads(p.read_text('utf-8-sig')) for p in (R/'tmp/me00a-20261012/traces').glob('*.json') if p.stem.isdecimal()]
assert len(rows)==485 and sum(r.get('phase')=='candidateValidation' for r in rows)==415
basis='用户持续授权代理选择后续真实评测与需批准决定；本轮明确重分配candidate43r1关闭余4次及candidate41r2-core-acceptance此前扣除转出11次后仍关闭的1次，共5次。正式500/80/420不变；直接增强4、固定复制正文作品1，无Plan/新基线/双评审，不做质量重试。'
protocol['activeBatch']={'version':'candidate44-direct-contract','sourceCandidate':'candidate44','authorizationStatus':'DRAFT_NOT_ACTIVE','authorizationBasis':basis,'authorizationAmendment':45,'callsBefore':485,'candidateCallsBefore':415,'maximumNewCalls':5,'maximumActualCallsAfterBatch':490,'purposeMaximums':{'PLANNING':0,'PLATFORM_GENERATION':4,'EXECUTION':1,'REVIEW':0},'qualityRetriesAllowed':False,'accountCreationAllowed':False,'model':'deepseek-flash','casesFile':'candidate44-cases.json','casesSha256':sha((P/'request-cases.json').read_bytes()),'sourceManifestSha256':sha((P/'candidate44-source.json').read_bytes()),'commonSourcesSha256':sha((P/'source-pack.md').read_bytes()),'qoderReferenceSha256':sha((P/'qoder-reference.md').read_bytes()),'status':'DRAFT'}
put(V/'protocol-draft.json',protocol)
put(P/'protocol-amendment-45-draft.json',{'amendment':45,'authorizationBasis':basis,'formalLimitsUnchanged':[500,80,420],'batch':protocol['activeBatch'],'canonicalLedgerWritten':False})
print(json.dumps({'sourceFiles':len(entries),'runtimeFiles':len(compiled),'modelCalls':0,'canonicalChanged':False}))
