"""Audit all closed transports and preserved runtime evidence after38; no network."""
from pathlib import Path
from collections import Counter
import datetime
import hashlib
import json
import re

B = Path(__file__).resolve().parent
R = B.parents[3]
P = R / 'tmp/natural-intent-completion-2026-10-24'
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
old = ['regression', 'heldout', 'works', 'completion']
current = ['boundary38-regression', 'boundary38-heldout', 'boundary38-works']
registration_paths = {
    'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptInstructions.java',
    'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptStageInstructions.java',
    'services/api/src/main/java/com/promptoptimizer/provider/domain/NaturalPromptIntentCompletionInstructions.java',
    'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
}
rows = []
phases = Counter()
traces_all = []
for stage in old + current:
    public, private = B / stage, P / stage
    closure = read(public / 'closure.json')
    assert closure['status'] == 'CLOSED' and closure == read(private / 'closure.json')
    traces = [read(f) for f in sorted((private / 'traces').glob('*.json'))]
    exported = [json.loads(line) for line in (public / 'transports.jsonl').read_text(encoding='utf-8').splitlines()]
    assert traces == exported and len(traces) == closure['actualCalls']
    traces_all.extend(traces)
    phases.update(t['phase'] for t in traces)
    manifest = read(public / 'source-manifest.json')
    drift = [f['path'] for f in manifest['files'] if sha(R / f['path']) != f['sha256']]
    if stage in current:
        assert not drift, (stage, drift)
    else:
        assert set(drift).issubset(registration_paths), (stage, drift)
    stable_runtime = [f for f in manifest['files'] if '/runtime/classes/' in f['path'] or '/runner/' in f['path']]
    assert stable_runtime and all(sha(R/f['path']) == f['sha256'] for f in stable_runtime)
    histories = [read(p) for p in (public / 'history-checks').glob('*.json')]
    assert all(h['status'] == 200 and all(h[k] for k in
        ['bodyEqual', 'versionEqual', 'noDefaultRules', 'rulesEqual', 'noInternalReviewOnWire']) for h in histories)
    info = read(public / 'same-information-checks.json')
    assert all(c['equal'] for c in info['checks'])
    assert (public / 'compiled-default-version.txt').read_text(encoding='utf-8-sig').strip() == '31'
    rows.append(dict(stage=stage, actualCalls=len(traces), transportExportsEqual=True,
        sourceEntries=len(manifest['files']), unchangedRuntimeEntries=len(stable_runtime),
        currentSourceDriftPaths=drift,
        sourceInterpretation='All frozen entries match current files.' if not drift else
            'Expected registration changes between closed37 and38; frozen runtime entries are unchanged. The earlier87-call audit remains a historical snapshot.',
        historiesVerified=len(histories), sameInformationChecks=len(info['checks'])))

revision = read(B/'task-protocol-revision-144.json')
assert sha(R/revision['previousAudit']['path']) == revision['previousAudit']['sha256']
assert sha(R/revision['previousProtocol']['path']) == revision['previousProtocol']['sha256']
assert len(traces_all) <= revision['selectedMaximumActualCalls'] == 144
resume = read(B/'boundary38-works/tooling-resume-manifest.json')
assert resume['status'] == 'FROZEN'
assert all(sha(R/f['path']) == f['sha256'] for f in resume['files'])
patterns = {
    'privateKey': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'apiKey': re.compile(r'\bsk-[A-Za-z0-9_-]{20,}\b'),
    'jwt': re.compile(r'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}\b'),
    'literalCredential': re.compile(r'(?i)["\'](?:password|api[_-]?key|access[_-]?token|refresh[_-]?token)["\']\s*:\s*["\'][^"\'\s]{16,}["\']'),
}
hits, scanned = [], 0
for p in sorted(B.rglob('*')):
    if not p.is_file() or p.suffix == '.pyc' or p.name == 'final-boundary38-audit.json':
        continue
    try:
        content = p.read_text(encoding='utf-8-sig')
    except UnicodeDecodeError:
        continue
    scanned += 1
    counts = {name: len(pattern.findall(content)) for name, pattern in patterns.items()}
    if any(counts.values()):
        hits.append(dict(path=p.relative_to(R).as_posix(), counts=counts))
assert not hits, 'Potential credential match: inspect locally; never print matching contents.'
out = dict(status='PASS', checkedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    stages=rows, taskActual=len(traces_all), taskMaximum=144,
    cumulativeActual=1848+len(traces_all), previousCumulative=1848,
    unusedReservationReleased=144-len(traces_all), phaseCounts=dict(phases),
    previous87AuditUnchanged=True, previous128ProtocolUnchanged=True,
    toolingInterruptions=1, extraModelCallsForToolInterruption=0,
    supplementaryResumeFilesVerified=len(resume['files']),
    modelCallsForAudit=0, semanticDecision='Separate frozen artifact reviews; engineering integrity is not adoption.',
    publicCredentialScan=dict(status='NO_PATTERN_MATCHES', filesScanned=scanned,
        matches=0, paths=[], matchContentsPrinted=False,
        limitation='Finite pattern scan, not proof of absence of all sensitive data.'))
target = B/'final-boundary38-audit.json'
assert not target.exists(), 'A prior final audit must not be overwritten.'
target.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(status='PASS', taskActual=out['taskActual'],
    phaseCounts=dict(phases), stagesVerified=len(rows), publicFilesScanned=scanned,
    credentialMatches=0, oldCurrentSourceDifferences={r['stage']:len(r['currentSourceDriftPaths']) for r in rows if r['stage'] in old})))
