"""Explicitly record the delegated six-call reallocation; formal phase/total caps stay unchanged."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import sys

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
LEDGER = ROOT / 'tmp/me00a-20261012'
sha = lambda data: hashlib.sha256(data).hexdigest()


def main():
    assert sys.argv[1:] == ['--apply']
    target = LEDGER / 'protocol.json'
    original = target.read_bytes()
    protocol = json.loads(original)
    assert protocol['maximumActualModelCalls'] == 438
    assert protocol['phaseLimits'] == {'baseline': 80, 'candidateValidation': 358}
    previous = protocol['activeBatch']
    assert previous['version'] == 'candidate40-live' and previous['status'] == 'CLOSED'
    prior_close = json.loads((ROOT / 'docs/testing/evidence/me01-candidate40-language-intent-2026-10-18/batch-close-summary.json').read_text())
    assert prior_close['actualTotal'] == 422 and prior_close['unusedClosed'] == 6
    traces = sorted((LEDGER / 'traces').glob('*.json'), key=lambda path: int(path.stem))
    assert [int(path.stem) for path in traces] == list(range(1, 423))
    rows = [json.loads(path.read_text(encoding='utf-8-sig')) for path in traces]
    assert sum(row.get('phase') == 'baseline' for row in rows) == 70
    assert sum(row.get('phase') == 'candidateValidation' for row in rows) == 352
    preflight = json.loads((PUBLIC / 'scope-preflight.json').read_text())
    assert preflight['checks'] == preflight['passed'] == 33
    assert preflight['modelCalls'] == preflight['budgetReservations'] == 0
    manifest_bytes = (PUBLIC / 'candidate41-source.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    for row in manifest['sourceFiles']:
        assert sha((ROOT / row['path']).read_bytes()) == row['sha256'], row['path']
    cases = (PUBLIC / 'request-cases.json').read_bytes()
    maxima = {'PLANNING': 3, 'PLATFORM_GENERATION': 1, 'EXECUTION': 2, 'REVIEW': 0}
    basis = '依据用户已授权后续候选、真实测试与重分配自行判断执行，本轮明确将候选四十关闭余6次转入四十一；不扩正式总／阶段上限，不自动挪用其他批次或基线余额。'
    batch = {'version': 'candidate41-live', 'sourceCandidate': 'candidate41', 'status': 'OPEN',
             'authorizationStatus': 'APPROVED_BY_USER', 'authorizationAmendment': 34,
             'authorizationBasis': basis, 'callsBefore': 422, 'candidateCallsBefore': 352,
             'maximumNewCalls': 6, 'maximumActualCallsAfterBatch': 428, 'purposeMaximums': maxima,
             'qualityRetriesAllowed': False, 'secondQualityRoundAllowed': False,
             'sourceManifestSha256': sha(manifest_bytes), 'casesFile': 'candidate41-request-cases.json', 'casesSha256': sha(cases),
             'model': 'deepseek-flash', 'executionModel': 'deepseek-flash', 'reviewModels': [],
             'accountCreationAllowed': False,
             'dependencyFailurePolicy': 'Actual bound answers only; unknown questions stop dependent work. First failure stays recorded; no quality retry.',
             'reviewScope': 'No new AI reviews in this six-call scope; overall G1-G8 and downstream quality gates remain open.'}
    amendment = {'number': 34, 'candidate': 'candidate41', 'authorization': basis,
                 'formalBefore': [438, 80, 358], 'formalAfter': [438, 80, 358], 'actualBefore': 422,
                 'maximumNewCalls': 6, 'maximumActualAfter': 428, 'purposeMaximums': maxima,
                 'closedAllowanceTransferred': {'from': 'candidate40-live', 'calls': 6},
                 'protocolBeforeSha256': sha(original), 'sourceManifestSha256': sha(manifest_bytes),
                 'casesSha256': sha(cases), 'appliedAt': datetime.now(timezone.utc).isoformat()}
    for path, data in [(LEDGER / 'protocol-before-amendment-34.json', original),
                       (PUBLIC / 'protocol-amendment-34.json', (json.dumps(amendment, ensure_ascii=False, indent=2) + '\n').encode()),
                       (LEDGER / batch['casesFile'], cases)]:
        with path.open('xb') as output:
            output.write(data)
    assert target.read_bytes() == original
    protocol['activeBatch'] = batch
    staged = LEDGER / 'protocol-candidate41-staged.json'
    with staged.open('x', encoding='utf-8') as output:
        json.dump(protocol, output, ensure_ascii=False, indent=2)
    staged.replace(target)
    print(json.dumps({'batch': batch['version'], 'formal': [438, 80, 358], 'actualBefore': 422, 'reallocatedCalls': 6, 'callsMade': 0}))


if __name__ == '__main__':
    main()
