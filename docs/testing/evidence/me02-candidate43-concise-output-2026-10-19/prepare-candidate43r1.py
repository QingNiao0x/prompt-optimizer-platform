"""Thin private adaptation of candidate43 for a bounded code-fix replay.

prepare emits r1 private runners and the two unchanged COMPACT cases.
freeze snapshots current compiled source and creates a DRAFT protocol only.
It never opens the canonical batch, starts services, or calls a model.
"""
import datetime, hashlib, json, pathlib, shutil, subprocess, sys

REPO=pathlib.Path(__file__).resolve()
while not (REPO/'.git').exists():
    if REPO.parent==REPO: raise RuntimeError('Repository required')
    REPO=REPO.parent
PUB=REPO/'docs/testing/evidence/me02-candidate43-concise-output-2026-10-19'
OLD=REPO/'tmp/me02-candidate43-20261019'
PRIVATE=REPO/'tmp/me02-candidate43r1-20261019'
RUNNER=PRIVATE/'runner'
VERSION='candidate43r1-concise-output'
CAPS={'PLANNING':1,'PLATFORM_GENERATION':6,'EXECUTION':1,'REVIEW':0}
def sha(value):return hashlib.sha256(value).hexdigest()
def save(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def rep(value,old,new):
    if old not in value:raise RuntimeError('Source adapter changed: '+old[:90])
    return value.replace(old,new)
def common(value):
    for old,new in [('candidate43-concise-output',VERSION),('candidate43-source.json','candidate43r1-source.json'),
                    ('me02-candidate43-20261019','me02-candidate43r1-20261019'),('Candidate43EvaluationServer','Candidate43r1EvaluationServer'),
                    ('ReserveEvaluationCall43','ReserveEvaluationCall43r1'),('ScopePreflight43','ScopePreflight43r1')]:value=value.replace(old,new)
    return value.replace('me02-candidate43r1-concise-output-2026-10-19',PUB.name)
def verifiedClosedBatch():
    protocol=json.loads((REPO/'tmp/me00a-20261012/protocol.json').read_text('utf-8-sig'))
    rows=[json.loads(p.read_text('utf-8-sig')) for p in (REPO/'tmp/me00a-20261012/traces').glob('*.json') if p.stem.isdecimal()]
    previous=protocol['activeBatch']
    if len(rows)!=481 or sum(r.get('phase')=='candidateValidation' for r in rows)!=411:raise RuntimeError('Ledger changed')
    if protocol['maximumActualModelCalls']!=500 or protocol['phaseLimits']!={'baseline':80,'candidateValidation':420}:raise RuntimeError('Formal caps changed')
    if previous['version']!='candidate43-concise-output' or previous['status']!='CLOSED' or previous['unusedCalls']!=8:raise RuntimeError('Candidate43 CLOSED with eight unused calls required')
    if previous['actualNewCalls']!=16 or previous['maximumNewCalls']!=24:raise RuntimeError('Closed allocation drift')
    return protocol,previous

if sys.argv[1:] not in [['prepare'],['freeze']]:raise SystemExit('prepare-candidate43r1.py prepare|freeze')
RUNNER.mkdir(parents=True,exist_ok=True)
if sys.argv[1]=='prepare':
    cases=[]
    for row in json.loads((PUB/'request-cases.json').read_text('utf-8-sig')):
        if row['id'] in ['AI-USAGE','HANDOVER']:
            row['promptFormats']=['COMPACT'];cases.append(row)
    if len(cases)!=2:raise RuntimeError('Two unchanged source cases required')
    save(PUB/'request-cases-r1.json',cases)
    server=common((OLD/'runner/Candidate43EvaluationServer.java').read_text('utf-8-sig'))
    for old,new in [('"candidate43".equals','"candidate43r1".equals'),('.asInt() != 465','.asInt() != 481'),
                    ('.asInt() != 395','.asInt() != 411'),('.asInt() != 24','.asInt() != 8'),
                    ('caps.path("PLANNING").asInt() != 6','caps.path("PLANNING").asInt() != 1'),
                    ('caps.path("PLATFORM_GENERATION").asInt() != 12','caps.path("PLATFORM_GENERATION").asInt() != 6'),
                    ('caps.path("EXECUTION").asInt() != 4','caps.path("EXECUTION").asInt() != 1'),
                    ('caps.path("REVIEW").asInt() != 2','caps.path("REVIEW").asInt() != 0'),
                    ('Set.of("HANDOVER", "COURSE-CHOICE")','Set.of("HANDOVER")'),
                    ('prompt-optimizer:candidate43','prompt-optimizer:candidate43r1')]:server=rep(server,old,new)
    save(RUNNER/'Candidate43r1EvaluationServer.java',server.replace('只有本批两项Plan任务','只有本批HANDOVER任务'))
    for old,new in [('ReserveEvaluationCall43.java','ReserveEvaluationCall43r1.java'),('ScopePreflight43.java','ScopePreflight43r1.java')]:save(RUNNER/new,common((OLD/'runner'/old).read_text('utf-8-sig')))
    flow=common((OLD/'runner/run-flow.mjs').read_text('utf-8-sig'))
    for old,new in [("batch.sourceCandidate !== 'candidate43'","batch.sourceCandidate !== 'candidate43r1'"),
                    ('batch.maximumNewCalls !== 24','batch.maximumNewCalls !== 8'),('batch.callsBefore !== 465','batch.callsBefore !== 481'),
                    ("'request-cases.json'","'request-cases-r1.json'")]:flow=rep(flow,old,new)
    save(RUNNER/'run-flow.mjs',flow)
    browser=common((OLD/'runner/account-browser.mjs').read_text('utf-8-sig'))
    browser=rep(rep(browser,'batch.callsBefore !== 465','batch.callsBefore !== 481'),'batch.maximumNewCalls !== 24','batch.maximumNewCalls !== 8')
    save(RUNNER/'account-browser.mjs',browser)
    runner=common((PUB/'run-candidate43.ps1').read_text('utf-8-sig'))
    for old,new in [("$evaluationRepo=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path","$evaluationRepo='"+REPO.as_posix()+"'"),
                    ('$evaluationPublic=$PSScriptRoot',"$evaluationPublic='"+PUB.as_posix()+"'"),
                    ('maximumNewCalls -ne 24','maximumNewCalls -ne 8'),("'AI-USAGE','HANDOVER','COURSE-CHOICE'","'AI-USAGE','HANDOVER'"),
                    ("'COMPACT','STRUCTURED'","'COMPACT'"),("'request-cases.json'","'request-cases-r1.json'"),
                    ("'scope-preflight.json'","'scope-preflight-r1.json'"),("'prepare-works','prepare-reviews','work','review'","'prepare-works','work'"),
                    ("'RAW','COMPACT','QODER_REFERENCE'","'COMPACT'"),
                    ("'external-calls.mjs'","'external-calls-r1.mjs'"),("Join-Path $evaluationPublic 'external-calls-r1.mjs'","Join-Path $evaluationRunner 'external-calls-r1.mjs'"),
                    ("'run-candidate43.ps1'","'run-candidate43r1.ps1'")]:runner=rep(runner,old,new)
    save(RUNNER/'run-live.ps1',runner)
    external=common((PUB/'external-calls.mjs').read_text('utf-8-sig'))
    for old,new in [("b.sourceCandidate!=='candidate43'","b.sourceCandidate!=='candidate43r1'"),('b.callsBefore!==465','b.callsBefore!==481'),
                    ('b.candidateCallsBefore!==395','b.candidateCallsBefore!==411'),('b.maximumNewCalls!==24','b.maximumNewCalls!==8'),
                    ('b.purposeMaximums.EXECUTION!==4','b.purposeMaximums.EXECUTION!==1'),('b.purposeMaximums.REVIEW!==2','b.purposeMaximums.REVIEW!==0'),
                    ("'request-cases.json'","'request-cases-r1.json'"),
                    ("return {RAW:raw,COMPACT:await copy('COMPACT'),STRUCTURED:await copy('STRUCTURED'),QODER_REFERENCE:await readFile(resolve(pub,'qoder-reference.md'),'utf8')};","return {COMPACT:await copy('COMPACT')};"),
                    ("sourceCandidate:'candidate43'","sourceCandidate:'candidate43r1'"),('preparedWorks:4','preparedWorks:1')]:external=rep(external,old,new)
    # Runtime adapter lives in tmp; use the immutable repository evaluation helper by absolute file URL.
    external=rep(external,"../../../../scripts/me00a-eval.mjs",(REPO/'scripts/me00a-eval.mjs').as_uri())
    marker="const[operation,id]=process.argv.slice(2);"
    external=rep(external,marker,marker+"\nif(!['prepare-works','work'].includes(operation))throw Error('No reviews or extra works authorized in r1');")
    external=rep(external,"await preflight();const values=await prompts(),sources=await readFile(resolve(pub,'source-pack.md'),'utf8'),jobs=[];",
        "await preflight();await verifyArchivedRaw();const values=await prompts(),sources=await readFile(resolve(pub,'source-pack.md'),'utf8'),jobs=[];")
    external=rep(external,"await preflight();const dir=resolve(root,purpose==='EXECUTION'?'works':'reviews',id);",
        "await preflight();await verifyArchivedRaw();const dir=resolve(root,purpose==='EXECUTION'?'works':'reviews',id);")
    external += "\nasync function verifyArchivedRaw(){const baseline=await json(resolve(pub,'r1-raw-reference-lock.json'));for(const item of baseline.files)if(hash(await readFile(item.path))!==item.sha256)throw Error('Archived RAW reference changed');const request=await json(baseline.requestPath);if(request.messages.length!==2||request.messages[0].content!=='请帮我查询全球使用AI最多的职业/行业'||request.messages[1].content!==await readFile(resolve(pub,'source-pack.md'),'utf8'))throw Error('Archived RAW uses different source information');}\n"
    save(RUNNER/'external-calls-r1.mjs',external)
    raw=OLD/'works/RAW'
    files=[raw/'request.json',raw/'work.md',raw/'response.json',PUB/'source-pack.md']
    save(PUB/'r1-raw-reference-lock.json',{'requestPath':(raw/'request.json').relative_to(REPO).as_posix(),'files':[{'path':p.relative_to(REPO).as_posix(),'sha256':sha(p.read_bytes())} for p in files],
        'newRawCalls':0,'newReviews':0,'comparisonLimit':'修复后自然正文单份作品与既有同资料RAW核对；没有新三臂或双评审，不关闭整体质量门禁。'})
    save(PUB/'tool-adapter-hashes-r1.json',{'bases':[{'path':p.relative_to(REPO).as_posix(),'sha256':sha(p.read_bytes())} for p in [
        OLD/'runner/Candidate43EvaluationServer.java',OLD/'runner/ReserveEvaluationCall43.java',OLD/'runner/ScopePreflight43.java',OLD/'runner/run-flow.mjs',OLD/'runner/account-browser.mjs',PUB/'run-candidate43.ps1',PUB/'external-calls.mjs']],
        'generated':[{'path':p.relative_to(REPO).as_posix(),'sha256':sha(p.read_bytes())} for p in RUNNER.iterdir() if p.suffix in ['.java','.mjs','.ps1']]})
    print(json.dumps({'prepared':str(RUNNER),'modelCalls':0,'canonicalChanged':False}))
else:
    protocol,previous=verifiedClosedBatch()
    snap=PRIVATE/'frozen';src=snap/'source';runtime=snap/'classes'
    if src.exists() or runtime.exists():raise RuntimeError('Frozen snapshots exist; no overwrite')
    sources=[]
    for directory in ['services/api/src','apps/web/src','apps/web/e2e']:sources += [p for p in (REPO/directory).rglob('*') if p.is_file()]
    sources += [REPO/'scripts/me00a-execution-claims.mjs',REPO/'scripts/me00a-execution-claims.test.mjs']
    entries=[]
    for p in sorted(set(sources)):
        relative=p.relative_to(REPO);target=src/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,target)
        entries.append({'path':relative.as_posix(),'sha256':sha(p.read_bytes())})
    shutil.copytree(REPO/'services/api/target/classes',runtime)
    compiled=[{'path':p.relative_to(runtime).as_posix(),'sha256':sha(p.read_bytes())} for p in sorted(runtime.rglob('*')) if p.is_file()]
    save(PUB/'candidate43r1-source.json',{'version':'candidate43r1','baseHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
        'frozenAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'parentCandidate':'candidate43','parentManifestSha256':sha((PUB/'candidate43-source.json').read_bytes()),
        'sourceSnapshot':src.relative_to(REPO).as_posix(),'runtimeSnapshot':runtime.relative_to(REPO).as_posix(),'sourceFiles':entries,'runtimeFiles':compiled})
    basis='用户本轮授权代理自行决定需批准事项；明确将candidate43关闭未用8次重分配给candidate43r1：Plan1、增强6、作品1、评审0；同题代码修复复验，不做作品质量重试，正式500/80/420不变。'
    protocol['activeBatch']={'version':VERSION,'sourceCandidate':'candidate43r1','authorizationStatus':'DRAFT_NOT_ACTIVE','authorizationBasis':basis,'authorizationAmendment':44,
        'callsBefore':481,'candidateCallsBefore':411,'maximumNewCalls':8,'maximumActualCallsAfterBatch':489,'purposeMaximums':CAPS,
        'qualityRetriesAllowed':False,'accountCreationAllowed':False,'model':'deepseek-flash','casesFile':'candidate43r1-cases.json',
        'casesSha256':sha((PUB/'request-cases-r1.json').read_bytes()),'sourceManifestSha256':sha((PUB/'candidate43r1-source.json').read_bytes()),
        'commonSourcesSha256':sha((PUB/'source-pack.md').read_bytes()),'qoderReferenceSha256':sha((PUB/'qoder-reference.md').read_bytes()),'status':'DRAFT'}
    save(PUB/'protocol-amendment-44-draft.json',{'amendment':44,'authorizationBasis':basis,'formalLimitsUnchanged':[500,80,420],
        'closedBalanceSources':[{'version':previous['version'],'unusedCalls':previous['unusedCalls'],'transfer':8,'stillClosed':0}],
        'previousClosedBatch':previous,'actualBefore':481,'maximumActualAfter':489,'batch':protocol['activeBatch'],'canonicalLedgerWritten':False})
    save(PRIVATE/'protocol-draft.json',protocol)
    print(json.dumps({'sourceFiles':len(entries),'runtimeFiles':len(compiled),'protocolDraft':str(PRIVATE/'protocol-draft.json'),'modelCalls':0,'canonicalChanged':False}))
