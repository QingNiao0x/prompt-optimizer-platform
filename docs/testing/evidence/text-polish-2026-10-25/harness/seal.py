import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

repo = Path.cwd()
evidence = repo / 'docs/testing/evidence/text-polish-2026-10-25'
runtime = repo / 'tmp/text-polish-acceptance'
ledger = json.loads((evidence / 'ledger.json').read_text(encoding='utf-8'))
assert ledger['closed'] and ledger['actualCalls'] == 48 and ledger['reserved'] == 0
names = ['TextPolishServiceTest', 'OpenAiTextPolishProviderTest', 'TextPolishModelServiceTest',
         'TextPolishControllerSecurityTest', 'TextPolishDatabaseAcceptanceTest',
         'PlatformModelCatalogServiceTest', 'ModelConcurrencyHttpIntegrationTest', 'ImagePromptControllerSecurityTest']
suites = []
for name in names:
    matches = list((repo / 'services/api/target/surefire-reports').glob('TEST-*.' + name + '.xml'))
    assert len(matches) == 1, name
    suite = ET.parse(matches[0]).getroot()
    data = {key: suite.attrib[key] for key in ['name', 'tests', 'failures', 'errors', 'skipped', 'time']}
    assert all(int(data[key]) == 0 for key in ['failures', 'errors', 'skipped']), name
    data['testNames'] = [node.attrib['name'] for node in suite.findall('testcase')]
    suites.append(data)
assert sum(int(suite['tests']) for suite in suites) == 27
for name, marker in [('frontend-final.log', '19 passed (19)'), ('build-final.log', 'built in'), ('browser-final.log', '8 passed')]:
    assert marker in (runtime / name).read_text(encoding='utf-8', errors='replace'), name
verification = evidence / 'verification'
verification.mkdir(exist_ok=True)
for name in ['frontend-final.log', 'build-final.log', 'browser-final.log', 'java-final.log', 'java-lock-check.log']:
    shutil.copyfile(runtime / name, verification / name)
for name in ['visual.mjs', 'CheckPolishProfile.java', 'seal.py']:
    shutil.copyfile(runtime / name, evidence / 'harness' / name)
validation = {'verifiedAt': datetime.now(timezone.utc).isoformat(), 'javaUniqueTests': 27, 'javaSuites': suites,
              'javaFinalAffectedRerun': 13, 'frontendUnitTests': 19, 'articleBrowserChecks': 8,
              'sharedBrowserChecksEarlierInSession': 18, 'sharedBrowserRawLogAvailable': False,
              'typecheckAndBuild': 'passed', 'localMockYaml': 'parsed with existing SnakeYAML; explicit MEMORY; no separate live profile run'}
(evidence / 'validation.json').write_text(json.dumps(validation, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
changed = []
for command in [['git', 'diff', '--name-only', '-z'], ['git', 'ls-files', '--others', '--exclude-standard', '-z']]:
    changed.extend(name for name in subprocess.check_output(command).decode('utf-8').split('\0') if name)
source_names = sorted(set(name for name in changed if not name.startswith('docs/testing/evidence/text-polish-2026-10-25/')))
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {'sealedAt': datetime.now(timezone.utc).isoformat(), 'gitHead': subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip(),
            'actualCalls': 48, 'releasedUnusedCalls': 2,
            'changedSourceAndDocs': [{'path': name, 'sha256': sha(repo / name)} for name in source_names if (repo / name).is_file()],
            'evidence': [{'path': str(path.relative_to(evidence)).replace('\\', '/'), 'sha256': sha(path)}
                         for path in sorted(evidence.rglob('*')) if path.is_file() and path.name != 'manifest.json']}
(evidence / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'sealed': True, 'actualCalls': 48, 'javaTests': 27, 'sourceFiles': len(manifest['changedSourceAndDocs']), 'evidenceFiles': len(manifest['evidence'])}))
