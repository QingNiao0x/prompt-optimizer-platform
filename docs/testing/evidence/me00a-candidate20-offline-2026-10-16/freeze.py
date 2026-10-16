"""Freeze candidate20 and all API test sources; preserve candidate19 runtime/evidence unchanged."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[3]


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, value):
    with (OUT / name).open('x', encoding='utf-8') as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write('\n')


def main():
    if '--verify' in sys.argv:
        frozen = load(OUT / ('candidate20-source-r2.json' if (OUT / 'candidate20-source-r2.json').exists() else 'candidate20-source.json'))
        for e in frozen['sourceFiles']:
            assert digest(ROOT / e['path']) == e['sha256'], e['path']
            assert digest(ROOT / frozen['sourceSnapshot'] / e['path']) == e['sha256'], e['path']
        for e in frozen['runtimeFiles']:
            assert digest(ROOT / frozen['runtimeSnapshot'] / e['path']) == e['sha256'], e['path']
        print(json.dumps({'candidate': 'candidate20', 'verified': True, 'sourceFiles': len(frozen['sourceFiles']),
                          'runtimeFiles': len(frozen['runtimeFiles']), 'verificationModelCalls': 0}))
        return
    parent = load(ROOT / 'docs/testing/evidence/me00a-candidate19-offline-2026-10-16/candidate19-source.json')
    for e in parent['sourceFiles']:
        assert digest(ROOT / parent['sourceSnapshot'] / e['path']) == e['sha256']
    for e in parent['runtimeFiles']:
        assert digest(ROOT / parent['runtimeSnapshot'] / e['path']) == e['sha256']
    run = load(ROOT / 'tmp/candidate20-affected-run.json')
    started = datetime.fromisoformat(run['startedAt']).timestamp()
    reports = []
    folder = OUT / 'test-reports'
    folder.mkdir(exist_ok=False)
    for path in (ROOT / 'services/api/target/surefire-reports').glob('TEST-*.xml'):
        tree = ET.parse(path).getroot()
        if tree.attrib['name'].split('.')[-1] not in run['classes']:
            continue
        assert path.stat().st_mtime >= started - 3, 'Stale report'
        row = {k: int(tree.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}
        assert row['failures'] == row['errors'] == row['skipped'] == 0, path.name
        row.update(name=tree.attrib['name'], seconds=tree.attrib['time'], originalReportSha256=digest(path))
        projection = ET.Element('testsuite', tree.attrib)
        for case in tree.findall('testcase'):
            ET.SubElement(projection, 'testcase', case.attrib)
        ET.ElementTree(projection).write(folder / path.name, encoding='utf-8', xml_declaration=True)
        reports.append(row)
    totals = {k: sum(r[k] for r in reports) for k in ('tests', 'failures', 'errors', 'skipped')}
    assert len(reports) == len(run['classes']) == 40 and totals['tests'] == 753
    save('verification.json', {'kind': 'OFFLINE_AFFECTED_REGRESSION', 'java': {'classes': len(reports), **totals, 'reports': reports},
        'newTargetedTests': 14, 'candidate20RealCalls': 0, 'fullRepositoryRegression': 'NOT_RUN',
        'candidate19ActualWorksDoNotValidateCandidate20': True})
    original = load(OUT / 'probe-parent19.json')
    current = load(OUT / 'probe-candidate20.json')
    assert all(not r['hasScoringObligationAndAnchors'] for r in original['rows'])
    assert all(r['hasScoringObligationAndAnchors'] and r['pendingBefore'] == r['pendingAfter'] for r in current['rows'])
    assert current['rows'][0]['remainingDelegatedReminders'] == 3 and current['rows'][0]['pendingAfter'] == 5
    assert original['keptQuestionIds'] == ['q1', 'q2', 'q4', 'q5', 'q6']
    assert current['keptQuestionIds'] == ['q1', 'q2', 'q4']
    fixture_checks = []
    for path in sorted((ROOT / 'services/api/src/test/resources/enhancement').glob('candidate20-*.json')):
        value = load(path)
        source = ROOT / value['source']
        assert digest(source) == value['sourceSha256']
        captured = load(source)
        if 'response' in value:
            assert value['response'] == json.loads(captured['response']['choices'][0]['message']['content'])
        else:
            assert value['questions'] == captured['body']['data']['questions']
        fixture_checks.append({'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(path), 'sourceSha256': value['sourceSha256'], 'verbatim': True})
    save('fixture-verification.json', fixture_checks)
    snapshot = ROOT / 'tmp/me00a-candidate20-20261016'
    snapshot.mkdir(exist_ok=False)
    paths = {ROOT / e['path'] for e in parent['sourceFiles']}
    paths.update(p for p in (ROOT / 'services/api/src/test').rglob('*') if p.is_file())
    source_entries, runtime_entries = [], []
    for path in sorted(paths):
        assert path.resolve().is_relative_to(ROOT.resolve())
        relative = path.relative_to(ROOT)
        target = snapshot / 'source' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        source_entries.append({'path': relative.as_posix(), 'sha256': digest(path)})
    for path in sorted((ROOT / 'services/api/target/classes').rglob('*')):
        if path.is_file():
            relative = path.relative_to(ROOT / 'services/api/target/classes')
            target = snapshot / 'classes' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
            runtime_entries.append({'path': relative.as_posix(), 'sha256': digest(path)})
    changed = [e['path'] for e in parent['sourceFiles'] if '/src/main/java/' in e['path'] and digest(ROOT / e['path']) != e['sha256']]
    assert len(changed) == 6, changed
    tree_hash = lambda rows: hashlib.sha256('\n'.join(f"{e['path']} {e['sha256']}" for e in rows).encode()).hexdigest()
    save('candidate20-source.json', {'version': 'candidate20', 'scope': 'OFFLINE_ONLY', 'frozenAt': datetime.now(timezone.utc).isoformat(),
        'baseHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'parentCandidate': 'candidate19', 'parentSourceTreeSha256': parent['sourceTreeSha256'],
        'changedProductionFilesFromParent': changed, 'sourceSnapshot': (snapshot / 'source').relative_to(ROOT).as_posix(),
        'runtimeSnapshot': (snapshot / 'classes').relative_to(ROOT).as_posix(), 'sourceFiles': source_entries, 'runtimeFiles': runtime_entries,
        'sourceTreeSha256': tree_hash(source_entries), 'runtimeTreeSha256': tree_hash(runtime_entries),
        'includesAllApiTestSourcesAndResources': True, 'candidate20RealModelCalls': 0,
        'withdrawnStrategies': load(OUT / 'strategy-withdrawal.json')['files'],
        'strategy': 'Deterministic scale-alias projection, delegated field projection and two bounded question variants; no new model instruction or retry'})
    print(json.dumps({'candidate': 'candidate20', 'classes': 40, **totals, 'sourceFiles': len(source_entries),
                      'runtimeFiles': len(runtime_entries), 'candidate20RealCalls': 0}))


if __name__ == '__main__':
    main()
