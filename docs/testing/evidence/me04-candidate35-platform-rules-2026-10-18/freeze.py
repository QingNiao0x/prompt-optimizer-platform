"""Freeze source and tested runtime for candidate35 without any model request."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
PRIVATE = ROOT / 'tmp/me04-candidate35-20261018'
BUILD = PRIVATE / 'backend-tests/build'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_new(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    suites = []
    for path in sorted((BUILD / 'surefire-reports').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        suites.append({'name': suite.get('name'), 'reportSha256': sha(path),
                       **{key: int(suite.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}})
    totals = {key: sum(suite[key] for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert totals['tests'] > 0 and all(totals[key] == 0 for key in ('failures', 'errors', 'skipped'))
    assert any(suite['name'].endswith('PlatformConstraintPresentationTest') for suite in suites)
    assert any(suite['name'].endswith('MybatisOptimizationHistoryServiceTest') for suite in suites)
    assert any(suite['name'].endswith('ProtectedContextFilterTest') for suite in suites)
    unit_path = ROOT / 'tmp/candidate35-frontend-unit.json'
    browser_path = ROOT / 'tmp/candidate35-frontend-e2e.json'
    unit = json.loads(unit_path.read_text(encoding='utf-8'))
    browser = json.loads(browser_path.read_text(encoding='utf-8'))
    assert unit['success'] and unit['numFailedTests'] == 0 and unit['numPassedTests'] > 0
    assert browser['stats']['expected'] > 0 and all(browser['stats'][key] == 0
        for key in ('unexpected', 'skipped', 'flaky'))
    snapshot = PRIVATE / 'frozen'
    assert not snapshot.exists(), 'Never overwrite a frozen candidate'
    source_files = []
    for base in ('services/api/src', 'apps/web/src', 'apps/web/e2e'):
        for path in sorted((ROOT / base).rglob('*')):
            if path.is_file():
                relative = path.relative_to(ROOT)
                source_files.append({'path': relative.as_posix(), 'sha256': sha(path)})
    parent_path = ROOT / 'docs/testing/evidence/me04-candidate34-framework-natural-2026-10-18/candidate34-r1-source.json'
    parent = json.loads(parent_path.read_text(encoding='utf-8'))
    previous = {item['path']: item['sha256'] for item in parent['sourceFiles']}
    current = {item['path']: item['sha256'] for item in source_files}
    changed = sorted(path for path in previous.keys() | current.keys() if previous.get(path) != current.get(path))
    for item in source_files:
        target = snapshot / 'source' / item['path']
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / item['path'], target)
    shutil.copytree(BUILD / 'classes', snapshot / 'classes')
    runtime_files = [{'path': path.relative_to(BUILD / 'classes').as_posix(), 'sha256': sha(path)}
                     for path in sorted((BUILD / 'classes').rglob('*')) if path.is_file()]
    base_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    write_new(PUBLIC / 'candidate35-source.json', {
        'version': 'candidate35', 'parentCandidate': 'candidate34-r1',
        'parentManifestSha256': sha(parent_path), 'frozenAt': datetime.now(timezone.utc).isoformat(),
        'baseHead': base_head, 'sourceSnapshot': (snapshot / 'source').relative_to(ROOT).as_posix(),
        'runtimeSnapshot': (snapshot / 'classes').relative_to(ROOT).as_posix(),
        'sourceFiles': source_files, 'runtimeFiles': runtime_files,
        'policyFiles': [item for item in source_files if item['path'].startswith('services/api/src/main/')
                        and item['path'] in changed], 'changedSourcePaths': changed,
    })
    write_new(PUBLIC / 'verification.json', {
        'candidate': 'candidate35', 'java': {**totals, 'suites': suites},
        'frontend': {
            'unit': {'tests': unit['numTotalTests'], 'passed': unit['numPassedTests'],
                     'failures': unit['numFailedTests'], 'reportSha256': sha(unit_path)},
            'controlledBrowser': {**browser['stats'], 'reportSha256': sha(browser_path),
                                  'newModelCalls': 0, 'newAccountsCreated': 0},
            'details': 'frontend-verification.json',
        },
        'sourceDelta': changed, 'newModelCallsAtFreeze': 0,
        'previousResultsNotAdded': True, 'qualityGate': 'OPEN_PENDING_BOUNDED_REAL_VALIDATION',
        'limitation': 'Offline replay and affected regression do not prove downstream work quality.',
    })
    print(json.dumps({'candidate': 'candidate35', 'java': totals, 'sourceFiles': len(source_files),
                      'runtimeFiles': len(runtime_files), 'changedFiles': len(changed), 'newModelCalls': 0}))


if __name__ == '__main__':
    main()
