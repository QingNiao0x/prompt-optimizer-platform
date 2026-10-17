"""归档冻结开关的真实证据并关闭本批；保留Plan内容失败，未用余额不自动转移。"""
from pathlib import Path
from datetime import datetime, timezone
from collections import Counter
import json
import hashlib
import shutil

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
WORK = ROOT / 'tmp/me00a-candidate32-live-20261018'
LEDGER = ROOT / 'tmp/me00a-20261012'


def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save(p, value):
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    assert not target.exists()
    shutil.copy2(source, target)


def main():
    manifest = read(PUBLIC / 'candidate32-source.json')
    assert all(sha(ROOT / row['path']) == row['sha256'] for row in manifest['sourceFiles'])
    assert all(sha(ROOT / manifest['runtimeSnapshot'] / row['path']) == row['sha256'] for row in manifest['runtimeFiles'])
    assert all(sha(ROOT / 'services/api/target/classes' / row['path']) == row['sha256'] for row in manifest['runtimeFiles'])
    tools = read(PUBLIC / 'live-tool-lock.json')
    assert all(sha(PUBLIC / row['path']) == row['sha256'] for row in tools['files'])
    traces = [read(p) for p in sorted((LEDGER / 'traces').glob('*.json'))]
    assert len(traces) == 369
    assert Counter(r.get('phase') for r in traces) == {'baseline':70,'candidateValidation':299}
    current = [r for r in traces if r.get('version') == 'candidate32-live']
    purposes = dict(Counter(r['purpose'] for r in current))
    assert len(current) == 10 and purposes == {'PLATFORM_GENERATION':3,'PLANNING':1,'EXECUTION':4,'REVIEW':2}
    assert all(r.get('httpStatus') == 200 and not r.get('failure') for r in current)
    call_rows = []
    for number in range(360,370):
        source = LEDGER / f'traces/{number:03}.json'
        record = read(source)
        copy(source, PUBLIC / f'live/traces/{number:03}.json')
        call_rows.append({'trace':number,'purpose':record['purpose'],'operation':record.get('operation'),
            'requestId':record.get('requestId'),'model':record['request']['model'],
            'httpStatus':record['httpStatus'],'elapsedMs':record['elapsedMs'],'usage':record['response']['usage']})
    ui = {}
    for case in ['MAIL-COMPACT','TRANSLATION-COMPACT','PROJECT-CHOICE-STRUCTURED']:
        base = WORK / 'live-results' / case
        check = read(base / 'check.json')
        assert check['success'] and check['nativeCopyMatches'] and check['localSwitch']['noNewModelHttpRequests']
        assert all(check['history'][k] for k in ['body','promptFormat','sections','ambiguities','appliedConstraints'])
        result = read(base / 'enhancement-platform.private.json')
        copied = (base / 'native-copy.md').read_bytes().decode('utf-8')
        assert copied.replace('\r\n','\n') == result['body']['data']['optimizedPrompt'].replace('\r\n','\n')
        for name in ['native-copy.md','local-switch-native-copy.md','check.json']:
            copy(base / name, PUBLIC / 'live' / case / name)
        data = result['body']['data'].copy()
        for key in ['optimizationId','sessionId','workflowId','planId']:
            data.pop(key,None)
        save(PUBLIC / 'live' / case / 'platform-result.json',{'requestId':result['body']['requestId'],
            'data':data,'publicProjection':True,'privateOriginalSha256':sha(base / 'enhancement-platform.private.json')})
        ui[case] = check
    plan = read(WORK / 'live-results/PROJECT-CHOICE-STRUCTURED/plan-platform.private.json')
    answers = read(WORK / 'live-results/PROJECT-CHOICE-STRUCTURED/actual-answers.private.json')
    assert len(plan['body']['data']['questions']) == len(answers) == 1
    save(PUBLIC / 'live/actual-plan-question-answer.json',{'questions':plan['body']['data']['questions'],'answers':answers,
        'originalResponsePrivateSha256':sha(WORK / 'live-results/PROJECT-CHOICE-STRUCTURED/plan-platform.private.json')})
    for group in ['MAIL','TRANSLATION']:
        for arm in ['RAW','ENHANCED']:
            for name in ['request.json','response.json','work.md']:
                copy(WORK / 'works' / group / arm / name, PUBLIC / 'live/works' / group / arm / name)
    copy(WORK / 'works/input-lock.json', PUBLIC / 'live/works/input-lock.json')
    copy(WORK / 'hard-checks.json', PUBLIC / 'live/hard-checks.json')
    reviews = {}
    for reviewer in ['R1','R2']:
        for name in ['request.json','response.json','review.md']:
            copy(WORK / 'reviews' / reviewer / name, PUBLIC / 'live/reviews' / reviewer / name)
        reviews[reviewer] = read(WORK / 'reviews' / reviewer / 'review.md')
        assert all(reviews[reviewer]['cases'][case]['verdict'] == 'TIE' for case in ['MAIL','TRANSLATION'])
    copy(WORK / 'reviews/input-lock.json', PUBLIC / 'live/reviews/input-lock.json')
    copy(WORK / 'reviews/blind-mapping.private.json', PUBLIC / 'live/reviews/unblinded-mapping.json')
    costs = {}
    for group in ['MAIL','TRANSLATION']:
        raw, enhanced = [read(WORK / 'works' / group / arm / 'response.json') for arm in ['RAW','ENHANCED']]
        costs[group] = {'rawTokens':raw['response']['usage']['total_tokens'],
            'enhancedTokens':enhanced['response']['usage']['total_tokens'],
            'tokenRatio':round(enhanced['response']['usage']['total_tokens']/raw['response']['usage']['total_tokens'],3),
            'rawElapsedMs':raw['elapsedMs'],'enhancedElapsedMs':enhanced['elapsedMs'],
            'singlePairNotPerformanceDistribution':True}
    summary = {'candidate':'candidate32','recordedAt':datetime.now(timezone.utc).isoformat(),
        'realCalls':10,'failedActualCalls':0,'protocolRepairCalls':0,'qualityRetries':0,
        'actualBefore':359,'actualAfter':369,'baseline':70,'candidateValidation':299,
        'formalLimits':{'total':389,'baseline':80,'candidateValidation':309},
        'approvedMaximum':18,'purposeActuals':purposes,'unusedClosed':8,'calls':call_rows,
        'tokensTotal':sum(r['response']['usage']['total_tokens'] for r in current),'executionCost':costs,
        'currencyCost':'UNKNOWN','historyBefore':1488,'historyAfter':1491,'newAccountsCreated':0,
        'passedScopedGates':['Default compact direct email and translation; explicit structured Plan',
            'Native clipboard, sections, constraints, ambiguities, format and saved history agree',
            'Local opposite-format switch preserves sections and constraints with no extra model HTTP request',
            'Both mail works 33 Han <=120; both translation works identical and meet two-line placeholder requirements',
            'Natural Plan asks one user-reserved login-method question; actual answer integrated in task section'],
        'reviewVotes':{who:{case:review['cases'][case]['verdict'] for case in ['MAIL','TRANSLATION']} for who,review in reviews.items()},
        'reviewScope':'Independent AI-assisted calls by Flash and Pro, same supplier, not human/domain experts',
        'qualityConclusion':'Two limited pairs tie; no observed regression, no demonstrated quality improvement or statistical win rate',
        'failedOrOpenGates':[{'issue':'Answered Plan copy still projects original unchosen state and extra authentication/session pending condition',
            'attribution':'Platform task-view/requirement projection; raw enhancement already integrates email/password and has no ambiguities; detailed source audit separate',
            'state':'FAILED_CONTENT_GATE','doNotTreatUiSuccessAsSemanticSuccess':True},
            {'issue':'Existing repeated user/platform rules and prompt overhead','state':'OPEN'},
            {'issue':'Full representative output/format/example matrix and 14-framework combination','state':'NOT_COVERED'},
            {'issue':'Prior requests credential echo, scoring and YLL professional correctness','state':'NOT_COVERED'}],
        'sourceDrift':False,'runtimeDrift':False,'productSourceModifiedDuringLive':False,
        'committed':False,'pushed':False,'published':False}
    save(PUBLIC / 'live-result.json', summary)
    protocol = read(LEDGER / 'protocol.json')
    batch = protocol['activeBatch']
    assert batch['version'] == 'candidate32-live' and batch['status'] == 'OPEN'
    batch.update(status='CLOSED', actualNewCalls=10, actualCallsAfterBatch=369,
        actualCandidateCallsAfterBatch=299, actualPurposeCounts=purposes,
        unusedAllowance=8, unusedAllowanceStatus='CLOSED_NOT_TRANSFERABLE',
        outcome='Presentation/copy/history pass; both small work pairs tie; answered Plan still has stale/extra pending projections, overall content gate open')
    save(PUBLIC / 'protocol-closed.json',protocol)
    protocol_path = LEDGER / 'protocol.json'
    protocol_path.write_text(json.dumps(protocol,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    proposal = read(PUBLIC / 'live-proposal.json')
    proposal.update(status='CLOSED', actualNewCalls=10,actualAfter=369,unusedClosed=8)
    (PUBLIC / 'live-proposal.json').write_text(json.dumps(proposal,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('ARCHIVED_AND_CLOSED: 10 calls; total369=baseline70+candidate299; unused8 closed; Plan content gate failed')


if __name__ == '__main__':
    main()
