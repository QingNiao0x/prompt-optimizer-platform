"""Prepare/record the explicitly approved candidate33 budget; never reserve a call or contact a model.

Default execution only checks the frozen files. Root must inspect the draft and pass --apply
after the offline TaskViewFixture and scope preflight have produced the four exact inputs.
"""
from pathlib import Path
from datetime import datetime, timezone
from collections import Counter
import argparse
import hashlib
import json
import os


PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
RUNTIME = ROOT / 'tmp/me00a-20261012'
AUTHORIZATION = '批准20次，执行冻结范围'
PURPOSES = {'PLATFORM_GENERATION': 12, 'EXECUTION': 6, 'REVIEW': 2}
OLD_LIMITS = {'total': 389, 'baseline': 80, 'candidate': 309}
NEW_LIMITS = {'total': 399, 'baseline': 80, 'candidate': 319}
REALLOCATION = {'candidate32ClosedUnused': 8, 'candidate27ClosedUnused': 1,
                'candidate24r3ClosedUnused': 1}


def read(path):
    """Reject duplicate JSON keys so approval cannot disagree with the runtime parser."""
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON key in approval evidence')
            result[key] = value
        return result
    return json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=unique)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def new(path, value):
    """Only create new evidence; never overwrite a previous approval or case file."""
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def owned_file(value):
    """Scope proofs must stay under this candidate's public directory, including after symlink resolution."""
    result = Path(value).resolve()
    assert result.is_relative_to(PUBLIC.resolve()) and result.is_file(), 'Proof outside candidate directory'
    return result


def verify_balance_chain(protocol):
    previous = protocol['activeBatch']
    assert previous['version'] == 'candidate32-live' and previous['status'] == 'CLOSED'
    assert previous['actualNewCalls'] == 10 and previous['actualCallsAfterBatch'] == 369
    assert previous['unusedAllowance'] == 8 and previous['unusedAllowanceStatus'] == 'CLOSED_NOT_TRANSFERABLE'
    evidence = ROOT / 'docs/testing/evidence'
    old27 = read(evidence / 'me00a-candidate27-live-2026-10-17/protocol-closed.json')['activeBatch']
    amendment24 = read(evidence / 'me00a-candidate31-contract-lookup-2026-10-17/protocol-amendment-24.json')
    assert old27['status'] == 'CLOSED' and old27['unusedClosedNotTransferable'] == 5
    assert amendment24['activeBatch']['reallocation']['candidate27ClosedUnused'] == 4
    old24 = read(evidence / 'me00a-candidate24-r3-live-2026-10-17/protocol-final.json')['activeBatch']
    assert old24['status'] == 'CLOSED' and old24['unusedClosedNotTransferable'] == 1
    # Candidate26's allowance was already transferred to32. Reusing it here would double count.
    assert previous['reallocation'] == {'candidate31ClosedUnused': 8, 'candidate26ClosedUnused': 10}
    return {'candidate32': {'remaining': 8, 'source': 'current CLOSED protocol'},
            'candidate27': {'closedUnused': 5, 'explicitlyTransferredTo31': 4, 'remaining': 1},
            'candidate24r3': {'remaining': 1},
            'totalReallocated': 10, 'newCandidateAllowance': 10,
            'baselineAllowanceTransferred': 0}


def verify_scope(args, proposal, source):
    request_path = owned_file(PUBLIC / 'request-cases.json')
    expected_path = owned_file(args.expected_inputs)
    preflight_path = owned_file(args.preflight)
    taskset_path = owned_file(PUBLIC / proposal['casesFile'])
    taskset = read(taskset_path)
    requests = read(request_path)
    assert len(taskset['cases']) == 2 and {item['id'] for item in taskset['cases']} == {'PATCH', 'MAIL'}
    assert taskset['executionArms'] == ['RAW', 'COMPACT_NATIVE_COPY', 'STRUCTURED_NATIVE_COPY']
    assert taskset['qualityRetriesAllowed'] is False and taskset['accountCreationAllowed'] is False
    assert sha(taskset_path) == proposal['casesSha256'], 'Approved task set changed'
    assert sha(PUBLIC / 'candidate33-source.json') == proposal['sourceManifestSha256'], 'Candidate freeze changed'
    expected_requests = []
    for item in taskset['cases']:
        assert item['files'] == [] and item['includeExamples'] is False and item['planConfirmation'] is None
        assert item['formats'] == ['COMPACT', 'STRUCTURED']
        for form in item['formats']:
            expected_requests.append({'id': item['id'] + '-' + form, 'rawPrompt': item['rawPrompt'],
                                      'promptFormat': form, 'includeExamples': False, 'mode': 'direct'})
    assert requests == expected_requests, 'Flat request cases differ from approved two-task/two-mode scope'
    expected = read(expected_path)
    if isinstance(expected, dict):
        expected = expected['expectedEnhancementInputs']
    assert isinstance(expected, list) and len(expected) == 4
    assert len({row['caseId'] for row in expected}) == 4
    by_id = {row['caseId']: row for row in expected}
    for request in requests:
        row = by_id[request['id']]
        assert row['rawPrompt'] == request['rawPrompt'] and row['promptFormat'] == request['promptFormat']
        assert row['planConfirmed'] is False
        view = row['taskView']
        assert isinstance(view, dict) and isinstance(view['intent'], dict) and view['intent']
        assert view['executionRequirements'] == [] and view['pendingDecisions'] == []
        assert view['examples']['mode'] in ['DISABLED', 'PROHIBITED']
    # Scope fixture results must be actual offline success, not an empty list or a network probe.
    preflight = read(preflight_path)
    assert type(preflight['checks']) is int and preflight['checks'] > 0
    assert preflight['passed'] == preflight['checks'] and preflight['failed'] == 0
    assert preflight['externalModelCalls'] == 0 and preflight['budgetReservationAttempted'] is False
    if 'springStarted' in preflight:
        assert preflight['springStarted'] is False
    # The frozen source/runtime are checked by bytes without printing configuration values.
    for rows, snapshot_key in [(source['sourceFiles'], 'sourceSnapshot'),
                               (source['runtimeFiles'], 'runtimeSnapshot')]:
        snapshot = (ROOT / source[snapshot_key]).resolve()
        assert snapshot.is_relative_to(ROOT.resolve())
        for row in rows:
            file = (snapshot / row['path']).resolve()
            assert file.is_relative_to(snapshot) and sha(file) == row['sha256'], 'Frozen snapshot drift'
    return requests, expected, {'taskSetSha256': sha(taskset_path), 'requestCasesSha256': sha(request_path),
                               'expectedInputsSha256': sha(expected_path), 'scopePreflightSha256': sha(preflight_path)}, request_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Record approved revision26 and open exactly this frozen batch')
    parser.add_argument('--expected-inputs', default=str(PUBLIC / 'expected-inputs.json'))
    parser.add_argument('--preflight', default=str(PUBLIC / 'scope-preflight.json'))
    args = parser.parse_args()
    protocol_path = RUNTIME / 'protocol.json'
    protocol_bytes = protocol_path.read_bytes()
    protocol = read(protocol_path)
    traces = sorted((RUNTIME / 'traces').glob('*.json'))
    assert len(traces) == 369 and [int(path.stem) for path in traces] == list(range(1, 370)), 'Actual ledger changed'
    phase_counts = Counter(read(path).get('phase') for path in traces)
    assert phase_counts == Counter({'baseline': 70, 'candidateValidation': 299})
    assert protocol['maximumActualModelCalls'] == OLD_LIMITS['total']
    assert protocol['phaseLimits'] == {'baseline': OLD_LIMITS['baseline'], 'candidateValidation': OLD_LIMITS['candidate']}
    balances = verify_balance_chain(protocol)
    proposal = read(PUBLIC / 'live-proposal.json')
    assert proposal['candidate'] == 'candidate33' and proposal['maximumNewCalls'] == 20
    assert proposal['purposeMaximums'] == PURPOSES and sum(PURPOSES.values()) == 20
    assert proposal['reallocationRequested'] == REALLOCATION and proposal['additionalAllowanceRequested'] == 10
    assert proposal['formalLimitsBefore'] == OLD_LIMITS and proposal['formalLimitsProposed'] == NEW_LIMITS
    assert proposal['models'] == {'enhancement': 'deepseek-flash', 'execution': 'deepseek-flash',
                                  'review': ['deepseek-flash', 'deepseek-v4-pro']}
    assert proposal['parameters'] == {'temperature': 0.2, 'max_tokens': 10000, 'thinking': 'disabled'}
    source = read(PUBLIC / 'candidate33-source.json')
    assert source['version'] == 'candidate33'
    requests, expected, scope_hashes, request_path = verify_scope(args, proposal, source)
    case_file = RUNTIME / 'candidate33-generation-mode-cases.json'
    batch = {'version': 'candidate33-live', 'sourceCandidate': 'candidate33', 'group': 'me04-mode-driven-generation',
             'authorizationStatus': 'APPROVED_BY_USER', 'authorizationBasis': AUTHORIZATION,
             'authorizationAmendment': 'protocol-amendment-26.json', 'status': 'OPEN',
             'secondRoundAuthorized': True, 'secondQualityRoundAllowed': False,
             'maximumNewCalls': 20, 'callsBefore': 369, 'candidateCallsBefore': 299,
             'maximumActualCallsAfterBatch': 389, 'purposeMaximums': PURPOSES,
             'model': proposal['models']['enhancement'], 'executionModel': proposal['models']['execution'],
             'reviewModels': proposal['models']['review'], 'cases': [row['id'] for row in requests],
             'casesFile': case_file.name, 'casesSha256': scope_hashes['requestCasesSha256'],
             'allowedRawPromptSha256': sorted({hashlib.sha256(row['rawPrompt'].encode()).hexdigest() for row in requests}),
             'expectedEnhancementInputs': expected, 'expectedTaskViews': [],
             'generationParameters': {'temperature': 0.2, 'max_tokens': 10000, 'thinking': {'type': 'disabled'}},
             'sameTestAccountFile': 'tmp/local-acceptance/account.json', 'accountCreationAllowed': False,
             'qualityRetriesAllowed': False, 'naturalPlanningCallsAllowed': False,
             'closedAllowanceTransfer': 'EXPLICIT_USER_REALLOCATION_ONLY', 'reallocation': REALLOCATION,
             'additionalCandidateAllowance': 10, 'sourceManifestSha256': sha(PUBLIC / 'candidate33-source.json'),
             'scopeProofHashes': scope_hashes,
             'executionParity': 'RAW versus each unchanged native copied mode; same original information, no invented Plan answers',
             'reviewScope': 'Two independent AI-assisted reviews, same supplier, not human/domain experts',
             'generationScope': 'Exactly four direct enhancements, one per case-format; existing protocol repair at most three attempts each',
             'excluded': proposal['excluded']}
    # Keep the legacy approval marker for the shared guard; the exact four operations, not another quality round, are authorized.
    amendment = {'id': 26, 'approvedAtRecorded': datetime.now(timezone.utc).isoformat(),
                 'authorization': AUTHORIZATION, 'formalLimitsBefore': OLD_LIMITS, 'formalLimitsAfter': NEW_LIMITS,
                 'actualBefore': 369, 'candidateBefore': 299, 'maximumNewCalls': 20,
                 'maximumActualAfter': 389, 'maximumCandidateAfter': 319,
                 'balanceChain': balances, 'activeBatch': batch}
    if not args.apply:
        print(json.dumps({'amendment': 26, 'mode': 'CHECK_ONLY', 'scopeBound': True,
                          'requestCases': len(requests), 'actualBefore': 369, 'maximumNewCalls': 20,
                          'formalLimitsAfter': NEW_LIMITS, 'newModelCalls': 0}))
        return
    # Preflight all destinations before writes. The canonical protocol is replaced last; no open batch is created on partial failure.
    before_path = PUBLIC / 'protocol-before-amendment-26.json'
    amendment_path = PUBLIC / 'protocol-amendment-26.json'
    staged_path = RUNTIME / 'protocol-candidate33-approved.new.json'
    for path in [before_path, amendment_path, case_file, staged_path]:
        assert not path.exists(), 'Approval output already exists; inspect before any retry'
    assert protocol_path.read_bytes() == protocol_bytes and len(list((RUNTIME / 'traces').glob('*.json'))) == 369
    new(before_path, protocol)
    with case_file.open('xb') as target:
        target.write(request_path.read_bytes())
    new(amendment_path, amendment)
    updated = dict(protocol)
    updated['maximumActualModelCalls'] = NEW_LIMITS['total']
    updated['phaseLimits'] = {'baseline': NEW_LIMITS['baseline'], 'candidateValidation': NEW_LIMITS['candidate']}
    updated['activeBatch'] = batch
    new(staged_path, updated)
    assert protocol_path.read_bytes() == protocol_bytes and len(list((RUNTIME / 'traces').glob('*.json'))) == 369
    os.replace(staged_path, protocol_path)
    print(json.dumps({'amendment': 26, 'mode': 'APPLIED', 'actualBefore': 369,
                      'maximumNewCalls': 20, 'formalLimitsAfter': NEW_LIMITS, 'newModelCalls': 0}))


if __name__ == '__main__':
    main()
