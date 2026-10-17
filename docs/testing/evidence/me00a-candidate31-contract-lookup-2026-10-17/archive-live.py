"""归档本轮原件和独立检查，关闭批次；不把AI评审投票替代硬约束。"""
from pathlib import Path
from datetime import datetime, timezone
from collections import Counter
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
WORK = ROOT / 'tmp/me00a-candidate31-live-20261017'
LEDGER = ROOT / 'tmp/me00a-20261012'

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')

def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    assert not destination.exists()
    shutil.copy2(source, destination)

manifest = read(PUBLIC / 'candidate31r2-source.json')
assert all(digest(ROOT / row['path']) == row['sha256'] for row in manifest['sourceFiles'])
assert all(digest(ROOT / manifest['runtimeSnapshot'] / row['path']) == row['sha256'] for row in manifest['runtimeFiles'])
offline = read(PUBLIC / 'evidence-manifest.json')
assert all(digest(PUBLIC / row['path']) == row['sha256'] for row in offline['files'])
tools = read(PUBLIC / 'live-tool-lock.json')
lineage = read(PUBLIC / 'live-tool-lineage.json')
for row in tools['tools']:
    source = ROOT / row['path']
    if source.name == 'account-browser.mjs':
        source = PUBLIC / lineage['liveSource']
    assert digest(source) == row['sha256']
traces = [read(p) for p in sorted((LEDGER / 'traces').glob('*.json'))]
assert len(traces) == 359
phase_counts = Counter(r.get('phase') for r in traces)
assert phase_counts['baseline'] == 70 and phase_counts['candidateValidation'] == 289
current = [r for r in traces if r.get('version') == 'candidate31-live']
assert len(current) == 8 and all(r.get('httpStatus') == 200 for r in current)
purposes = dict(Counter(r['purpose'] for r in current))
assert purposes == {'PLANNING': 2, 'PLATFORM_GENERATION': 2, 'EXECUTION': 2, 'REVIEW': 2}
for number in range(352, 360):
    copy(LEDGER / f'traces/{number:03}.json', PUBLIC / f'live/traces/{number:03}.json')

ui = read(WORK / 'live-results/ui-check.json')
choice = read(WORK / 'live-results/USER-CHOICE-PLAN/platform.json')
assert ui['planStatus'] == ui['enhancementStatus'] == 200 and ui['planQuestions'] == []
assert ui['planConfirmationSent']['answers'] == [] and ui['automaticFinalGeneration']
assert len(choice['body']['data']['questions']) == 1
assert choice['body']['data']['questions'][0]['id'] == 'login_method'
history_before = read(WORK / 'live-results/history-before.json')
history_after = read(WORK / 'live-results/history-after.json')
assert history_before['total'] == 1486 and history_after['total'] == 1488
assert history_after['sameAccount'] and history_after['newAccountsCreated'] == 0

for name in ['EXISTING-PLAN-ENHANCE', 'EXISTING-DIRECT']:
    base = WORK / 'live-results' / name
    response = read(base / 'platform.json')
    native = (base / 'native-copy.md').read_text(encoding='utf-8')
    assert response['status'] == 200
    assert native == response['body']['data']['optimizedPrompt'].replace('\r\n', '\n')
    assert read(base / 'native-copy-check.json')['nativeCopyMatches']
    assert all(read(base / 'history-check.json')[key] for key in ['bodyMatches','sectionsMatch','ambiguitiesMatch','constraintsMatch'])
    for filename in ['native-copy.md','native-copy-check.json','history-check.json','result-copy.png']:
        copy(base / filename, PUBLIC / 'live' / name / filename)
    # The original response stays private; public result omits transient ownership identifiers.
    data = response['body']['data'].copy()
    for key in ['planId','optimizationId','sessionId','workflowId']:
        data.pop(key, None)
    save(PUBLIC / 'live' / name / 'platform-result.json', {'requestId':response['body']['requestId'],'data':data,'publicProjection':True,'privateOriginalSha256':digest(base / 'platform.json')})

save(PUBLIC / 'live/plan-checks.json', {
    'existingContractNaturalPlanQuestions':0, 'existingContractModelRawQuestions':0,
    'actualUiAutoGeneration':True, 'actualAnswers':[],
    'planIdSent':bool(ui['planConfirmationSent']['planId']),
    'userChoiceNaturalPlanQuestions':choice['body']['data']['questions'],
    'userChoiceNotAnsweredOrEnhanced':True,
    'copyAndHistoryBothFlowsMatch':True, 'sameCanonicalAccount':True,
    'historyBefore':1486, 'historyAfter':1488, 'newAccountsCreated':0,
    'priorPureReadingEvidenceReused':'candidate30 traces 346-349; not regenerated',
})

works = {}
for arm in ['RAW','PLAN']:
    result = read(WORK / f'compiled-{arm}/result.json')
    assert result['passed'] and result['mavenExit'] == 0
    works[arm] = result
    for filename in ['request.json','response.json','work.md']:
        copy(WORK / 'works' / arm / filename, PUBLIC / 'live/works' / arm / filename)
    copy(WORK / f'compiled-{arm}/result.json', PUBLIC / 'live/works' / arm / 'independent-checks.json')
    for report in (WORK / f'compiled-{arm}/target/surefire-reports').glob('TEST-*.xml'):
        copy(report, PUBLIC / 'live/works' / arm / 'junit' / report.name)
copy(WORK / 'works/input-lock.json', PUBLIC / 'live/works/input-lock.json')
mapping = read(WORK / 'reviews/blind-mapping.private.json')
reviews = {}
for reviewer in ['R1','R2']:
    for filename in ['request.json','response.json','review.md']:
        copy(WORK / 'reviews' / reviewer / filename, PUBLIC / 'live/reviews' / reviewer / filename)
    reviews[reviewer] = json.loads((WORK / 'reviews' / reviewer / 'review.md').read_text(encoding='utf-8'))
copy(WORK / 'reviews/input-lock.json', PUBLIC / 'live/reviews/input-lock.json')
save(PUBLIC / 'live/reviews/unblinded-mapping.json', mapping)

raw_execution = next(r for r in current if r['purpose']=='EXECUTION' and r['operation']=='RAW')
plan_execution = next(r for r in current if r['purpose']=='EXECUTION' and r['operation']=='PLAN')
call_rows = []
for number, row in zip(range(352,360), current):
    call_rows.append({'trace':number,'purpose':row['purpose'],'operation':row.get('operation'),
        'requestId':row.get('requestId'),'model':row['request']['model'],
        'httpStatus':row.get('httpStatus'),'elapsedMs':row['elapsedMs'],
        'usage':row['response'].get('usage'), 'finishReason':row['response']['choices'][0]['finish_reason']})
summary = {
    'candidate':'candidate31r2','phase':'REAL_VERIFICATION','recordedAt':datetime.now(timezone.utc).isoformat(),
    'realCalls':8,'failedActualCalls':0,'protocolRepairCalls':0,'qualityRetries':0,
    'actualBefore':351,'actualAfter':359,'baseline':70,'candidateValidation':289,
    'formalLimits':{'total':389,'baseline':80,'candidateValidation':309},
    'approvedMaximum':16,'purposeActuals':purposes,'unusedClosed':8,'calls':call_rows,
    'tokensTotal':sum(r['response']['usage']['total_tokens'] for r in current),
    'executionCost':{
        'rawTotalTokens':raw_execution['response']['usage']['total_tokens'],
        'planTotalTokens':plan_execution['response']['usage']['total_tokens'],
        'tokenRatio':round(plan_execution['response']['usage']['total_tokens']/raw_execution['response']['usage']['total_tokens'],3),
        'rawElapsedMs':raw_execution['elapsedMs'],'planElapsedMs':plan_execution['elapsedMs'],
        'elapsedRatio':round(plan_execution['elapsedMs']/raw_execution['elapsedMs'],3),
        'singlePairNotPerformanceDistribution':True,'currencyCost':'UNKNOWN'},
    'passedScopedGates':[
        'Exact trace350 mixed task: natural model zero-question Plan; actual UI automatic enhancement',
        'Explicit user-owned login-method choice retained, not defaulted',
        'Plan and independent direct both succeed on first attempt, no ambiguities',
        'Native clipboard, assembled sections, constraints and canonical history agree',
        'Both implementations and model tests compile together; independent8 each plus authored8/12 pass'],
    'reviewVotes':{key:{'verdict':value['verdict'],'scores':value['scores']} for key,value in reviews.items()},
    'reviewScope':'Independent AI-assisted reviews by Flash and Pro, same supplier, not human experts',
    'reviewScoreDisagreement':False,
    'sourceAuditOverride':{
        'state':'OPEN',
        'finding':'PLAN work first lists Bearer as a known source fact, then lists tokenType exact case as an unconfirmed assumption. Both reviewers miss this factual contradiction.',
        'attribution':'Downstream explanatory text error; the final copied prompt does not claim Bearer is unknown. Possible guidance interaction is unproven.',
        'reviewPolicy':'Keep both raw reviews unchanged; do not declare a global quality win based on their scores.'},
    'remainingFindings':[
        {'issue':'Composite original rules and task-related constraints are still repeated in copied bodies',
         'attribution':'Platform append paths plus upstream cross-section repetition; compare traces353/355 to native copies',
         'next':'Offline scope-preserving replay before deciding any minimal correction; no global semantic deletion'},
        {'issue':'Plan explanatory text adds redundant uncertainty and false unconfirmed Bearer case',
         'attribution':'Downstream first occurrence; possible prompt influence remains unproven',
         'next':'Evaluate simplifying existing guidance; do not pile on new instructions'},
        {'issue':'Prior requests credential echo, Q05 scoring and scientific correctness gates',
         'attribution':'Previous blocker-audit.json and saved-code-replay.json; not covered by this software pair',
         'next':'Keep open; no claim that these are fixed by this batch'}],
    'notCovered':['Statistical win rate','Repeated generation stability','Real DB/Redis/JWT implementation integration',
        'User-choice answered Plan completion','Live upstream timeout repeat or production failure rate',
        'Cross-provider generalization','Examples combinations','Full ME/G1-G8 release gates'],
    'sourceDrift':False,'runtimeDrift':False,'newAccountsCreated':0,
    'productSourceModifiedDuringLive':False,'committed':False,'pushed':False,'published':False,
}
save(PUBLIC / 'live-result.json', summary)
protocol = read(LEDGER / 'protocol.json')
batch = protocol['activeBatch']
assert batch['version']=='candidate31-live' and batch['status']=='OPEN'
batch.update({'status':'CLOSED','actualNewCalls':8,'actualCallsAfterBatch':359,
    'actualCandidateCallsAfterBatch':289,'actualPurposeCounts':purposes,
    'unusedAllowance':8,'unusedAllowanceStatus':'CLOSED_NOT_TRANSFERABLE',
    'outcome':'Scoped lookup and user-choice boundaries pass; clipboard/history and both code works pass. Review misses a factual contradiction; copy redundancy and broader quality gates stay open.'})
temporary = LEDGER / 'protocol-candidate31-closed.new.json'
save(temporary, protocol)
temporary.replace(LEDGER / 'protocol.json')
save(PUBLIC / 'protocol-closed.json', protocol)
print('ARCHIVED_AND_CLOSED: 8 actual calls; total359=baseline70+candidate289; unused8 closed')
