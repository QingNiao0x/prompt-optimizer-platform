"""Close only candidate35's bounded batch; failures and reservations remain counted."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
LEDGER = ROOT / 'tmp/me00a-20261012'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    protocol_path = LEDGER / 'protocol.json'
    before = protocol_path.read_bytes()
    protocol = json.loads(before)
    batch = protocol['activeBatch']
    assert batch['version'] == 'candidate35-live' and batch['status'] == 'OPEN'
    assert batch['maximumNewCalls'] == 1 and batch['callsBefore'] == 388
    assert protocol['maximumActualModelCalls'] == 399
    assert protocol['phaseLimits'] == {'baseline': 80, 'candidateValidation': 319}
    paths = sorted((LEDGER / 'traces').glob('*.json'), key=lambda path: int(path.stem))
    assert [int(path.stem) for path in paths] == list(range(1, len(paths) + 1))
    records = [json.loads(path.read_text(encoding='utf-8')) for path in paths]
    own = [record for record in records if record.get('version') == 'candidate35-live']
    assert len(own) <= 1 and len(paths) == 388 + len(own)
    assert all(record.get('phase') == 'candidateValidation'
               and record.get('purpose') == 'PLATFORM_GENERATION' for record in own)
    phases = {phase: sum(record.get('phase') == phase for record in records)
              for phase in ('baseline', 'candidateValidation')}
    assert phases == {'baseline': 70, 'candidateValidation': 318 + len(own)}
    failed = sum(record.get('httpStatus') != 200 for record in own)
    summary = {'candidate': 'candidate35', 'status': 'CLOSED', 'actualNewCalls': len(own),
               'actualCallsAfterBatch': len(paths), 'phases': phases,
               'formalLimits': {'total': 399, 'baseline': 80, 'candidate': 319},
               'failedOrUnresolvedCallsCounted': failed,
               'unusedAllowance': 1 - len(own), 'qualityRetriesPerformed': 0,
               'newDownstreamWorks': 0, 'newAIReviews': 0,
               'qualityGate': 'OPEN_WITH_PRIOR_WORK_FAILURES_AND_LIMITED_NEW_COVERAGE',
               'closedAt': datetime.now(timezone.utc).isoformat()}
    print(json.dumps({**summary, 'applied': args.apply}))
    if not args.apply:
        return
    batch.update(status='CLOSED', closedAt=summary['closedAt'], actualNewCalls=len(own),
                 actualCallsAfterBatch=len(paths), actualCandidateCallsAfterBatch=phases['candidateValidation'],
                 actualPurposeCounts={'PLATFORM_GENERATION': len(own), 'EXECUTION': 0, 'REVIEW': 0},
                 failedCallsCounted=failed, qualityRetriesPerformed=0, unusedAllowance=1-len(own),
                 unusedAllowanceStatus='CLOSED_NOT_TRANSFERABLE', qualityGate=summary['qualityGate'])
    assert protocol_path.read_bytes() == before, 'Canonical protocol changed during closure'
    for name, value in [('batch-close-summary.json', summary), ('batch-closed.json', batch)]:
        with (PUBLIC / name).open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    stage = LEDGER / 'protocol.candidate35.closed.json'
    with stage.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(protocol, ensure_ascii=False, indent=2) + '\n')
    stage.replace(protocol_path)


if __name__ == '__main__':
    main()
