"""记录本次明确的16次余额重分配；不增加正式上限。"""
from pathlib import Path
import hashlib
import json
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
PRIVATE = ROOT / 'tmp/me00a-candidate31-live-20261017'
LEDGER = ROOT / 'tmp/me00a-20261012'

def write_new(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')

protocol = json.loads((LEDGER / 'protocol.json').read_text(encoding='utf-8-sig'))
traces = [json.loads(p.read_text(encoding='utf-8-sig')) for p in (LEDGER / 'traces').glob('*.json')]
assert len(traces) == 351
assert sum(r.get('phase') == 'candidateValidation' for r in traces) == 281
assert protocol['maximumActualModelCalls'] == 389
assert protocol['phaseLimits'] == {'baseline': 80, 'candidateValidation': 309}
assert protocol['activeBatch']['version'] == 'candidate30-live' and protocol['activeBatch']['status'] == 'CLOSED'
manifest = json.loads((PUBLIC / 'candidate31r2-source.json').read_text(encoding='utf-8'))
for row in manifest['sourceFiles']:
    assert hashlib.sha256((ROOT / row['path']).read_bytes()).hexdigest() == row['sha256'], row['path']
case_bytes = (PUBLIC / 'cases.json').read_bytes()
assert case_bytes == (LEDGER / 'candidate31-project-cases.json').read_bytes()
cases = json.loads(case_bytes)
batch = {
    'version': 'candidate31-live', 'sourceCandidate': 'candidate31r2',
    'group': 'candidate31-existing-contract-lookup', 'authorizationStatus': 'APPROVED_BY_USER',
    'authorizationBasis': '批准重分配16次，执行冻结范围',
    'authorizationAmendment': 'protocol-amendment-24.json', 'status': 'OPEN',
    'secondRoundAuthorized': True, 'maximumNewCalls': 16, 'callsBefore': 351,
    'candidateCallsBefore': 281, 'maximumActualCallsAfterBatch': 367,
    'purposeMaximums': {'PLANNING': 6, 'PLATFORM_GENERATION': 6, 'EXECUTION': 2, 'REVIEW': 2},
    'model': 'deepseek-flash', 'executionModel': 'deepseek-flash',
    'reviewModels': ['deepseek-flash', 'deepseek-v4-pro'],
    'cases': [c['id'] for c in cases], 'casesFile': 'candidate31-project-cases.json',
    'casesSha256': hashlib.sha256(case_bytes).hexdigest(),
    'allowedRawPromptSha256': [hashlib.sha256(c['rawPrompt'].encode()).hexdigest() for c in cases],
    'expectedTaskViews': [json.loads((PRIVATE / name).read_text(encoding='utf-8')) for name in ['taskview-existing.json','taskview-choice.json']],
    'generationParameters': {'temperature': 0.2, 'max_tokens': 10000, 'thinking': {'type': 'disabled'}},
    'accountCreationAllowed': False, 'sameTestAccountFile': 'tmp/local-acceptance/account.json',
    'qualityRetriesAllowed': False, 'closedAllowanceTransfer': 'EXPLICIT_USER_REALLOCATION_ONLY',
    'reallocation': {'candidate30ClosedUnused': 12, 'candidate27ClosedUnused': 4},
    'executionParity': 'Identical frozen synthetic project; raw vs actual native Plan copy; no fabricated answers',
    'reviewScope': 'Two independent AI-assisted reviewers; same supplier; not human or domain experts',
}
amendment = {'id': 24, 'approvedAtRecorded': datetime.now(timezone.utc).isoformat(),
             'formalLimitsUnchanged': {'total': 389, 'baseline': 80, 'candidate': 309},
             'actualBefore': 351, 'activeBatch': batch}
write_new(PUBLIC / 'protocol-before-amendment-24.json', protocol)
write_new(PUBLIC / 'protocol-amendment-24.json', amendment)
write_new(LEDGER / 'protocol-amendment-24.json', amendment)
protocol['activeBatch'] = batch
temporary = LEDGER / 'protocol-candidate31.new.json'
write_new(temporary, protocol)
temporary.replace(LEDGER / 'protocol.json')
print('APPROVED_16_REALLOCATION_RECORDED; actual=351; formal=389/80/309')
