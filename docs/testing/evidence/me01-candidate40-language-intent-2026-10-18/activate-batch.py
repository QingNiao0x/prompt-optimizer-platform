"""Apply only the explicit new thirteen-call authorization; never transfer closed allowances."""
from pathlib import Path
from datetime import datetime, timezone
import json
import hashlib
import sys

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
LEDGER = ROOT / 'tmp/me00a-20261012'
digest = lambda value: hashlib.sha256(value).hexdigest()


def main():
    assert sys.argv[1:] == ['--apply']
    target = LEDGER / 'protocol.json'
    before = target.read_bytes()
    protocol = json.loads(before)
    assert protocol['maximumActualModelCalls'] == 425 and protocol['phaseLimits'] == {'baseline': 80, 'candidateValidation': 345}
    assert protocol['activeBatch']['status'] == 'CLOSED'
    traces = sorted((LEDGER / 'traces').glob('*.json'), key=lambda p: int(p.stem))
    assert [int(p.stem) for p in traces] == list(range(1, 416))
    rows = [json.loads(p.read_text(encoding='utf-8-sig')) for p in traces]
    assert sum(v.get('phase') == 'baseline' for v in rows) == 70
    assert sum(v.get('phase') == 'candidateValidation' for v in rows) == 345
    preflight = json.loads((PUBLIC / 'scope-preflight.json').read_text(encoding='utf-8'))
    assert preflight['checks'] == preflight['passed'] == 24 and preflight['modelCalls'] == preflight['budgetReservations'] == 0
    manifest_bytes = (PUBLIC / 'candidate40-source.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    for row in manifest['sourceFiles']:
        assert digest((ROOT / row['path']).read_bytes()) == row['sha256'], row['path']
    cases = (PUBLIC / 'request-cases.json').read_bytes()
    maxima = {'PLANNING': 6, 'PLATFORM_GENERATION': 3, 'EXECUTION': 2, 'REVIEW': 2}
    basis = '用户明确答复：批准新增13次，执行冻结范围。候选、用例自行选择；不转移闭批余额、不做作品质量重试。'
    batch = {'version': 'candidate40-live', 'sourceCandidate': 'candidate40', 'status': 'OPEN',
             'authorizationStatus': 'APPROVED_BY_USER', 'authorizationAmendment': 33, 'authorizationBasis': basis,
             'callsBefore': 415, 'candidateCallsBefore': 345, 'maximumNewCalls': 13, 'maximumActualCallsAfterBatch': 428,
             'purposeMaximums': maxima, 'qualityRetriesAllowed': False, 'secondQualityRoundAllowed': False,
             'sourceManifestSha256': digest(manifest_bytes), 'casesFile': 'candidate40-request-cases.json', 'casesSha256': digest(cases),
             'model': 'deepseek-flash', 'executionModel': 'deepseek-flash', 'reviewModels': ['deepseek-flash', 'deepseek-v4-pro'],
             'accountCreationAllowed': False,
             'dependencyFailurePolicy': 'Answer only the actual bound questions under the frozen synthetic answer policy. Unknown questions stop dependent work. Native copy is never patched.',
             'reviewScope': 'Two independent AI reviews with the same supplier; preserve disagreements and exact-quote checks; hard gates independent.'}
    amendment = {'number': 33, 'candidate': 'candidate40', 'authorization': basis,
                 'formalBefore': {'total': 425, 'baseline': 80, 'candidate': 345},
                 'formalAfter': {'total': 438, 'baseline': 80, 'candidate': 358},
                 'actualBefore': 415, 'maximumActualAfter': 428, 'maximumNewCalls': 13, 'purposeMaximums': maxima,
                 'closedAllowanceTransferred': False, 'protocolBeforeSha256': digest(before),
                 'sourceManifestSha256': digest(manifest_bytes), 'casesSha256': digest(cases), 'appliedAt': datetime.now(timezone.utc).isoformat()}
    for path, data in [(LEDGER / 'protocol-before-amendment-33.json', before),
                       (PUBLIC / 'protocol-amendment-33.json', (json.dumps(amendment, ensure_ascii=False, indent=2) + '\n').encode()),
                       (LEDGER / batch['casesFile'], cases)]:
        with path.open('xb') as output:
            output.write(data)
    assert target.read_bytes() == before, 'Concurrent protocol change'
    protocol['maximumActualModelCalls'] = 438
    protocol['phaseLimits']['candidateValidation'] = 358
    protocol['activeBatch'] = batch
    staged = LEDGER / 'protocol-candidate40-staged.json'
    with staged.open('x', encoding='utf-8') as output:
        json.dump(protocol, output, ensure_ascii=False, indent=2)
    staged.replace(target)
    print(json.dumps({'batch': batch['version'], 'formal': [438, 80, 358], 'actualBefore': 415, 'maximumNewCalls': 13, 'callsMade': 0}))


if __name__ == '__main__':
    main()
