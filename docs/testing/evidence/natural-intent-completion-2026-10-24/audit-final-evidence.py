"""Verify frozen evidence without making model calls or printing matched secrets."""
from pathlib import Path
from collections import Counter
import datetime
import hashlib
import json
import re

BASE = Path(__file__).resolve().parent
REPO = BASE.parents[3]
PRIVATE = REPO / 'tmp/natural-intent-completion-2026-10-24'
read = lambda path: json.loads(path.read_text(encoding='utf-8-sig'))
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
stages = ['regression', 'heldout', 'works', 'completion']
stage_checks = []
phases = Counter()
all_traces = []
for stage in stages:
    public = BASE / stage
    local = PRIVATE / stage
    closure = read(public / 'closure.json')
    assert closure['status'] == 'CLOSED'
    assert closure == read(local / 'closure.json')
    manifest = read(public / 'source-manifest.json')
    drift = [entry['path'] for entry in manifest['files']
             if digest(REPO / entry['path']) != entry['sha256']]
    assert not drift, (stage, drift)
    traces = [read(path) for path in sorted((local / 'traces').glob('*.json'))]
    exported = [json.loads(line) for line in (public / 'transports.jsonl').read_text(encoding='utf-8').splitlines()]
    assert traces == exported
    assert len(traces) == closure['actualCalls']
    assert all('response' in trace or 'transportFailure' in trace for trace in traces)
    phases.update(trace['phase'] for trace in traces)
    all_traces.extend(traces)
    histories = [read(path) for path in (public / 'history-checks').glob('*.json')]
    required = ['bodyEqual', 'versionEqual', 'noDefaultRules', 'rulesEqual', 'noInternalReviewOnWire']
    assert all(h['status'] == 200 and all(h[key] for key in required) for h in histories)
    info = read(public / 'same-information-checks.json')
    assert all(check['equal'] for check in info['checks'])
    stage_checks.append(dict(stage=stage, actualCalls=len(traces),
                             sourceFilesVerified=len(manifest['files']),
                             transportExportsEqual=True, historyChecks=len(histories),
                             sameInformationChecks=len(info['checks']), sourceDrift=[]))

patterns = {
    'privateKey': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'apiKey': re.compile(r'\bsk-[A-Za-z0-9_-]{20,}\b'),
    'jwt': re.compile(r'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}\b'),
    'literalCredential': re.compile(r'(?i)["\'](?:password|api[_-]?key|access[_-]?token|refresh[_-]?token)["\']\s*:\s*["\'][^"\'\s]{16,}["\']'),
}
hits = []
files_scanned = 0
for path in sorted(BASE.rglob('*')):
    if not path.is_file() or path.name in {'final-evidence-audit.json'}:
        continue
    try:
        content = path.read_text(encoding='utf-8-sig')
    except UnicodeDecodeError:
        continue
    files_scanned += 1
    counts = {name: len(pattern.findall(content)) for name, pattern in patterns.items()}
    if any(counts.values()):
        hits.append(dict(path=path.relative_to(REPO).as_posix(), counts=counts))

assert len(all_traces) == 87
assert not hits, 'Potential public credential matches: inspect locally; contents intentionally not printed'
output = dict(status='PASS', checkedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              stages=stage_checks, taskActual=len(all_traces), previousCumulative=1848,
              cumulativeActual=1848 + len(all_traces), phaseCounts=dict(phases),
              taskMaximum=128, unusedReservationReleased=128-len(all_traces),
              publicCredentialScan=dict(status='NO_PATTERN_MATCHES', filesScanned=files_scanned,
                                        matches=0, paths=[], matchContentsPrinted=False,
                                        limitation='Finite pattern scan; not a proof of absence of all sensitive data.'),
              modelCallsForAudit=0)
target = BASE / 'final-evidence-audit.json'
assert not target.exists(), 'Do not overwrite a prior audit'
target.write_text(json.dumps(output, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps(dict(status=output['status'], taskActual=output['taskActual'],
                      phaseCounts=output['phaseCounts'], stagesVerified=len(stages),
                      publicFilesScanned=files_scanned, credentialMatches=0)))
