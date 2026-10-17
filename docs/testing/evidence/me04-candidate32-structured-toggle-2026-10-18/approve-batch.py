"""记录用户明确批准的18次闭批余额重分配；不增加正式上限，不发送模型请求。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json


def main():
    root = Path(__file__).resolve().parents[4]
    public = Path(__file__).resolve().parent
    runtime = root / 'tmp/me00a-20261012'
    read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    def new(p, value):
        with p.open('x', encoding='utf-8', newline='\n') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
    protocol_path = runtime / 'protocol.json'
    protocol = read(protocol_path)
    traces = [read(p) for p in (runtime / 'traces').glob('*.json')]
    assert len(traces) == 359
    assert sum(t.get('phase') == 'baseline' for t in traces) == 70
    assert sum(t.get('phase') == 'candidateValidation' for t in traces) == 289
    assert protocol['maximumActualModelCalls'] == 389 and protocol['phaseLimits'] == {'baseline':80, 'candidateValidation':309}
    previous = protocol['activeBatch']
    assert previous['version'] == 'candidate31-live' and previous['status'] == 'CLOSED' and previous['unusedAllowance'] == 8
    # 关闭余额不自动转用；此处只执行本次用户明确批准的31余8次和26余10次。
    new(public / 'protocol-before-amendment-25.json', protocol)
    cases = read(public / 'live-cases.json')
    case_file = runtime / 'candidate32-structured-cases.json'
    assert not case_file.exists()
    case_file.write_bytes((public / 'live-cases.json').read_bytes())
    batch = dict(version='candidate32-live', sourceCandidate='candidate32', group='me04-structured-presentation',
        authorizationStatus='APPROVED_BY_USER', authorizationBasis='批准重分配18次，执行冻结范围',
        authorizationAmendment='protocol-amendment-25.json', status='OPEN', secondRoundAuthorized=True,
        maximumNewCalls=18, callsBefore=359, candidateCallsBefore=289, maximumActualCallsAfterBatch=377,
        purposeMaximums={'PLANNING':3,'PLATFORM_GENERATION':9,'EXECUTION':4,'REVIEW':2},
        model='deepseek-flash', executionModel='deepseek-flash', reviewModels=['deepseek-flash','deepseek-v4-pro'],
        cases=[s['id'] for s in cases], casesFile=case_file.name, casesSha256=sha(case_file),
        allowedRawPromptSha256=[hashlib.sha256(s['rawPrompt'].encode()).hexdigest() for s in cases],
        expectedEnhancementInputs=[], expectedTaskViews=[],
        generationParameters={'temperature':0.2,'max_tokens':10000,'thinking':{'type':'disabled'}},
        sameTestAccountFile='tmp/local-acceptance/account.json',accountCreationAllowed=False,qualityRetriesAllowed=False,
        closedAllowanceTransfer='EXPLICIT_USER_REALLOCATION_ONLY',
        reallocation={'candidate31ClosedUnused':8,'candidate26ClosedUnused':10},
        sourceManifestSha256=sha(public / 'candidate32-source.json'),
        executionParity='Raw versus actual unchanged native copy; same source evidence, no evaluator answer repair',
        reviewScope='Two independent AI-assisted reviews, same supplier, not human/domain experts')
    amendment={'id':25,'approvedAtRecorded':datetime.now(timezone.utc).isoformat(),
        'formalLimitsUnchanged':{'total':389,'baseline':80,'candidate':309},'actualBefore':359,'activeBatch':batch}
    new(public / 'protocol-amendment-25.json', amendment)
    protocol['activeBatch'] = batch
    protocol_path.write_text(json.dumps(protocol,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    proposal=read(public/'live-proposal.json')
    proposal['status']='APPROVED_OPEN'
    proposal['authorizationAmendment']='protocol-amendment-25.json'
    (public/'live-proposal.json').write_text(json.dumps(proposal,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'amendment':25,'actualBefore':359,'maximumNewCalls':18,'newModelCalls':0}))


if __name__ == '__main__':
    main()
