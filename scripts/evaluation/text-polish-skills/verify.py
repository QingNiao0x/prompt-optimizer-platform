"""只核对本地冻结资料、同条件配对和预算，无模型调用。"""
import hashlib
import json
from pathlib import Path

root = Path('docs/testing/evidence/text-polish-skills-2026-11-16')
protocol = json.loads((root/'protocol.json').read_text(encoding='utf-8'))
assert hashlib.sha256((root/'protocol.json').read_bytes()).hexdigest() == (root/'protocol.sha256').read_text().strip()
for name, expected in protocol['sourceSha256'].items():
    assert hashlib.sha256(Path(name).read_bytes()).hexdigest() == expected, name
samples = json.loads((root/'samples.json').read_text(encoding='utf-8'))
assert len(samples) == 24
assert len(protocol['jobs']) == 48
assert protocol['maxActualCalls'] == 60
for item in samples:
    jobs = [job for job in protocol['jobs'] if job['sample'] == item['id']]
    assert len(jobs) == 2 and jobs[0]['input'] == jobs[1]['input']
    assert {job['strategy'] for job in jobs} == {'text-polish-v3', 'text-polish-auto-v1'}
    assert jobs[0]['input']['sourceText'] == item['text']
    assert item['meanings']
for language in ['ZH', 'EN', 'MIXED']:
    assert sum(s['language'] == language and s['group'] == 'pilot' for s in samples) == 2
    assert sum(s['language'] == language and s['group'] == 'expand' for s in samples) == 6
ledger = json.loads((root/'ledger.json').read_text(encoding='utf-8'))
assert ledger['actualCalls'] <= 60
print('Verified 24 samples, 48 same-condition jobs, frozen hashes and 60-call proposed cap.')
