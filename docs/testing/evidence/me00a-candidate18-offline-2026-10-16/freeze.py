"""Freeze the two bounded offline repairs without changing any closed real-evaluation batch."""
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
        frozen = load(OUT / 'candidate18-source.json')
        for entry in frozen['sourceFiles']:
            assert digest(ROOT / entry['path']) == entry['sha256'], entry['path']
            assert digest(ROOT / frozen['sourceSnapshot'] / entry['path']) == entry['sha256'], entry['path']
        for entry in frozen['runtimeFiles']:
            assert digest(ROOT / frozen['runtimeSnapshot'] / entry['path']) == entry['sha256'], entry['path']
        print(json.dumps({'candidate': 'candidate18', 'sourceAndRuntimeUnchanged': True,
                          'sourceFiles': len(frozen['sourceFiles']), 'runtimeFiles': len(frozen['runtimeFiles']), 'newRealModelCalls': 0}))
        return
    parent = load(ROOT / 'docs/testing/evidence/me00a-candidate17-2026-10-16/candidate17-source.json')
    changed = [e['path'] for e in parent['sourceFiles'] if digest(ROOT / e['path']) != e['sha256']]
    expected_changes = ['services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/PlanDecisionIntegrator.java',
                        'services/api/src/main/java/com/promptoptimizer/provider/domain/RequirementBoundaryContract.java']
    assert sorted(changed) == sorted(expected_changes), changed
    for entry in parent['sourceFiles']:
        assert digest(ROOT / parent['sourceSnapshot'] / entry['path']) == entry['sha256'], 'Parent snapshot drift'
    for entry in parent['runtimeFiles']:
        assert digest(ROOT / parent['runtimeSnapshot'] / entry['path']) == entry['sha256'], 'Parent runtime drift'

    run = load(ROOT / 'tmp/candidate18-affected-run.json')
    started = datetime.fromisoformat(run['startedAt']).timestamp()
    reports, report_folder = [], OUT / 'test-reports'
    report_folder.mkdir(exist_ok=False)
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
        ET.ElementTree(projection).write(report_folder / path.name, encoding='utf-8', xml_declaration=True)
        reports.append(record)
    assert len(reports) == len(run['classes']) == 33
    totals = {key: sum(r[key] for r in reports) for key in ('tests', 'failures', 'errors', 'skipped')}
    save('verification.json', {'kind': 'OFFLINE_AFFECTED_REGRESSION', 'java': {'classes': len(reports), **totals, 'reports': reports},
                             'targetedNewTests': 29, 'newRealCalls': 0, 'fullRepositoryRegression': 'NOT_RUN',
                             'scripts': 'Product scripts unchanged from candidate17; not rerun or added to Java count',
                             'history': 'Existing affected history projection/scope tests; no new candidate18 database history',
                             'candidate18BrowserAndDownstreamWorks': 'NOT_RUN'})
    fixture_entries = []
    for trace in (266, 267):
        path = ROOT / f'services/api/src/test/resources/enhancement/candidate18-trace{trace}.json'
        fixture = load(path)
        original = load(ROOT / fixture['source'])
        message = original['request']['messages'][1]['content']
        assert digest(ROOT / fixture['source']) == fixture['sourceSha256']
        assert fixture['response'] == json.loads(original['response']['choices'][0]['message']['content'])
        assert fixture['rawPrompt'] == json.loads(message[message.index('{'):])['rawPrompt']
        fixture_entries.append({'trace': trace, 'fixtureSha256': digest(path), 'sourceSha256': fixture['sourceSha256'], 'verbatim': True})
    save('fixture-verification.json', fixture_entries)
    snapshot = ROOT / 'tmp/me00a-candidate18-20261016'
    snapshot.mkdir(exist_ok=False)
    paths = {ROOT / e['path'] for e in parent['sourceFiles']}
    paths.add(ROOT / 'services/api/src/test/java/com/promptoptimizer/enhancement/service/impl/CandidateEighteenReplayTest.java')
    paths.update(ROOT / f'services/api/src/test/resources/enhancement/candidate18-trace{n}.json' for n in (266, 267))
    source_entries, runtime_entries = [], []
    for path in sorted(paths):
        assert path.resolve().is_relative_to(ROOT.resolve())
        relative = path.relative_to(ROOT)
        target = snapshot / 'source' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        source_entries.append({'path': relative.as_posix(), 'sha256': digest(path)})
    for path in sorted((ROOT / 'services/api/target/classes').rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT / 'services/api/target/classes')
        target = snapshot / 'classes' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        runtime_entries.append({'path': relative.as_posix(), 'sha256': digest(path)})
    tree_hash = lambda rows: hashlib.sha256('\n'.join(f"{e['path']} {e['sha256']}" for e in rows).encode()).hexdigest()
    save('candidate18-source.json', {'version': 'candidate18', 'scope': 'OFFLINE_ONLY', 'frozenAt': datetime.now(timezone.utc).isoformat(),
         'baseHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
         'parentCandidate': parent['version'], 'parentSourceTreeSha256': parent['sourceTreeSha256'],
         'changedProductionFilesFromParent': changed, 'sourceSnapshot': (snapshot / 'source').relative_to(ROOT).as_posix(),
         'runtimeSnapshot': (snapshot / 'classes').relative_to(ROOT).as_posix(),
         'sourceTreeSha256': tree_hash(source_entries), 'runtimeTreeSha256': tree_hash(runtime_entries),
         'sourceFiles': source_entries, 'runtimeFiles': runtime_entries,
         'modelGuidanceText': 'No new system prompt, default rating scale or extra retries; original generic scoring obligation retained',
         'newRealModelCalls': 0, 'candidate17WorksAreNotCandidate18Validation': True})
    assembled = ROOT / 'services/api/target/candidate18-trace267.json'
    shutil.copy2(assembled, OUT / 'trace267-assembled-offline.json')
    (OUT / 'trace267-copiable-offline.md').write_text(load(assembled)['optimizedPrompt'], encoding='utf-8')
    print(json.dumps({'candidate': 'candidate18', 'javaClasses': len(reports), **totals,
                      'sourceFiles': len(source_entries), 'runtimeFiles': len(runtime_entries), 'newRealModelCalls': 0}))


if __name__ == '__main__':
    main()
