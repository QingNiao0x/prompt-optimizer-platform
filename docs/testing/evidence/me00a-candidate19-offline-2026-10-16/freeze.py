"""Freeze bounded candidate19 changes and fresh local evidence; never opens an evaluation budget."""
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
        frozen = load(OUT / 'candidate19-source.json')
        for entry in frozen['sourceFiles']:
            assert digest(ROOT / entry['path']) == entry['sha256'], entry['path']
            assert digest(ROOT / frozen['sourceSnapshot'] / entry['path']) == entry['sha256'], entry['path']
        for entry in frozen['runtimeFiles']:
            assert digest(ROOT / frozen['runtimeSnapshot'] / entry['path']) == entry['sha256'], entry['path']
        print(json.dumps({'candidate': 'candidate19', 'sourceAndRuntimeUnchanged': True,
                          'sourceFiles': len(frozen['sourceFiles']), 'runtimeFiles': len(frozen['runtimeFiles']), 'modelCalls': 0}))
        return
    parent = load(ROOT / 'docs/testing/evidence/me00a-candidate18-offline-2026-10-16/candidate18-source.json')
    for entry in parent['sourceFiles']:
        assert digest(ROOT / parent['sourceSnapshot'] / entry['path']) == entry['sha256'], 'Parent snapshot changed'
    for entry in parent['runtimeFiles']:
        assert digest(ROOT / parent['runtimeSnapshot'] / entry['path']) == entry['sha256'], 'Parent runtime changed'
    changed = [e['path'] for e in parent['sourceFiles'] if digest(ROOT / e['path']) != e['sha256']]
    production = sorted(p for p in changed if '/src/main/java/' in p)
    assert len(production) == 5, production
    run = load(ROOT / 'tmp/candidate19-affected-run.json')
    started = datetime.fromisoformat(run['startedAt']).timestamp()
    reports = []
    destination = OUT / 'test-reports'
    destination.mkdir(exist_ok=False)
    for path in (ROOT / 'services/api/target/surefire-reports').glob('TEST-*.xml'):
        tree = ET.parse(path).getroot()
        if tree.attrib['name'].split('.')[-1] not in run['classes']:
            continue
        assert path.stat().st_mtime >= started - 3, 'Stale report'
        record = {key: int(tree.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        assert record['failures'] == record['errors'] == record['skipped'] == 0, path.name
        record.update(name=tree.attrib['name'], seconds=tree.attrib['time'], originalReportSha256=digest(path))
        projection = ET.Element('testsuite', tree.attrib)
        for case in tree.findall('testcase'):
            ET.SubElement(projection, 'testcase', case.attrib)
        ET.ElementTree(projection).write(destination / path.name, encoding='utf-8', xml_declaration=True)
        reports.append(record)
    assert len(reports) == len(run['classes']) == 39
    totals = {key: sum(r[key] for r in reports) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert totals['tests'] == 739
    save('verification.json', {'kind': 'OFFLINE_AFFECTED_REGRESSION', 'java': {'classes': len(reports), **totals, 'reports': reports},
                             'newTargetedTests': 21, 'newRealModelCalls': 0, 'fullRepositoryRegression': 'NOT_RUN',
                             'browserAndNewHistory': 'NOT_RUN; affected ownership and history tests passed',
                             'scripts': 'Existing product counters reused unchanged; no product script modifications'})
    fixture_path = ROOT / 'services/api/src/test/resources/enhancement/candidate19-trace265.json'
    fixture = load(fixture_path)
    trace = load(ROOT / fixture['source'])
    assert digest(ROOT / fixture['source']) == fixture['sha256']
    assert fixture['questions'] == json.loads(trace['response']['choices'][0]['message']['content'])['questions']
    actual_input = load(ROOT / 'docs/testing/evidence/me00a-candidate17-live-2026-10-16/real/Q05-plan/input.json')
    assert fixture['rawPrompt'] == actual_input['rawPrompt']
    save('fixture-verification.json', {'trace': 265, 'sourceSha256': fixture['sha256'],
                                      'fixtureSha256': digest(fixture_path), 'allSixQuestionsVerbatim': True, 'modelCalls': 0})
    old, current = load(OUT / 'probe-parent18.json'), load(OUT / 'probe-candidate19.json')
    assert old['keptQuestionIds'] == ['plan_rounds', 'task_source', 'output_scope']
    assert current['keptQuestionIds'] == ['plan_rounds', 'task_source']
    assert '各等级评分锚点' not in old['scoringTask'] and '各等级评分锚点' in current['scoringTask']
    save('projection-comparison.json', {'oldCandidate': 'candidate18', 'newCandidate': 'candidate19',
                                      'oldLengthGuidanceCharacters': len(old['lengthGuidance']),
                                      'newLengthGuidanceCharacters': len(current['lengthGuidance']),
                                      'unit': 'Unicode code points, NOT tokens or downstream body length',
                                      'newActualWorkQuality': 'UNVERIFIED', 'modelCalls': 0})
    security = load(OUT / 'security-replay-v3/result.json')
    assert all(o['pass'] for o in security['observations'] if o['arm'] == 'DIAGNOSTIC_REFERENCE_ONLY')
    snapshot = ROOT / 'tmp/me00a-candidate19-20261016'
    snapshot.mkdir(exist_ok=False)
    paths = {ROOT / entry['path'] for entry in parent['sourceFiles']}
    paths.update([ROOT / 'services/api/src/test/java/com/promptoptimizer/enhancement/service/impl/CandidateNineteenBoundaryTest.java', fixture_path])
    source_entries, runtime_entries = [], []
    for path in sorted(paths):
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
    tree_hash = lambda rows: hashlib.sha256('\n'.join(f"{e['path']} {e['sha256']}" for e in rows).encode()).hexdigest()
    save('candidate19-source.json', {'version': 'candidate19', 'scope': 'OFFLINE_ONLY', 'frozenAt': datetime.now(timezone.utc).isoformat(),
         'baseHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
         'parentCandidate': parent['version'], 'parentSourceTreeSha256': parent['sourceTreeSha256'],
         'changedProductionFilesFromParent': production, 'allChangedParentFiles': changed,
         'sourceSnapshot': (snapshot / 'source').relative_to(ROOT).as_posix(), 'runtimeSnapshot': (snapshot / 'classes').relative_to(ROOT).as_posix(),
         'sourceTreeSha256': tree_hash(source_entries), 'runtimeTreeSha256': tree_hash(runtime_entries),
         'sourceFiles': source_entries, 'runtimeFiles': runtime_entries, 'newRealModelCalls': 0,
         'strategies': 'Bounded question coverage; shorter existing length guidance; explicit proposed anchors; task-size implementation and URL display/request boundary',
         'existingWorksAreNotCandidate19Validation': True})
    print(json.dumps({'candidate': 'candidate19', 'javaClasses': len(reports), **totals,
                      'sourceFiles': len(source_entries), 'runtimeFiles': len(runtime_entries), 'newRealModelCalls': 0}))


if __name__ == '__main__':
    main()
