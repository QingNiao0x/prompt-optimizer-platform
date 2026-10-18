"""Apply the user's explicit 26-call authorization after source, cases and guards have been frozen."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
LEDGER = ROOT / 'tmp/me00a-20261012'
CAPS = {'PLATFORM_GENERATION': 18, 'EXECUTION': 6, 'REVIEW': 2}


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def new(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    protocol_file = LEDGER / 'protocol.json'
    before = protocol_file.read_bytes()
    protocol = json.loads(before)
    traces = sorted((LEDGER / 'traces').glob('*.json'), key=lambda p: int(p.stem))
    assert [int(p.stem) for p in traces] == list(range(1, 390))
    records = [read(p) for p in traces]
    assert sum(r.get('phase') == 'baseline' for r in records) == 70
    assert sum(r.get('phase') == 'candidateValidation' for r in records) == 319
    assert protocol['maximumActualModelCalls'] == 399 and protocol['phaseLimits'] == {'baseline': 80, 'candidateValidation': 319}
    assert protocol['activeBatch']['version'] == 'candidate35-live' and protocol['activeBatch']['status'] == 'CLOSED'
    proposal = read(PUBLIC / 'live-proposal.json')
    assert proposal['maximumNewCalls'] == 26 and proposal['purposeMaximums'] == CAPS
    assert proposal['formalProposed'] == {'total': 425, 'baseline': 80, 'candidate': 345}
    manifest_file = PUBLIC / 'candidate36-source.json'
    manifest = read(manifest_file)
    assert manifest['version'] == 'candidate36' and proposal['sourceManifestSha256'] == sha(manifest_file)
    for row in manifest['sourceFiles']:
        path = (ROOT / row['path']).resolve()
        assert path.is_relative_to(ROOT) and sha(path) == row['sha256']
    for row in manifest['runtimeFiles']:
        path = (ROOT / manifest['runtimeSnapshot'] / row['path']).resolve()
        assert path.is_relative_to(ROOT / 'tmp') and sha(path) == row['sha256']
    planned = read(PUBLIC / 'planned-cases.json')['cases']
    request_file = PUBLIC / 'request-cases.json'
    requests = read(request_file)
    assert len(requests) == len(planned) == 6
    for row, original in zip(requests, planned, strict=True):
        assert row['id'] == original['caseId'] and row['rawPrompt'] == original['rawPrompt']
        assert row['promptFormat'] == original['promptFormat'] and row['includeExamples'] is False and row['mode'] == 'direct'
    assert sha(PUBLIC / 'planned-cases.json') == proposal['casesSha256']
    expected = read(PUBLIC / 'expected-inputs.json')
    assert len(expected) == 6
    for entry, row in zip(expected, requests, strict=True):
        assert entry['caseId'] == row['id'] and entry['rawPrompt'] == row['rawPrompt']
        assert entry['promptFormat'] == row['promptFormat'] and entry['planConfirmed'] is False
        assert entry['taskView'] and set(entry['rewriteStrategy']) == {'mode'}
    preflight = read(PUBLIC / 'scope-preflight.json')
    assert preflight['checks'] == preflight['passed'] and preflight['failed'] == 0 and preflight['checks'] >= 82
    assert preflight['externalModelCalls'] == 0 and preflight['springStarted'] is False and preflight['budgetReservationAttempted'] is False
    for row in read(PUBLIC / 'evaluation-tools.json')['tools']:
        path = (PUBLIC / row['path']).resolve()
        assert path.is_relative_to(PUBLIC) and sha(path) == row['sha256']
    summary = {'candidate': 'candidate36', 'ready': True, 'applied': args.apply, 'actualBefore': 389,
               'maximumNewCalls': 26, 'maximumActualAfter': 415, 'purposeMaximums': CAPS}
    if not args.apply:
        print(json.dumps(summary))
        return
    updated = copy.deepcopy(protocol)
    updated['maximumActualModelCalls'] = 425
    updated['phaseLimits'] = {'baseline': 80, 'candidateValidation': 345}
    basis = '用户明确答复：批准新增26次，执行冻结范围。6次增强请求含协议修复最多18次、作品6次、独立AI辅助评审2次；不转闭批余额。'
    updated['activeBatch'] = {'version': 'candidate36-live', 'sourceCandidate': 'candidate36',
        'authorizationStatus': 'APPROVED_BY_USER', 'authorizationBasis': basis,
        'authorizationAmendment': 'protocol-amendment-29.json', 'status': 'OPEN',
        'qualityRetriesAllowed': False, 'secondQualityRoundAllowed': False,
        'callsBefore': 389, 'candidateCallsBefore': 319, 'maximumNewCalls': 26,
        'maximumActualCallsAfterBatch': 415, 'purposeMaximums': CAPS,
        'sourceManifestSha256': sha(manifest_file), 'model': 'deepseek-flash', 'executionModel': 'deepseek-flash',
        'reviewModels': ['deepseek-flash', 'deepseek-v4-pro'], 'cases': [r['id'] for r in requests],
        'casesFile': 'candidate36-compact-guidance-cases.json', 'casesSha256': sha(request_file),
        'expectedEnhancementInputs': expected, 'accountCreationAllowed': False,
        'dependencyFailurePolicy': 'No verified native copy means dependent work is skipped. Every actual send counts; no reconstructed prompt, quality retry or allowance transfer.'}
    new(PUBLIC / 'protocol-before-amendment-29.json', protocol)
    new(PUBLIC / 'protocol-amendment-29.json', {'amendment': 29, 'candidate': 'candidate36', 'authorizationBasis': basis,
        'formalLimitsBefore': proposal['formalBefore'], 'formalLimitsAfter': proposal['formalProposed'],
        'callsBefore': 389, 'candidateCallsBefore': 319, 'maximumNewCalls': 26, 'purposeMaximums': CAPS,
        'sourceManifestSha256': sha(manifest_file), 'casesSha256': sha(request_file), 'closedAllowanceTransferred': 0,
        'expectedEnhancementInputs': expected, 'qualityRetriesAllowed': False})
    (LEDGER / updated['activeBatch']['casesFile']).write_bytes(request_file.read_bytes())
    assert protocol_file.read_bytes() == before and len(list((LEDGER / 'traces').glob('*.json'))) == 389
    new(LEDGER / 'protocol.candidate36.staged.json', updated)
    (LEDGER / 'protocol.candidate36.staged.json').replace(protocol_file)
    proposal['status'] = 'APPROVED_OPEN'
    proposal['authorizationBasis'] = basis
    proposal['models'] = {'enhancement': 'deepseek-flash', 'execution': 'deepseek-flash', 'reviews': ['deepseek-flash', 'deepseek-v4-pro']}
    proposal['plannedCasesSha256'] = proposal['casesSha256']
    proposal['casesSha256'] = sha(PUBLIC / 'live-cases.json')
    (PUBLIC / 'live-proposal.json').write_text(json.dumps(proposal, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
