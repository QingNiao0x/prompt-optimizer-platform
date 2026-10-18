"""Freeze only the post-evaluation deterministic assembly repair; no new model calls."""
from datetime import datetime, timezone
from pathlib import Path
import json
import shutil
import xml.etree.ElementTree as ET
from freeze import ROOT, PUBLIC, PRIVATE, sha, write_new


def main():
    parent_path = PUBLIC / 'candidate34-source.json'
    parent = json.loads(parent_path.read_text(encoding='utf-8'))
    build = PRIVATE / 'task-r1-safety-final/build'
    suites = []
    for path in sorted((build / 'surefire-reports').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        suites.append({'name': suite.get('name'), 'reportSha256': sha(path),
                       **{key: int(suite.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}})
    totals = {key: sum(suite[key] for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert totals['tests'] > 100 and all(totals[key] == 0 for key in ('failures', 'errors', 'skipped'))
    assert any(suite['name'].endswith('TaskConstraintWholeProjectionTest') for suite in suites)
    snapshot = PRIVATE / 'frozen-r1'
    assert not snapshot.exists(), 'Never overwrite a frozen candidate'
    source_files = []
    for base in ('services/api/src', 'apps/web/src', 'apps/web/e2e'):
        for path in sorted((ROOT / base).rglob('*')):
            if path.is_file():
                relative = path.relative_to(ROOT)
                source_files.append({'path': relative.as_posix(), 'sha256': sha(path)})
    previous = {item['path']: item['sha256'] for item in parent['sourceFiles']}
    current = {item['path']: item['sha256'] for item in source_files}
    changed = sorted(path for path in previous.keys() | current.keys() if previous.get(path) != current.get(path))
    expected = {
        'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/OptimizationResultAssembler.java',
        'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/TaskConstraintCoverage.java',
        'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/AdditionalRuleProjection.java',
        'services/api/src/test/java/com/promptoptimizer/enhancement/service/impl/TaskConstraintWholeProjectionTest.java',
        'services/api/src/test/java/com/promptoptimizer/enhancement/service/impl/AdditionalRuleProjectionTest.java',
        'services/api/src/test/resources/fixtures/candidate34-task-constraint-replay.json',
    }
    assert set(changed) == expected, 'Revision scope drift'
    for item in source_files:
        target = snapshot / 'source' / item['path']
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / item['path'], target)
    shutil.copytree(build / 'classes', snapshot / 'classes')
    runtime_files = [{'path': path.relative_to(build / 'classes').as_posix(), 'sha256': sha(path)}
                     for path in sorted((build / 'classes').rglob('*')) if path.is_file()]
    policy_paths = {item['path'] for item in parent['policyFiles']} | {
        'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/TaskConstraintCoverage.java'}
    write_new(PUBLIC / 'candidate34-r1-source.json', {
        'version': 'candidate34-r1', 'sourceRevision': 'r1', 'parentCandidate': 'candidate34',
        'parentManifestSha256': sha(parent_path), 'frozenAt': datetime.now(timezone.utc).isoformat(),
        'baseHead': parent['baseHead'], 'sourceSnapshot': (snapshot / 'source').relative_to(ROOT).as_posix(),
        'runtimeSnapshot': (snapshot / 'classes').relative_to(ROOT).as_posix(),
        'sourceFiles': source_files, 'runtimeFiles': runtime_files,
        'policyFiles': [item for item in source_files if item['path'] in policy_paths],
        'changedSourcePaths': changed,
    })
    write_new(PUBLIC / 'verification-r1.json', {
        'candidate': 'candidate34-r1', 'java': {**totals, 'suites': suites},
        'parentVerification': 'verification.json', 'parentCountsNotAddedToCurrentCounts': True,
        'sourceDelta': changed, 'frontendSourceUnchangedFromInitialCandidate34': True,
        'rawModelResponsesReplayed': [382, 383], 'newModelCalls': 0,
        'newRealBrowserOrDownstreamWorks': False,
        'qualityGate': 'OPEN_WITH_INITIAL_REAL_WORK_FAILURES',
        'limitation': 'Initial candidate34 works and browser checks remain bound to the initial source; this revision has deterministic replay and affected tests only.',
    })
    print(json.dumps({'candidate': 'candidate34-r1', 'java': totals, 'sourceFiles': len(source_files),
                      'runtimeFiles': len(runtime_files), 'changedFiles': len(changed), 'newModelCalls': 0}))


if __name__ == '__main__':
    main()
