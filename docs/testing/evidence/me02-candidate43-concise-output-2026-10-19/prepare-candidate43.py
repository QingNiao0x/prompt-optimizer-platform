"""Reuse the audited candidate42 runner, emitting only private runtime adapters.

prepare: stage public input/tool files, adapt old harness into private runner; no calls.
freeze: snapshot current source/classes and write a DRAFT protocol outside canonical ledger.
Neither command starts services, changes account data, or opens the formal batch.
"""
import hashlib, json, pathlib, shutil, subprocess, sys, datetime

REPO=pathlib.Path(__file__).resolve()
while not (REPO/'.git').exists():
    if REPO.parent==REPO: raise RuntimeError('Repository required')
    REPO=REPO.parent
PREP=REPO/'tmp/eval43'
PUB=REPO/'docs/testing/evidence/me02-candidate43-concise-output-2026-10-19'
PRIVATE=REPO/'tmp/me02-candidate43-20261019'
RUNNER=PRIVATE/'runner'
BASE=REPO/'docs/testing/evidence/me02-candidate42-source-ownership-2026-10-19'
VERSION='candidate43-concise-output'
CAPS={'PLANNING':6,'PLATFORM_GENERATION':12,'EXECUTION':4,'REVIEW':2}

def digest(data): return hashlib.sha256(data).hexdigest()
def save(path, value):
    path.parent.mkdir(parents=True,exist_ok=True)
    data=value.encode('utf-8') if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2).encode('utf-8')+b'\n'
    path.write_bytes(data)
def replace(text,old,new):
    if old not in text: raise RuntimeError('Adapter source changed: '+old[:100])
    return text.replace(old,new)
def adapt(name):
    text=(BASE/name).read_text('utf-8-sig')
    for old,new in [('candidate42r2-source-ownership',VERSION),('candidate42r2','candidate43'),
                    ('Candidate42r2EvaluationServer','Candidate43EvaluationServer'),('ReserveEvaluationCallR2','ReserveEvaluationCall43'),
                    ('ScopePreflightR2','ScopePreflight43'),('me02-candidate42-source-ownership-2026-10-19',PUB.name)]:
        text=text.replace(old,new)
    return text
def draft():
    original=json.loads((REPO/'tmp/me00a-20261012/protocol.json').read_text('utf-8-sig'))
    traces=[p for p in (REPO/'tmp/me00a-20261012/traces').glob('*.json') if p.stem.isdecimal()]
    rows=[json.loads(p.read_text('utf-8-sig')) for p in traces]
    if len(rows)!=465 or sum(r.get('phase')=='candidateValidation' for r in rows)!=395: raise RuntimeError('Ledger changed; re-evaluate allocation')
    if original['maximumActualModelCalls']!=500 or original['phaseLimits']!={'baseline':80,'candidateValidation':420}: raise RuntimeError('Formal caps changed')
    if original['activeBatch']['status']!='CLOSED': raise RuntimeError('Previous batch must be closed')
    if 465+sum(CAPS.values())>500 or 395+sum(CAPS.values())>420: raise RuntimeError('Phase or total ceiling exceeded')
    original['activeBatch']={
        'version':VERSION,'sourceCandidate':'candidate43','authorizationStatus':'DRAFT_NOT_ACTIVE',
        'authorizationBasis':'用户已授权本轮增强与Plan修复并由代理自行决定需批准事项；代理明确拟将candidate42r2关闭且扣除r3后余11次、candidate42r3关闭余2次、candidate41r2-core-acceptance关闭余12次中的11次重分配至本批24次，后者1次仍关闭；正式500/80/420不变。',
        'authorizationAmendment':43,'callsBefore':465,'candidateCallsBefore':395,'maximumNewCalls':24,
        'maximumActualCallsAfterBatch':489,'purposeMaximums':CAPS,'qualityRetriesAllowed':False,
        'accountCreationAllowed':False,'model':'deepseek-flash','casesFile':'candidate43-cases.json',
        'casesSha256':digest((PUB/'request-cases.json').read_bytes()),
        'sourceManifestSha256':digest((PUB/'candidate43-source.json').read_bytes()),
        'commonSourcesSha256':digest((PUB/'source-pack.md').read_bytes()),
        'qoderReferenceSha256':digest((PUB/'qoder-reference.md').read_bytes()),'status':'DRAFT'}
    save(PUB/'protocol-amendment-43-draft.json',{'formalLimitsUnchanged':{'total':500,'baseline':80,'candidateValidation':420},
        'actualBefore':{'total':465,'baseline':70,'candidateValidation':395},'maximumActualAfter':{'total':489,'candidateValidation':419},
        'activeBatch':original['activeBatch'],'closedBalancesTransferred':24,
        'closedBalanceSources':[
            {'version':'candidate42r2-source-ownership','closed':17,'previouslyTransferredToR3':6,'available':11,'transfer':11,'stillClosed':0},
            {'version':'candidate42r3-source-ownership','closed':2,'transfer':2,'stillClosed':0},
            {'version':'candidate41r2-core-acceptance','closed':12,'transfer':11,'stillClosed':1}],
        'sourceEvidence':[
            {'path':p.relative_to(REPO).as_posix(),'sha256':digest(p.read_bytes())} for p in [
                BASE/'batch-close-r2.json',BASE/'protocol-amendment-41.json',BASE/'batch-close-r3.json',
                REPO/'docs/testing/evidence/me01-plan-core-acceptance-2026-10-19/batch-close-summary.json',
                REPO/'docs/testing/evidence/me01-plan-core-acceptance-2026-10-19/protocol-amendment-38.json']],
        'canonicalLedgerWritten':False})
    save(PRIVATE/'protocol-draft.json',original)

if sys.argv[1:] not in [['prepare'],['freeze']]: raise SystemExit('prepare-candidate43.py prepare|freeze')
PUB.mkdir(parents=True,exist_ok=True);RUNNER.mkdir(parents=True,exist_ok=True)
if sys.argv[1]=='prepare':
    for filename in ['request-cases.json','source-pack.md','qoder-reference.md','external-calls.mjs','run-candidate43.ps1','prepare-candidate43.py']:
        shutil.copyfile(PREP/filename,PUB/filename)
    server=adapt('Candidate42r2EvaluationServer.java')
    for old,new in [('.asInt() != 442','.asInt() != 465'),('.asInt() != 372','.asInt() != 395'),('.asInt() != 36','.asInt() != 24'),
                    ('caps.path("PLANNING").asInt() != 5','caps.path("PLANNING").asInt() != 6'),
                    ('caps.path("PLATFORM_GENERATION").asInt() != 23','caps.path("PLATFORM_GENERATION").asInt() != 12'),
                    ('caps.path("EXECUTION").asInt() != 6','caps.path("EXECUTION").asInt() != 4'),
                    ('Set.of("LOG-SUMMARY", "RESERVED-REFUND")','Set.of("HANDOVER", "COURSE-CHOICE")'),
                    ('prompt-optimizer:candidate42','prompt-optimizer:candidate43')]: server=replace(server,old,new)
    server=server.replace('只有本批三题','只有本批两项Plan任务')
    save(RUNNER/'Candidate43EvaluationServer.java',server)
    save(RUNNER/'ReserveEvaluationCall43.java',adapt('ReserveEvaluationCallR2.java'))
    save(RUNNER/'ScopePreflight43.java',adapt('ScopePreflightR2.java'))
    flow=adapt('run-flow-r2.mjs')
    for old,new in [('batch.maximumNewCalls !== 36','batch.maximumNewCalls !== 24'),('batch.callsBefore !== 442','batch.callsBefore !== 465'),
                    ('files.length >= 478','files.length >= 489')]: flow=replace(flow,old,new)
    old="../me01-candidate41r2-responsibility-projection-2026-10-18/semantic-json.mjs"
    flow=replace(flow,old,(REPO/'docs/testing/evidence/me01-candidate41r2-responsibility-projection-2026-10-18/semantic-json.mjs').as_uri())
    save(RUNNER/'run-flow.mjs',flow)
    browser=(BASE/'account-browser.mjs').read_text('utf-8-sig')
    for old,new in [('candidate42-source-ownership',VERSION),('batch.callsBefore !== 440','batch.callsBefore !== 465'),
                    ('batch.maximumNewCalls !== 38','batch.maximumNewCalls !== 24'),('me02-candidate42-20261019','me02-candidate43-20261019')]: browser=replace(browser,old,new)
    save(RUNNER/'account-browser.mjs',browser)
    save(PUB/'tool-adapter-hashes.json',{'reusedSources':[{'path':p.relative_to(REPO).as_posix(),'sha256':digest(p.read_bytes())} for p in [BASE/n for n in ['Candidate42r2EvaluationServer.java','ReserveEvaluationCallR2.java','ScopePreflightR2.java','run-flow-r2.mjs','account-browser.mjs']]],'generatedPrivate':[{'path':p.relative_to(REPO).as_posix(),'sha256':digest(p.read_bytes())} for p in RUNNER.iterdir() if p.suffix in ['.java','.mjs']]})
    print(json.dumps({'prepared':str(PUB),'modelCalls':0,'canonicalChanged':False}))
else:
    snap=PRIVATE/'frozen';src=snap/'source';runtime=snap/'classes'
    if src.exists() or runtime.exists(): raise RuntimeError('Frozen destination already exists; do not overwrite')
    sources=[]
    for directory in ['services/api/src','apps/web/src','apps/web/e2e']:
        sources += [p for p in (REPO/directory).rglob('*') if p.is_file()]
    sources += [REPO/'scripts/me00a-execution-claims.mjs',REPO/'scripts/me00a-execution-claims.test.mjs']
    entries=[]
    for path in sorted(set(sources)):
        relative=path.relative_to(REPO);target=src/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
        entries.append({'path':relative.as_posix(),'sha256':digest(path.read_bytes())})
    classes=REPO/'services/api/target/classes'
    if not classes.exists(): raise RuntimeError('Compiled classes required')
    shutil.copytree(classes,runtime)
    compiled=[{'path':p.relative_to(runtime).as_posix(),'sha256':digest(p.read_bytes())} for p in sorted(runtime.rglob('*')) if p.is_file()]
    parent=BASE/'candidate42r3-source.json'
    save(PUB/'candidate43-source.json',{'version':'candidate43','baseHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
        'frozenAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'parentCandidate':'candidate42r3','parentManifestSha256':digest(parent.read_bytes()),
        'sourceSnapshot':src.relative_to(REPO).as_posix(),'runtimeSnapshot':runtime.relative_to(REPO).as_posix(),'sourceFiles':entries,'runtimeFiles':compiled})
    draft()
    print(json.dumps({'sourceFiles':len(entries),'runtimeFiles':len(compiled),'protocolDraft':str(PRIVATE/'protocol-draft.json'),'modelCalls':0,'canonicalChanged':False}))
