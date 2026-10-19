import pathlib,json,hashlib,shutil,subprocess,datetime
R=pathlib.Path(__file__).resolve().parent
while not (R/'.git').is_dir(): R=R.parent
P=R/'docs/testing/evidence/me02-candidate44-direct-contract-2026-10-19';O=R/'tmp/me02-candidate44-20261019/runner';V=R/'tmp/me02-candidate44r1-20261019';N=V/'runner';N.mkdir(parents=True,exist_ok=True)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def put(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(v if isinstance(v,str) else json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def common(s):
 for a,b in [('Candidate44EvaluationServer','Candidate44r1EvaluationServer'),('ScopePreflight44','ScopePreflight44r1'),('ReserveEvaluationCall44','ReserveEvaluationCall44r1'),('candidate44-direct-contract','candidate44r1-direct-contract'),('candidate44-source.json','candidate44r1-source.json'),('me02-candidate44-20261019','me02-candidate44r1-20261019'),('request-cases.json','request-cases-r1.json'),('run-candidate44.ps1','run-candidate44r1.ps1')]:s=s.replace(a,b)
 return s.replace('me02-candidate44r1-direct-contract-2026-10-19',P.name)
server=common((O/'Candidate44EvaluationServer.java').read_text('utf-8-sig'))
for a,b in [('"candidate44".equals','"candidate44r1".equals'),('batch.path("callsBefore").asInt() != 485','batch.path("callsBefore").asInt() != 489'),('batch.path("candidateCallsBefore").asInt() != 415','batch.path("candidateCallsBefore").asInt() != 419'),('batch.path("maximumNewCalls").asInt() != 5','batch.path("maximumNewCalls").asInt() != 1'),('caps.path("PLATFORM_GENERATION").asInt() != 4','caps.path("PLATFORM_GENERATION").asInt() != 1'),('caps.path("EXECUTION").asInt() != 1','caps.path("EXECUTION").asInt() != 0')]:
 assert a in server,a;server=server.replace(a,b)
put(N/'Candidate44r1EvaluationServer.java',server)
for a,b in [('ScopePreflight44.java','ScopePreflight44r1.java'),('ReserveEvaluationCall44.java','ReserveEvaluationCall44r1.java')]:put(N/b,common((O/a).read_text('utf-8-sig')).replace('"scope-preflight.json"','"scope-preflight-r1.json"'))
flow=common((O/'run-flow.mjs').read_text('utf-8-sig'))
for a,b in [("sourceCandidate !== 'candidate44'","sourceCandidate !== 'candidate44r1'"),('maximumNewCalls !== 5','maximumNewCalls !== 1'),('callsBefore !== 485','callsBefore !== 489')]:
 assert a in flow,a;flow=flow.replace(a,b)
put(N/'run-flow.mjs',flow)
runner=common((P/'run-candidate44.ps1').read_text('utf-8-sig')).replace('maximumNewCalls -ne 5','maximumNewCalls -ne 1').replace("'scope-preflight.json'","'scope-preflight-r1.json'")
put(P/'run-candidate44r1.ps1',runner)
cases=[r for r in json.loads((P/'request-cases.json').read_text('utf-8-sig')) if r['id']=='EXPLICIT-OUTPUT'];assert len(cases)==1;put(P/'request-cases-r1.json',cases)
snap=V/'frozen';assert not snap.exists();entries=[]
for directory in ['services/api/src','apps/web/src','apps/web/e2e']:
 for p in sorted((R/directory).rglob('*')):
  if not p.is_file():continue
  dest=snap/'source'/p.relative_to(R);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest);entries.append({'path':p.relative_to(R).as_posix(),'sha256':sha(p)})
shutil.copytree(R/'services/api/target/classes',snap/'classes');compiled=[{'path':p.relative_to(snap/'classes').as_posix(),'sha256':sha(p)} for p in sorted((snap/'classes').rglob('*')) if p.is_file()]
put(P/'candidate44r1-source.json',{'version':'candidate44r1','baseHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),'frozenAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceSnapshot':(snap/'source').relative_to(R).as_posix(),'runtimeSnapshot':(snap/'classes').relative_to(R).as_posix(),'sourceFiles':entries,'runtimeFiles':compiled})
C=R/'tmp/me00a-20261012/protocol.json';d=json.loads(C.read_text('utf-8-sig'));old=d['activeBatch'];assert old['version']=='candidate44-direct-contract' and old['status']=='CLOSED' and old['unusedCalls']==1
rows=[json.loads(p.read_text('utf-8-sig')) for p in (C.parent/'traces').glob('*.json') if p.stem.isdecimal()];assert len(rows)==489 and sum(r.get('phase')=='candidateValidation' for r in rows)==419
assert d['maximumActualModelCalls']==500 and d['phaseLimits']=={'baseline':80,'candidateValidation':420}
basis='依用户持续授权，将candidate44已关闭余1次明确重分配给本节点修正后的同题直接增强；最多1次实际上游调用，无新作品/Plan/评审，不扩正式500/80/420、不重试到成功。'
b={'version':'candidate44r1-direct-contract','sourceCandidate':'candidate44r1','authorizationStatus':'APPROVED_BY_USER','authorizationBasis':basis,'authorizationAmendment':46,'callsBefore':489,'candidateCallsBefore':419,'maximumNewCalls':1,'maximumActualCallsAfterBatch':490,'purposeMaximums':{'PLANNING':0,'PLATFORM_GENERATION':1,'EXECUTION':0,'REVIEW':0},'qualityRetriesAllowed':False,'accountCreationAllowed':False,'model':'deepseek-flash','casesFile':'candidate44r1-cases.json','casesSha256':sha(P/'request-cases-r1.json'),'sourceManifestSha256':sha(P/'candidate44r1-source.json'),'commonSourcesSha256':sha(P/'source-pack.md'),'qoderReferenceSha256':sha(P/'qoder-reference.md'),'status':'OPEN'}
shutil.copyfile(C,V/'protocol-before.json');shutil.copyfile(P/'request-cases-r1.json',C.parent/b['casesFile']);d['activeBatch']=b;put(C,d)
put(P/'protocol-amendment-46.json',{'authorizationBasis':basis,'formalLimitsUnchanged':[500,80,420],'previousClosedBatch':old,'batch':b,'canonicalLedgerWritten':True})
put(P/'tool-adapter-hashes-r1.json',{'generated':[{'path':p.relative_to(R).as_posix(),'sha256':sha(p)} for p in [*N.iterdir(),P/'run-candidate44r1.ps1'] if p.is_file()],'reuseBrowserSessionNamespace':'prompt-optimizer:candidate44','newAccounts':0,'scope':'One direct enhancement only; prior execution and reviews are not new quality evidence.'})
print(json.dumps({'sourceFiles':len(entries),'runtimeFiles':len(compiled),'active':b['version'],'maximumNewCalls':1,'actualBefore':489}))
