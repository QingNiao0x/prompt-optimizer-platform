"""Close the exact approved round from the canonical ledger; unused allowance cannot silently move."""
from pathlib import Path
from collections import Counter
from datetime import datetime, timezone
import json
import os

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
LEDGER = ROOT / 'tmp/me00a-20261012'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def main():
    path = LEDGER / 'protocol.json'
    before = path.read_bytes()
    protocol = json.loads(before)
    batch = protocol['activeBatch']
    assert batch['version'] == 'candidate33-live' and batch['status'] == 'OPEN'
    assert batch['callsBefore'] == 369 and batch['maximumNewCalls'] == 20
    traces = sorted((LEDGER / 'traces').glob('*.json'))
    assert [int(f.stem) for f in traces] == list(range(1, 382))
    records = [(int(f.stem), read(f)) for f in traces if int(f.stem) > 369]
    assert all(r['version'] == 'candidate33-live' and r['phase'] == 'candidateValidation' for _, r in records)
    counts = Counter(r['purpose'] for _, r in records)
    assert counts == {'PLATFORM_GENERATION': 4, 'EXECUTION': 6, 'REVIEW': 2}
    assert all(r.get('httpStatus') == 200 and not r.get('failure') for _, r in records)
    assert protocol['maximumActualModelCalls'] == 399 and protocol['phaseLimits'] == {'baseline': 80, 'candidateValidation': 319}
    batch.update(status='CLOSED', closedAt=datetime.now(timezone.utc).isoformat(),
                 actualNewCalls=len(records), actualCallsAfterBatch=len(traces),
                 actualCandidateCallsAfterBatch=311, actualPurposeCounts=dict(counts),
                 unusedAllowance=8, unusedAllowanceStatus='CLOSED_NOT_TRANSFERABLE',
                 unusedClosedNotTransferable=8, qualityRetriesPerformed=0,
                 providerProtocolRepairCalls=0, qualityGate='OPEN_WITH_KNOWN_FAILURES')
    staged = LEDGER / 'protocol-candidate33-closed.new.json'
    assert not staged.exists() and not (PUBLIC / 'protocol-closed.json').exists()
    with staged.open('x', encoding='utf-8') as stream:
        json.dump(protocol, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    assert path.read_bytes() == before and len(list((LEDGER / 'traces').glob('*.json'))) == 381
    os.replace(staged, path)
    (PUBLIC / 'protocol-closed.json').write_bytes(path.read_bytes())
    print(json.dumps({'status': 'CLOSED', 'actual': 381, 'baseline': 70, 'candidate': 311,
                      'batchCalls': 12, 'unusedClosedNotTransferable': 8, 'qualityPassed': False}))


if __name__ == '__main__':
    main()
