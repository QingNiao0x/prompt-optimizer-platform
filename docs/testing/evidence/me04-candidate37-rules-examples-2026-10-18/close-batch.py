"""Close candidate37 using actual canonical traces; failures consume allowance and unused calls remain closed."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
LEDGER = ROOT / 'tmp/me00a-20261012'


def main():
    path = LEDGER / 'protocol.json'
    before = path.read_bytes()
    protocol = json.loads(before)
    batch = protocol['activeBatch']
    assert batch['version'] == 'candidate37-live' and batch['status'] == 'OPEN'
    assert protocol['maximumActualModelCalls'] == 425 and protocol['phaseLimits'] == {'baseline': 80, 'candidateValidation': 345}
    files = sorted((LEDGER / 'traces').glob('*.json'), key=lambda item: int(item.stem))
    assert [int(p.stem) for p in files] == list(range(1, len(files) + 1))
    records = [json.loads(p.read_text(encoding='utf-8-sig')) for p in files]
    owned = [row for row in records if row.get('version') == 'candidate37-live']
    counts = {purpose: sum(row.get('purpose') == purpose for row in owned) for purpose in batch['purposeMaximums']}
    assert len(files) == 403 + len(owned) and len(owned) <= 12
    assert sum(row.get('phase') == 'baseline' for row in records) == 70
    assert sum(row.get('phase') == 'candidateValidation' for row in records) == 333 + len(owned) <= 345
    assert all(counts[key] <= cap for key, cap in batch['purposeMaximums'].items())
    summary = {'candidate': 'candidate37', 'status': 'CLOSED', 'actualNewCalls': len(owned),
               'actualCallsAfterBatch': len(files), 'phases': {'baseline': 70, 'candidateValidation': 333 + len(owned)},
               'formalLimits': {'total': 425, 'baseline': 80, 'candidate': 345}, 'actualPurposeCounts': counts,
               'failedOrIncompleteCallsCounted': sum(row.get('httpStatus') != 200 for row in owned),
               'unusedAllowance': 12 - len(owned), 'unusedAllowanceStatus': 'CLOSED_NOT_TRANSFERABLE',
               'qualityRetriesPerformed': 0, 'closedAt': datetime.now(timezone.utc).isoformat()}
    for name, value in [('batch-close-summary.json', summary), ('batch-closed.json', {**batch, **summary})]:
        with (PUBLIC / name).open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    assert path.read_bytes() == before
    batch.update(summary)
    staged = LEDGER / 'protocol.candidate37.closed-staged.json'
    with staged.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(protocol, ensure_ascii=False, indent=2) + '\n')
    staged.replace(path)
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
