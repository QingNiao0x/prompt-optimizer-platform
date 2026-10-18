"""Freeze candidate34 source/runtime and affected-test evidence without contacting a model."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
PRIVATE = ROOT / 'tmp/me04-candidate34-20261018'
PARENT = ROOT / 'docs/testing/evidence/me04-candidate33-generation-modes-2026-10-18/candidate33-source.json'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_new(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
        out.write('\n')


def main():
    build = PRIVATE / 'backend-final/build'
    suites = []
    for path in sorted((build / 'surefire-reports').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        suites.append({'name': suite.get('name'), 'reportSha256': sha(path),
                       **{key: int(suite.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}})
    totals = {key: sum(suite[key] for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert totals['tests'] > 100 and all(totals[key] == 0 for key in ('failures', 'errors', 'skipped'))
    snapshot = PRIVATE / 'frozen'
    assert not snapshot.exists(), 'Do not overwrite a frozen candidate'
    source_files = []
    for base in ('services/api/src', 'apps/web/src', 'apps/web/e2e'):
        for path in sorted((ROOT / base).rglob('*')):
            if path.is_file():
                relative = path.relative_to(ROOT)
                target = snapshot / 'source' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
                source_files.append({'path': relative.as_posix(), 'sha256': sha(path)})
    runtime = build / 'classes'
    shutil.copytree(runtime, snapshot / 'classes')
    runtime_files = [{'path': path.relative_to(runtime).as_posix(), 'sha256': sha(path)}
                     for path in sorted(runtime.rglob('*')) if path.is_file()]
    parent = json.loads(PARENT.read_text(encoding='utf-8-sig'))
    previous = {row['path']: row['sha256'] for row in parent['sourceFiles']}
    frontend = [row for row in source_files if row['path'].startswith('apps/web/')]
    unchanged_frontend = all(previous.get(row['path']) == row['sha256'] for row in frontend)
    policy_names = {'PromptFramework.java', 'PromptFrameworkSelector.java', 'PromptFrameworkSelection.java',
                    'PromptRewriteStrategy.java', 'PromptTemplateRegistryImpl.java',
                    'OptimizationResultAssembler.java', 'AdditionalRuleProjection.java',
                    'OpenAiCompatiblePromptEnhancementProvider.java', 'TaskIntentResolver.java'}
    write_new(PUBLIC / 'candidate34-source.json', {
        'version': 'candidate34', 'sourceRevision': 'initial', 'parentCandidate': 'candidate33-r1',
        'parentManifestSha256': sha(PARENT), 'frozenAt': datetime.now(timezone.utc).isoformat(),
        'baseHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'sourceSnapshot': (snapshot / 'source').relative_to(ROOT).as_posix(),
        'runtimeSnapshot': (snapshot / 'classes').relative_to(ROOT).as_posix(),
        'sourceFiles': source_files, 'runtimeFiles': runtime_files,
        'policyFiles': [row for row in source_files if Path(row['path']).name in policy_names
                        and '/src/main/' in row['path']],
    })
    write_new(PUBLIC / 'verification.json', {
        'candidate': 'candidate34', 'java': {**totals, 'suites': suites},
        'frontendSourceMatchesCandidate33': unchanged_frontend,
        'frontendFileCount': len(frontend),
        'priorFrontendEvidence': 'candidate33 61 unit tests and 10 controlled browser checks; not rerun or counted as new passes',
        'newRealCallsAtFreeze': 0, 'qualityGate': 'OPEN_PENDING_REAL_WORKS',
    })
    print(json.dumps({'java': totals, 'runtimeFiles': len(runtime_files),
                      'frontendUnchanged': unchanged_frontend, 'frozen': True}))


if __name__ == '__main__':
    main()
